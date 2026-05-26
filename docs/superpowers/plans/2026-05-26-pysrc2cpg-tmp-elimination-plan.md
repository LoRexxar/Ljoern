# pysrc2cpg tmp Elimination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `tmp\d+` intermediate locals from pysrc2cpg output by changing AST→CPG lowering for call chains, destructuring assignments, and comprehensions.

**Architecture:** Avoid generating `tmp*` locals in the visitor/lowering helpers. For cases that previously needed tmp to preserve single-evaluation semantics, allow duplicated subtrees (user accepted semantic approximation). Represent comprehensions as operator calls rather than expanded loops with temp containers.

**Tech Stack:** Scala 3, sbt, Joern x2cpg, pysrc2cpg frontend, ScalaTest.

---

## Files to Change / Add

**Modify**
- `d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala`
- `d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala` (only if needed for new helpers/wiring)
- `d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/frontendspecific/pysrc2cpg/Constants.scala` (extend `PythonOperators`)

**Create**
- `d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/TmpEliminationTests.scala`

---

### Task 1: Add regression tests for “no tmp locals”

**Files:**
- Create: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/TmpEliminationTests.scala`

- [ ] **Step 1: Create the test file skeleton**

```scala
package io.joern.pysrc2cpg.cpg

import io.joern.pysrc2cpg.testfixtures.PySrc2CpgFixture
import io.shiftleft.semanticcpg.language.*

class TmpEliminationTests extends PySrc2CpgFixture(withOssDataflow = false) {

  private def assertNoTmpLocals(cpg: io.shiftleft.codepropertygraph.generated.Cpg): Unit = {
    cpg.local.name("tmp\\d+").l shouldBe Nil
  }

  "tmp elimination" should {

    "not introduce tmp locals for chained calls" in {
      val cpg = code("""
          |def get():
          |  return client
          |
          |class C:
          |  def upload(self, x):
          |    pass
          |
          |client = C()
          |
          |get().upload(1)
          |""".stripMargin).cpg
      assertNoTmpLocals(cpg)
    }

    "not introduce tmp locals for attribute call chains" in {
      val cpg = code("""
          |def get():
          |  return api
          |
          |class Api:
          |  def client(self):
          |    return self
          |  def upload(self, x):
          |    pass
          |
          |api = Api()
          |get().client().upload(1)
          |""".stripMargin).cpg
      assertNoTmpLocals(cpg)
    }

    "not introduce tmp locals for destructuring assignment" in {
      val cpg = code("""
          |def foo():
          |  return (1, 2)
          |
          |a, b = foo()
          |""".stripMargin).cpg
      assertNoTmpLocals(cpg)
    }

    "not introduce tmp locals for starred destructuring assignment" in {
      val cpg = code("""
          |def foo():
          |  return (1, 2, 3, 4)
          |
          |a, *b, c = foo()
          |""".stripMargin).cpg
      assertNoTmpLocals(cpg)
    }

    "not introduce tmp locals for list comprehensions" in {
      val cpg = code("""
          |xs = [1,2,3]
          |ys = [x + 1 for x in xs if x > 1]
          |""".stripMargin).cpg
      assertNoTmpLocals(cpg)
    }

    "not introduce tmp locals for dict comprehensions" in {
      val cpg = code("""
          |xs = [(1,2),(3,4)]
          |m = {k: v for k,v in xs}
          |""".stripMargin).cpg
      assertNoTmpLocals(cpg)
    }

    "not introduce tmp locals for generator comprehensions" in {
      val cpg = code("""
          |xs = [1,2,3]
          |g = (x + 1 for x in xs)
          |""".stripMargin).cpg
      assertNoTmpLocals(cpg)
    }
  }
}
```

- [ ] **Step 2: Run the test and confirm it fails (current behavior)**

Run:

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.TmpEliminationTests"
```

Expected: FAIL, because current lowering introduces `tmp\d+` locals for at least call-chain and comprehensions.

---

### Task 2: Eliminate tmp lowering for chained calls (`x.y(args)`)

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala`

- [ ] **Step 1: Add a helper to duplicate subtrees safely**

Add a local helper near `createXDotYCall`:

```scala
  private def dupNode[T <: NewNode](mk: () => T): () => T = () => mk()
