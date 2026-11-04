# Source and environment baseline

本文记录 `integration/pre-main-20261010` 与
`fix/budget-procurement-review-20261012` 受控合并后的源码和环境边界。历史 evidence
只证明其记录时的命令与环境，不能覆盖当前源码或本次合并验证。

## Current Git baseline

本次合并的第一 parent 为 `63120a0141ce36773b222a410a3fb6fd8b3b66d1`，第二 parent 为
`04333efca10e21a71cd2b3b8019d6b662bae697d`。最终 current HEAD、测试结果和远端状态
以本次合并完成报告为准；这些事实不外推到 `main` 或客户部署。

### Historical 2026-09-14 observation

2026-09-14 在注册 worktree `D:\code\audit_agent_1D\.worktrees\main-next` 观察到：

| 项 | 值 |
|---|---|
| branch | `feature/main-next-20260914` |
| HEAD | `dd0d02c04c72b8a3811bcd653ba23f8f1aee2ffc` |
| origin/main | `dd0d02c04c72b8a3811bcd653ba23f8f1aee2ffc` |
| log | `dd0d02c Merge pull request #19 from ztysew2/integration/release-20260911` |
| initial worktree | clean |

执行门禁：

```powershell
git status --short
git branch --show-current
git rev-parse HEAD
git rev-parse origin/main
git log -1 --oneline
```

当时结果：`HEAD == origin/main`。这只是 2026-09-14 的历史观察，
不代表当前 hardening 分支或 `main` 已包含 P1-A/B/C。

## Authoritative source inventory

本轮逐项核对：

- `backend/src/main/java/com/fuquan/audit/{common,security,review,contract,budget,blacklist,procurement,integration}`；
- `backend/src/main/resources` 的 profile、安全、AI/OCR、SQL 与日志配置；
- `backend/src/test` 的单元、结构、并发和集成回归；
- `frontend/src`、`frontend/package.json` 的 React、同源 API、Session/CSRF 和审查状态；
- `services/audit-ai` 与 tests 的 FastAPI、PaddleOCR、model gateway/evidence/chunker/correction；
- `db/dm8/V001` 至 `V051`、受控 `CONTRACT__*.sql` 演进线、migration helper 及说明；
- 当前 `docs`、ADR、evidence 和 runbook 引用关系。

源码事实地图见 [architecture](architecture.md)，数据库地图见 [db/dm8/README](../db/dm8/README.md)。

## Current implementation baseline

当前主链已经实现：

- React/Vite 同源调用 Spring Boot `/api/v1`；
- Session Cookie + CSRF，不是 JWT；
- Project、ProjectFile、安全 ZIP、受控内容寻址存储、解析 provenance 与恢复；
- PDFBox、POI、PDF usefulness gate 和 FastAPI/PaddleOCR；
- ExtractionResult、ContractCandidate、人工 promotion、ProjectContract；
- immutable ParseResult、确定性/有界 AI 角色评估、人工 RoleDecision、项目身份评估/人工
  IdentityDecision、Candidate 专属 AWARD_NOTICE support authority、持久 Confirmation；
- F02 五条 Java `BigDecimal` 确定性规则和 durable idempotency/task/result/acknowledgement；
- F01、F03 的当前纵向切片和 F04 demo 规则集；
- 采购多材料上传/恢复/关联、报价与合规审查，以及预算材料后台任务和历史结果导航；
- Contract semantic AI 的脱敏、bounded evidence、有限修正与 locator 验证；
- durable ReviewRun、冻结 Contract/材料输入、step/attempt、semantic/breach/term finding/evidence、
  server worker、精确与 `/latest` 重开；F02 step 关联 durable AuditTask；
- 非 F02 finding acknowledgement、版本化报告 metadata/artifact、sanitized metadata-only debug bundle。

当前仍需单独工作：

- 与真实客户材料模板/更广语义表达的兼容；本 hardening 只验证了合成原件中
  无冒号“成交金额/成交供应商”字段行及键值表的有限模式；
