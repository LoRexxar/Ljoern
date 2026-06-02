package io.joern.jimple2cpg.rewrite

import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, Operators, PropertyNames}
import io.shiftleft.codepropertygraph.generated.nodes.{Call, Expression, Identifier, Literal, Method, Unknown}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*

import java.util.concurrent.atomic.{AtomicLong, LongAdder}
import scala.collection.mutable

class JimpleAstRewriter(cpg: Cpg) extends ForkJoinParallelCpgPass[Method](cpg) {

  import JimpleAstRewriter.*

  override def generateParts(): Array[Method] = cpg.method.toArray

  override def runOnPart(builder: DiffGraphBuilder, method: Method): Unit = {
    val locals = method.ast.isLocal.l
    if (locals.isEmpty) return

    val tmpLocalSet = mutable.HashSet.empty[String]
    locals.foreach { l =>
      if (isTmpName(l.name)) tmpLocalSet += l.name
    }
    if (tmpLocalSet.isEmpty) return

    totalTmps.addAndGet(tmpLocalSet.size)

    val localsByName = locals.filter(l => tmpLocalSet.contains(l.name)).groupBy(_.name)
    val processed    = mutable.HashSet.empty[String]

    val allIdentifiers = method.ast.isIdentifier.l
    val idByName       = allIdentifiers.groupBy(_.name)

    method.ast.isCall
      .nameExact(Operators.assignment)
      .sortBy(_.order)
      .foreach { assign =>
        val arg1 = assign.argument(1)
        arg1 match {
          case id: Identifier if tmpLocalSet.contains(id.name) && !processed.contains(id.name) =>
            val rhsNode = assign.argument(2)
            val name    = id.name
            val rhsCode = rhsNode.code
            val rhsType = rhsNode.properties.get(PropertyNames.TypeFullName).map(_.toString).getOrElse("")
            if (rhsCode.nonEmpty) {
              val uses = idByName.getOrElse(name, Nil).filterNot(_ eq id)
              val rhsKind = classifyRhs(rhsNode)

              if (uses.isEmpty) {
                deadCount.incrementAndGet()
                deadByRhs.getOrElseUpdate(rhsKind, new LongAdder()).increment()
                processed += name
                builder.setNodeProperty(assign, PropertyNames.Code, s"<dead: $name = $rhsCode>")
                removeLocal(builder, localsByName, name)
              } else if (uses.size == 1) {
                fuseSingleUse(builder, uses.head, assign, name, rhsCode, rhsType)
                processed += name
                fusedCount.incrementAndGet()
                removeLocal(builder, localsByName, name)
              } else if (isInlineableRhs(rhsNode)) {
                uses.foreach { use =>
                  fuseSingleUse(builder, use, assign, name, rhsCode, rhsType)
                }
                processed += name
                fusedCount.addAndGet(uses.size)
                multiFusedCount.incrementAndGet()
                removeLocal(builder, localsByName, name)
              } else {
                multiUseCount.incrementAndGet()
                multiByRhs.getOrElseUpdate(rhsKind, new LongAdder()).increment()
                multiByUseCount.getOrElseUpdate(uses.size, new LongAdder()).increment()
              }
            }
          case _ => ()
        }
      }
  }

  private def fuseSingleUse(
    builder: DiffGraphBuilder,
    use: Identifier,
    assign: Call,
    name: String,
    rhsCode: String,
    rhsType: String
  ): Unit = {
    builder.setNodeProperty(use, PropertyNames.Code, rhsCode)
    val useCurrentType = use.properties.get(PropertyNames.TypeFullName).map(_.toString).getOrElse("")
    val betterType = if (rhsType.nonEmpty && rhsType != "ANY") rhsType else useCurrentType
    if (betterType.nonEmpty && betterType != "ANY") {
      builder.setNodeProperty(use, PropertyNames.TypeFullName, betterType)
    }
    builder.setNodeProperty(assign, PropertyNames.Code, s"<fused: $name = $rhsCode>")
  }

  private def removeLocal(
    builder: DiffGraphBuilder,
    localsByName: Map[String, ? <: Seq[? <: io.shiftleft.codepropertygraph.generated.nodes.Local]],
    name: String
  ): Unit = {
    localsByName.get(name).toList.flatten.foreach { local =>
      local.inE(EdgeTypes.REF).foreach(builder.removeEdge)
      local.inE(EdgeTypes.AST).foreach(builder.removeEdge)
      builder.removeNode(local)
    }
  }
}

object JimpleAstRewriter {

  private val TmpPattern = """^\$(stack|r|i|l|d|f|z|c|b|s)\d+$|^tmp(\d+|_.*)?$""".r

  val totalTmps      = new AtomicLong(0)
  val fusedCount     = new AtomicLong(0)
  val deadCount      = new AtomicLong(0)
  val multiUseCount  = new AtomicLong(0)
  val multiFusedCount = new AtomicLong(0)

  val multiByRhs      = mutable.Map.empty[String, LongAdder]
  val multiByUseCount = mutable.Map.empty[Int, LongAdder]
  val deadByRhs       = mutable.Map.empty[String, LongAdder]

  def isTmpName(name: String): Boolean = TmpPattern.findFirstIn(name).isDefined

  private def classifyRhs(node: Expression): String = node match {
    case _: Literal    => "literal"
    case _: Identifier => "identifier"
    case _: Call       => "call"
    case _: Unknown    => "unknown"
    case _             => node.getClass.getSimpleName
  }

  private def isInlineableRhs(node: Expression): Boolean = node match {
    case _: Literal    => true
    case _: Identifier => true
    case _             => false
  }

  def reset(): Unit = {
    totalTmps.set(0)
    fusedCount.set(0)
    deadCount.set(0)
    multiUseCount.set(0)
    multiFusedCount.set(0)
    multiByRhs.clear()
    multiByUseCount.clear()
    deadByRhs.clear()
  }

  def run(cpg: Cpg): Unit = {
    reset()
    new JimpleAstRewriter(cpg).createAndApply()
  }
}