```

We will use providers (functions) to ensure the receiver and instance nodes are distinct.

- [ ] **Step 2: Rewrite `createXDotYCall` to never create tmp locals**

Replace the `if (xMayHaveSideEffects)` branch with a unified implementation that builds two distinct x-subtrees:

```scala
  protected def createXDotYCall(
    x: () => NewNode,
    y: String,
    xMayHaveSideEffects: Boolean,
    lineAndColumn: LineAndColumn,
    argumentNodes: Iterable[NewNode],
    keywordArguments: Iterable[(String, NewNode)],
    callAstNode: Option[ast.iast]
  ): NewNode = {
    val xForReceiver  = x()
    val xForInstance  = x()
    val receiverNode  = createFieldAccess(xForReceiver, y, lineAndColumn)
    val instanceNode  = xForInstance
    createInstanceCall(receiverNode, instanceNode, y, lineAndColumn, argumentNodes, keywordArguments, callAstNode)
  }
```

Also remove now-dead tmp-specific lowering code to prevent any `createAssignmentToIdentifier(tmpVarName, ...)` paths.

- [ ] **Step 3: Run a focused suite**

Run:

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.CallCpgTests"
```

Expected: May require rebaseline if some tests assume the temporary block structure; update assertions as needed to match new structure.

---

### Task 3: Eliminate tmp lowering for destructuring assignments

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala`

- [ ] **Step 1: Introduce a provider-based decomposition API**

Change the signature:

```scala
  protected def createValueToTargetsDecomposition(
    targets: Iterable[ast.iexpr],
    valueProvider: () => NewNode,
    lineAndColumn: LineAndColumn
  ): Iterable[NewNode]
```

- [ ] **Step 2: Update the “single target” fast path**

```scala
    if (
      targets.size == 1 &&
      !targets.head.isInstanceOf[ast.Tuple] &&
      !targets.head.isInstanceOf[ast.List]
    ) {
      val targetNode = convert(targets.head)
      Iterable.single(createAssignment(targetNode, valueProvider(), lineAndColumn))
    } else {
      ...
    }
```

- [ ] **Step 3: Replace tmp-based lowering with direct index/slice access**

In the multi-target branch, remove:

```scala
      val tmpVariableName = getUnusedName()
      val tmpVariableAssignNode = createAssignmentToIdentifier(tmpVariableName, valueNode, lineAndColumn)
      loweredAssignNodes.append(tmpVariableAssignNode)
```

And instead, for each target decomposition, create a fresh base value for each assignment:

```scala
      targets.foreach { target =>
        val targetWithAccessChains = getTargetsWithAccessChains(target)
        targetWithAccessChains.foreach { case (trgt, accessChain, starredInfoOpt) =>
          val targetNode = convert(trgt)
          val baseValue  = valueProvider()
          val sourceNode = starredInfoOpt match {
            case Some(starredInfo) =>
              val baseNode = if (accessChain.tail.nonEmpty) {
                createIndexAccessChain(baseValue, accessChain.tail, lineAndColumn)
              } else {
                baseValue
              }
              val upperIndex = if (starredInfo.countAfter == 0) None else Some(-starredInfo.countAfter)
              createSliceCall(baseNode, starredInfo.position, upperIndex, lineAndColumn)
            case None =>
              createIndexAccessChain(baseValue, accessChain, lineAndColumn)
          }
          loweredAssignNodes.append(createAssignment(targetNode, sourceNode, lineAndColumn))
        }
      }
```

- [ ] **Step 4: Update all call sites of `createValueToTargetsDecomposition`**

Search for current invocations that pass a `valueNode: NewNode`. Replace with `() => valueNodeProducer` style providers.

Example transformation:

Before:

```scala
createValueToTargetsDecomposition(targets, convert(valueExpr), lineAndCol)
```

After:

```scala
createValueToTargetsDecomposition(targets, () => convert(valueExpr), lineAndCol)
```

- [ ] **Step 5: Run destructuring-related suites**

Run:

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.AssignCpgTests"
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.StarredTargetCpgTests"
```

Expected: Some assertions may reference tmp locals; rebaseline to the new structure.

---

### Task 4: Replace comprehension lowering with operator-call representation