- 更广真实材料回归、客户特定业务规则、F09/F15 等后续范围；
- V047-V051 在各目标 DM8 的 schema/catalog preflight、受控迁移/回滚及客户平台/provider/公网验收；
- 预算/采购材料 worker 在迁移验证前保持默认关闭，不能用应用启动试错代替 Catalog 门禁。

`Project != ProjectFile != ContractCandidate != Contract != ReviewRun != AuditTask`。
P1-B authority mode 为 `PRIMARY_ONLY_COMPAT` 或 `EXPLICIT_SUPPORT_AUTHORITY`。显式 support
仅限 Candidate 专属、已确认的 `AWARD_NOTICE`，只提供 `AWARD_AMOUNT`、`AWARD_SUPPLIER`、
`AWARD_SUPPLIER_CREDIT_CODE`；零个 distinct 值为 missing，一个为 resolved，多个为 conflict/null，
不会自动选赢家。Human Confirmation 才是 Contract 最终业务事实权威。

## Current verification boundary

P1-C 完成证据记录了 Windows/JDK 17 后端 `clean verify` 1,761/1,761、前端两种隔离模式
各 583/583 和 build PASS；真实 P1-B 集成 DM8 上，完整 Confirmed Contract 冻结/重开后
F02 五个 rule code 产生六条可计算结果，刷新与后端重启重开稳定。参见
[P1-C F02 evidence](evidence/phase1c-f02-structured-data-completeness-20260921.md) 和
[P1-B Candidate evidence](evidence/candidate-cross-material-authority-verification-20260920.md)。
状态为 `IMPLEMENTED` / `VERIFIED_LOCAL` / `VERIFIED_INTEGRATION`；客户环境仍
`NOT_VERIFIED_CUSTOMER`。本 hardening 的最终门禁另见本次完成报告，不回填历史测试计数。

2026-09-22 hardening 进行中：历史 AuditTask 导航改为由持久化 ReviewRun step 归属证明
精确 runId；新的条款 finding 以可读文字保存已识别值；报告快照 v2 展示冻结 finding
状态与证据，而 v1 已准备报告仍使用原字节模板。01—05 套合成原件通知书只读解析与
SHA 核对已通过；这些均不等于原件上传到正式 Contract 的 E2E 验收。

### Historical 2026-09-14 local verification

2026-09-14 source audit 后执行：

```powershell
cd backend
.\mvnw.cmd clean verify
```

环境：Windows worktree、本地 `local`/测试 seams、JDK 17/Maven Wrapper；未连接客户 DM8、Redis、
PaddleOCR 或模型服务。

