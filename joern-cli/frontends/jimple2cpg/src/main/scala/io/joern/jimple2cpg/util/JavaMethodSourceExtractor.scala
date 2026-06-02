package io.joern.jimple2cpg.util

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.{ConstructorDeclaration, MethodDeclaration}
import soot.{RefType, SootMethod}

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional
import scala.util.Try

object JavaMethodSourceExtractor {

  final case class ExtractionResult(code: String, startLine: Int, endLine: Int)

  def extract(javaSource: String, method: SootMethod, declaringType: RefType): Option[ExtractionResult] = {
    if (method.getName == "<clinit>") {
      None
    } else {
      val methodName =
        if (method.isConstructor) declaringType.getClassName.split("\\.").lastOption.getOrElse(declaringType.getClassName)
        else method.getName
      val paramTypes = method.getParameterTypes.asScala.toList.map(_.toQuotedString)
      val returnType = Option.unless(method.isConstructor)(method.getReturnType.toQuotedString)
      extract(javaSource, methodName, paramTypes, returnType, isConstructor = method.isConstructor)
    }
  }

  def extract(
    javaSource: String,
    methodName: String,
    paramTypes: List[String],
    returnType: Option[String],
    isConstructor: Boolean
  ): Option[ExtractionResult] = {
    val normalizedSource = normalizeLineEndings(javaSource)
    val cu              = Try(StaticJavaParser.parse(normalizedSource)).toOption
    cu.flatMap { compilationUnit =>
      val targetParamTypes = paramTypes.map(normalizeTypeName)
      val targetReturnType = returnType.map(normalizeTypeName)

      if (isConstructor) {
        val ctors = compilationUnit.findAll(classOf[ConstructorDeclaration]).asScala.toList
        pickBestCallable(
          normalizedSource,
          ctors.filter(_.getNameAsString == methodName),
          targetParamTypes,
          targetReturnType = None,
          isConstructor = true
        )
      } else {
        val methods = compilationUnit.findAll(classOf[MethodDeclaration]).asScala.toList
        pickBestCallable(
          normalizedSource,
          methods.filter(_.getNameAsString == methodName),
          targetParamTypes,
          targetReturnType,
          isConstructor = false
        )
      }
    }
  }

  private def pickBestCallable[T](
    source: String,
    candidates: List[T],
    targetParamTypes: List[String],
    targetReturnType: Option[String],
    isConstructor: Boolean
  ): Option[ExtractionResult] = {
    val filtered = candidates.flatMap { candidate =>
      val (paramTypes, returnType, rangeOpt) = candidate match {
        case m: MethodDeclaration =>
          val paramTypes = m.getParameters.asScala.toList.map { p =>
            val varargsSuffix = if (p.isVarArgs) "[]" else ""
            normalizeTypeName(p.getTypeAsString + varargsSuffix)
          }
          val returnType = Option(normalizeTypeName(m.getTypeAsString))
          (paramTypes, returnType, m.getRange.toScala)
        case c: ConstructorDeclaration =>
          val paramTypes = c.getParameters.asScala.toList.map { p =>
            val varargsSuffix = if (p.isVarArgs) "[]" else ""
            normalizeTypeName(p.getTypeAsString + varargsSuffix)
          }
          (paramTypes, None, c.getRange.toScala)
        case _ => (Nil, None, None)
      }

      Option.when(paramTypes.size == targetParamTypes.size && rangeOpt.isDefined) {
        val range     = rangeOpt.get
        val scoreBase = if (isConstructor) 100 else 0
        val paramMatches = paramTypes
          .zip(targetParamTypes)
          .count { case (a, b) => a == b }
        val returnMatches = (returnType, targetReturnType) match {
          case (Some(a), Some(b)) if a == b => 1
          case (_, None)                    => 1
          case _                            => 0
        }
        val score = scoreBase + (paramMatches * 10) + (returnMatches * 5)
        (score, range.begin.line, range.begin.column, range, paramTypes)
      }
    }

    val best =
      filtered
        .sortBy { case (score, beginLine, beginCol, _, paramTypes) =>
          val exactMatchRank = if (paramTypes == targetParamTypes) 0 else 1
          (-score, exactMatchRank, beginLine, beginCol)
        }
        .headOption
        .map(_._4)

    best.flatMap { range =>
      sliceByRange(source, range.begin.line, range.begin.column, range.end.line, range.end.column).map { code =>
        ExtractionResult(code, range.begin.line, range.end.line)
      }
    }
  }

  private def sliceByRange(
    source: String,
    beginLine: Int,
    beginColumn: Int,
    endLine: Int,
    endColumn: Int
  ): Option[String] = {
    val lineStartOffsets = buildLineStartOffsets(source)
    for {
      begin <- offset(lineStartOffsets, beginLine, beginColumn)
      end   <- offset(lineStartOffsets, endLine, endColumn)
      endExclusive = end + 1
      if begin >= 0 && endExclusive <= source.length && endExclusive >= begin
    } yield source.substring(begin, endExclusive).trim
  }

  private def buildLineStartOffsets(source: String): Array[Int] = {
    val offsets = Array.newBuilder[Int]
    offsets += 0
    var i = 0
    while (i < source.length) {
      if (source.charAt(i) == '\n' && i + 1 < source.length) {
        offsets += (i + 1)
      }
      i += 1
    }
    offsets.result()
  }

  private def offset(lineStartOffsets: Array[Int], line: Int, column: Int): Option[Int] = {
    val lineIndex = line - 1
    val colIndex  = column - 1
    Option.when(lineIndex >= 0 && lineIndex < lineStartOffsets.length && colIndex >= 0) {
      lineStartOffsets(lineIndex) + colIndex
    }
  }

  private def normalizeLineEndings(code: String): String = {
    code.replace("\r\n", "\n").replace("\r", "\n")
  }

  private def normalizeTypeName(typeName: String): String = {
    val strippedTypeArgs = stripTypeArguments(typeName.trim)
    val (base, dims)     = peelArrayDims(strippedTypeArgs)
    val normalizedBase   = base.replace('$', '.').split("\\.").lastOption.getOrElse(base)
    normalizedBase + ("[]" * dims)
  }

  private def peelArrayDims(typeName: String): (String, Int) = {
    var s    = typeName.trim
    var dims = 0
    while (s.endsWith("[]")) {
      dims += 1
      s = s.dropRight(2).trim
    }
    (s, dims)
  }

  private def stripTypeArguments(typeName: String): String = {
    val out       = new StringBuilder(typeName.length)
    var depth     = 0
    var i         = 0
    var prevSpace = false

    while (i < typeName.length) {
      val ch = typeName.charAt(i)
      ch match {
        case '<' =>
          depth += 1
        case '>' =>
          if (depth > 0) depth -= 1
        case _ if depth == 0 =>
          val isWs = ch.isWhitespace
          if (!isWs || !prevSpace) {
            out.append(if (isWs) ' ' else ch)
          }
          prevSpace = isWs
        case _ =>
      }
      i += 1
    }
    out.result().trim
  }
}
