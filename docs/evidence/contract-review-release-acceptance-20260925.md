# 合同内审三日收口验收记录

记录日期：2026-09-22（第一工作日的可复现快照）。本文件按照 2026-09-25 最终验收格式持续记录；截至本次记录，结论为 **BLOCKED**，不得称作业务验收通过。唯一写入工作区为 `fix/p1abc-premerge-hardening-20260922`，起始 HEAD 为 `36a44958d03d48fb9d1c88410bfca8e85fc70091`，其祖先包含冻结 P1-C `b3615b8adf81989c83f7135de1a7061df3636399`。最终候选 HEAD 与 clean 状态以提交后的 `git rev-parse HEAD`、`git status --short` 和交付回复记录为准。未 push、merge、部署或执行 DDL。

详细根因、结构性设计 A/B/C、原件预期与 SHA-256 见 [单一发布台账](contract-review-release-ledger-20260922.md)。这里仅记录验收门禁与未完成项，不能用自动化 PASS 替代真实原件链路。

## 逐项结论

| 项 | 本次状态 | 可复核依据及剩余门禁 |
| --- | --- | --- |
| R01 历史任务重开 | FIXED_NOT_VERIFIED | 精确 task→run/step/attempt 与 actor 归属、旧 run 冻结 Contract/材料展示已通过自动化和集成 DM8 只读 SQL；本次刷新、新浏览器、后端重启写入链路未运行。 |
| R02 F02/ReviewRun 状态 | FIXED_NOT_VERIFIED | 列表与详情分开显示 F02 DONE、ReviewRun 聚合状态，前端自动化通过；真实浏览器未运行。 |
| R03 已知条款值与报告 | FIXED_NOT_VERIFIED | 新 finding 摘要保留已有比例、周期、日期等值；报告 schema v2 渲染冻结 evidence，旧 schema v1 字节模板回归通过；真实 artifact 下载未运行。 |
| R04 真实失败 run | BLOCKED | 集成 DM8 只读证实 `771b826f-27e1-4747-9de5-8bafd8f14470` 的 SEMANTIC_AI attempt `2cd75ea9-a249-3188-b120-c65b3ecf9ac8` 为 `WORKER_PROVEN_COMPUTATION_FAILURE`；provider/model/request ID 缺失，无法把五分钟推断为 timeout，也无法定位下层具体异常。旧 run 未重试或改写。 |
| R05 未知原因展示 | FIXED_NOT_VERIFIED | 空/无效日期不再由 UI 统称“材料依据不足”，一侧未知的状态使用中性说明；双方 typed 值仍属 C 设计。 |
| R06 成交事实确定性兼容 | FIXED_NOT_VERIFIED | 原件 01—05 通知书 PDF 的无冒号金额/供应商与 locator 只读解析通过；键值表、候选人语境等反例自动化通过；角色/项目身份、人工确认到 ReviewRun 的真实链未运行。新 AI 协议 A 仅有详细设计。 |
| R07 仅金额分项 | BLOCKED | 原件 01 合同四项 28,000/16,000/9,000/14,000 可从表中只读确认；正式 ContractLineItem 与 DM8 数量/单价非空，不能持久化真实未知。B 仅获准详细设计；无迁移、无伪造数量或单价。 |
| R08 typed 双方条款 | DEFERRED | 现有 v1 不持久化完整双方 typed 值；C 仅获准详细设计。不能宣称原件 03/04 全部差异已覆盖。 |
| R09 集成环境独占 | BLOCKED | 2026-09-22 12:59 +08 只读时 PENDING/RUNNING run、RUNNING attempt、有效 lease 都为 0，但未证明后续唯一 worker；配置脚本会指向旧 demo storage，不能直接执行。 |
| R10 Promotion 归属 | FIXED_VERIFIED | 祖先提交 `79546e4` 的 fail-closed 实现由本次后端全量回归覆盖，无重复修改。 |
| R11 文档/API 同步 | FIXED_VERIFIED | README、architecture、source-baseline、roadmap、runbook、OpenAPI 与当前有界能力一致；YAML 解析和 diff 检查通过。 |
| R12 原件完整验收 | BLOCKED | 仅源解析证据和自动化门禁；原件 01—06 的浏览器/AI/报告完整链均未运行。 |
| R13 间歇角色测试 | NOT_REPRODUCED | `ProjectIntakeContinuityApiIntegrationTest` 连续三次 3/3 通过；未为未复现原因改生产代码。 |