观察结果：`Tests run: 698, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

这只证明当时源码在该本地测试环境可构建和可回归；不代表当前 hardening 测试计数，
也不证明客户 DM8、Redis、PaddleOCR、模型或国产化运行时已兼容。

附加本地验证：

- `frontend`: `npm.cmd run build`，Vite 6.3.5 转换 2382 modules，build exit 0；存在既有
  >500 kB chunk warning。目标 worktree 缺 `node_modules`，验证临时复用了与其 `package-lock.json`
  SHA-256 完全一致的现有依赖目录，完成后已删除 junction。
- `services/audit-ai`: 复用 requirements/pyproject SHA-256 完全一致的现有 venv 执行
  `python -m pytest`，`229 passed, 1 warning`。该解释器实际为 Python 3.14，而项目声明
  `>=3.11,<3.12`，所以这是额外回归证据；支持范围内 Python 3.11 本轮仍为 `NOT_VERIFIED`。
- Markdown relative file links：自定义只读 checker，`59` links checked，`0` missing。

## OpenAPI refresh

2026-09-22 从本 hardening 分支新构建的 JAR（`local` profile、localhost 临时端口）请求
`GET /v3/api-docs`，得到 HTTP 200、OpenAPI 3.1.0、114 paths、217 runtime schemas。
[openapi.yaml](openapi.yaml) 以这些实际注册路径和公开 record schema 机械刷新，同时保留已有
经源码验证的严格 `JsonNode` 请求/错误契约、安全元数据，以及仅在
`local-dev/dev29/customer` 且 server worker 开启时注册的 retry 路由。静态文档为 115 paths、
257 schemas（包含这些条件路由/手工约束 schema）；server URL 固定为同源 `/`，不包含本机端口。
Candidate detail/ContractCandidate 的 provenance/support 字段取自本次 Springdoc 输出。
Confirmation 的必填字段按 `ConfirmedContractPayload` 构造校验补全，不能仅据注解缺失说它可选。
focused OpenAPI 结构测试验证这些字段和主要生命周期/收口路径。

此处是 `VERIFIED_LOCAL` 接口文档生成事实；静态文档不证明所有 API 已在客户环境验收。

## Deployment facts supplied for the current baseline

以下是本任务输入中给出的最新环境事实，不是在本轮通过 SSH/公网探测重新取得的证据：

- 已部署版本对应 `main@dd0d02c…`；
- LAN 完整 E2E 已通过：login、Project、upload、OCR、Contract promotion、Qwen semantic review、
  evidence drill-down；
- Nginx 长请求 reverse-proxy read timeout 已修正，须保留约 `650s` 语义；
- HTTPS / certificate sync 已执行；
- 网络侧已配置公网端口到内部 HTTPS 的映射；
- 真正公网访问仍失败。

因此 current status 必须写为：

```text
LAN_E2E=VERIFIED_BY_REPORTED_ACCEPTANCE
HTTPS_CERTIFICATE_SYNC=REPORTED_COMPLETED
PUBLIC_PORT_MAPPING=REPORTED_CONFIGURED
PUBLIC_HTTPS_CONNECTIVITY=NOT_WORKING / NOT_ACCEPTED
PUBLIC_DEPLOYMENT=NOT_VERIFIED
```

DNS、NAT、证书或映射单项存在都不能推出公网验收通过。公网故障属于 deployment/network
follow-up，与应用业务代码分开处理。

## Database evidence boundary

仓库中 migration 到 V051，另有依赖受控的 `CONTRACT__*.sql` 手工演进线；
`MIGRATION EXISTS != DEPLOYED ON CUSTOMER DM8`。本任务不连接或 repair 客户数据库，
所以客户 schema、字段宽度、索引和 migration history 均为 `NOT_VERIFIED_CUSTOMER`。
现有风险分类见 [engineering follow-ups](engineering-followups.md)。

## Retained historical evidence

以下文件保留为历史可执行交付证据；其中 SHA、测试数和环境状态只适用于各自记录时点：

- [bootstrap delivery](evidence/bootstrap-delivery-20260814.md)
- [round 2 delivery](evidence/round2-delivery-20260814.md)
- [F02 contract review](evidence/f02-contract-review-20260817.md)
- [local account/auth/observability](evidence/local-account-auth-observability-20260817.md)

引用这些 evidence 时必须带日期与环境，不能把历史 `VERIFIED` 自动外推到当前客户环境。

## 2026-10-14 local contract-repair increment

Scope is the existing `integration/pre-main-20261010` worktree only. The increment retains
the synchronous upload contract while adding durable 202 file jobs, independent archive
children, GET-based frontend recovery, ordered mixed-PDF/DOCX parsing, bounded fact
batches, and shared versioned business opinions/report snapshots. Both contract workers
are explicit startup gates; unrelated budget/procurement workers remain disabled.

Fresh local Windows verification before runtime acceptance: Maven `clean verify` reports
2,243 tests, zero failures/errors and two existing condition skips; actual Node test suite
753 passes; supported Python 3.11 full pytest 470 passes with one existing dependency
warning. These are build/test evidence, not browser acceptance or customer verification.
The dedicated runner is `scripts/local/contract-test.ps1`; exact clean Git SHA, artifact
fingerprints, protected local DM8 migration, fresh storage receipt and dependency checks
are required. Private settings, logs, backups and runtime receipts stay ignored.
