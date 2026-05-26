# pysrc2cpg Phase A Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以 php2cpg 为参照，将 pysrc2cpg 的“节点/命名约定与 pipeline 骨架”做规范化对齐，并保持默认尽量兼容现有 Python 查询与测试。

**Architecture:** Phase A 聚焦“结构对齐”而非替换解析器：统一使用 MetaDataPass 写入 META_DATA/root，移除手写 ANY 壳，补齐 TypeNodePass，并对齐关键节点属性（优先 METHOD_RETURN.evaluationStrategy）。

**Tech Stack:** Scala 3, sbt multi-module, CPG/flatgraph, x2cpg passes, ScalaTest.

---

## Files Map

**Modify**
- `d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2Cpg.scala`
- `d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/NodeBuilder.scala`

**Add**
- `d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/AlignmentPhaseATests.scala`

**Docs (optional)**
- Update `d:/program/Ljoern/docs/superpowers/specs/2026-05-22-pysrc2cpg-alignment-design.md` if design changes.

---

### Task 1: 用 MetaDataPass 替换 pysrc2cpg 的手写 META/root/ANY 壳

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2Cpg.scala`
- Test: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/AlignmentPhaseATests.scala`

- [ ] **Step 1: 写一个会失败的测试（META_DATA.root 规范化 + ANY type 存在）**

创建 `AlignmentPhaseATests.scala`：

```scala
package io.joern.pysrc2cpg.cpg

import io.joern.pysrc2cpg.testfixtures.PySrc2CpgFixture
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

class AlignmentPhaseATests extends PySrc2CpgFixture(withPostProcessing = false) with Matchers {
  "Phase A alignment" should {
    "write META_DATA.root as absolute path" in {
      val cpg = code("pass", "test.py")
      val root = cpg.metaData.root.headOption.getOrElse("<empty>")
      root shouldBe Paths.get(root).toAbsolutePath.normalize.toString
    }

    "ensure TYPE node ANY exists via TypeNodePass" in {
      val cpg = code("pass", "test.py")
      cpg.typ.nameExact("ANY").head.fullName shouldBe "ANY"
    }
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
sbt "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.AlignmentPhaseATests"
```

Expected:
- META_DATA.root 断言失败（当前 root 非 absolute 或带尾分隔符）
- 或 TYPE(ANY) 不存在（当前前端不通过 TypeNodePass 统一补齐 types）

- [ ] **Step 3: 修改 Py2Cpg.buildCpg 改为标准 pipeline**

将 `Py2Cpg.buildCpg()` 从“手写 diffGraph + ANY 壳 + applyDiff”重构为“标准 pass 顺序”：

目标结构（示意）：

```scala
import io.joern.x2cpg.passes.frontend.{MetaDataPass, TypeNodePass}
import io.shiftleft.codepropertygraph.generated.Languages

def buildCpg(): Unit = {
  new MetaDataPass(outputCpg, Languages.PYTHONSRC, config.inputPath).createAndApply()
  new CodeToCpg(outputCpg, inputProviders, config.schemaValidation, !config.disableFileContent).createAndApply()
  new ConfigFileCreationPass(outputCpg, config.requirementsTxt, config).createAndApply()
  new DependenciesFromRequirementsTxtPass(outputCpg).createAndApply()
  TypeNodePass.withTypesFromCpg(outputCpg).createAndApply()
}
```

并删除以下遗留点（全部来自当前 `Py2Cpg.scala`）：

- `diffGraph/nodeBuilder/edgeBuilder/DiffGraphApplier.applyDiff(...)` 相关逻辑
- 手工创建的 `ANY` TYPE/TYPE_DECL 以及 `TYPE_DECL -> NAMESPACE_BLOCK` 的 AST 边
- `metaNode(...).root(...)` 手写 meta 写入（由 MetaDataPass 取代）

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
sbt "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.AlignmentPhaseATests"
```

Expected: PASS

- [ ] **Step 5: 全量跑 pysrc2cpg 单测**

Run:

```bash
sbt pysrc2cpg/test
```

Expected: PASS（已有大量测试覆盖 `<module>` 命名约定，应保持兼容）

- [ ] **Step 6: Commit**

```bash
git add joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2Cpg.scala \
        joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/AlignmentPhaseATests.scala
