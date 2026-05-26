# pysrc2cpg FullName Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 pysrc2cpg 中全量重写 `METHOD/TYPE_DECL/CALL.methodFullName` 的命名体系，使其以 `moduleFullName` 为根、AST 阶段尽量确定填充，解析不了统一落 `<unresolvedNamespace>.*`，并保证 `CALL.name` 不被污染。

**Architecture:** 在每个文件解析后先生成 `moduleFullName` 与 `PythonSymbolSummary`（顶层 class/function 名集合），在 `PythonAstVisitor` 内维护 `PythonScope`（含 import alias 映射、enclosing class/method 栈），用于生成声明端 fullName 与调用端 methodFullName。

**Tech Stack:** Scala 3, sbt, Joern x2cpg/flatgraph, Scalatest, semanticcpg language.

---

## File Structure (Create/Modify)

**Create (main):**
- `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/ModuleFullName.scala`
- `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonSymbolSummary.scala`
- `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonScope.scala`

**Modify (main):**
- `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/CodeToCpg.scala`
- `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala`
- `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala`
- `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/NodeBuilder.scala`

**Create/Modify (tests):**
- Create: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/FullNameRewritePhaseBTests.scala`
- Modify (expected large diffs):  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/MethodCpgTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/CallCpgTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/ClassCpgTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/FunctionDefCpgTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/ModuleFunctionCpgTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/MemberCpgTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/VariableReferencingCpgTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/passes/TypeRecoveryPassTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/passes/InheritanceFullNamePassTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/passes/DynamicTypeHintFullNamePassTests.scala`  
  - `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/dataflow/DataFlowTests.scala`

---

### Task 1: moduleFullName 推导工具

**Files:**
- Create: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/ModuleFullName.scala`
- Test: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/FullNameRewritePhaseBTests.scala`

- [ ] **Step 1: Add ModuleFullName utility**

```scala
package io.joern.pysrc2cpg

import java.io.File

object ModuleFullName {
  def fromRelFileName(relFileName: String): String = {
    val normalized = relFileName.replace('\\', '/')
    val noExt =
      if (normalized.endsWith(".py")) normalized.dropRight(3)
      else normalized

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
```

- [ ] **Step 2: Add PhaseB tests skeleton and moduleFullName cases**

```scala
package io.joern.pysrc2cpg.cpg

import io.joern.pysrc2cpg.ModuleFullName
import io.joern.pysrc2cpg.testfixtures.PySrc2CpgFixture
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers

import java.io.File

class FullNameRewritePhaseBTests extends PySrc2CpgFixture(withPostProcessing = false) with Matchers {
  "Phase B fullName rewrite" should {
    "derive moduleFullName from relFileName" in {
      ModuleFullName.fromRelFileName("mod.py") shouldBe "mod"
      ModuleFullName.fromRelFileName(Seq("pkg", "sub", "mod.py").mkString(File.separator)) shouldBe "pkg.sub.mod"
      ModuleFullName.fromRelFileName(Seq("pkg", "__init__.py").mkString(File.separator)) shouldBe "pkg"
    }
  }
}
```

- [ ] **Step 3: Run tests to confirm compile**

Run: `sbt pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.FullNameRewritePhaseBTests`  
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/ModuleFullName.scala ^
  joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/FullNameRewritePhaseBTests.scala
git commit -m "feat(pysrc2cpg): add moduleFullName derivation utility"
```

---

### Task 2: PythonSymbolSummary（顶层声明预扫描）

**Files:**
- Create: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonSymbolSummary.scala`
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/CodeToCpg.scala`

- [ ] **Step 1: Add PythonSymbolSummary**

```scala
package io.joern.pysrc2cpg

import io.joern.pythonparser.ast

case class PythonSymbolSummary(topLevelFunctions: Set[String], topLevelClasses: Set[String])

