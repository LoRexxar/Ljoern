# 全部 *2cpg 前端架构总览

本文目标是“逐一梳理”仓库内所有 `*2cpg` 前端：入口、核心 Frontend、依赖形态、管线（主要 passes）、后处理与关键文件定位。

## 0. 统一骨架（X2Cpg 生态）

### 0.1 入口形态

绝大多数前端入口为：

- `object Main extends X2CpgMain(new <Frontend>(), cmdLineParser)`

`X2CpgMain` 负责：

- 解析命令行参数
- 在 `--server` 模式下启动 HTTP server（用于 joern 的前端服务化调用）
- 调用 `frontend.run(config)`，并保证异常时以 exit code 1 退出（[X2CpgMain](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/X2Cpg.scala#L118-L217)）。

### 0.2 通用参数（所有前端共享）

由 `X2Cpg.commandLineParser` 注入（[X2Cpg.commandLineParser](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/X2Cpg.scala#L271-L337)）：

- `input-dir` / `-o, --output`
- `--exclude` / `--exclude-regex`
- `--enable-early-schema-checking`
- `--enable-file-content`
- `--server` / `--server-timeout-minutes`

### 0.3 默认 overlays（前端之外的“补全层”）

前端生成的“frontend CPG”通常只保证基础 AST/语义节点可用；控制流、类型关系、调用图等由默认 overlays 统一补齐（[X2Cpg.applyDefaultOverlays](file:///d:/program/Ljoern/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/X2Cpg.scala#L371-L384)）：

- Base
- ControlFlow
- TypeRelations
- CallGraph

## 1. abap2cpg

- 入口：[abap2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/abap2cpg/src/main/scala/io/joern/abap2cpg/Main.scala)
- 核心 Frontend：[Abap2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/abap2cpg/src/main/scala/io/joern/abap2cpg/Abap2Cpg.scala)
- 依赖形态：调用随发行包携带的 astgen（`abapgen`），通过 runner 封装（[AbapAstGenRunner](file:///d:/program/Ljoern/joern-cli/frontends/abap2cpg/src/main/scala/io/joern/abap2cpg/parser/AbapAstGenRunner.scala)）
- 主流程（按顺序）：`MetaDataPass` → `AstCreationPass` → `ContainsEdgePass` → `TypeNodePass` → `RefEdgePass` → `AbapTypeInferencePass` → `TypeEvalPass`（见 [Abap2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/abap2cpg/src/main/scala/io/joern/abap2cpg/Abap2Cpg.scala)）
- passes 目录：[abap2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/abap2cpg/src/main/scala/io/joern/abap2cpg/passes)

## 2. c2cpg

- 入口：[c2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/c2cpg/src/main/scala/io/joern/c2cpg/Main.scala)
- 核心 Frontend：[C2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/c2cpg/src/main/scala/io/joern/c2cpg/C2Cpg.scala)
- 依赖形态：以 JVM 内部解析/预处理与编译数据库支持为主（非独立 astgen 可执行）
- 主流程（按顺序）：`MetaDataPass` → `AstCreationPass(sources)` → `AstCreationPass(headers)` → `TypeNodePass.withRegisteredTypes` → `TypeDeclNodePass` → `FunctionDeclNodePass` → `FullNameUniquenessPass`（见 [C2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/c2cpg/src/main/scala/io/joern/c2cpg/C2Cpg.scala)）
- 特殊模式：`--print-ifdef-only` 走预处理 pass（[C2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/c2cpg/src/main/scala/io/joern/c2cpg/C2Cpg.scala)，[c2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/c2cpg/src/main/scala/io/joern/c2cpg/Main.scala)）
- passes 目录：[c2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/c2cpg/src/main/scala/io/joern/c2cpg/passes)

## 3. csharpsrc2cpg

- 入口：[csharpsrc2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/csharpsrc2cpg/src/main/scala/io/joern/csharpsrc2cpg/Main.scala)
- 核心 Frontend：[CSharpSrc2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/csharpsrc2cpg/src/main/scala/io/joern/csharpsrc2cpg/CSharpSrc2Cpg.scala)
- 依赖形态：调用随发行包携带的 `dotnetastgen`（[DotNetAstGenRunner](file:///d:/program/Ljoern/joern-cli/frontends/csharpsrc2cpg/src/main/scala/io/joern/csharpsrc2cpg/utils/DotNetAstGenRunner.scala)）
- 主流程（按顺序）：`MetaDataPass` → `DependencyPass` → `AstCreationPass` → `TypeNodePass.withTypesFromCpg`（见 [CSharpSrc2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/csharpsrc2cpg/src/main/scala/io/joern/csharpsrc2cpg/CSharpSrc2Cpg.scala)）
- 后处理：`NaiveCallLinker`（见同文件的 `postProcessingPasses`）
- passes 目录：[csharpsrc2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/csharpsrc2cpg/src/main/scala/io/joern/csharpsrc2cpg/passes)

## 4. ghidra2cpg

- 入口：[ghidra2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/ghidra2cpg/src/main/scala/io/joern/ghidra2cpg/Main.scala)
- 核心 Frontend：[Ghidra2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/ghidra2cpg/src/main/scala/io/joern/ghidra2cpg/Ghidra2Cpg.scala)
- 依赖形态：依赖 Ghidra 作为库/运行时（headless API），非单独 astgen 可执行
- 主流程（概览）：导入/反编译 → `MetaDataPass` → `NamespacePass` → 按 CPU 架构选择函数 pass（MIPS/x86 特化）→ `TypeNodePass.withRegisteredTypes` → `JumpPass` → `LiteralPass`（见 [Ghidra2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/ghidra2cpg/src/main/scala/io/joern/ghidra2cpg/Ghidra2Cpg.scala)）
- passes 目录：[ghidra2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/ghidra2cpg/src/main/scala/io/joern/ghidra2cpg/passes)

## 5. gosrc2cpg

- 入口：[gosrc2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/gosrc2cpg/src/main/scala/io/joern/gosrc2cpg/Main.scala)
- 核心 Frontend：[GoSrc2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/gosrc2cpg/src/main/scala/io/joern/gosrc2cpg/GoSrc2Cpg.scala)
- 依赖形态：调用随发行包携带的 `goastgen`（[GoAstGenRunner](file:///d:/program/Ljoern/joern-cli/frontends/gosrc2cpg/src/main/scala/io/joern/gosrc2cpg/utils/GoAstGenRunner.scala)）
- 主流程（按顺序）：`MetaDataPass` → astgen（按 module 分组）→ `InitialMainSrcPass` →（可选）`PackageCtorCreationPass` →（可选）`DownloadDependenciesPass` → `AstCreationPass`（见 [GoSrc2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/gosrc2cpg/src/main/scala/io/joern/gosrc2cpg/GoSrc2Cpg.scala)）
- passes 目录：[gosrc2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/gosrc2cpg/src/main/scala/io/joern/gosrc2cpg/passes)

## 6. javasrc2cpg

- 入口：[javasrc2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/javasrc2cpg/src/main/scala/io/joern/javasrc2cpg/Main.scala)
- 核心 Frontend：[JavaSrc2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/javasrc2cpg/src/main/scala/io/joern/javasrc2cpg/JavaSrc2Cpg.scala)
- 依赖形态：核心解析 JVM 内完成；可选使用 delombok 与依赖下载（提升类型求解质量）
- 主流程（按顺序）：`MetaDataPass` → `AstCreationPass` → 清理 delombok/缓存/类型求解器 → `OuterClassRefPass` → `JavaConfigFileCreationPass` →（可选）`TypeNodePass.withRegisteredTypes` + `TypeInferencePass`（见 [JavaSrc2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/javasrc2cpg/src/main/scala/io/joern/javasrc2cpg/JavaSrc2Cpg.scala)）
- passes 目录：[javasrc2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/javasrc2cpg/src/main/scala/io/joern/javasrc2cpg/passes)

## 7. jimple2cpg（JVM 字节码/Android）

- 入口：[jimple2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/jimple2cpg/src/main/scala/io/joern/jimple2cpg/Main.scala)
- 核心 Frontend：[Jimple2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/jimple2cpg/src/main/scala/io/joern/jimple2cpg/Jimple2Cpg.scala)
- 依赖形态：依赖 Soot（Jimple/Android 分析框架）；可选反编译生成 Java 文本
- 主流程（分支）：`.apk/.dex` 走 `SootAstCreationPass`；其他 class/jar/dir 走 `AstCreationPass`，并统一 `MetaDataPass` → `TypeNodePass.withRegisteredTypes` → `DeclarationRefPass` → `JavaConfigFileCreationPass`（见 [Jimple2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/jimple2cpg/src/main/scala/io/joern/jimple2cpg/Jimple2Cpg.scala)）
- passes 目录：[jimple2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/jimple2cpg/src/main/scala/io/joern/jimple2cpg/passes)

## 8. jssrc2cpg（JavaScript/TypeScript）

- 入口：[jssrc2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/jssrc2cpg/src/main/scala/io/joern/jssrc2cpg/Main.scala)
- 核心 Frontend：[JsSrc2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/jssrc2cpg/src/main/scala/io/joern/jssrc2cpg/JsSrc2Cpg.scala)
- 依赖形态：调用随发行包携带的 `astgen`（可 `ASTGEN_BIN` 覆盖）[AstGenRunner](file:///d:/program/Ljoern/joern-cli/frontends/jssrc2cpg/src/main/scala/io/joern/jssrc2cpg/utils/AstGenRunner.scala)
- 主流程（按顺序）：astgen → `AstCreationPass` → `JavaScriptTypeNodePass` → `JavaScriptMetaDataPass` → `DependenciesPass` → `ConfigPass` → `PrivateKeyFilePass` → `ImportsPass`（见 [JsSrc2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/jssrc2cpg/src/main/scala/io/joern/jssrc2cpg/JsSrc2Cpg.scala)）
- passes 目录：[jssrc2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/jssrc2cpg/src/main/scala/io/joern/jssrc2cpg/passes)

## 9. kotlin2cpg

- 入口：[kotlin2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/kotlin2cpg/src/main/scala/io/joern/kotlin2cpg/Main.scala)
- 核心 Frontend：[Kotlin2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/kotlin2cpg/src/main/scala/io/joern/kotlin2cpg/Kotlin2Cpg.scala)
- 依赖形态：核心解析依赖 Kotlin Compiler API；可选依赖下载/解析（提升 binding context 与类型信息）
- 主流程（概览）：构建编译环境与 bindingContext → `MetaDataPass` → `AstCreationPass` → `SamTypeDeclPass` →（可选）Java interop AST → `TypeNodePass.withRegisteredTypes`（见 [Kotlin2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/kotlin2cpg/src/main/scala/io/joern/kotlin2cpg/Kotlin2Cpg.scala)）
- 后处理：`KotlinTypeRecoveryPassGenerator.generate` + `KotlinTypeHintCallLinker`（同文件 `postProcessingPass`）
- passes 目录：[kotlin2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/kotlin2cpg/src/main/scala/io/joern/kotlin2cpg/passes)

## 10. php2cpg

- 深度文档：[php2cpg.md](file:///d:/program/Ljoern/docs/frontends/php2cpg.md)
- 入口：[php2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Main.scala)
- 核心 Frontend：[Php2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Php2Cpg.scala)
- 依赖形态：系统 `php` + php-parser 脚本；可选依赖下载
- 主流程：`MetaDataPass` → `DependencyPass` →（可选）依赖下载/符号抽取 → `SymbolSummaryPass`（第 1 遍）→ `AstCreationPass`（第 2 遍）→ `TypeNodePass.withTypesFromCpg`（见 [Php2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/php2cpg/src/main/scala/io/joern/php2cpg/Php2Cpg.scala)）

## 11. pysrc2cpg

- 深度文档：[pysrc2cpg.md](file:///d:/program/Ljoern/docs/frontends/pysrc2cpg.md)
- 入口：[pysrc2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Main.scala)
- 核心 Frontend：[Py2CpgOnFileSystem](file:///d:/program/Ljoern/joern-cli/frontends/pysrc2cpg/src/main/scala/io/joern/pysrc2cpg/Py2CpgOnFileSystem.scala)
- 依赖形态：内置 pythonparser，纯 JVM 解析；并行按文件粒度

## 12. rubysrc2cpg

- 入口：[rubysrc2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/rubysrc2cpg/src/main/scala/io/joern/rubysrc2cpg/Main.scala)
- 核心 Frontend：[RubySrc2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/rubysrc2cpg/src/main/scala/io/joern/rubysrc2cpg/RubySrc2Cpg.scala)
- 依赖形态：JRuby 环境执行 ruby ast generator（[RubyAstGenRunner](file:///d:/program/Ljoern/joern-cli/frontends/rubysrc2cpg/src/main/scala/io/joern/rubysrc2cpg/parser/RubyAstGenRunner.scala)）
- 主流程（概览）：`MetaDataPass` → `ConfigFileCreationPass` → `DependencyPass` → astgen → `AstCreationPass` →（可选）依赖下载与 summary 合并 →（可选）`DependencySummarySolverPass` → `TypeNodePass.withTypesFromCpg`（见 [RubySrc2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/rubysrc2cpg/src/main/scala/io/joern/rubysrc2cpg/RubySrc2Cpg.scala)）
- 后处理（概览）：require/import 解析 + type recovery + call linker + 二次 ast linker（同文件 `postProcessingPasses`）
- passes 目录：[rubysrc2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/rubysrc2cpg/src/main/scala/io/joern/rubysrc2cpg/passes)

## 13. rust2cpg

- 入口：[rust2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/rust2cpg/src/main/scala/io/joern/rust2cpg/Main.scala)
- 核心 Frontend：[Rust2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/rust2cpg/src/main/scala/io/joern/rust2cpg/Rust2Cpg.scala)
- 依赖形态：调用随发行包携带的 `rust_ast_gen`（[RustAstGenRunner](file:///d:/program/Ljoern/joern-cli/frontends/rust2cpg/src/main/scala/io/joern/rust2cpg/astgen/RustAstGenRunner.scala)）
- 主流程：astgen → `MetaDataPass` → `AstCreationPass`（见 [Rust2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/rust2cpg/src/main/scala/io/joern/rust2cpg/Rust2Cpg.scala)）
- passes 目录：[rust2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/rust2cpg/src/main/scala/io/joern/rust2cpg/passes)

## 14. swiftsrc2cpg

- 入口：[swiftsrc2cpg/Main.scala](file:///d:/program/Ljoern/joern-cli/frontends/swiftsrc2cpg/src/main/scala/io/joern/swiftsrc2cpg/Main.scala)
- 核心 Frontend：[SwiftSrc2Cpg](file:///d:/program/Ljoern/joern-cli/frontends/swiftsrc2cpg/src/main/scala/io/joern/swiftsrc2cpg/SwiftSrc2Cpg.scala)
- 依赖形态：调用随发行包携带的 swift ast generator（可 `SWIFTASTGEN_BIN` 覆盖）[AstGenRunner](file:///d:/program/Ljoern/joern-cli/frontends/swiftsrc2cpg/src/main/scala/io/joern/swiftsrc2cpg/utils/AstGenRunner.scala)
- 主流程（概览）：astgen → `AstCreationPass` → `MetaDataPass(hash)` → `BuiltinTypesPass` → `SwiftTypeNodePass` → `ConfigFileCreationPass` → `DependenciesPass` → `ExtensionsPass` → `ObjcCallFullNamePass` → `ClosureBindingsPass` → `FullNameUniquenessPass`（见 [SwiftSrc2Cpg.scala](file:///d:/program/Ljoern/joern-cli/frontends/swiftsrc2cpg/src/main/scala/io/joern/swiftsrc2cpg/SwiftSrc2Cpg.scala)）
- passes 目录：[swiftsrc2cpg/passes](file:///d:/program/Ljoern/joern-cli/frontends/swiftsrc2cpg/src/main/scala/io/joern/swiftsrc2cpg/passes)

