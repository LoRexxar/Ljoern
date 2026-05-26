# Changelog

## 2026-05-26

### pysrc2cpg（Python 前端）

- 移除/避免生成合成临时变量（`tmp\d+`、`manager/enter/exit/value` 等），并补充/更新回归测试覆盖
- 调整 compare chain、for/with lowering、dict literal with unpack 等场景的图结构，使输出更简洁
- 继承基类为“函数调用”的场景补回 `inheritsFromTypeFullName` 的 `<returnValue>` 解析逻辑（不依赖临时变量）
- 统一/修正部分 `methodFullName`、import resolver 与 type recovery 的期望与行为，并完成全量回归

验证：

- `sbt pysrc2cpg/test` 全绿

### CI（GitHub Actions）

- PR 工作流增强：增加并发取消（同一 PR 新 push 自动取消旧任务）、统一 Action 版本、保留全量测试与发行脚本测试
- master 工作流增强：补充并发取消与权限配置、统一 Action 版本

涉及文件：

- `.github/workflows/pr.yml`
- `.github/workflows/master.yml`

### 仓库治理（gitignore / 文档目录）

- `.gitignore` 增加 `/docs/` 忽略规则
- 将已被追踪的 `docs/` 从 git index 移除（后续 PR 不再携带 docs 目录变更）

涉及文件：

- `.gitignore`