object PythonSymbolSummary {
  def fromModule(module: ast.Module): PythonSymbolSummary = {
    val funcs  = scala.collection.mutable.HashSet.empty[String]
    val classes = scala.collection.mutable.HashSet.empty[String]

    module.stmts.foreach {
      case f: ast.FunctionDef => funcs.add(f.name)
      case c: ast.ClassDef    => classes.add(c.name)
      case _                  =>
    }

    PythonSymbolSummary(funcs.toSet, classes.toSet)
  }
}
```

- [ ] **Step 2: Wire summary + moduleFullName into CodeToCpg -> PythonAstVisitor**

Modify `CodeToCpg.runOnPart` in [CodeToCpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/CodeToCpg.scala#L21-L38) to:

```scala
override def runOnPart(diffGraph: DiffGraphBuilder, inputProvider: InputProvider): Unit = {
  val inputPair              = inputProvider()
  val parser                 = new PyParser()
  val lineBreakCorrectedCode = inputPair.content.replace("\r\n", "\n").replace("\r", "\n")
  try {
    val astRoot    = parser.parse(lineBreakCorrectedCode)
    val nodeToCode = new NodeToCode(lineBreakCorrectedCode)

    val moduleFullName = ModuleFullName.fromRelFileName(inputPair.relFileName)
    val symbolSummary  = PythonSymbolSummary.fromModule(astRoot)

    val astVisitor =
      new PythonAstVisitor(inputPair.relFileName, moduleFullName, symbolSummary, nodeToCode, PythonV2AndV3, enableFileContent)(
        schemaValidationMode
      )
    astVisitor.convert(astRoot)
    diffGraph.absorb(astVisitor.createAst())
  } catch {
    case exception: Throwable =>
      val lineBreakWasCorrected = lineBreakCorrectedCode != inputPair.content
      handleParsingError(inputPair.relFileName, lineBreakCorrectedCode, lineBreakWasCorrected, exception, diffGraph)
  }
}
```

- [ ] **Step 3: Run compile check**

Run: `sbt pysrc2cpg/compile`  
Expected: compile errors due to `PythonAstVisitor` constructor mismatch (fixed in next task)

- [ ] **Step 4: Commit**

```bash
git add joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonSymbolSummary.scala ^
  joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/CodeToCpg.scala
git commit -m "feat(pysrc2cpg): add per-file symbol summary and pass to visitor"
```

---

### Task 3: PythonScope（import + 顶层声明解析）

**Files:**
- Create: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonScope.scala`
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala`

- [ ] **Step 1: Add PythonScope**

```scala
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
```

- [ ] **Step 2: Update PythonAstVisitor constructor + store scope**

Update `class PythonAstVisitor(...)` signature to accept `(relFileName: String, moduleFullName: String, symbolSummary: PythonSymbolSummary, ...)` and create:

```scala
private val scope = new PythonScope(moduleFullName, symbolSummary)
```

- [ ] **Step 3: Commit**

```bash
git add joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonScope.scala ^
  joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala
git commit -m "feat(pysrc2cpg): introduce PythonScope for imports and top-level declarations"
```

---

### Task 4: 重写声明端 fullName（替换 calculateFullNameFromContext）

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala`
- Modify: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/FullNameRewritePhaseBTests.scala`

- [ ] **Step 1: Implement fullName helpers in PythonAstVisitor**

Replace [calculateFullNameFromContext](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala#L2180-L2183) with:

```scala
private def qualPartsForFullName: List[String] = {
  val raw = contextStack.qualName
  if (raw.isEmpty) Nil
  else {
    raw.split('.').toList.filterNot(_ == Constants.moduleName).filter(_.nonEmpty)
  }
}

