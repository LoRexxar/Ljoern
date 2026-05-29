package io.joern.jimple2cpg.rewrite

import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, Operators, PropertyNames}
import io.shiftleft.codepropertygraph.generated.nodes.{Identifier, Method}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*

import java.util.concurrent.atomic.AtomicLong
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
              if (uses.size == 1) {
                val use = uses.head
                builder.setNodeProperty(use, PropertyNames.Code, rhsCode)
                val useCurrentType = use.properties.get(PropertyNames.TypeFullName).map(_.toString).getOrElse("")
                val betterType = if (rhsType.nonEmpty && rhsType != "ANY") rhsType else useCurrentType
                if (betterType.nonEmpty && betterType != "ANY") {
                  builder.setNodeProperty(use, PropertyNames.TypeFullName, betterType)
                }
                builder.setNodeProperty(assign, PropertyNames.Code, s"<fused: $name = $rhsCode>")
                processed += name
                fusedCount.incrementAndGet()
                localsByName.get(name).toList.flatten.foreach { local =>
                  local.inE(EdgeTypes.REF).foreach(builder.removeEdge)
                  local.inE(EdgeTypes.AST).foreach(builder.removeEdge)
                  builder.removeNode(local)
                }
              } else if (uses.size > 1) {
                multiUseCount.incrementAndGet()
              }
            }
          case _ => ()
        }
      }
  }
}

object JimpleAstRewriter {

  private val TmpPattern = """^\$(stack|r|i|l|d|f|z|c|b|s)\d+$|^tmp(\d+|_.*)?$""".r

  val totalTmps     = new AtomicLong(0)
  val fusedCount    = new AtomicLong(0)
  val multiUseCount = new AtomicLong(0)

  def isTmpName(name: String): Boolean = TmpPattern.findFirstIn(name).isDefined

  def reset(): Unit = {
    totalTmps.set(0)
    fusedCount.set(0)
    multiUseCount.set(0)
  }

  def run(cpg: Cpg): Unit = {
    reset()
    new JimpleAstRewriter(cpg).createAndApply()
  }

  def stats: String =
    s"JimpleAstRewriter: totalTmps=${totalTmps.get()}, fused=${fusedCount.get()}, multiUse=${multiUseCount.get()}, " +
      s"elimRate=${if (totalTmps.get() > 0) f"${fusedCount.get().toDouble / totalTmps.get() * 100}%.1f%%" else "N/A"}"
}
