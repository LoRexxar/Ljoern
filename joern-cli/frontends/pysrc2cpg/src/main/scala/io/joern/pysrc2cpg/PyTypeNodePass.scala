package io.joern.pysrc2cpg

import io.joern.x2cpg.Defines
import io.shiftleft.codepropertygraph.generated.nodes.NewType
import io.shiftleft.codepropertygraph.generated.{Cpg, Properties}
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal

import scala.collection.mutable

class PyTypeNodePass private (cpg: Cpg, getTypesFromCpg: Boolean) extends CpgPass(cpg, "types") {

  private def typeDeclTypes: mutable.Set[String] = {
    val typeDeclTypes = mutable.Set[String]()
    cpg.typeDecl.foreach { typeDecl =>
      typeDeclTypes += typeDecl.fullName
    }
    typeDeclTypes
  }

  private def typeFullNamesFromCpg: Set[String] = {
    cpg.all
      .map(_.property(Properties.TypeFullName))
      .filter(_ != null)
      .toSet
  }

  private def fullToShortName(typeName: String): String = {
    val afterColon = typeName.split(':').lastOption.getOrElse(typeName)
    afterColon.split('.').lastOption.getOrElse(afterColon)
  }

  override def run(diffGraph: DiffGraphBuilder): Unit = {
    val typeFullNameValues = if (getTypesFromCpg) typeFullNamesFromCpg else Set.empty[String]

    val usedTypesSet = typeDeclTypes ++ typeFullNameValues
    usedTypesSet.remove("<empty>")
    usedTypesSet.addOne(Defines.Any)

    val usedTypes = usedTypesSet.filterInPlace(!_.endsWith(NamespaceTraversal.globalNamespaceName)).sorted

    usedTypes.foreach { typeName =>
      val shortName = fullToShortName(typeName)
      val node = NewType()
        .name(shortName)
        .fullName(typeName)
        .typeDeclFullName(typeName)
      diffGraph.addNode(node)
    }
  }
}

object PyTypeNodePass {
  def withTypesFromCpg(cpg: Cpg): PyTypeNodePass = new PyTypeNodePass(cpg, getTypesFromCpg = true)
}