private def fullNameOfDecl(name: String): String = {
  val prefix = (scope.moduleFullName :: qualPartsForFullName).filter(_.nonEmpty)
  (prefix :+ name).mkString(".")
}
```

Then update all places that computed `methodFullName` / `typeDeclFullName` / `typeFullName` from `calculateFullNameFromContext` to call `fullNameOfDecl(...)`.

Minimum required edits visible in:
- Module method creation: replace `val methodFullName = ...` in [convert(module)](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala#L96) with:

```scala
val methodFullName = fullNameOfDecl(Constants.moduleName)
```

- Method creation in [createMethodAndMethodRef](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala#L352-L376):

```scala
val methodFullName = fullNameOfDecl(methodName) + suffix
```

- Class meta/instance `TYPE_DECL.fullName` in [convert(classDef)](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala#L452-L470):

```scala
val metaTypeDeclFullName     = fullNameOfDecl(metaTypeDeclName)
val instanceTypeDeclFullName = fullNameOfDecl(instanceTypeDeclName)
```

- [ ] **Step 2: Extend PhaseB tests for method/typeDecl fullName**

Append to `FullNameRewritePhaseBTests`:

```scala
"rewrite METHOD.fullName to module-based naming" in {
  val path = Seq("a", "b.py").mkString(File.separator)
  val cpg  = code("def method():\n  pass\n", path)
  cpg.method.nameExact("method").head.fullName shouldBe "a.b.method"
}

"rewrite TYPE_DECL.fullName to module-based naming" in {
  val path = Seq("a", "b.py").mkString(File.separator)
  val cpg  = code("class Foo:\n  pass\n", path)
  cpg.typeDecl.nameExact("Foo").head.fullName shouldBe "a.b.Foo"
  cpg.typeDecl.nameExact("Foo<meta>").head.fullName shouldBe "a.b.Foo<meta>"
}
```

- [ ] **Step 3: Run tests**

Run: `sbt pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.FullNameRewritePhaseBTests`  
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala ^
  joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/FullNameRewritePhaseBTests.scala
git commit -m "feat(pysrc2cpg): rewrite declaration fullName to module-based scheme"
```

---

### Task 5: NodeBuilder.callNode API 分离 name 与 methodFullName

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/NodeBuilder.scala`
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala`
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala`

- [ ] **Step 1: Update NodeBuilder.callNode signature**

Replace the current method in [NodeBuilder.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/NodeBuilder.scala#L18-L31) with:

```scala
def callNode(
  code: String,
  name: String,
  methodFullName: String,
  dispatchType: String,
  lineAndColumn: LineAndColumn
): nodes.NewCall = {
  val callNode = nodes
    .NewCall()
    .code(code)
    .name(name)
    .methodFullName(methodFullName)
    .dispatchType(dispatchType)
    .typeFullName(Constants.ANY)
    .lineNumber(lineAndColumn.line)
    .columnNumber(lineAndColumn.column)
    .offset(lineAndColumn.offset)
    .offsetEnd(lineAndColumn.endOffset)
  addNodeToDiff(callNode)
}
```

- [ ] **Step 2: Update all call sites (compile-driven)**

Minimum mechanical edits:
- operator calls (example from [PythonAstVisitor.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala#L1291-L1295)):

```scala
val callNode =
  nodeBuilder.callNode(code, "<operator>.raise", "<operator>.raise", DispatchTypes.STATIC_DISPATCH, lineAndColOf(raise))
```

- [createCall](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala#L349-L377) and [createInstanceCall](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala#L379-L410) will be fully rewritten in Task 6.

- [createStaticCall](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala#L448-L479) must be updated to keep `CALL.name = name`:

```scala
val callNode = nodeBuilder.callNode(code, name, methodFullName, DispatchTypes.STATIC_DISPATCH, lineAndColumn)
```

- [ ] **Step 3: Compile**

Run: `sbt pysrc2cpg/compile`  
Expected: compile errors only in unresolved call builder paths, fixed after Task 6

- [ ] **Step 4: Commit**

```bash
git add joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/NodeBuilder.scala ^
  joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala ^
  joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala
