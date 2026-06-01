package io.joern.csharpsrc2cpg.astcreation

import io.joern.csharpsrc2cpg.Constants
import io.joern.csharpsrc2cpg.datastructures.{
  CSharpField,
  CSharpMethod,
  CSharpProgramSummary,
  CSharpType,
  NamespaceToTypeMap
}

import io.joern.csharpsrc2cpg.parser.ParserKeys
import io.joern.x2cpg.{Ast, Defines, ValidationMode}
import io.shiftleft.codepropertygraph.generated.ModifierTypes
import io.shiftleft.codepropertygraph.generated.nodes.*

import scala.collection.mutable

trait AstSummaryVisitor(implicit withSchemaValidation: ValidationMode) { this: AstCreator =>

  def summarize(): CSharpProgramSummary = {
    this.parseLevel = AstParseLevel.SIGNATURES
    val fileNode        = NewFile().name(relativeFileName)
    val compilationUnit = createDotNetNodeInfo(parserResult.json(ParserKeys.AstRoot))
    val ast             = Ast(fileNode).withChildren(astForCompilationUnit(compilationUnit))
    extractSummaryFromAst(ast)
  }

  def withSummary(newSummary: CSharpProgramSummary): AstCreator = {
    AstCreator(relativeFileName, parserResult, newSummary)
  }

  private def extractSummaryFromAst(ast: Ast): CSharpProgramSummary = {
    val allNodes = ast.nodes
    val edges    = ast.edges

    val childrenMap = mutable.HashMap.empty[NewNode, Seq[NewNode]]
    edges.foreach { edge =>
      childrenMap.updateWith(edge.src) {
        case Some(children) => Some(children :+ edge.dst)
        case None           => Some(Seq(edge.dst))
      }
    }

    def childrenOf(node: NewNode): Seq[NewNode] = childrenMap.getOrElse(node, Nil)

    val importNodes = allNodes.collect { case x: NewImport => x }.toSeq
    val imports     = importNodes.flatMap(_.importedEntity).toSet
    val globalImports = importNodes
       .filter(n => n.code != null && n.code.startsWith("global"))
       .flatMap(_.importedEntity)
       .toSet

    val typeDecls       = allNodes.collect { case x: NewTypeDecl => x }
    val namespaceBlocks = allNodes.collect { case x: NewNamespaceBlock => x }

    def toMethod(m: NewMethod): CSharpMethod = {
      val mChildren = childrenOf(m)
      val returnType = mChildren
        .collectFirst { case x: NewMethodReturn => x }
        .flatMap(r => Option(r.typeFullName))
        .getOrElse(Defines.Any)
      val params = mChildren.collect { case x: NewMethodParameterIn => x }.map { p =>
        Option(p.name).getOrElse("") -> Option(p.typeFullName).getOrElse(Defines.Any)
      }
      val isStatic = mChildren.exists {
        case x: NewModifier => x.modifierType == ModifierTypes.STATIC
        case _              => false
      }
      CSharpMethod(Option(m.name).getOrElse(""), returnType, params.toList, isStatic)
    }

    def toField(f: NewMember): CSharpField = {
      CSharpField(Option(f.name).getOrElse(""), Option(f.typeFullName).getOrElse(Defines.Any))
    }

    def toType(t: NewTypeDecl): CSharpType = {
      val tChildren = childrenOf(t)
      val tMethods  = tChildren.collect { case x: NewMethod => x }.map(toMethod).toList
      val tMembers  = tChildren.collect { case x: NewMember => x }.map(toField).toList
      CSharpType(Option(t.fullName).getOrElse(""), tMethods, tMembers)
    }

    val typesInNamespaces = mutable.Set.empty[NewTypeDecl]
    val withExplicitNamespace = namespaceBlocks.map { ns =>
      val nsChildren = childrenOf(ns)
      val nsTypes    = nsChildren.collect { case x: NewTypeDecl => x }
      typesInNamespaces ++= nsTypes
      Option(ns.fullName).getOrElse("") -> mutable.Set.from(nsTypes.map(toType))
    }

    val remainingTypes = typeDecls.filterNot(typesInNamespaces.contains)
    val (globalTypes, otherTypes) = remainingTypes.partition { t =>
      Option(t.astParentFullName).contains(Constants.Global)
    }

    val withGlobalNamespace = if (globalTypes.nonEmpty) {
      Seq(Constants.Global -> mutable.Set.from(globalTypes.map(toType)))
    } else {
      Seq.empty
    }

    val withoutExplicitNamespace = Set("" -> mutable.Set.from(otherTypes.map(toType)))

    val mapping = mutable.Map.from(withExplicitNamespace ++ withGlobalNamespace ++ withoutExplicitNamespace)
    CSharpProgramSummary(mapping, imports, globalImports)
  }

}