**Files:**
- Modify: `joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/frontendspecific/pysrc2cpg/Constants.scala`
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitorHelpers.scala`

- [ ] **Step 1: Extend `PythonOperators`**

In `Constants.scala`, add:

```scala
object PythonOperators {
  val slice: String = "<operator>.slice"
  val listComprehension: String = "<operator>.listComprehension"
  val dictComprehension: String = "<operator>.dictComprehension"
  val setComprehension: String  = "<operator>.setComprehension"
  val genComprehension: String  = "<operator>.genComprehension"
}
```

- [ ] **Step 2: Add a helper to create an operator call node**

In `PythonAstVisitorHelpers.scala`, add:

```scala
  private def createOperatorCall(
    name: String,
    methodFullName: String,
    lineAndColumn: LineAndColumn,
    callAstNode: Option[ast.iast]
  ): NewCall = {
    val code = callAstNode.map(new AstPrinter("").print).getOrElse(name)
    val receiver = createIdentifierNode(name, Load, lineAndColumn)
    val callNode = nodeBuilder.callNode(code, name, methodFullName, DispatchTypes.STATIC_DISPATCH, lineAndColumn)
    edgeBuilder.astEdge(receiver, callNode, 1)
    edgeBuilder.receiverEdge(receiver, callNode)
    callNode
  }
```

If `DispatchTypes` import is missing in this scope, use the existing `DispatchTypes` already imported by the file.

- [ ] **Step 3: Replace `createComprehensionLowering` usage sites**

Identify where list/dict/set/generator comprehensions are converted (look for pattern that currently allocates `tmpVariableName` and calls `createComprehensionLowering`). Replace with operator calls:

- list comprehension → `createOperatorCall("listComprehension", PythonOperators.listComprehension, ...)`
- dict comprehension → `createOperatorCall("dictComprehension", PythonOperators.dictComprehension, ...)`
- set comprehension  → `createOperatorCall("setComprehension", PythonOperators.setComprehension, ...)`
- generator expr     → `createOperatorCall("genComprehension", PythonOperators.genComprehension, ...)`

The created operator call should be returned as the expression node (no block/tmp/append).

- [ ] **Step 4: Update/disable tests that assert loop/tmp structure**

Run:

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.ComprehensionCpgTests"
```

Rebaseline expectations to the new operator-call representation.

---

### Task 5: Make TmpEliminationTests pass + full pysrc2cpg regression

**Files:**
- Test: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/TmpEliminationTests.scala`

- [ ] **Step 1: Run TmpEliminationTests**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.TmpEliminationTests"
```

Expected: PASS with `cpg.local.name("tmp\\d+").l shouldBe Nil`.

- [ ] **Step 2: Run full pysrc2cpg test**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/test"
```

Expected: PASS (may require additional rebaseline for tests that assumed tmp-based lowering).

---

## Self-Review Checklist

- Search plan for placeholders: none.
- Spec coverage:
  - Call-chain tmp removal → Task 2
  - Destructuring tmp removal → Task 3
  - Comprehension tmp removal → Task 4
  - Verification and regression → Task 1 + Task 5
- Type consistency: operator names use `PythonOperators` with `<operator>.*` full names; no tmp locals should remain.

---

## Phase 2: Eliminate remaining synthetic locals (for/with/dict-unpack/compare/inheritance)

### Task 6: Extend TmpEliminationTests to cover phase-2 patterns

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/TmpEliminationTests.scala`

- [ ] **Step 1: Extend the “no synthetic locals” assertion**

Replace the helper with:

```scala
  private def assertNoSyntheticLocals(cpg: Cpg): Unit = {
    cpg.local.name("tmp\\d+").l shouldBe Nil
    cpg.local.name("(manager|enter|exit|value)\\d+").l shouldBe Nil
  }
```

And update all call sites to use `assertNoSyntheticLocals`.

- [ ] **Step 2: Add phase-2 samples**

Append tests:

```scala
    "not introduce synthetic locals for compare chains" in {
      val cpg = code("""x = 1; y = 2; z = 3; a = 4; b = x < y < z < a""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce synthetic locals for dict literals with unpack" in {
      val cpg = code("""z = {"k": 1}; x = {"a": 1, **z}""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce synthetic locals for for lowering" in {
      val cpg = code("""xs=[1,2,3]; for x in xs: pass""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce synthetic locals for with lowering" in {
      val cpg = code("""
          |class M:
          |  def __enter__(self): return self
          |  def __exit__(self, t, v, tb): return False
          |m = M()
          |with m as x:
          |  pass
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce synthetic locals for inheritance call bases" in {
      val cpg = code("""
          |def Foo(): return object
          |class X(Foo()):
          |  pass
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }
```

- [ ] **Step 3: Run tests and confirm failures (baseline)**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.TmpEliminationTests"
```

Expected: FAIL before implementing the following tasks.

---

### Task 7: Remove tmp in inheritance base handling

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala` (class conversion, `handleInheritance`)