git commit -m "refactor(pysrc2cpg): decouple CALL.name from CALL.methodFullName"
```

---

### Task 6: CALL.methodFullName 解析（import 命中 + 本模块声明 + unresolved）

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala`
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala`
- Test: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/FullNameRewritePhaseBTests.scala`

- [ ] **Step 1: Add call resolution helpers to PythonAstVisitor**

Add:

```scala
private def callFullNameForSimple(name: String): String = {
  scope.resolveImported(name)
    .orElse(if (scope.isTopLevelFunction(name)) Some(s"${scope.moduleFullName}.$name") else None)
    .getOrElse(scope.unresolvedCall(name))
}

private def callFullNameForAttribute(xName: Option[String], y: String): String = {
  xName.flatMap(scope.resolveImported) match {
    case Some(modOrSymbol) => s"$modOrSymbol.$y"
    case None              => scope.unresolvedCall(y)
  }
}

private def callFullNameForCtor(name: String): String = {
  scope.resolveImported(name) match {
    case Some(typeOrSymbol) => s"$typeOrSymbol.__init__"
    case None if scope.isTopLevelClass(name) =>
      s"${scope.moduleFullName}.$name.__init__"
    case _ =>
      scope.unresolvedCall(name)
  }
}
```

- [ ] **Step 2: Update import conversion to populate scope.imports**

