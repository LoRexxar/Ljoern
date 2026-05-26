# pysrc2cpg 架构对齐重构设计（Phase A）

## 1. 背景与目标

pysrc2cpg（Python 源码前端）当前在节点模型与命名约定上与多数前端（尤其 php2cpg）存在显著差异，导致：

- 跨语言查询难以复用
- post-processing/overlays 的结果不稳定、难预测
- 工程结构更偏“手写生成”，缺乏可组合的 pipeline 约束

本设计的目标是先做 Phase A：在不更换解析器与 AST Visitor 的前提下，对齐“节点/命名约定”与“管线骨架”，并尽量保持对现有 Python 查询/扫描规则的兼容。

## 2. 非目标（Phase A 不做）

- 不替换 `PyParser` 或引入新的外部解析器/astgen
- 不引入 php2cpg 的“两遍解析”符号汇总策略（作为 Phase B 候选）
- 不对类型恢复算法本身做大改（只做接口与产物一致性约束）

## 3. 对齐参照

以 php2cpg 为黄金参照，优先对齐以下约定：

- META_DATA/root 的写入方式与 root 规范化
- 文件级 `<global>` namespace block 的 fullName 约定
- 顶层语句承载方式（global method）
- CALL.methodFullName 的默认策略（未链接阶段不得“伪装成已解析完成”）

## 4. 现状差异（摘要）

- pysrc2cpg 不使用 `MetaDataPass`，而是手写 diffGraph 写 meta/root/global namespace，并额外拼接路径分隔符
- pysrc2cpg 手工创建 `ANY` 的 `TYPE` 与 `TYPE_DECL` 并挂到 `<global>`，与其他前端的 `TypeNodePass` 机制不一致
- pysrc2cpg 的 CALL.methodFullName 在静态调用时直接设置为 `name`，与 php2cpg 更依赖 resolver/linker 的策略不一致

## 5. Phase A 设计

### 5.1 Pipeline 骨架（frontend 内）

将 pysrc2cpg 的 pipeline 固化为“四段式”，并与 x2cpg 生态对齐：

1) MetaData/Global namespace（统一 pass）
2) AST creation（保留现有 visitor）
3) Config/Dependency（保留现有 pass，但统一节点命名策略）
4) Types（统一通过 TypeNodePass 补齐，不在前端手工塞 ANY 壳）

### 5.2 META_DATA/root 统一

- 前端统一使用 `MetaDataPass(cpg, language, root)` 写入 META_DATA 与全局 `<global>` namespace block
- root 统一为 absolutePath（不做尾部分隔符拼接）
- pysrc2cpg 不再手写 meta/root/global namespace

兼容性：META_DATA 存在与否不应破坏已有查询，且多数查询只依赖 cpg 的结构与 fullName 约定。

### 5.3 NamespaceBlock 与 fullName 统一

引入“文件级 global namespace block”规则：

- 每个文件仍创建 `NewNamespaceBlock(name="<global>", fullName="<file>:<global>", filename="<file>")`
- fullName 统一使用 `MetaDataPass.getGlobalNamespaceBlockFullName(Some(file))`

保留 `MetaDataPass` 额外创建的“通用 global namespace block”（fullName 为 `<global>`），用于无法归属的节点或兼容场景。

### 5.4 顶层语句承载（global method）

在每个文件的 `<file>:<global>` 下创建一个“global method”承载模块顶层语句：

- 在 Legacy 模式下：保留现有 `<module>` method 的 name/fullName 逻辑，但强制其 AST parent 指向 `<file>:<global>` namespace block
- 在 Aligned 模式下：创建 `name="<global>"` 的 global method，使其 fullName 与 `<file>:<global>` 相匹配，并保留 `<module>` 作为别名或 stub（取决于兼容性评估）

Phase A 默认开启 Legacy 模式，提供可配置开关切换到 Aligned 模式。

### 5.5 CALL.methodFullName 默认策略

将 CALL.methodFullName 的默认策略分为两种模式：

- Legacy：保持现状，静态调用时使用 `name`
- Aligned：静态调用时也使用“可解析但未解析完成”的 methodFullName 格式：
  - 如果可确定当前 scope（例如在某 method 内），优先拼接 `<file>:<qual>.<name>`
  - 若不可确定，则使用 `<unknownFullName>` 或更明确的 `<file>:<global>.<name>`（由规则细化）

目标是让“未链接阶段”的 CALL 不出现过于乐观的 fullName，避免后续 linker 与查询出现不一致。

### 5.6 TYPE/ANY 节点策略

- 移除 pysrc2cpg 在 AST 前手工创建的 `ANY` TYPE/TYPE_DECL 壳
- 统一依赖 `TypeNodePass.withTypesFromCpg(cpg)` 在构建后补齐 `TYPE` 节点集合（确保 `ANY` 仍存在）
- 若存在必须要 `TYPE_DECL(ANY)` 的历史原因，改为在专门的 compatibility pass 中生成，并清晰标注 parent/edge 规则

## 6. 兼容性策略

目标：尽量兼容现有 Python 查询/扫描规则。

- 默认运行在 Legacy 模式
- 新增 `--naming-scheme`（或同义参数）用于切换 `legacy/aligned`
- 对 fullName 的强对齐仅在 aligned 模式生效
- 在 legacy 模式下新增节点（如 `<file>:<global>`）不得破坏现有 `<module>` 查询路径

## 7. 验收标准（Phase A）

结构一致性：

- META_DATA/root 由 MetaDataPass 生成，root 为 absolutePath
- 每个文件存在 `<file>:<global>` 的 namespace block
- 每个文件存在“顶层语句承载 method”，且其 AST parent 固定

查询一致性（抽样）：

- 不引入明显的节点缺失（METHOD/TYPE_DECL/CALL/IDENTIFIER 等数量级异常）
- 与 php2cpg 对齐的字段：`NAMESPACE_BLOCK.fullName` 形如 `<file>:<global>`，且可用于统一查询模板

## 8. Phase B 候选（不在本次实现）

- 引入类似 php2cpg 的符号汇总 pass（先 summary，再 AST creation），用于进一步对齐 fullName 与 import/alias 解析

