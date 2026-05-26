# pysrc2cpg：消除 tmp* 中间变量（Design）

## 背景

pysrc2cpg 当前会在 AST 构建阶段生成大量 `tmp\d+` 形式的 `LOCAL`（以及对应的赋值/读取），主要来源于：

- 链式调用 lowering：`x.y(args)` 在 `x` 可能有副作用时会先生成 `tmp = x`，再用 `tmp.y(...)`
- 解构/多目标赋值 lowering：`a, b = expr` 会先 `tmp = expr`，再用 `tmp[0]`/`tmp[1]`
- 推导式/生成器 lowering：`[f(x) for x in xs]` 通过 `tmp` 容器 + `append` + loop 还原语义

这些 tmp 变量会让最终 CPG 图“噪声很大”，影响阅读、对齐与后续分析。

用户目标：尽量不出现 `tmp*` 中间变量；允许一定程度的语义近似（例如副作用表达式在静态图层面可能被复制/看起来求值多次）。

## 目标

- CPG 中尽量不出现 `tmp\d+` 形式的 `LOCAL`/`IDENTIFIER`/`ASSIGNMENT` 结构（尤其是由 lowering 人工引入的那部分）。
- 对于典型链式调用表达式，图结构应当直接呈现“调用链”，不以 tmp 分段表达。
- 不引入“safe/aggressive 两档模式”；只采用一套统一策略。

## 非目标

- 不追求 100% 语义等价（已明确可接受近似）。
- 不要求本阶段更换 Python parser；如需更换 parser 作为单独议题评估（更换 parser 不会自动消除 tmp，根因在 lowering 策略）。

## 总体方案（不换 parser，改造 AST→CPG 构建策略）

### 1) 链式调用：禁用 `createXDotYCall` 的 tmp lowering

现状：当 `xMayHaveSideEffects=true` 时，`x.y(args)` 会 lowering 为：

- `tmp = x`
- `CALL(recv = tmp.y, inst = tmp, args = ...)`

新策略：

- 永远生成“无 tmp”的调用形态
- receiver 表达为 `FieldAccess(x, y)`，instance 表达为 `x`
- 允许 `x` 子树在 receiver 与 instance 处各出现一次（静态图层面可能看起来是重复求值）
- 要求每次使用 `x` 都生成独立 AST 子树，避免同一个节点被挂到多个父节点（保持 AST 合法性）

验收点：

- `get().client().upload(x)` 不应产生任何 `tmp*` local；CALL 链应以嵌套结构出现。

### 2) 解构/多目标赋值：取消 `tmp = rhs` 的分解策略

现状：`a, b = rhs` 通过：

- `tmp = rhs`
- `a = tmp[0]`
- `b = tmp[1]`

新策略：

- 不引入 `tmp` 保存 rhs
- 直接生成：
  - `a = rhs[0]`
  - `b = rhs[1]`
- 允许 rhs 子树在多个 target 赋值中重复出现（语义近似）

实现要点：

- 目前 `createValueToTargetsDecomposition` 以 `valueNode: NewNode` 作为输入，这个节点不能被复用到多个父节点
- 需要把输入改为可重复构建的形式（例如 `valueProvider: () => NewNode` 或传入原始 `ast.iexpr` 以便多次 convert）
- 所有调用点需要同步调整，确保每次生成 RHS 都是独立子树

### 3) 推导式/生成器：改为“单节点高阶表达”，不再展开 tmp 容器/loop

现状：comprehension 会生成：

- `tmp = []` / `{}` 等容器初始化
- 多层 `for`/`if` lowering
- `tmp.append(...)`
- 返回 `tmp`

新策略：

- 不展开为 loop/block/tmp
- 将推导式表达为一个 `CALL`（operator call）：
  - `methodFullName` 采用 `<operator>.*` 命名空间（类似已有 `<operator>.slice`）
  - `name` 保持短名（如 `listComprehension` / `dictComprehension` / `setComprehension` / `genComprehension`）
  - `code` 使用原始源码片段（尽量来自 AstPrinter）
- comprehension 内部的过滤/迭代关系不再以 CFG 显式表示（语义更抽象、图更干净）

验收点：

- 典型 list/dict/set/generator comprehension 的 CPG 不应出现 tmp* local。