git commit -m "refactor(pysrc2cpg): normalize pipeline with MetaDataPass and TypeNodePass"
```

---

### Task 2: 对齐 METHOD_RETURN.evaluationStrategy（BY_SHARING → BY_VALUE）

**Files:**
- Modify: `joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/NodeBuilder.scala`
- Test: `joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/AlignmentPhaseATests.scala`

- [ ] **Step 1: 写一个会失败的测试（methodReturn.evaluationStrategy）**

在 `AlignmentPhaseATests` 增加：

```scala
import io.shiftleft.codepropertygraph.generated.EvaluationStrategies

"align METHOD_RETURN evaluationStrategy" in {
  val cpg = code("def f():\n  return 1\n", "test.py")
  val mr = cpg.method.name("f").methodReturn.head
  mr.evaluationStrategy shouldBe EvaluationStrategies.BY_VALUE
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
sbt "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.AlignmentPhaseATests"
```

Expected: FAIL（当前 pysrc2cpg 为 BY_SHARING）

- [ ] **Step 3: 修改 NodeBuilder.methodReturnNode 的默认策略**

在 [NodeBuilder.methodReturnNode](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/NodeBuilder.scala#L189-L206) 中将：

```scala
.evaluationStrategy(EvaluationStrategies.BY_SHARING)
```

改为：

```scala
.evaluationStrategy(EvaluationStrategies.BY_VALUE)
```

（对齐 x2cpg 默认实现：见 [AstNodeBuilder.methodReturnNodeWithExplicitPositionInfo](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/AstNodeBuilder.scala#L453-L470)）

- [ ] **Step 4: 运行测试确认通过 + 全量测试**

```bash
sbt "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.AlignmentPhaseATests"
sbt pysrc2cpg/test
```

- [ ] **Step 5: Commit**

```bash
git add joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/NodeBuilder.scala \
        joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/AlignmentPhaseATests.scala
git commit -m "refactor(pysrc2cpg): align METHOD_RETURN evaluation strategy with x2cpg"
```

---

### Task 3: 回归与对齐风险评估（输出差异清单）

**Files:**
- Modify (optional): `docs/frontends/php2cpg-vs-pysrc2cpg-nodes.html`
- Add (optional): `docs/frontends/pysrc2cpg-phase-a-changelog.md`

- [ ] **Step 1: 运行关键现有用例（确保默认兼容）**

重点验证现有命名约定仍可用（例如 [MethodCpgTests](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/test/scala/io/joern/pysrc2cpg/cpg/MethodCpgTests.scala#L21-L25) 依赖 `<module>` fullName）：

```bash
sbt "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.MethodCpgTests"
sbt "pysrc2cpg/testOnly io.joern.pysrc2cpg.cpg.ModuleFunctionCpgTests"
```

- [ ] **Step 2: 记录“结构变更点”（Phase A 输出）**

在 `docs/frontends/pysrc2cpg-phase-a-changelog.md` 记录：

- META_DATA.root 规范化（absolute）
- TYPE 节点由 TypeNodePass 统一补齐（ANY 确保存在）
- METHOD_RETURN.evaluationStrategy 对齐为 BY_VALUE

- [ ] **Step 3: Commit（可选）**

```bash
git add docs/frontends/pysrc2cpg-phase-a-changelog.md
git commit -m "docs(pysrc2cpg): record phase A alignment changes"
```

---

## Plan Self-Review

- Spec coverage:
  - MetaData/root 统一：Task 1
  - Type 节点策略统一：Task 1
  - METHOD_RETURN 对齐：Task 2
  - 兼容性与回归：Task 3
- Placeholder scan: 无 TBD/TODO；每步给出文件与代码。
- Type consistency: 使用 `Languages.PYTHONSRC`、`MetaDataPass`、`TypeNodePass.withTypesFromCpg`、`EvaluationStrategies.BY_VALUE` 与现有工程一致。

---

Plan complete and saved to `docs/superpowers/plans/2026-05-22-pysrc2cpg-alignment-plan.md`. Two execution options:

1. Subagent-Driven (recommended) - I dispatch a fresh subagent per task, review between tasks, fast iteration
2. Inline Execution - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?

