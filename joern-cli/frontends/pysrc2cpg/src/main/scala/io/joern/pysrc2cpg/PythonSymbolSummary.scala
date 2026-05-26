package io.joern.pysrc2cpg

import io.joern.pythonparser.ast

case class PythonSymbolSummary(topLevelFunctions: Set[String], topLevelClasses: Set[String])

object PythonSymbolSummary {
  def fromModule(module: ast.Module): PythonSymbolSummary = {
    val funcs   = scala.collection.mutable.HashSet.empty[String]
    val classes = scala.collection.mutable.HashSet.empty[String]

    module.stmts.foreach {
      case f: ast.FunctionDef => funcs.add(f.name)
      case c: ast.ClassDef    => classes.add(c.name)
      case _                  =>
    }

    PythonSymbolSummary(funcs.toSet, classes.toSet)
  }
}
