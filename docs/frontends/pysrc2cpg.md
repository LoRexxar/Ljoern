# pysrc2cpg 架构复盘（Python 源码前端）

## 1. 范围与产物

- 输入：文件系统中的 Python 源码（`.py`）。
- 输出：前端生成的 `cpg.bin`（flatgraph 存储）。该图至少包含 FILE/NAMESPACE/METHOD/AST 等基础节点；CFG/CallGraph/TypeRelations 等默认 overlays 通常由上层统一补齐（见 [X2Cpg.defaultOverlayCreators](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/X2Cpg.scala#L371-L384)）。

## 2. 入口与 CLI

- 入口：`object NewMain extends X2CpgMain(new Py2CpgOnFileSystem(), cmdLineParser)`（[Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Main.scala#L42)）。
- CLI 组织方式：通用参数由 `X2CpgMain/X2Cpg` 注入（input/output/exclude/schema checking/file content/server），前端专属参数在 `Frontend.cmdLineParser` 中追加（[X2CpgMain](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/X2Cpg.scala#L118-L217)）。

前端专属参数（核心）：

- venv 相关：`--venvDirs`、`--ignoreVenvDir`（隐藏兼容参数 `--venvDir`）（[Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Main.scala#L17-L38)）。
- 过滤：`--ignore-paths`、`--ignore-dir-names`（同上）。
- 类型恢复：`XTypeRecoveryConfig.parserOptionsForParserConfig`（同上）。

配置对象：

- `Py2CpgOnFileSystemConfig`：在 `genericConfig`（通用）之外，额外包含 venv/忽略规则/requirementsTxt/typeRecoveryConfig（[Py2CpgOnFileSystem.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2CpgOnFileSystem.scala#L13-L54)）。

## 3. 文件发现与过滤策略

文件枚举：

- `SourceFiles.determine(inputPath, Set(".py"), ...)`（[Py2CpgOnFileSystem.createCpg](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2CpgOnFileSystem.scala#L80-L93)）。

过滤链（按优先级）：

- 自动识别 venv：当用户未显式配置 venvDirs/venvDir 且 `ignoreVenvDir=true` 时，路径链上出现 `pyvenv.cfg` 则过滤（[isAutoDetectedVenv](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2CpgOnFileSystem.scala#L119-L125)）。
- 目录名过滤：相对路径中出现 `ignoreDirNames` 任一项即过滤（[isIgnoredDir](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2CpgOnFileSystem.scala#L127-L129)）。
- 路径前缀过滤：`ignorePaths + venvIgnorePath` 先 resolve 成绝对路径前缀，再 `startsWith` 判断（[absoluteIgnorePaths](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2CpgOnFileSystem.scala#L70-L107)）。

## 4. 建图管线（从文件到 CPG）

### 4.1 输入模型（并行安全）

每个文件构造成 `InputProvider: () => InputPair(content, relFileName)`，以满足并行 pass 的线程安全要求（[inputProviders](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2CpgOnFileSystem.scala#L94-L99)，[InputPair](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2Cpg.scala#L10-L13)）。

### 4.2 pass 顺序与职责

顶层编排在 `Py2Cpg.buildCpg()`（[Py2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2Cpg.scala#L33-L47)）：

- 写入最小骨架：META + ROOT + global namespace block + ANY type/typeDecl
- 解析源码并生成 AST：`new CodeToCpg(...).createAndApply()`（同上 #L44）
- 生成配置文件节点：`ConfigFileCreationPass`（同上 #L45）
- 依赖抽取：`DependenciesFromRequirementsTxtPass`（同上 #L46）

### 4.3 AST 生成机制（纯 JVM）

- 并行模型：`CodeToCpg` 继承 `ForkJoinParallelCpgPass[InputProvider]`，按文件粒度并行（[CodeToCpg](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/CodeToCpg.scala#L11-L20)）。
- 解析器：`io.joern.pythonparser.PyParser`（[CodeToCpg](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/CodeToCpg.scala#L21-L33)）。
- AST visitor：`PythonAstVisitor` 基于 `AstCreatorBase` 直接构造 diffGraph；文件顶层会被建模为 `<module>` 方法，body 包含 builtin identifier 绑定与顶层语句（[PythonAstVisitor.convert(module)](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/PythonAstVisitor.scala#L79-L125)）。

### 4.4 错误处理（降级而非中断）

单文件解析失败会：

- 仍创建 FILE 节点（可选写入 content）
- 记录 warn
- 不影响其他文件继续解析

实现见 [CodeToCpg.handleParsingError](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/CodeToCpg.scala#L41-L62)。

## 5. 后处理（imports / 类型恢复 / 调用链接）

pysrc2cpg 的“语言语义增强”主要通过 x2cpg 的 frontendspecific 后处理 pass 统一提供（[postProcessingPasses](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/frontendspecific/pysrc2cpg/package.scala#L11-L26)）：

- Imports/ImportResolver：`ImportsPass`、`PythonImportResolverPass`
- 类型提示与继承名补全：`DynamicTypeHintFullNamePass`、`PythonInheritanceNamePass`
- 类型恢复：`PythonTypeRecoveryPassGenerator.generate()`
- 调用链接：`PythonTypeHintCallLinker` + `NaiveCallLinker`
- 二次 `AstLinkerPass`：因为类型恢复可能会创建新 method，需要重新链接 AST