In [convert(importStmt)](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala#L1331-L1333) and [convert(importFrom)](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala#L1344-L1353), before calling `createTransformedImport(...)`, update `scope`:

For `import x as y`:
- localName = `asName.getOrElse(name)`
- targetFullName = `x` normalized with `.` (keep as-is; do not resolve filesystem here)

For `from m import n as a`:
- localName = alias
- targetFullName = `s"$m.$n"` with leading relative dots stripped (treat leading dots as unresolved for now)

Concrete code snippet (inside each convert):

```scala
importStmt.names.foreach { alias =>
  val local = alias.asName.getOrElse(alias.name).split('.').headOption.getOrElse(alias.name)
  val target = alias.name
  scope.addImport(local, target)
}
```

```scala
val modulePart = importFrom.module.getOrElse("")
importFrom.names.foreach { alias =>
  val local = alias.asName.getOrElse(alias.name)
  val target = if (modulePart.isEmpty) alias.name else s"$modulePart.${alias.name}"
  scope.addImport(local, target)
}
```

- [ ] **Step 3: Rewrite createCall/createInstanceCall to set methodFullName**

Update [createCall](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala#L349-L377):

```scala
val methodFullName = callFullNameForSimple(name)
val callNode = nodeBuilder.callNode(code, name, methodFullName, DispatchTypes.DYNAMIC_DISPATCH, lineAndColumn)
```

Update [createInstanceCall](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala#L379-L410):

```scala
val methodFullName = callFullNameForAttribute(Some(codeOf(instanceNode)), name) match {
  case x if x.startsWith("<") => callFullNameForAttribute(None, name)
  case x                      => x
}
val callNode = nodeBuilder.callNode(code, name, methodFullName, DispatchTypes.DYNAMIC_DISPATCH, lineAndColumn)
```

Then in `convert(call: ast.Call)` branch for `foo(...)` vs `x.y(...)`, add ctor detection:
- if receiver is `ast.Name` and `scope.isTopLevelClass(name)` or `scope.resolveImported(name).exists(_.headOption.exists(_.isUpper))`, call `callFullNameForCtor(name)`

- [ ] **Step 4: Add PhaseB tests for call methodFullName**

Append to `FullNameRewritePhaseBTests`:

```scala
"fill CALL.methodFullName for same-module calls" in {
  val cpg = code("def f(a,b):\n  return a+b\n\nx = f(1,2)\n", "test.py")
  cpg.call.nameExact("f").head.methodFullName shouldBe "test.f"
}

"fill CALL.methodFullName for unresolved calls" in {
  val cpg = code("x = unknown(1)\n", "test.py")
  cpg.call.nameExact("unknown").head.methodFullName shouldBe "<unresolvedNamespace>.unknown"
}

"fill CALL.methodFullName for import calls" in {
  val cpg = code("from foo import bar as baz\nx = baz(1)\n", "test.py")
    .moreCode("def bar(x):\n  return x\n", "foo.py")
  cpg.call.nameExact("baz").head.methodFullName shouldBe "foo.bar"
}
```

- [ ] **Step 5: Run tests**

Run: `sbt pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.FullNameRewritePhaseBTests`  
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala ^
  joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala ^
  joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/FullNameRewritePhaseBTests.scala
git commit -m "feat(pysrc2cpg): fill CALL.methodFullName using imports, locals, and unresolved fallback"
```

---

### Task 7: 更新核心 CPG 测试（Method/Call/Class 等）

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/MethodCpgTests.scala`
- Modify: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/CallCpgTests.scala`
- (and the rest listed in File Structure)

- [ ] **Step 1: Update MethodCpgTests expected fullNames**

In [MethodCpgTests.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/MethodCpgTests.scala#L9-L55), update:

```scala
method.fullName shouldBe "a.b.method"
```

and:

```scala
List(
  ("method", "a.b.Foo.method"),
  ("method", "a.b.Foo.method$redefinition1"),
  ("method", "a.b.Foo.method$redefinition2")
)
```

as well as:

```scala
List("a.b.Foo.method$redefinition2")
List("a.b.Foo.method<metaClassAdapter>")
```

- [ ] **Step 2: Update CallCpgTests expected methodFullName**

Update [CallCpgTests.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/CallCpgTests.scala#L178-L295):

```scala
callNode.methodFullName shouldBe "test.func"
```

and import test expectations:

```scala
callNode.methodFullName shouldBe "foo.foo_func"
callNode.methodFullName shouldBe "foo.bar.bar_func"
callNode.methodFullName shouldBe "foo.faz"
```

Update identifier returnValue typeFullName similarly:

```scala
x.typeFullName shouldBe "foo.foo_func.<returnValue>"
y.typeFullName shouldBe "foo.bar.bar_func.<returnValue>"
z.typeFullName shouldBe "foo.faz.<returnValue>"
```

- [ ] **Step 3: Run focused tests**

Run: `sbt pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.MethodCpgTests`  
Expected: PASS  

Run: `sbt pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.CallCpgTests`  
Expected: PASS

- [ ] **Step 4: Update remaining failing test files by applying the same mapping**

Mapping rules to apply:
- `<path>.py:<module>.X` -> `<moduleFullName>.X`
- `Seq("foo","bar","__init__.py").mkString(File.separator):<module>.f` -> `foo.bar.f`
- `...<returnValue>` suffix remains unchanged, only前缀替换

For each file listed in “File Structure / Modify (tests)”:
- run `sbt pysrc2cpg/testOnly <TestClassName>`
- update expected strings using the mapping rules above until that test file passes

- [ ] **Step 5: Run full suite**

Run: `sbt pysrc2cpg/test`  
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg
git commit -m "test(pysrc2cpg): update expectations for module-based fullName scheme"
```

---

## Plan Self-Review

**Spec coverage:**
- moduleFullName 推导：Task 1
- 声明端 fullName：Task 4
- CALL.methodFullName（import/local/unresolved）：Task 6
- CALL.name 不被污染：Task 5 + Task 6/7 覆盖
- 测试锁定：Task 1/4/6/7

**Placeholder scan:** 本计划避免 TBD/TODO；唯一需要实现时迭代的是“更新剩余 failing 测试文件”，通过确定的映射规则与逐文件 `testOnly` 方式完成。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-25-pysrc2cpg-fullname-rewrite-plan.md`. Two execution options:

1. Subagent-Driven (recommended) - I dispatch a fresh subagent per task, review between tasks, fast iteration
2. Inline Execution - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