- [ ] **Step 1: Remove `tmpVar = <call>` lowering**

For `case (x: ast.Call) :: xs => ...`, replace the assignment-based approach with a direct string extraction:

- Prefer `nodeToCode.getCode(x)` if present
- Otherwise use `codeOf(convert(x))`

Return the chosen string as the `inheritsFromTypeFullName` entry.

- [ ] **Step 2: Run focused inheritance tests**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.InheritanceFullNamePassTests"
```

---

### Task 8: Remove synthetic iterator variable in for lowering

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala` (`createForLowering`)

- [ ] **Step 1: Remove `iterVariableName` and its assignment**

Instead of:
- `iterator = y.__iter__()`
- `iterator.__next__()`

Use a nested call chain inside the target assignment provider:

```scala
() =>
  createXDotYCall(
    () =>
      createXDotYCall(
        () => convert(iter),
        "__iter__",
        xMayHaveSideEffects = !iter.isInstanceOf[ast.Name],
        lineAndColumn,
        Nil,
        Nil,
        None
      ),
    "__next__",
    xMayHaveSideEffects = true,
    lineAndColumn,
    Nil,
    Nil,
    None
  )
```

And delete the `iterAssignNode` statement from the surrounding block.

- [ ] **Step 2: Run for-related suites**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.ForCpgTests"
```

---

### Task 9: Remove synthetic locals in with lowering

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala` (`convertWithItem`)

- [ ] **Step 1: Inline `context_expr` and derived calls (no manager/enter/exit/value locals)**

Rewrite `convertWithItem` to:
- Build `managerExpr` twice via `convert(withItem.context_expr)` (distinct subtrees)
- Build `enterCallExpr` as `createFieldAccess(managerExprForEnter, "__enter__", ...)`
- Build `valueExpr` as `createInstanceCall(enterCallExpr, managerExprForValue, "", ...)`
- For `optional_vars`, pass `() => valueExprProvider()` into `createValueToTargetsDecomposition`
- For final block, build `exitCallExpr` as `createFieldAccess(managerExprForExit, "__exit__", ...)` and `createInstanceCall(exitCallExpr, managerExprForExitInstance, "", ...)`

Delete all `createAssignmentToIdentifier(getUnusedName("..."), ...)` statements.

- [ ] **Step 2: Run try/with-related suites**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.TryCpgTests"
```

---

### Task 10: Remove tmp in dict literals with unpack (`{x:1, **z}`)

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala` (`convert(dict: ast.Dict)`)
- Modify: `joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/frontendspecific/pysrc2cpg/Constants.scala` (extend `PythonOperators`)

- [ ] **Step 1: Add operator full name**

Add:

```scala
  val dictLiteralWithUnpack: String = "<operator>.dictLiteralWithUnpack"
```

- [ ] **Step 2: Replace tmp-container lowering with a single operator call**

If `dict.keys` contains `None` (the `**z` case), create:
- `CALL.name = "dictLiteralWithUnpack"`
- `CALL.methodFullName = PythonOperators.dictLiteralWithUnpack`
- args: for each `Some(key)` add `convert(key)` then `convert(value)`; for each `None` add `convert(value)` only

If there is no `None` (no unpack), keep the existing `<operator>.dictLiteral` implementation.

- [ ] **Step 3: Run dict-related suites**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.DictCpgTests"
```

---

### Task 11: Remove tmp in compare-chain lowering

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala` (`lowerComparatorChain`)

- [ ] **Step 1: Rewrite lowering as pure “and-of-compares”**

Instead of allocating `tmpVariableName`, build:

- `compare1 = lhs < rhs`
- `rest    = lowerComparatorChain(rhsCopy, tailOps, tailComparators, ...)`
- `return  = compare1 and restExpr`

To avoid node re-use, always compute `rhs` via a provider:
- `val rhs1 = () => convert(comparators.head)`
- use `rhs1()` for `compare1`
- use a fresh `rhs2()` for recursion lhs

- [ ] **Step 2: Run compare-related suites**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.CompareCpgTests"
```

---

### Task 12: Final verification

- [ ] **Step 1: Run TmpEliminationTests**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.TmpEliminationTests"
```

- [ ] **Step 2: Run full pysrc2cpg test**

```bash
& "c:\Users\lorex\AppData\Local\Coursier\data\bin\sbt.bat" "pysrc2cpg/test"
```

Expected: PASS.
