package io.joern.pysrc2cpg

import scala.collection.mutable

final class PythonScope(val moduleFullName: String, val symbolSummary: PythonSymbolSummary) {
  private val imports: mutable.Map[String, String] = mutable.HashMap.empty

  def addImport(localName: String, targetFullName: String): Unit = {
    imports.update(localName, targetFullName)
  }

  def resolveImported(name: String): Option[String] =
    imports.get(name)

  def isTopLevelFunction(name: String): Boolean =
    symbolSummary.topLevelFunctions.contains(name)

  def isTopLevelClass(name: String): Boolean =
    symbolSummary.topLevelClasses.contains(name)

  def unresolvedCall(name: String): String =
    (moduleFullName :: Nil).filter(_.nonEmpty).appended(name).mkString(".")
}
