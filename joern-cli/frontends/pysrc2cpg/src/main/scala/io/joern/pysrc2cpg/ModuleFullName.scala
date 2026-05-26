package io.joern.pysrc2cpg

object ModuleFullName {
  def fromRelFileName(relFileName: String): String = {
    val normalized = relFileName.replace('\\', '/')
    val trimmed    = normalized.stripPrefix("./").stripPrefix("/")
    val noExt =
      if (trimmed.endsWith(".py")) trimmed.dropRight(3)
      else trimmed

    val parts = noExt.split('/').toList.filter(_.nonEmpty)

    parts match {
      case Nil =>
        ""
      case init :+ "__init__" =>
        init.mkString(".")
      case xs =>
        xs.mkString(".")
    }
  }
}

