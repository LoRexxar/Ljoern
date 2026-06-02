package io.joern.x2cpg.utils

object CanonicalName {

  def normalizeTypeFullName(typeFullName: String): String = {
    if (typeFullName == null || typeFullName.isEmpty) return typeFullName

    val preserve =
      typeFullName == "ANY" ||
        typeFullName.startsWith("<unresolvedNamespace>") ||
        typeFullName.startsWith("<unknownFullName>")

    if (preserve) return typeFullName

    eraseGenerics(typeFullName).replace('$', '.')
  }

  def normalizeSignature(signature: String): String = {
    if (signature == null || signature.isEmpty) return signature
    if (signature.startsWith("<unresolvedSignature>(")) return signature

    val lParen = signature.indexOf('(')
    val rParen = signature.lastIndexOf(')')
    if (lParen <= 0 || rParen <= lParen) return signature

    val ret  = signature.substring(0, lParen)
    val args = signature.substring(lParen + 1, rParen)

    val normRet = normalizeTypeFullName(ret)
    val normArgs =
      if (args.isBlank) ""
      else splitTopLevelArgs(args).map(x => normalizeTypeFullName(x.trim)).mkString(",")

    s"$normRet($normArgs)"
  }

  def normalizeMethodFullName(methodFullName: String): String = {
    if (methodFullName == null || methodFullName.isEmpty) return methodFullName

    val idx = methodFullName.indexOf(':')
    if (idx == -1) return methodFullName.replace('$', '.')

    val left = methodFullName.substring(0, idx)
    val sig  = methodFullName.substring(idx + 1)

    val lastDot = left.lastIndexOf('.')
    if (lastDot == -1) {
      s"${left.replace('$', '.')}:${normalizeSignature(sig)}"
    } else {
      val decl = left.substring(0, lastDot)
      val name = left.substring(lastDot + 1)
      s"${normalizeTypeFullName(decl)}.$name:${normalizeSignature(sig)}"
    }
  }

  private def eraseGenerics(s: String): String = {
    val sb    = new StringBuilder(s.length)
    var depth = 0
    var i     = 0
    while (i < s.length) {
      val ch = s.charAt(i)
      if (ch == '<') depth += 1
      else if (ch == '>') depth = math.max(0, depth - 1)
      else if (depth == 0) sb.append(ch)
      i += 1
    }
    sb.toString
  }

  private def splitTopLevelArgs(args: String): List[String] = {
    val out   = List.newBuilder[String]
    val sb    = new StringBuilder(args.length)
    var depth = 0
    var i     = 0

    while (i < args.length) {
      val ch = args.charAt(i)
      if (ch == '<') {
        depth += 1
        sb.append(ch)
      } else if (ch == '>') {
        depth = math.max(0, depth - 1)
        sb.append(ch)
      } else if (ch == ',' && depth == 0) {
        out += sb.toString
        sb.clear()
      } else {
        sb.append(ch)
      }
      i += 1
    }

    out += sb.toString
    out.result()
  }
}
