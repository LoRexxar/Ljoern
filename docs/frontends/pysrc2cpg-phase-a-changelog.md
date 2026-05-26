# pysrc2cpg Phase A 结构对齐变更记录

## 已完成变更

- META_DATA/root 写入改为统一使用 `MetaDataPass`（root 规范化为 absolute path）
- TYPE 生成改为集中式 pass：新增 `PyTypeNodePass` 统一生成 `TYPE` 节点
  - 说明：不从 `inheritsFromTypeFullName` 直接生成 TYPE，避免 default overlays 过早创建 `INHERITS_FROM` 边导致 post-processing 后出现多余 baseType
- METHOD_RETURN.evaluationStrategy 对齐为 `BY_VALUE`（与 x2cpg 默认一致）

## 影响面（当前验证）

- `sbt pysrc2cpg/test` 全量测试通过（包含类型恢复、继承全名解析、数据流等）