## 测试与验收

### 新增最小样例（作为回归基准）

将新增一组最小 Python 样例用于验证“无 tmp”：

- 链式调用：`get().client().upload(x)`、`a().b().c()`、`obj.m().n()`
- 解构赋值：`a, b = foo()`、`x, (y, z) = foo()`、`a, *b, c = foo()`
- 推导式：`[f(x) for x in xs if p(x)]`、`{k:v for k,v in xs}`、`(f(x) for x in xs)`

### 断言策略

- 在对应测试中增加断言：`cpg.local.name("tmp\\d+").l shouldBe Nil`
- 对关键 CALL 的 `code/name/methodFullName` 仍保持可预期（结合现有 Phase B fullName 规则）
- 对于已有测试中依赖 lowering 结构的部分，允许重标定为新结构（以“无 tmp”优先）

## 风险与取舍

- 链式调用与解构赋值的 rhs 子树可能重复出现，静态图层面看起来像重复求值（用户已接受）。
- comprehension 从“显式 loop + append”变为“高阶 operator call”，会降低 CFG/dataflow 的可解释性，需要同步调整部分 dataflow 相关测试与语义预期。

## Phase 2：消除剩余“合成局部变量”

在完成 Phase 1（链式调用/解构/推导式）后，仍存在一些 lowering 会通过 `getUnusedName(...)` 引入合成局部变量（例如 `manager0/enter0/exit0/value0`，以及部分场景仍会生成 `tmp\d+`）。本阶段目标是将这些也纳入“无合成局部变量”的范围。

### 4) class 继承基类：移除 `handleInheritance` 中的 tmp 赋值

现状：当 `class X(Foo(), Bar): ...` 的基类表达式是 `Call` 时，会通过 `tmp = Foo()` 产生 identifier 名作为 `inheritsFromTypeFullName` 的字符串输入。

新策略：

- 不再创建任何 `LOCAL/IDENTIFIER/ASSIGNMENT`
- 直接使用基类表达式的 `code`（或可推导的 name）作为 `inheritsFromTypeFullName` 的元素
- 允许近似：如 `Foo()` 视为 `Foo` 或 `Foo()`（取决于现有下游更能消费的形式）

### 5) for lowering：移除 iterator 临时变量

现状：`for x in y` 会先 `iterator = y.__iter__()`，并在 while 内多次对 iterator 调用 `__next__()`。

新策略：

- 不再创建 iterator 的合成局部变量
- 直接将 `y.__iter__()` 内联到 `__next__` 调用链中（例如 `y.__iter__().__next__()`），即便这会导致静态图层面看起来重复调用 `__iter__`（用户已接受近似）

### 6) with lowering：移除 manager/enter/exit/value 合成局部变量

现状：`with expr as x:` 会创建 `manager/enter/exit/value` 等多个合成局部变量，并拼装 try/finally。

新策略：

- 不再创建 `manager*/enter*/exit*/value*` 合成局部变量
- 通过内联 `expr` 子树构建 enter/exit/value 的调用结构，并保留 try/finally 结构（CFG 仍然可用）

### 7) dict literal with unpack：移除 tmp dict 容器

现状：`{x: 1, **z}` 会 lowering 成 `tmp = {}; tmp[x]=1; tmp.update(z); tmp`。

新策略：

- 不再使用 tmp 容器
- 引入/复用 `<operator>.*` 的高阶表达（operator call）来表示“带 unpack 的 dict literal”

### 8) compare chain：移除 tmp 保存 rhs 的 lowering

现状：`x < y < z` 会 lowering 为 `tmp1=y; x<tmp1 && { tmp2=z; tmp1<tmp2 && ... }`。

新策略：

- 不再创建 tmp 保存中间 comparator
- 直接表达为 `(x < y) and (y < z) and ...`，其中 `y/z/...` 子树允许重复出现

### 验收

- 扩展测试断言：除 `tmp\d+` 外，也要求 `cpg.local.name("(manager|enter|exit|value)\\d+").l` 为空
- 典型样例覆盖：
  - `class X(Foo(), Bar): ...`
  - `for x in y: ...`
  - `with manager() as x: ...`
  - `{x: 1, **z}`
  - `x < y < z < a`