独立代码审查还发现一项 Important：同角色非 run 发起人可按已知 UUID 读取/下载报告，或读取/写入 ACK。新增回归先 RED，随后 closeout 的 run owner 校验覆盖报告读写和 ACK 读写，定向测试 GREEN。`contract-audit-tasks` 保留既有角色可见的任务中心列表语义；详细 ReviewRun 与 closeout 仍只允许发起人读取，历史导航对其他 actor 不可用。此处不把任务列表视为可读取他人的报告或冻结审查结果的授权。

## 三套验收矩阵

### A. 原始文件（只读字节，非合成完整 Contract）

| 套件 | 原件→parse/extraction | Role/Candidate→Confirmation→Contract→freeze→结果→页面/报告 |
| --- | --- | --- |
| 01 一致基线 | 通知书 67,000 与供应商 locator PASS；合同四项金额从 DOCX 表读取 PASS | NOT_RUN；行乘法无数量/单价应保持 NOT_EVALUATED |
| 02 金额差异 | 通知书 67,000 与供应商 locator PASS | NOT_RUN；合同 69,000 差异未在本次真实 run 证明 |
| 03 服务差异 | 通知书金额与供应商源解析 PASS | NOT_RUN；期限/质保/响应差异未完成双侧 typed 验收 |
| 04 条款差异 | 通知书金额与供应商源解析 PASS | NOT_RUN；违约条款逐项比较仍受 R08 限制 |
| 05 异项目通知 | 通知书字段可从文件解析，按设计不等于当前项目权威事实 | NOT_RUN；跨项目阻断的真实 Role/Identity 链未证明 |
| 06 扫描/空白 | 本次未作完整 OCR 原件解析 | NOT_RUN；不能伪造 PASS 或把 UNKNOWN 改成缺失 |

原件只读解析覆盖 5 套通知书和 1 份合同，**完整原件 E2E 为 0/6**。`OriginalAwardMaterialCharacterizationTest` 的 2 个断言组通过；不把解析成功记成 authority、Promotion 或审核结论成功。原件 SHA 清单在台账中；原文件未修改。

### B. 格式/语义变体与反例

当前自动化已覆盖 7 类：成交同义表达、键值表、无冒号段落、元/万元、否定句、候选人到明确最终结果的上下文转换、预算/报价排除。相关测试为 `ProjectFactExtractorTest` 的 `finalTransactionSynonymsRemainDistinctFromCandidateQuotesAndBudget`、`keyValueAwardTableKeepsTheValueCellAsEvidenceSource`、`candidateHeadingOnTheSamePdfPageDoesNotBecomeFinalAward`、`candidateHeadingInAwardKeyValueTableDoesNotBecomeFinalAward`、`candidateTableInlineCellCannotBypassTheTableContextGuard`、`explicitFinalResultAfterCandidateSectionCanStillBeExtracted` 与 `noColonAwardNoticeLinesKeepFinalAmountAndSupplierDistinctFromCandidateQuote`，另有既有金额单位测试。

冻结的 12 类建议集合中，其余 5 类尚未按 **最终成交事实协议** 验证：列序变化、多标包冲突、仅部分分项、小计/合计与正文重复、引用旧模板。既有项目预算分包/模板测试不能替代这五类的成交事实验收。新 AI 协议 A 未实施，因此没有声称“盲测”或其成功率。当前矩阵：7 类 source-level PASS、5 类 NOT_VERIFIED、0 类真实全链 PASS。

