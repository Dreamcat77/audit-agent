# Documentation index

## Current facts

- [Architecture](architecture.md) — 唯一 current 总架构、数据流和功能状态矩阵。
- [Source baseline](source-baseline.md) — 唯一 current Git/source/environment baseline。
- [Compatibility matrix](compatibility-matrix.md) — 组件、profile 与验证边界。
- [Roadmap](roadmap.md) — 唯一 current P0/P1/P2 路线图。
- [Open questions](open-questions.md) — 需要甲方/产品输入的问题。
- [Engineering follow-ups](engineering-followups.md) — 已知工程债与关闭标准。
- [OpenAPI](openapi.yaml) — 由当前 local Springdoc 生成或显式标记 blocked 的静态契约。

## Test and operations strategy

- [Material test and sanitized debug strategy](material-test-and-debug-strategy.md)
- [AI/OCR quality strategy](ai-ocr-quality-strategy.md)
- [Current customer deployment runbook](runbook/customer-deployment-current.md)
- [Account bootstrap](runbook/account-bootstrap.md)
- [DM8 driver provisioning](runbook/dm8-driver-provisioning.md)
- [DM8 schema map](../db/dm8/README.md)

`runbook/ai-ocr-project-intake-dev29.md` 暂时保留为被当前 Java 结构回归读取的既有验证资料，
不是 current customer deployment 入口。不得向其中加入真实地址、账号或 secret。

## Architecture decisions

`adr/` 记录长期决策及其历史上下文。ADR 即使 superseded 也可保留，但其过时 gate 不能覆盖
current architecture：

- [ADR-001 Infrastructure Adapter](adr/ADR-001-infrastructure-adapter-boundary.md)
- [ADR-002 DM8/MyBatis driver](adr/ADR-002-dm8-mybatis-driver-strategy.md)
- [ADR-003 Redis/TongRDS cache boundary](adr/ADR-003-redis-tongrds-cache-boundary.md)
- [ADR-004 FastAPI HTTP adapters](adr/ADR-004-fastapi-http-adapters.md)
- [ADR-005 Session authentication](adr/ADR-005-local-account-session-authentication.md)
- [ADR-006 Observability and audit](adr/ADR-006-observability-and-durable-audit.md)
- [ADR-007 AuditTask/idempotency](adr/ADR-007-audit-task-lifecycle-and-durable-idempotency.md)
- [ADR-008 ReviewSession/file ingestion](adr/ADR-008-contract-review-session-and-durable-file-ingestion.md)
- [ADR-009 Project Intake alignment](adr/ADR-009-project-intake-and-pm-product-alignment.md)

## Historical evidence

`evidence/` 中的文件保留其日期、命令和环境证明。历史 evidence 不是当前客户环境状态，也不能
替代本轮 [source baseline](source-baseline.md)。已完成的临时 implementation plan/spec 不再作为
current 文档保留；Git 历史仍保存其演进过程。

## Maintenance rules

- CURRENT 架构、baseline、roadmap 各只有一份。
- 新事实优先更新上述 current 文档，不创建 `*_RESULT_V2.md`、`*_FINAL_RESULT.md` 或散落诊断。
- 临时诊断保持 untracked/ignored；只有满足 VERIFIED 定义的脱敏 evidence 才进入 `evidence/`。
- 删除文档前做 repository-wide reference search，修复链接并运行 link check。
- migration 存在、配置存在、UI/API 存在都不能被写成客户部署或持久化已完成。
