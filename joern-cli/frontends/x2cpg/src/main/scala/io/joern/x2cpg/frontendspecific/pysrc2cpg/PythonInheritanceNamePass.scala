package io.joern.x2cpg.frontendspecific.pysrc2cpg

import io.joern.x2cpg.passes.frontend.XInheritanceFullNamePass
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.DiffGraphBuilder
import io.shiftleft.codepropertygraph.generated.nodes.{Call, TypeDecl}
import io.shiftleft.codepropertygraph.generated.{EdgeTypes, PropertyNames}
import io.shiftleft.semanticcpg.language.*

/** Using some basic heuristics, will try to resolve type full names from types found within the CPG. Requires
  * ImportPass as a pre-requisite.
  */
class PythonInheritanceNamePass(cpg: Cpg) extends XInheritanceFullNamePass(cpg) {

  override val moduleName: String = Constants.moduleName
  override val fileExt: String    = ".py"

  override def runOnPart(builder: DiffGraphBuilder, source: TypeDecl): Unit = {
    val resolvedTypeDecls = super.resolveInheritedTypeFullName(source, builder)
    val importedEntities = source.file.method
      .flatMap(_._callViaContainsOut)
      .collect { case call: Call if call._isCallForImportOut.nonEmpty => call.isCallForImportOut.importedEntity }
      .flatten
      .distinct
      .l

    val callReturnBases = source.inheritsFromTypeFullName
      .filter(_.contains("("))
      .flatMap { base =>
        val calleeExpr   = base.takeWhile(_ != '(').trim
        val calleeSimple = calleeExpr.split(pathSep).lastOption.getOrElse(calleeExpr)
        importedEntities
          .find(_.split(pathSep).lastOption.contains(calleeSimple))
          .map(_ + ".<returnValue>")
      }

    val fullNamesForResolvedTypes = resolvedTypeDecls.map(_.fullName)
    val fullNames                 = (fullNamesForResolvedTypes ++ callReturnBases).distinct

    if (fullNames.nonEmpty) {
      builder.setNodeProperty(source, PropertyNames.InheritsFromTypeFullName, fullNames)
      cpg.typ.fullNameExact(fullNamesForResolvedTypes*).foreach(tgt => builder.addEdge(source, tgt, EdgeTypes.INHERITS_FROM))
    }
  }

}
