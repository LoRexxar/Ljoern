# 项目整体架构（Joern）

## 1. 定位与目标

Joern 是面向源代码/字节码/二进制的静态分析平台：通过生成 Code Property Graph（CPG）并存储到本地图数据库（flatgraph），再通过 Scala DSL 执行跨语言查询与扫描规则来支持漏洞发现与程序分析研究（见 [README.md](file:///d:/program/Ljoern/README.md#L9-L16)）。

核心产物是 `cpg.bin`，在此基础上会叠加一组 overlays（基础语义、控制流、类型关系、调用图、数据流等），让图具备更完整的分析语义。

## 2. 技术栈与工程形态

- 语言/构建：Scala 3 + sbt 多模块工程（[build.sbt](file:///d:/program/Ljoern/build.sbt#L1-L55)）。
- 运行时要求：JDK 21（见 [README.md](file:///d:/program/Ljoern/README.md#L30-L34)）。
- 分发：发行包由 `joern-cli` 统一打包，根任务 `createDistribution` 产出 `target/joern-cli.zip`（[build.sbt](file:///d:/program/Ljoern/build.sbt#L89-L99)）。

## 3. 模块划分与职责边界

### 3.1 joern-cli（用户入口与发行包）

职责：提供所有命令行入口、把各语言前端与脚本打进发行包、复用 console 与分析能力实现 parse/scan/export/flow。

关键入口：

- 解析生成 CPG：`joern-parse`（[JoernParse](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernParse.scala#L14-L165)）
- 扫描：`joern-scan`（[JoernScan](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernScan.scala#L45-L250)）
- 导出：`joern-export`（[JoernExport](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernExport.scala#L23-L170)）
- 默认 overlays：`DefaultOverlays.create`（[DefaultOverlays](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/DefaultOverlays.scala#L8-L25)）
- 通用：CPG 加载、参数拆分、补算 dataflow overlay（[CpgBasedTool](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/CpgBasedTool.scala#L12-L69)）

### 3.2 console（交互式工作台）

职责：REPL 体验、workspace/project 生命周期、`importCode/open/close/delete` 等用户 API、插件机制（scan/querydb 等）。

关键实现：

- 当前活动项目的 `cpg` 根对象通过 `implicit def cpg: Cpg = workspace.cpg` 暴露（[Console.cpg](file:///d:/program/Ljoern/console/src/main/scala/io/joern/console/Console.scala#L121-L144)）。
- `importCode` 负责选择前端并落盘到 workspace project，导入完成后默认会应用 overlays 与 post-processing passes（[ImportCode.apply(generator,...)](file:///d:/program/Ljoern/console/src/main/scala/io/joern/console/cpgcreation/ImportCode.scala#L211-L235)）。
- 插件以 zip/jar 方式安装到 `installDir/lib`，命名为 `joernext-<plugin>-...`（[PluginManager](file:///d:/program/Ljoern/console/src/main/scala/io/joern/console/PluginManager.scala#L18-L109)）。

### 3.3 frontends（*2cpg：多语言/多形态 CPG 生成器）

职责：将输入（源码/字节码/二进制）转为“前端 CPG”。不同语言前端的解析方式不同：纯 JVM 内解析、调用随发行包带的 astgen、或依赖外部运行时/库（如 php、Ghidra、Soot）。

共性骨架：所有前端 CLI 入口通常继承 `X2CpgMain`，由 `X2Cpg` 注入通用参数（input/output/exclude/schema checking/file content/server mode 等）（[X2CpgMain](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/X2Cpg.scala#L118-L217)，[X2Cpg.commandLineParser](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/X2Cpg.scala#L271-L337)）。

### 3.4 semanticcpg（查询 DSL 与语义层）

职责：提供 `cpg.method...` 等 Scala DSL、节点扩展与语义层组织（目录见 [semanticcpg](file:///d:/program/Ljoern/semanticcpg/src/main/scala/io/shiftleft/semanticcpg)）。

### 3.5 dataflowengineoss（数据流/污点分析）

职责：在 CPG/SCPG 上进行跨过程数据流推导；在 CLI/导出/默认 overlays 中会按需计算 overlay（例如 `DefaultOverlays` 会跑 `OssDataFlow`）（[DefaultOverlays](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/DefaultOverlays.scala#L18-L25)，导出也会必要时补算（[CpgBasedTool.addDataFlowOverlayIfNonExistent](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/CpgBasedTool.scala#L26-L35)）。

### 3.6 querydb + macros（扫描规则与查询宏）

职责：`querydb` 提供官方查询/扫描规则 bundle，`macros` 提供 `@q` 等宏支持，供 QueryDatabase 收集与加载。扫描入口在 `joern-scan`，当本地无 querydb 时会从发布页下载并安装（[JoernScan.downloadAndInstallQueryDatabase](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernScan.scala#L202-L208)）。

## 4. 关键数据流（端到端）

### 4.1 生成 CPG（joern-parse 路径）

- 输入路径校验与语言识别（显式 `--language` 或 guessLanguage）（[JoernParse.getLanguage](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernParse.scala#L112-L125)）。
- 启动对应语言前端生成 `cpg.bin`（[JoernParse.generateCpg](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernParse.scala#L127-L152)）。
- 默认 overlays：加载 `cpg.bin` 并叠加 overlays + 数据流层，同时调用前端的 post-processing passes（[JoernParse.applyDefaultOverlays](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernParse.scala#L154-L164)，[DefaultOverlays.create](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/DefaultOverlays.scala#L18-L25)）。

### 4.2 交互式导入（console 路径）

- `importCode(...)` 选择前端并创建 workspace project（[ImportCode.apply](file:///d:/program/Ljoern/console/src/main/scala/io/joern/console/cpgcreation/ImportCode.scala#L35-L49)）。
- 运行 generator，必要时把 proto CPG 转换为 flatgraph，并最终形成 `cpg.bin`（[CpgGeneratorFactory.runGenerator](file:///d:/program/Ljoern/console/src/main/scala/io/joern/console/cpgcreation/CpgGeneratorFactory.scala#L59-L84)）。
- 导入完成后对活动 CPG 应用 overlays 与 post-processing（[ImportCode.apply(generator,...)](file:///d:/program/Ljoern/console/src/main/scala/io/joern/console/cpgcreation/ImportCode.scala#L227-L234)）。

### 4.3 扫描（joern-scan 路径）

- 若本地无 querydb，则下载并安装后退出，让用户再次执行（[JoernScan.runScanPlugin](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernScan.scala#L160-L184)）。
- 扫描通过 console 插件 `scan` 执行，内部用 `ScanPass` 串行跑 queries（避免与 dataflow 引擎并行冲突）（[ScanPass](file:///d:/program/Ljoern/console/src/main/scala/io/joern/console/scan/ScanPass.scala#L7-L15)）。

### 4.4 导出（joern-export 路径）

- 加载 CPG，必要时补算 dataflow overlay（[JoernExport.exportCpg](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernExport.scala#L99-L122)）。
- 支持多种格式，必要时按方法切分子图并输出（Windows 文件名去重逻辑见 [JoernExport.sanitizedFileName](file:///d:/program/Ljoern/joern-cli/src/main/scala/io/joern/joerncli/JoernExport.scala#L184-L200)）。

## 5. 构建与验证

- 单测：`sbt test`（[README.md](file:///d:/program/Ljoern/README.md#L64-L68)）。
- 发行包：`sbt joerncli/stage querydb/createDistribution`，随后可跑 `testDistro.py` 集成测试（[README.md](file:///d:/program/Ljoern/README.md#L70-L75)）。

