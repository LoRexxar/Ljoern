# php2cpg 架构复盘（PHP 源码前端）

## 1. 范围与产物

- 输入：文件系统中的 PHP 源码（`.php`），以及可选的 `composer.json`（依赖信息来源）。
- 输出：前端生成的 `cpg.bin`（flatgraph 存储），包含 FILE/NAMESPACE/METHOD/AST 等基础节点；CFG/CallGraph/TypeRelations 等默认 overlays 通常由上层统一补齐（见 [X2Cpg.defaultOverlayCreators](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/X2Cpg.scala#L371-L384)）。

## 2. 入口与 CLI

- 入口：`object Main extends X2CpgMain(new Php2Cpg(): Php2Cpg, cmdLineParser)`（[Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Main.scala#L68)）。
- 配置对象：`Config(phpIni, phpParserBin, downloadDependencies, genericConfig, typeRecoveryParserConfig, typeStubsFilePath)`（[Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Main.scala#L11-L44)）。

前端专属参数（核心）：

- `--php-ini`：用于执行 php-parser 的 ini 文件路径（默认使用随包内置 `php.ini`，运行时写到临时文件）（[PhpParser.withParser](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/parser/PhpParser.scala#L214-L229)）。
- `--php-parser-bin`：php-parser 脚本路径覆盖；也支持环境变量 `PHP_PARSER_BIN`（[maybePhpParserPath](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/parser/PhpParser.scala#L196-L203)）。
- 类型恢复：`XTypeRecoveryConfig.parserOptionsForParserConfig`（[Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Main.scala#L61-L64)）。
- 类型桩：`XTypeStubsParser.parserOptions`（同上）。
- 依赖下载：`--download-dependencies`（由通用 `DependencyDownloadConfig` 注入）（[DependencyDownloadConfig.parserOptions](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/X2Cpg.scala#L106-L115)）。

## 3. 外部依赖与运行时约束

- 强依赖系统 `php` 可执行，且版本需 >= 7.1.0（Composer/Parser 要求）；校验逻辑在 `php --version` 的输出解析（[isPhpVersionSupported](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Php2Cpg.scala#L27-L42)）。
- php-parser 脚本默认路径由安装包可执行目录推导 `php-parser/php-parser.php`（[defaultPhpParserBin](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/parser/PhpParser.scala#L166-L173)），覆盖路径必须存在且是文件（[configOverrideOrDefaultPath](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/parser/PhpParser.scala#L174-L194)）。

## 4. 建图管线（从文件到 CPG）

### 4.1 顶层编排（Php2Cpg.createCpg）

整体流程在 `Php2Cpg.createCpg` 中串联（[Php2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Php2Cpg.scala#L44-L97)）：

- 环境校验：php 版本不足/缺失则失败并终止（同文件 #L47-L95）
- 初始化 parser：`PhpParser.withParser(config){...}`（同文件 #L52-L85）
- 在 `withNewEmptyCpg` 内依次执行：
  - `MetaDataPass(Languages.PHP)`（同文件 #L55-L57）
  - `DependencyPass`：读取 `composer.json` 写入依赖节点（同文件 #L56-L58）
  - 可选依赖下载：`DependencyDownloader.download` + `DependencySymbolsPass`（同文件 #L58-L64）
  - 两遍解析：
    - `SymbolSummaryPass` 汇总全工程 namespace/type/function 可导入符号（同文件 #L65-L71）
    - `AstCreationPass` 构建 AST，并把上一步 summary 注入用于符号解析（同文件 #L69-L72）
  - `TypeNodePass.withTypesFromCpg`：把图中出现的类型名汇总为 TYPE 节点（同文件 #L71-L73）

### 4.2 两遍解析策略（设计动机）

php2cpg 明确选择“解析两次、降低内存”：

- 第一次解析只抽取符号摘要（`SymbolSummaryPass`），用于第二次构建 AST 时 resolving names/imports。
- 相比“解析一次并把中间 AST 全量驻留内存”，此策略更偏向节省内存，CPU 成本可接受（见 [Php2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Php2Cpg.scala#L65-L68)）。

### 4.3 解析与并行策略（批处理 + 并行）

- 批处理：`.php` 文件按 20 个一组喂给 php-parser，降低启动开销，并避免命令行长度限制（[AstParsingPass.generateParts](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/passes/AstParsingPass.scala#L19-L41)）。
- 命令构造：`php --php-ini <ini> <phpParserPath> --with-recovery --resolve-names --json-dump <files...>`（[PhpParser.phpParseCommand](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/parser/PhpParser.scala#L21-L24)）。
- 输出解码：php-parser 输出中混杂 info/warnings/json，`linesToJsonValues` 用状态机切分并 `ujson.read`（[linesToJsonValues](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/parser/PhpParser.scala#L103-L159)）。

### 4.4 AST 创建（AstCreationPass）

`AstCreationPass` 将 `Domain.PhpFile` 转换为 diffGraph 并 absorb（[AstCreationPass](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/passes/AstCreationPass.scala#L14-L28)）：

- `relativeFilename` 由 inputPath relativize 得到
- `new AstCreator(..., summary)(schemaValidation).createAst()` 完成节点与边创建

## 5. 后处理（autoload / import resolve / 类型恢复 / 调用链接）

PHP 的语言语义增强主要通过 x2cpg 的 frontendspecific 后处理 passes 提供（[postProcessingPasses](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/frontendspecific/php2cpg/package.scala#L9-L19)）：

- `ComposerAutoloadPass`
- `PhpTypeStubsParserPass`（known types/type stubs）
- `PhpImportResolverPass`
- `PhpTypeRecoveryPassGenerator.generate()`
- `PhpTypeHintCallLinker(cpg)`

## 6. 错误处理语义

- 环境不可用（php 不存在/版本不支持）会直接失败并终止（[createCpg](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Php2Cpg.scala#L44-L95)）。
- 单文件解析失败不会中断整体：warn 并跳过该文件 AST（[AstParsingPass.runOnPart](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/passes/AstParsingPass.scala#L43-L49)）。
- JSON/Domain 构建失败：该文件返回 `None` 并记录 error（[jsonToPhpFile](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/parser/PhpParser.scala#L57-L68)，[getJsonResult](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/parser/PhpParser.scala#L74-L95)）。

