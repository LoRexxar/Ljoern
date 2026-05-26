# pysrc2cpg FullName 体系重写（Phase B / Breaking）

## 背景与问题

pysrc2cpg 目前对绝大多数真实源码调用（`foo()`、`x.y()`）在 AST 构建阶段会生成 `DYNAMIC_DISPATCH` 的 `CALL`，并将 `CALL.methodFullName` 置为 `<unknownFullName>`。这会导致：

- `CALL.methodFullName` 缺乏稳定语义，难以作为跨语言对齐与后处理（call linking）的基础输入
- 解析能力依赖后处理是否“猜中”（type hint / naive linker），不稳定且难以复盘
- 与 php2cpg 的策略不一致：php2cpg 会在 AST 阶段尽量写入可用、可链接、可复现的 `CALL.methodFullName`，解析不了再使用 `<unresolvedNamespace>` 兜底；而 pysrc2cpg 需要避免 `<unresolvedNamespace>`，改用“module-based guess”兜底以降低对后续分析的干扰

用户决定不考虑兼容性，允许深度重写部分逻辑，核心目标是“合理性”与跨前端一致性（以 php2cpg 为黄金参照）。

## 目标（Success Criteria）

1. 同一份源码、同一 `inputPath` 下，多次运行产物的 `METHOD/TYPE_DECL/CALL.*fullName` 必须稳定、可预测。
2. 声明端与使用端共享同一套命名语法：
   - 声明端：`METHOD.fullName`、`TYPE_DECL.fullName` 按统一规则生成
   - 使用端：`CALL.methodFullName` 尽量写成“声明端将会使用的 fullName”，解析不了则使用统一 unresolved 兜底
3. `CALL.name` 永远保持短名（`foo` / `bar` / `__init__` / `<operator>.*`），不得被 fullName 污染。
4. 方案必须可测试锁定（包含 module 推导、import 命中、unresolved 兜底、name/fullName 不混淆等）。

## 非目标（Non-Goals）

- 不追求“最大化静态解析覆盖率”，避免在动态语言里产生大量误解析。
- 不要求一次性做全工程 index 的精准类型/符号解析；优先做到与 php2cpg 同级别的“尽量确定 + unresolved 兜底”。
- 不在本阶段定义完整的 signature/overload 表达（Python 的 signature 可能后续单独设计）。

## 术语与约定

- `fullName`：用于跨文件稳定标识节点（METHOD/TYPE_DECL/CALL.methodFullName）。
- `short name`：如 `CALL.name`、`METHOD.name`，用于展示与局部匹配。
- `moduleFullName`：由文件相对 `inputPath` 推导的 Python 模块/包名（使用 `.` 分隔）。
- unresolved 兜底（pysrc2cpg）：使用 “module-based guess”
  - simple call：`<moduleFullName>.<name>`
  - attribute call（base 无法解析且为 `x.y()`）：`<moduleFullName>.<x>.<y>`（保留原名）

本设计中，php2cpg 仍使用 `<unresolvedNamespace>.<name>`；pysrc2cpg 则统一使用 module-based guess，暂不引入 `<unresolvedSignature>(argc)` 的静态语言格式。

## 新的命名规范（Breaking Change）

### 1) moduleFullName 推导规则

输入：
- `inputPath`（已规范化为绝对路径写入 `META_DATA.root`）
- 单个源文件的相对路径 `relFileName`（相对 `inputPath`）

输出：`moduleFullName`

