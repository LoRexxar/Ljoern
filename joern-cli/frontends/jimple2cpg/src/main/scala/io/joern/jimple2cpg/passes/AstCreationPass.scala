package io.joern.jimple2cpg.passes

import io.joern.jimple2cpg.Config
import io.joern.jimple2cpg.astcreation.AstCreator
import io.joern.jimple2cpg.util.ProgramHandlingUtil.ClassFile
import io.joern.x2cpg.utils.FrontendProfiling
import io.shiftleft.semanticcpg.utils.FileUtil.*
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.passes.ForkJoinParallelCpgPassWithAccumulator
import org.slf4j.LoggerFactory
import soot.Scene

import scala.collection.mutable
import scala.util.Try

/** Creates the AST layer from the given class file and stores all types in the given global parameter.
  * @param classFiles
  *   List of class files and their fully qualified class names
  * @param cpg
  *   The CPG to add to
  */
class AstCreationPass(classFiles: List[ClassFile], cpg: Cpg, config: Config, decompiledSources: Map[String, String])
    extends ForkJoinParallelCpgPassWithAccumulator[ClassFile, AstCreationPass.Accumulator](cpg) {

  private val logger = LoggerFactory.getLogger(classOf[AstCreationPass])

  private var _usedTypes: Set[String] = Set.empty

  def usedTypes(): Set[String] = _usedTypes

  override def createAccumulator(): AstCreationPass.Accumulator = AstCreationPass.Accumulator()

  override def mergeAccumulator(left: AstCreationPass.Accumulator, right: AstCreationPass.Accumulator): Unit = {
    left.usedTypes ++= right.usedTypes
    left.partsProcessed += right.partsProcessed
    left.partsFailed += right.partsFailed
    left.sootGetHit += right.sootGetHit
    left.sootLoadFallback += right.sootLoadFallback
    left.nanosSootGet += right.nanosSootGet
    left.nanosSootLoadFallback += right.nanosSootLoadFallback
    left.nanosAstCreate += right.nanosAstCreate
    left.nanosAbsorb += right.nanosAbsorb
    left.nanosPartTotal += right.nanosPartTotal
    left.methodCodeJavaSuccess += right.methodCodeJavaSuccess
    left.methodCodeJavaFail += right.methodCodeJavaFail
    if (right.nanosPartMax > left.nanosPartMax) {
      left.nanosPartMax = right.nanosPartMax
      left.nanosPartMaxClass = right.nanosPartMaxClass
    }
  }

  override def onAccumulatorComplete(builder: DiffGraphBuilder, accumulator: AstCreationPass.Accumulator): Unit = {
    _usedTypes = accumulator.usedTypes.toSet
    FrontendProfiling.metric("AstCreationPass.partsProcessed", accumulator.partsProcessed)
    FrontendProfiling.metric("AstCreationPass.partsFailed", accumulator.partsFailed)
    FrontendProfiling.metric("AstCreationPass.sootGetHit", accumulator.sootGetHit)
    FrontendProfiling.metric("AstCreationPass.sootLoadFallback", accumulator.sootLoadFallback)
    FrontendProfiling.metric("AstCreationPass.nanosSootGet", accumulator.nanosSootGet)
    FrontendProfiling.metric("AstCreationPass.nanosSootLoadFallback", accumulator.nanosSootLoadFallback)
    FrontendProfiling.metric("AstCreationPass.nanosAstCreate", accumulator.nanosAstCreate)
    FrontendProfiling.metric("AstCreationPass.nanosAbsorb", accumulator.nanosAbsorb)
    FrontendProfiling.metric("AstCreationPass.nanosPartTotal", accumulator.nanosPartTotal)
    FrontendProfiling.metric("AstCreationPass.nanosPartMax", accumulator.nanosPartMax)
    FrontendProfiling.metric("methodCode.java.success", accumulator.methodCodeJavaSuccess)
    FrontendProfiling.metric("methodCode.java.fail", accumulator.methodCodeJavaFail)
    if (accumulator.partsProcessed > 0) {
      FrontendProfiling.metric("AstCreationPass.nanosPartAvg", accumulator.nanosPartTotal / accumulator.partsProcessed)
    }
    if (accumulator.nanosPartMaxClass.nonEmpty) {
      FrontendProfiling.metric("AstCreationPass.nanosPartMaxClass", accumulator.nanosPartMaxClass)
    }
  }

  override def generateParts(): Array[? <: AnyRef] = classFiles.toArray

  override def runOnPart(
    builder: DiffGraphBuilder,
    classFile: ClassFile,
    accumulator: AstCreationPass.Accumulator
  ): Unit = {
    val t0 = System.nanoTime()
    accumulator.partsProcessed += 1
    try {
      val fqcn = classFile.fullyQualifiedClassName.get
      val sootGetT0 = System.nanoTime()
      val sootClassFromScene = Try(Scene.v().getSootClass(fqcn)).toOption
      accumulator.nanosSootGet += (System.nanoTime() - sootGetT0)
      val sootClass = sootClassFromScene match {
        case Some(value) =>
          accumulator.sootGetHit += 1
          value
        case None =>
          accumulator.sootLoadFallback += 1
          val sootLoadT0 = System.nanoTime()
          val out = Scene.v().loadClassAndSupport(fqcn)
          accumulator.nanosSootLoadFallback += (System.nanoTime() - sootLoadT0)
          out
      }
      sootClass.setApplicationClass()

      val fileContent =
        if (config.disableFileContent) None
        else classFile.fullyQualifiedClassName.flatMap(decompiledSources.get)

      val astT0 = System.nanoTime()
      val localDiff =
        AstCreator(classFile.file.absolutePathAsString, sootClass, accumulator, fileContent = fileContent)(
          config.schemaValidation
        )
          .createAst()
      accumulator.nanosAstCreate += (System.nanoTime() - astT0)
      val absorbT0 = System.nanoTime()
      builder.absorb(localDiff)
      accumulator.nanosAbsorb += (System.nanoTime() - absorbT0)
    } catch {
      case e: Exception =>
        accumulator.partsFailed += 1
        logger.warn(s"Exception on AST creation for ${classFile.file.absolutePathAsString}", e)
    } finally {
      val dt = System.nanoTime() - t0
      accumulator.nanosPartTotal += dt
      if (dt > accumulator.nanosPartMax) {
        accumulator.nanosPartMax = dt
        accumulator.nanosPartMaxClass = classFile.fullyQualifiedClassName.getOrElse("")
      }
    }
  }

}

object AstCreationPass {
  case class Accumulator(
    usedTypes: mutable.HashSet[String] = mutable.HashSet.empty,
    var partsProcessed: Long = 0L,
    var partsFailed: Long = 0L,
    var sootGetHit: Long = 0L,
    var sootLoadFallback: Long = 0L,
    var nanosSootGet: Long = 0L,
    var nanosSootLoadFallback: Long = 0L,
    var nanosAstCreate: Long = 0L,
    var nanosAbsorb: Long = 0L,
    var nanosPartTotal: Long = 0L,
    var nanosPartMax: Long = 0L,
    var nanosPartMaxClass: String = "",
    var methodCodeJavaSuccess: Long = 0L,
    var methodCodeJavaFail: Long = 0L
  ) {
    def registerType(typeName: String): Unit = usedTypes.add(typeName)
  }
}