### C. 人工合成完整 Contract

现有 P1-C F02 和 ReviewRun 自动化覆盖五条金额/供应商规则、BigDecimal scale、单事实缺失、冻结快照往返和重开；本次 clean verify 包含这些回归。该矩阵只能证明完整结构化 fixture 的计算与持久化代码，不替代 A 中原件自动抽取，更不能填补 R07 中金额行的真实未知。

## 自动化、DM8 与运行门禁

| 门禁 | 本次执行环境/命令 | 结果 | 实际边界 |
| --- | --- | --- | --- |
| Backend | JDK 17，hardening worktree，`backend/.\mvnw.cmd -Daudit.original.materials.root=<只读原件目录> clean verify -q` | 275 suites、1790 tests、0 failure/error/skip | DM8 真实集成 IT 为 opt-in，普通 clean verify 不替代真实 DB |
| Frontend 默认隔离 | `node --test --experimental-strip-types "src/**/*.test.ts"` | 592/592 PASS | 自动化/SSR，非真实浏览器 |
| Frontend shared-process | 上式加 `--test-isolation=none` | 592/592 PASS | 自动化/SSR，非真实浏览器 |
| Frontend build | `npm.cmd run build` | exit 0，2408 modules | 有既有大 chunk 警告，非部署 |
| FastAPI | 已验证 Python 3.11 venv，hardening 服务源码，`python -m pytest -q` | 340 passed | 1 条 Starlette deprecation warning；无 Python 源码改动 |
| DM8 catalog opt-in | `ContractCandidateSupportAuthorityRepresentativeDm8IT#exactReadOnlyCatalog` | PASS，DM8 major 8 | 集成 DM8 SELECT only，无 DDL |
| DM8 navigation opt-in | `ContractAuditTaskNavigationRepresentativeDm8IT` | PASS | 已有 run 的 step/task、幂等与历史 SQL 只读；非浏览器 |
| 真实 AI/provider 新尝试 | 无 | NOT_RUN，0 attempts | 旧失败 run 的 attempt 不算本次新尝试 |
| 真实浏览器、重启、报告下载 | 无 | NOT_RUN | R09 独占与存储身份门禁未满足 |

运行配置 `D:\code\audit_agent_1D\.worktrees\ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.ps1` 仅按固定键作为只读输入解析；**没有 dot-source、改写或执行迁移**。其 storage root 指向旧 demo 工作树，`FileSystemStorageAdapter` 构造时会初始化目录和清理 staging，因此不能用它直接启动本 worktree。集成 DM8 是 Phase 1B 已迁移库，本次没有重跑 P1B migration。旧 run 的四份冻结文件只读 SHA 与持久记录一致；没有生成/修改旧 evidence。

## 交付和剩余条件

代码改动限于历史任务导航与冻结展示、F02/ReviewRun 状态呈现、已有条款值与报告证据展示、通知书确定性解析兼容、closeout owner 权限回归；文档与测试同步。没有新增 Candidate authority、AI 金额判断、第二套 F02、DM8 migration、公共破坏性 API 或 ReviewRun 生命周期重写。结构性 A/B/C **仅详细设计获批**，实施、DDL 与真实环境数据外发均未获批。

可进入 PR 代码审查：**是，本次有界改动的全量自动化与独立审查已完成**。可进入业务验收：**否**。解除 BLOCKED 至少需要：受控独占 worker 与独立 storage 身份的真实集成窗口；原件 01—06 按预期矩阵完成多层链路与浏览器/报告验收；若要求金额分项自动合计，另行批准 B 的模型/DM8 schema 及迁移方案；若要求可靠 typed 条款双方值，另行批准 C 的结构实施。R04 具体语义失败根因需在安全新运行中获得脱敏的请求/失败层证据。未满足条件时不得把局部 GREEN 宣称为完整发布通过。