规则：
1. 去掉扩展名 `.py`
2. 将路径分隔符（`/`、`\`）统一替换为 `.`
3. 特殊处理 `__init__.py`：
   - `pkg/__init__.py` 的 `moduleFullName = pkg`
   - 根目录的 `__init__.py` 则 `moduleFullName = <empty>`（或按实现选择跳过包名，仅用于 top-level）

示例：
- `pkg/sub/mod.py` -> `pkg.sub.mod`
- `pkg/__init__.py` -> `pkg`
- `mod.py` -> `mod`

### 2) TYPE_DECL.fullName 规则

- 顶层类 `class C`：`TYPE_DECL.fullName = <moduleFullName>.C`
- metaclass `C<meta>`：`TYPE_DECL.fullName = <moduleFullName>.C<meta>`
- 允许嵌套 class（若保留）：`TYPE_DECL.fullName = <enclosingTypeDeclFullName>.Inner`

`TYPE.fullName` 与 `TYPE.typeDeclFullName` 应与 `TYPE_DECL.fullName` 对齐（保持一一对应）。

### 3) METHOD.fullName 规则

- 顶层函数 `def f`：`METHOD.fullName = <moduleFullName>.f`
- 类方法 `class C: def m`：`METHOD.fullName = <moduleFullName>.C.m`
- 嵌套函数 `def outer: def inner`：`METHOD.fullName = <outerMethodFullName>.inner`
- 特殊方法：
  - module `<module>`：`METHOD.fullName = <moduleFullName>.<module>`（具体 name 常量沿用现有 `Constants.moduleName`）
  - lambda：保留现有 `<lambda>` 编号策略，但 fullName 前缀改为 enclosing method 的 fullName

### 4) CALL.methodFullName 默认生成规则（AST 阶段尽量确定）

原则：
- `CALL.name` 始终为短名（`foo` / `bar` / `__init__` / `<operator>.*`）
- `CALL.methodFullName` 采用 “能解析则写可链接 fullName，否则 unresolved” 的策略

#### 4.1 普通调用 `foo(...)`

优先级：
1. import/alias 解析命中（见下文 Scope/Resolver）：返回被导入符号的 fullName
2. 本模块声明命中（预扫描符号表）：`<moduleFullName>.foo`
3. unresolved：`<unresolvedNamespace>.foo`

#### 4.2 属性调用 `x.y(...)`

分类：
- 若 `x` 可静态判定为“模块别名”：
  - `import pkg.sub as x`，则 `CALL.methodFullName = pkg.sub.y`
  - `from pkg.sub import mod as x`，则 `CALL.methodFullName = pkg.sub.mod.y`
- 若 `x` 可静态判定为“类名/类型名”（同文件或 import 命中）：
  - `CALL.methodFullName = <typeFullName>.y`
- 否则：
  - unresolved：`<unresolvedNamespace>.y`

说明：
- 该策略不尝试把 `x` 解析为“运行时变量的实例类型”，避免误解析；实例方法的精准链接交由 type hint/linker 做“修正与连边”。

#### 4.3 构造调用 `C(...)`

当 `C` 被判定为类名（同文件或 import 命中）：
- `CALL.name = C`
- `CALL.methodFullName = <typeFullName>.__init__`

若无法判定：
- `CALL.methodFullName = <unresolvedNamespace>.C`

该规则与 `PythonTypeHintCallLinker` 对“首字母大写追加 `.__init__`”的策略保持一致，但前移到 AST 阶段的“可判定场景”。

#### 4.4 `<operator>.*` 与显式静态调用

- `<operator>.*` 继续使用 `STATIC_DISPATCH`，`CALL.methodFullName` 为 operator fullName。
- 前端合成的静态调用必须满足：
  - `CALL.name` 为短名或 operator 名（不写 fullName）
  - `CALL.methodFullName` 写入 fullName

## 架构改造：引入 Python 版 SymbolSummary + Scope 解析

为实现 “尽量确定填充”，需要在 AST 阶段具备基础的可解析信息。对齐 php2cpg 的两阶段策略：

1. Symbol Summary（预扫描）
   - 输入：文件 AST（或轻量解析结果）
   - 输出：本文件可见的顶层符号集合（functions/classes），以及包/模块全名
2. AST Creation（构建 CPG）
   - 使用 Scope 维护：
     - 当前 moduleFullName
     - import 别名映射（alias -> fullName）
     - 当前 class/method 嵌套栈（用于声明端 fullName 前缀）
   - 在构建 CALL 时优先使用 Scope 解析 `CALL.methodFullName`

### Scope 数据结构（建议）

最小可用字段：
- `moduleFullName: String`
- `imports: Map[String, String]`
  - key: 本地名字（alias 或原名）
  - value: 目标 fullName（模块或符号）
- `declaredTopLevelFunctions: Set[String]`
- `declaredTopLevelClasses: Set[String]`
- `enclosingTypeFullName: Option[String]`
- `enclosingMethodFullName: Option[String]`

解析接口（伪接口）：
- `resolveImported(name: String): Option[String]`
- `resolveLocalTopLevelFunction(name: String): Boolean`
- `resolveLocalTopLevelClass(name: String): Boolean`
- `currentMethodFullNameFor(name: String): String`
- `currentTypeFullNameFor(name: String): String`

### Import 规则（覆盖常见场景）

需要支持：
- `import a.b as x`：`imports(x) = a.b`
- `import a.b`：`imports("a") = a`
- `from a.b import c as x`：`imports(x) = a.b.c`
- `from a.b import c`：`imports("c") = a.b.c`

多重 import / 重名：
- 以“最后一次生效”为准（与 Python 运行时绑定一致），保持确定性。

## NodeBuilder API 改造（修正 name/fullName 耦合）

现状：`NodeBuilder.callNode(code, name, dispatchType, ...)` 在 `STATIC_DISPATCH` 下会把 `methodFullName` 置为 `name`，导致调用点难以表达 “name 与 methodFullName 分离”。

重写目标：显式区分
- `CALL.name`（短名）
- `CALL.methodFullName`（可链接 fullName 或 unresolved）

建议改造：
- 新增/替换为：`callNode(code, name, methodFullName, dispatchType, lineAndColumn)`
- 调整 `createStaticCall` / operator call 等路径，保证 `CALL.name` 不被 fullName 污染。

## 与后处理（Call Linking / Type Hint）的关系

新的默认策略并不替代后处理，而是为后处理提供更好的“稳定初值”：

- 当 AST 阶段可解析时：`CALL.methodFullName` 已可用于 `StaticCallLinker` 或其它 linker 做连边。
- 当 AST 阶段不可解析时：`CALL.methodFullName` 统一为 `<unresolvedNamespace>.<name>`，后处理可以：
  - 基于类型 hint 覆盖为更具体的 fullName（唯一候选时）
  - 或保持 unresolved，但仍具备稳定语义（用于查询/规则/策略）

## 测试策略

新增（或扩展）测试覆盖：

1. moduleFullName 推导
   - `pkg/sub/mod.py` -> `pkg.sub.mod`
   - `pkg/__init__.py` -> `pkg`
2. 声明端 fullName
   - 顶层函数、类方法、嵌套函数、meta class
3. 调用端 methodFullName
   - import 命中：`from a.b import c as x; x()` -> `a.b.c`（或 `a.b.c.__init__` 视类型判定）
   - 本模块命中：`def f; f()` -> `<module>.f`
   - unresolved：`unknown()` -> `<unresolvedNamespace>.unknown`
   - `x.y()`：模块别名命中 vs 未命中落 unresolved
4. `CALL.name` 不被污染
   - 任何静态/合成调用都应保证 `CALL.name` 为短名（operator 除外）

## 迁移与落地步骤（高层）

1. 先实现 moduleFullName 推导工具函数，并接入 Context/Scope。
2. 重写 fullName 生成：替换 `calculateFullNameFromContext`，使其基于 moduleFullName + 栈信息输出新格式。
3. 引入 SymbolSummaryPass（Python 版）收集本文件顶层声明。
4. 引入 import resolver：在 visit import 语句时写入 Scope.imports。
5. 改造 NodeBuilder.callNode API 与所有调用点，确保 name/fullName 分离。
6. 重写 CALL.methodFullName 生成策略（foo / x.y / ctor / unresolved）。
7. 跑全量 `pysrc2cpg/test`，并新增/修复断言保证可复现。

## 风险与应对

- 全量改造 fullName 会影响大量现有测试与下游查询：用新增测试锁定新规范，并在 PR 描述中明确 breaking。
- Python import 解析存在边界（`__all__`、动态 import、运行时修改 `sys.path`）：本阶段只保证静态 import 语句的确定性映射，不试图覆盖动态行为。
- 嵌套作用域与重名：沿用现有 `$redefinition` 去重思想，但新 fullName 体系下需要重新定义去重后缀的位置与稳定性。

## 未决项（需要在实现前最终确认）

1. 根目录 `__init__.py` 的 moduleFullName 是否视为 `<global>`（空前缀）还是特殊名字（例如 `__root__`）。
2. `CALL.methodFullName` 的 unresolved 格式是否需要追加参数个数或 signature（暂定不做）。
3. `METHOD.signature` 是否维持空字符串，还是在本阶段开始生成（暂定维持现状，后续单独 spec）。
