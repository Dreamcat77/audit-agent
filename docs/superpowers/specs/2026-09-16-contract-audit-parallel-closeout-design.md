# 内审系统 Contract Audit 1.0 并行收官设计

DESIGN APPROVED IN CHAT
WRITTEN SPEC PENDING HUMAN REVIEW

## 1. 文档目的与约束

本文把已经批准的“方案 B：并行收官”固化为单一、可审查的架构契约。本文不是 implementation plan，不授权编写 production code、数据库迁移、测试、部署脚本或五条实施分支；书面规格经人工复核后，各 Track 还必须分别形成实施计划并再次接受边界审查。

设计基线为：

- source branch：`origin/feature/contract-review-run-phase2c-frontend-20260916`
- exact source SHA：`20c74c0cde631ad611fcbfb443b7d467476e0e4d`
- target design branch：`feature/contract-closeout-design-20260916`
- target design worktree：`D:\code\audit_agent_1D\.worktrees\contract-closeout-design`
- 事实来源：该 SHA 的源码、DM8 DDL、Phase 1A–1E 与 Phase 2A–2C 已落地行为，以及 `2026-09-14-contract-audit-evolution-master-design.md`

本轮明确不做：实现、migration、API 变更、Java/Python/React 代码变更、部署、真实 DM8/OCR/模型环境验证、PR 或合并。

基线交付状态只按已有证据表述：Phase 1A–1E、Phase 2A、Phase 2B、Phase 2C 已完成；Phase2B 留有 local backend unit/integration 证据，Phase2C 留有 local frontend test/build 证据。当前仍是 `REAL DM8 NOT VERIFIED`、`REAL CUSTOMER MATERIAL NOT VERIFIED`、`BROWSER VISUAL E2E NOT VERIFIED`；当前 feature head 没有可引用的 GitHub status checks。

## 2. 当前状态审计

### 2.1 已形成的 durable 主链

当前代码已经形成以下可恢复链路：

```text
PROJECT
  -> PROJECT_FILE
  -> immutable PROJECT_FILE_PARSE_RESULT
  -> role assessment / human role decision
  -> identity assessment / human identity decision
  -> CONTRACT_CANDIDATE + candidate confirmation
  -> PROJECT_CONTRACT + CONTRACT
  -> CONTRACT_REVIEW_RUN
       -> frozen material manifest
       -> fixed step manifest
       -> step attempt #1
       -> F02 AuditTask/AuditCheckResult or non-F02 Finding/Evidence
```

已经验证的架构事实：

- DM8 保存 Project、ProjectFile、immutable parse、角色与项目身份决定、候选合同、Contract、ReviewRun、Step、Attempt、Finding/Evidence、F02 AuditTask/AuditCheckResult、幂等与业务操作审计。
- `ContractReviewRun` 在创建时冻结 Contract/version/candidate/confirmation、review profile/rule/model policy、材料 file SHA、parse result/version/text SHA、effective role、role decision、identity decision 和 input snapshot identity。
- ReviewRun 固定五个 step type：`MATERIAL_VALIDATION`、`F02_DETERMINISTIC`、`SEMANTIC_AI`、`BREACH`、`TERM`。
- Step 执行已经采用短 START 事务、事务外 computation、短 COMPLETE/FAILURE/UNKNOWN 收口；模型请求在 durable external-call state 变为 `REQUESTED` 后才发出。
- F02 的业务真相仍是已有 `AUDIT_TASK` 与 `AUDIT_CHECK_RESULT`；ReviewStep 只引用真实 AuditTask，不复制 F02 结果。
- 非 F02 模型结果已规范化为 durable Finding/Evidence；raw model response 不是正式 finding。
- Phase2C 前端通过 ReviewRun GET 重开 durable 状态；`sessionStorage` 只保存 run pointer、客户端命令 key 和已证明的 restart-required 导航标记，不保存业务事实。
- Phase2C 仍由浏览器调用各 step 的同步 execute API；尚无 DB-backed worker、lease recovery 或多 attempt retry。

### 2.2 仍需收官的结构缺口

| 缺口 | 当前事实 | 1.0 收官目标 |
|---|---|---|
| 非 F02 人工确认 | 只有 F02 `REVIEW_FINDING_ACKNOWLEDGEMENT` | 新建非 F02 finding acknowledgement，不破坏 F02 表 |
| Final Report | 无 durable、版本化正式报告 | 冻结 input/finding/ack snapshot，artifact 与 DM8 元数据双重可证明 |
| 后台执行 | 浏览器逐 step 同步触发 | DB claim/lease worker，进程退出后可恢复 |
| Retry | attempt #1 约束，UNKNOWN 无正式处置 | append-only attemptNo，显式 retry，UNKNOWN fail closed |
| 诊断交付 | 只有受控日志/metadata | 授权、审计、版本化 sanitized debug bundle |
| 质量证明 | 测试分散，provider 比较无统一闸门 | versioned fixture/ground truth/metric harness |
| 两套材料入口 | ProjectFile 与 ReviewDocument/MaterialBinding 并存 | 新 Project-backed ReviewSession 只引用 ProjectFile；旧表只做 legacy compatibility |
| 能力扩展 | 固定五步，无统一 capability descriptor | 保留固定五步，新增不可执行的 capability/profile seam |
| Provider 替换 | FastAPI 有 OCR port 与单一 OpenAI-compatible model gateway | capability-aware adapter/config routing，Java 保持业务权威 |

### 2.3 旧 ReviewSession 的真实边界

现有 `CONTRACT_REVIEW_SESSION` 只有 `PREPARING_MATERIALS -> READY`。`CONTRACT_REVIEW_DOCUMENT` 自己保存文件元数据、storageKey、上传状态和替换链；`CONTRACT_REVIEW_MATERIAL_BINDING` 保存当前 slot 绑定。上传通过相同 `FileStoragePort`，但它仍是一套独立于 `PROJECT_FILE` 的材料身份与接入流程。V005 tombstone 和内部 delete command 是历史兼容；当前产品不得恢复公共删除能力。

因此，本设计不会宣称两套体系已经统一，也不会删除历史表。Track D 的目标是：所有新 Project-backed ReviewSession 只能绑定已有 ProjectFile/parse，不再复制 bytes，不再重新 parse/OCR；legacy ReviewDocument 保持只读/兼容路径。

### 2.4 当前外部计算与存储边界

- `FileStoragePort` 是唯一文件/制品存储端口；实现使用受控 root、content-addressed opaque `StorageKey`、create-only publication，并区分 `PRESENT`、`DEFINITELY_MISSING` 与 `UNKNOWN`。
- FastAPI 当前把 OCR 与 model routes 作为计算入口。OCR 经 `OcrPort`/Paddle adapter；model 经 `ModelGatewayPort`/OpenAI-compatible adapter。FastAPI 对 schema、输入范围、安全输出做技术校验，但不拥有 Project/File/Run 身份或业务决定。
- Java 必须继续从 AuthContext 与 DM8 装配身份和 server-owned input；客户端或 FastAPI 返回的 userId/projectId/fileId/runId 文本不能成为所有权证明。

## 3. Contract Audit 1.0 完整闭环

目标用户流程：

```text
进入 Project
  -> 逐份上传 ProjectFile
  -> parse/OCR
  -> role assessment + human resolution
  -> identity assessment + human resolution
  -> ContractCandidate + human contract confirmation
  -> Contract
  -> 选择 ProjectFile 材料
  -> 创建并冻结 ReviewRun
  -> 后台、可恢复地执行已启用 steps
  -> 查看 Finding/Evidence
  -> 人工 acknowledgement / handling opinion
  -> 生成版本化 Final Report
  -> 刷新、重新登录、进程重启后 durable reopen
  -> 在授权下生成 sanitized diagnostics
  -> 用统一 quality harness 评测 parser/OCR/role/identity/AI/provider
```

任何正式结论必须能沿下列链条反向证明：

```text
Project
  -> Contract + exact contractVersion
  -> ProjectFile[] + SHA-256
  -> immutable parseResult[] + parseVersion + canonicalTextSha256
  -> human role/identity/candidate decisions where required
  -> ReviewRun + inputSnapshotIdentity
  -> profile/rule/model policy versions
  -> ReviewStep + exact Attempt
  -> Finding + Evidence
  -> Acknowledgement snapshot
  -> Report version + artifact SHA-256
```

链条中任一 durable 资源缺失、归属不一致、hash 不一致或状态为 UNKNOWN 时，系统不得伪造完成、自动补事实或从当前最新文件重建历史结论。

## 4. 权威边界冻结

### 4.1 System of Record

DM8 是以下事实的唯一 System of Record：

- 业务资源身份、所有权和状态；
- human role/identity/candidate/finding decisions；
- durable idempotency；
- ReviewRun、Material、Step、Attempt；
- Finding/Evidence 与 F02 AuditTask/AuditCheckResult 引用；
- acknowledgement、report metadata、worker claim/retry metadata；
- debug bundle metadata/authorization/audit；
- profile/capability policy identity 和生产路由批准状态；
- Operation Audit。

### 4.2 文件系统

现有 `FileStoragePort` 是唯一 durable bytes 边界，用于：

- 原始 ProjectFile；
- 已生成的 report artifact；
- sanitized debug bundle artifact；
- 明确批准并经过脱敏的 crop/fixture/PDF。

不得创建第二套 filesystem/storage subsystem。DM8 保存 storageKey、size、SHA-256、media type、artifact schema/version/status；文件系统不保存业务状态。只有 artifact bytes 成功 create-only publish 且经 inspect/hash 验证后，DM8 才能把 artifact 状态提交为可发现的 `AVAILABLE`。`inspect=UNKNOWN` 不能按 missing 或 success 处理。

### 4.3 Redis/TongRDS

仅用于 Session、Cache Aside 与可丢失临时态。它不能保存唯一 worker queue、claim、Finding、Ack、Report 或 debug bundle 真相；Redis 丢失只允许导致重新登录、重新读取 DM8 或重新计算无业务价值的缓存。

### 4.4 FastAPI 与 Java

FastAPI 是 OCR/model computation gateway，只接收 Java 构造的有界输入并返回 typed computation result/provenance。Java 负责：

- AuthContext、RBAC、CSRF、resource ownership；
- input snapshot、schema 与 evidence grounding 校验；
- 业务状态机、policy、transaction、idempotency；
- provider result 到正式 Finding/Evidence 的转换；
- acknowledgement、report、worker、debug bundle 持久化。

AI/OCR 不直接写 PROJECT_FILE role、project ownership、Contract、Finding acknowledgement 或 Report。

## 5. Common Model Freeze

五个并行 Track 必须共享并冻结以下模型，不得各自重定义：

- `ContractReviewRun`：一次完整、可证明的审核；
- `ContractReviewRunMaterial`：创建时冻结的材料 manifest；
- `ContractReviewStep`：run 内的逻辑 capability execution slot；
- `ContractReviewStepAttempt`：一次具体执行尝试；
- `ContractReviewFinding` / `ContractReviewEvidence`：非 F02 durable result；
- F02：`AuditTask` / `AuditCheckResult` 继续是 physical durable truth；
- ProjectFile parse/role/identity/candidate/confirmation authority chain。

冻结规则：

1. 当前五个 `ContractReviewStepType` 和默认 profile 顺序不变。
2. 不把 F02 check results 复制为新 finding 表中的另一份权威数据。
3. 不从当前 ProjectFile/Contract 反向改写已冻结 ReviewRun。
4. Attempt 只能 append；不能重置 attempt #1 或覆盖历史 terminal attempt。
5. Finding/Evidence 属于 exact run/step/attempt；ack 属于 exact finding/version；report 属于 exact frozen snapshot。
6. UNKNOWN 与 FAILED、MISSING、PENDING 都不等价。
7. 所有 durable resource 必须有基于服务器所有权校验的 GET reopen path。
8. 任一 Track 发现必须破坏这些契约才能实现时，立即停止并输出 `CROSS_TRACK_CONTRACT_BLOCKER`，由集成设计审查处理。

## 6. Track A — Acknowledgement 与 Final Report

### 6.1 非 F02 acknowledgement

新增逻辑实体 `ContractReviewFindingAcknowledgement`，只覆盖 `CONTRACT_REVIEW_FINDING`。现有 F02 `REVIEW_FINDING_ACKNOWLEDGEMENT` 不迁移、不改表、不换 API 语义。

建议最小字段：

| 字段 | 语义 |
|---|---|
| acknowledgementId | durable identity |
| projectId / contractId | 查询与 FK 之外的 ownership projection；写入时由 run 推导 |
| reviewRunId | exact frozen run |
| reviewStepId / attemptId | finding 的执行来源 |
| findingId | exact non-F02 finding |
| findingVersionNo | acknowledgement 时观察到的 finding version |
| acknowledgedBy | `AuthContext.currentUser.userId`，FK 到 SYS_USER |
| acknowledgedAt | server clock |
| handlingNote | 可空、有界，建议上限 1000 code points |
| requestId / traceId | 业务审计关联 |
| createdAt / versionNo | append-only row 的审计与并发字段 |

1.0 采用“一条 finding 最多一个正式 acknowledgement”的最小语义。它表示“该提示已由某人看到并留下处理意见”，不表示 finding 已消失、已修复或 AI 判断已被认可。重复同一业务命令返回同一 acknowledgement；不同 handling note、actor、finding version 使用相同 key 返回 409。

Ack eligibility：

- finding 必须属于请求 path 中的 project/contract/run；
- run、step、attempt、finding 归属链完整；
- finding 必须是 durable、可重开的非 F02 finding；
- acknowledgedBy 只来自 AuthContext；
- acknowledgement 不更新 finding status、不重写 recommendation、不删除 evidence；
- F02 finding acknowledgement 继续走现有 F02 路径。

幂等 scope：`CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE`。fingerprint 至少包含 actor、projectId、contractId、runId、stepId、attemptId、findingId、findingVersionNo、规范化 handlingNote。same key/same fingerprint replay；same key/different fingerprint 409；并发 loser 重开 winner durable result。

### 6.2 Final Report

新增逻辑实体 `ContractReviewReport`，append-only 且版本化。建议字段：

| 字段 | 语义 |
|---|---|
| reportId | durable identity |
| reviewRunId | report 所属 run |
| reportVersion | run 内从 1 开始严格递增 |
| reportSchemaVersion | canonical manifest/render contract version |
| status | `PENDING / GENERATING / AVAILABLE / FAILED / UNKNOWN` |
| inputSnapshotIdentity | 与 frozen run 完全一致 |
| findingSnapshotIdentity | canonical finding/evidence manifest hash |
| acknowledgementSnapshotIdentity | canonical F02 + non-F02 ack manifest hash |
| artifactStorageKey | AVAILABLE 时必填 |
| artifactMediaType / artifactSize / sha256 | artifact identity |
| generatedBy | AuthContext actor；系统自动生成时使用已持久化 system actor policy，而非客户端 userId |
| requestedAt / generatedAt / failedAt / unknownAt | durable timestamps |
| safeReasonCode | 非成功的 bounded reason |
| requestId / traceId / versionNo | audit/concurrency |

`reportVersion=N+1` 永不覆盖 N。finding 或 acknowledgement 变化后重新生成形成新版本；旧 artifact 继续可按 reportId/version reopen。PDF 是否为第一种 renderer 是实施选择，数据模型不依赖 PDF；renderer 通过 `ReportRendererPort` 返回 bytes/media type，artifact 统一经 FileStoragePort。

### 6.3 正式报告资格

只有满足全部条件才能生成 `AVAILABLE` 的正式报告：

- ReviewRun status 为 `COMPLETED`；
- frozen run/material/step/attempt manifest 自校验通过；
- 每个 required step 为 COMPLETED，且 exact output resource 可重开；
- F02 引用真实 AuditTask/AuditCheckResult；非 F02 Finding/Evidence 与 exact completed attempt 一致；
- input/finding/ack snapshot identity 可重复 canonicalize；
- artifact publish/inspect/hash 均可证明。

`PARTIALLY_COMPLETED`、`FAILED`、`UNKNOWN`、仍有 PENDING/RUNNING step 的 run 不能生成正式报告。产品可以生成明确标记为 `INCOMPLETE_REVIEW_EXPORT` 的非正式导出，但其标题和 manifest 必须包含“审核未完整完成”，且不能进入正式 `ContractReviewReport AVAILABLE` 序列；1.0 默认不提供该导出，避免客户误用。

### 6.4 Report snapshot canonicalization

`findingSnapshotIdentity` 输入按 step enum order、attemptNo、finding ordinal、findingId 排序，包含 finding version、source、risk/status/attention、policy/schema/model provenance 和 evidence identity/hash。`acknowledgementSnapshotIdentity` 同时包含：

- F02：checkResultId + acknowledgementId + actor + time + ruleVersion；
- 非 F02：findingId + findingVersion + acknowledgementId + actor + time + normalized handlingNote hash；
- 未 acknowledgement 的 finding 也以明确 `NONE` token 进入 manifest。

Report 从 frozen durable state 生成，绝不读取“当前最新 Contract”或“当前最新 ProjectFile parse”替换 frozen input。

### 6.5 Report transaction 与 storage recovery

```text
START tx
  validate ownership/eligibility/idempotency
  allocate reportId/version
  persist PENDING/GENERATING + frozen snapshot identities
  append operation audit
COMMIT

outside tx
  render bounded artifact
  FileStoragePort.stage/promote
  inspect + verify size/SHA

COMPLETE tx
  CAS GENERATING -> AVAILABLE
  persist storageKey/media/size/SHA/generatedAt
  idempotency SUCCEEDED + audit
COMMIT
```

确定失败以独立 FAILURE tx 收口；publish 请求已经发出但结果无法证明时写 `UNKNOWN`。Recovery 用 storageKey + expected hash 调用 inspect：PRESENT 且完全匹配可完成；DEFINITELY_MISSING 可在相同 report operation identity 下安全重 render；UNKNOWN 保持 UNKNOWN，不生成新 key 掩盖结果。

### 6.6 API seam（实施时 additive）

建议路径：

- `POST /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/findings/{findingId}/acknowledgements`
- `GET /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/findings/{findingId}/acknowledgements/{acknowledgementId}`
- `POST /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/reports`
- `GET /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/reports/{reportId}`
- `GET .../reports/{reportId}/artifact`

POST 必须要求 CSRF、role guard、Idempotency-Key、Operation Audit；GET 不要求 Idempotency-Key且不得生成资源。错误 ownership 一律安全 NOT_FOUND。

## 7. Track B — Durable Worker、Lease、Retry 与 Recovery

### 7.1 目标执行模型

```text
POST ReviewRun
  -> commit Run PENDING + Material + Steps + attempt #1 PENDING
  -> HTTP 202 / durable representation

DB-backed worker
  -> short claim tx (CAS + lease)
  -> compute outside write transaction
  -> short COMPLETE / FAILED / UNKNOWN tx

Frontend
  -> GET run / poll with bounded backoff
  -> refresh or re-login continues from DM8
```

现有同步 `/steps/{stepId}/execute` 在迁移期保留 compatibility，但它必须与 worker 使用同一 claim/attempt state machine；不能在 worker 已 claim 后旁路执行。同一环境切换到 async profile 后，前端不再逐 step 发 execute，只创建 run 并读取 durable state。

### 7.2 Claim 与 fencing

Claim candidate 必须是：current attempt、status PENDING、step 当前 attemptNo 相同、run 可执行、policy/input snapshot 未漂移且没有有效 lease。claim transaction：

1. 选择有限批次 candidate，不依赖 Redis queue；
2. 用 `attemptId + expected versionNo + expected status + expected lease state` CAS；
3. 写 `RUNNING`、随机 leaseOwner、claimedAt、leaseUntil、startedAt；
4. 同步 CAS Step/Run 到 RUNNING；
5. append technical/business audit distinction；
6. commit 后才开始 parser/rule/model computation。

`versionNo` 是 fencing token。heartbeat 以 `attemptId + leaseOwner + expected versionNo + RUNNING` CAS 延长 lease 并返回新 version。COMPLETE/FAILURE/UNKNOWN 必须携带 worker 最后观察的 version/owner，且 lease 未被另一个 worker接管；过期或 ownership 不符的 late worker 禁止写结果。

### 7.3 Crash 与 lease expiry

Recovery scanner 只处理到期 lease：

- `externalCallState=NOT_STARTED`：CAS 回 PENDING 或创建 recovery claim，允许同 attempt 重新执行；
- deterministic computation 已完成但 closure 未提交且无外部副作用：必须由 executor-specific recovery proof 决定能否重算；
- `externalCallState=REQUESTED` 且 provider outcome 无法证明：attempt/step 收口 UNKNOWN；
- `OUTCOME_UNKNOWN`：保持 UNKNOWN；
- `COMPLETED` 但 durable output 未能证明：UNKNOWN，而不是 FAILED/MISSING；
- lease 尚有效：scanner 不干预。

worker 进程退出不会丢任务，因为 PENDING/RUNNING/lease 均在 DM8。任何“定时器内存列表”“@Async future”“Redis list”都只能触发扫描，不能成为唯一队列。

### 7.4 Multi-attempt retry

Track B 才解除 Phase2B 的 attemptNo=1 限制。Retry 是新 command，不覆盖旧 attempt：

```text
Attempt 1 FAILED/UNKNOWN (immutable terminal)
  -> explicit retry command
  -> Attempt 2 PENDING
  -> Step.currentAttemptNo = 2
  -> Run resumes RUNNING/PENDING
```

Retry preconditions：

- caller 通过 AuthContext 和 role guard；
- path ownership、expected run/step/attempt/version 全部匹配；
- step 当前没有 PENDING/RUNNING attempt；
- FAILED 可按 executor policy retry；
- UNKNOWN 绝不自动 retry。

UNKNOWN 的显式 retry 还必须满足 capability-specific safety policy。对已经 `REQUESTED` 的模型请求，系统必须先记录“旧 attempt outcome 未证明、由授权用户显式 supersede”的 disposition；新 attempt 使用新 operation identity，旧 attempt 永久 UNKNOWN。若 provider 有可信 request-status/idempotency contract，则优先 reconcile 原 attempt；没有该合同就不能假设查询或重放安全。

Retry operation scope：`CONTRACT_REVIEW_STEP_RETRY`。fingerprint 包含 actor、project/contract/run/step、previousAttemptId/no/version/status/externalCallState、retry policy version、normalized reason。same key 重开同一 new attempt；changed command 409；outcome uncertain 时客户端不能换 key 偷偷重试。

### 7.5 Step 与 Run 聚合规则

- Step status 始终投影 currentAttemptNo 对应 attempt；历史 attempt 不被新状态覆盖。
- 任一 current attempt RUNNING -> Run RUNNING。
- 全部 required steps COMPLETED -> Run COMPLETED。
- 无 PENDING/RUNNING 且至少一 FAILED、其余可能完成 -> 按现有规则 FAILED 或 PARTIALLY_COMPLETED；不得由 Track B 私自重定义已有聚合契约。
- 任一 required current attempt UNKNOWN 且没有运行中 attempt -> Run UNKNOWN。
- retry 创建后，Run 从 FAILED/PARTIALLY_COMPLETED/UNKNOWN 恢复为 PENDING 或 RUNNING；这是显式 command 引起的 durable transition，不是后台静默回滚状态。

### 7.6 API 与前端迁移

建议 additive seam：

- `POST .../review-runs`：commit durable manifest，返回 202 或兼容 representation；
- `GET .../review-runs/{runId}`：持续作为 reopen truth；
- `POST .../steps/{stepId}/retries`：显式 append attempt；
- `GET .../steps/{stepId}`：返回全部 attempts 与 current output。

Phase2C workspace 保持唯一 UI，不新建第二个 dashboard。前端变为：create -> 保存 durable pointer -> bounded poll/reload -> 只按服务端状态展示；网络 timeout 不得伪装为 step failure。刷新/重新登录后通过服务器列表或已知 runId 恢复，sessionStorage 仍只可作为导航优化。

### 7.7 Schema 判断

当前 Attempt 已有 attemptNo、leaseOwner、leaseUntil、claimedAt、externalCallState、operationIdentity 和 versionNo，原则上足以实现 CAS/fencing。Track B 计划阶段必须先做 repository/DM8 query profile 验证；只有证明需要 claim index、next-claim time 或额外 recovery disposition 时，才在自己拥有的 CONTRACT migration 中 additive 变更。不得为“看起来方便”增加另一张 queue truth。

## 8. Track C — Sanitized Debug Bundle 与 Quality Harness

### 8.1 Debug Bundle 是授权 artifact

新增逻辑资源 `SanitizedDebugBundle`，状态建议为 `PENDING / GENERATING / AVAILABLE / FAILED / UNKNOWN`。它不是日志压缩包，也不是任意目录导出；生成必须绑定明确资源范围（project/contract/run/step/attempt）、bundle schema version、sanitization policy version、actor、request/trace、审批级别与 Operation Audit。

默认 `METADATA_ONLY` manifest 可包含：

- environment profile identifier、app version、migration namespace/status summary；
- run/step/attempt durable status、safeReasonCode、时间和 duration；
- file MIME、size、page count；
- parse route/method/version、parser/OCR engine/version/confidence；
- role/identity assessment metadata 与 decision identity，不含正文；
- model provider/model/revision、schema/prompt/policy version、chunk/retry counts；
- evidence locator 的 page/paragraph/table/row/cell/segment/offset/hash metadata，不含 excerpt；
- requestId、traceId、runId、stepId、attemptId；
- bounded sanitized error class/code；
- audit event references。

默认明确排除：

- original file、完整正文、raw OCR text；
- raw prompt、raw model response；
- secret、cookie、session id、token、API key、password、private key；
- PII、甲方敏感字段、无限长 exception message/stack dump；
- 未经策略批准的可跨系统关联 hash。

精确 SHA-256 虽是技术标识，仍可能成为客户材料关联符。站内 metadata bundle 可在 policy 允许时包含；对外导出必须由客户策略决定保留、盐化别名或删除。

只有 `CUSTOMER_POLICY_APPROVED_REDACTED` 级别可以包含 redacted excerpt、redacted crop、anonymous fixture 或 anonymous PDF。它要求已持久化 policy identity、授权人、审批范围与到期/撤销规则；缺任何一项就 fail closed。脱敏处理在 Java 控制的 pipeline 完成，产物经 FileStoragePort 发布并记录 SHA；原始内容不能作为 fallback。

### 8.2 Bundle manifest

manifest 顶层至少包含：

```text
bundleId
bundleSchemaVersion
sanitizationPolicyVersion
contentLevel
scope {projectId, contractId?, runId?, stepId?, attemptId?}
generatedBy / generatedAt
applicationBuild
entries[] {type, schemaVersion, safeMetadata, entrySha256}
artifact {storageKey, mediaType, size, sha256}
exclusions[]
verificationLabels[]
```

entries 稳定排序、canonical JSON 后计算 manifest hash。生成遵循 Track A 相同的 storage START/outside/COMPLETE/UNKNOWN 模式。下载也必须 authorization + audit；URL/路径不暴露 storage root。

### 8.3 独立 Quality Harness

fixture source 只允许：

- `SYNTHETIC`
- `PUBLIC`（保存来源与许可证明）
- `CUSTOMER_APPROVED_SANITIZED`（保存批准 identity，不把原始客户材料放入 Git）

每个 fixture set 都有不可变 version、input SHA、ground-truth schema/version、创建来源与适用 capability。生成 PDF/DOCX/image 可用于 parser/OCR integration；普通 domain test 优先使用 synthetic parsed-document fixtures。provider A/B 必须使用同一 fixture version、同一 metric implementation、同一 threshold profile。

指标：

| 能力 | 必须指标 |
|---|---|
| role | accuracy、macro-F1、confusion matrix |
| OCR | CER、WER、page coverage、empty-page rate |
| typed facts | exact match、normalized match、amount BigDecimal exactness |
| evidence | locator hit rate、unsupported rate、hash/parse binding validity |
| identity | precision、recall、conflict false-negative rate |
| semantic/breach/term | schema validity、unsupported claim rate、retry rate、precision/recall |
| performance | p50、p95、bounded failure/timeout count |

每次 evaluation 输出 versioned result manifest：code SHA、fixture/ground-truth version、provider/model/revision、policy/schema/prompt version、metric implementation version、threshold profile、raw aggregate counts 和 pass/fail。不得只存一个百分比或展示截图。

### 8.4 证明标签

质量和发布报告只能使用：

- `LOCAL_SYNTHETIC_VERIFIED`：本地/CI synthetic harness 有可执行证据；
- `REAL_PROVIDER_VERIFIED`：真实 provider 在指定环境、模型 revision 和 fixture 上有观测证据；
- `REAL_CUSTOMER_MATERIAL_VERIFIED`：客户现场授权材料按批准范围验证。

三个标签互不推导。synthetic 通过不能写成 real provider；provider 通过不能写成 customer material。未执行时明确写 `NOT VERIFIED`。

## 9. Track D — ReviewSession Authority Cleanup

### 9.1 新 Project-backed ReviewSession

新会话仍可复用 `CONTRACT_REVIEW_SESSION` 的材料准备生命周期，但材料关联必须是 `ProjectFile` reference。逻辑新增 `ReviewSessionProjectFileBinding`（实际表名在 Track D implementation plan 中冻结），至少保存：

| 字段 | 语义 |
|---|---|
| bindingId / sessionId | durable identity/owner |
| projectId / projectFileId | authoritative uploaded material |
| parseResultId / parseVersion | exact immutable parse |
| fileSha256 / canonicalTextSha256 | frozen binding proof |
| effectiveRole / roleDecisionId | binding 时 authority snapshot |
| identityDecisionId | project ownership proof |
| activeFlag | 当前会话材料选择；不删除历史 |
| boundBy / boundAt / versionNo | audit/CAS |

新会话创建时必须证明 Contract 属于 Project；绑定时必须证明 ProjectFile AVAILABLE、parse PARSED、file SHA/parse identity 匹配、effective role/identity 符合 readiness。它只写 reference，不复制 bytes、不调用 FileStoragePort.stage、不重新 parser/OCR。

### 9.2 Legacy compatibility

- `CONTRACT_REVIEW_DOCUMENT` 与 `CONTRACT_REVIEW_MATERIAL_BINDING` 历史数据继续可读、可下载、可用于既有 READY 会话。
- legacy 会话不得被后台偷偷“转换”为 ProjectFile；没有可信一一映射时保持 legacy source kind。
- 新 Project-backed 会话不得同时拥有 active ProjectFile binding 和 active ReviewDocument binding；source kind 在创建时冻结。
- legacy API 在兼容期维持现有语义；新 Project-backed API 对上传/替换 binary fail closed，并引导回 Project 文件上传入口。
- 不物理删除历史 migration、document、binding、storage object 或 audit。
- 不恢复公共 ReviewSession delete UI/API；V005 tombstone 只作已部署 schema/internal recovery compatibility。

### 9.3 与 ReviewRun 的关系

ReviewSession 是材料准备容器，ReviewRun 是一次冻结执行；二者不得合并。READY Project-backed ReviewSession 可作为创建 ReviewRun 的选择来源，但 ReviewRun 创建仍独立执行 current readiness 并把 ProjectFile/parse/decisions 复制为 immutable run material snapshot。之后 Session binding 改变不影响既有 Run。

## 10. Track E — Capability Framework 与 Provider Routing

Track E 严格分成 12A 和 12B；12B 的 production activation 依赖 Track C quality gate。

### 10.1 Track E/12A：Capability/Profile Framework

当前五个 step type 和当前 profile 不变：

```text
MATERIAL_VALIDATION
F02_DETERMINISTIC
SEMANTIC_AI
BREACH
TERM
```

引入描述性抽象：

- `ReviewCapabilityDescriptor`：capability code、中文显示名、execution kind、输入需求、output schema、policy version requirement；
- `ReviewProfileDescriptor`：profile version、按稳定顺序列出的 required/optional capabilities；
- `CustomerRuleCapabilityRegistry`：从受控配置读取批准的客户能力；
- `CapabilityAvailability`：`ENABLED / NOT_CONFIGURED / DISABLED_BY_POLICY`。

F09–F15 只注册 extension slot（code、中文名称、需要客户规则/政策版本、availability），不得写假业务条件、阈值、结论或执行器。`NOT_CONFIGURED` 不创建 ReviewStep、不影响现有 run completion、不显示为失败。只有收到已批准客户规则并形成 typed input/output schema、deterministic semantics、test ground truth、profile activation version 后，才可在后续任务中启用对应 step。

能力配置只能选择预编译、受测试的 adapter；禁止 arbitrary script、`eval`、动态 SQL、上传 customer code。

### 10.2 Track E/12B：Provider Capability Routing

定义 provider capability seam：

- `HANDWRITING_OCR` / OCR（当前 PaddleOCR adapter）；
- `TEXT_SEMANTIC` / `CONTRACT_SEMANTIC`（当前 Qwen-compatible route）；
- `DOCUMENT_ROLE`（未来 model fallback）；
- `DOCUMENT_VISION`（未来）；
- `STRUCTURED_EXTRACTION`（未来批准后）。

Java 选择业务 capability、input snapshot、policy/schema 并验证结果；FastAPI 根据 server config 把 capability 路由到 adapter。provider 名称、model、revision、schema/prompt/policy version、duration、request id 必须返回并最终写入 durable Attempt/Finding provenance。

路由配置需要显式状态：

- `CONFIGURED_NOT_EVALUATED`
- `EVALUATED_NOT_APPROVED`
- `APPROVED_FOR_PRODUCTION_ROUTE`
- `DISABLED_BY_POLICY`

只有在 Track C 同 fixture/同 metrics/同 threshold profile 上通过，且审批记录与具体 provider/model revision 绑定，才能进入 `APPROVED_FOR_PRODUCTION_ROUTE`。版本或 revision 改变使批准失效，回到未批准状态；不能用 provider 品牌替代 revision 证明。

DeepSeek、Qwen-VL 或其他 provider 只是未来 adapter/config，不成为当前业务依赖。GPU topology、vLLM、SGLang、Qianfan、特定主机/IP 不进入 domain contract。本 Track 不引入 RAG、vector database 或 general agent。

## 11. 统一状态机

### 11.1 ReviewRun / Step / Attempt

```text
Run:     PENDING -> RUNNING -> COMPLETED
                    |  |  \-> PARTIALLY_COMPLETED
                    |  \----> FAILED
                    \-------> UNKNOWN

Step:    PENDING -> RUNNING -> COMPLETED
                    |  \----> FAILED
                    \-------> UNKNOWN

Attempt: PENDING -> RUNNING -> COMPLETED
                    |  \----> FAILED
                    \-------> UNKNOWN
```

FAILED 表示系统能证明执行失败；UNKNOWN 表示请求或结果不能证明。UNKNOWN 不能由超时直接转换为 FAILED，也不能自动回 PENDING。显式 retry 是 `terminal attempt -> append next PENDING attempt`，不是修改旧 attempt。

### 11.2 Acknowledgement

```text
ABSENT -> ACKNOWLEDGED (append-only)
```

1.0 没有 UNACKNOWLEDGE、RESOLVED、DISMISSED 或多轮评论状态。后续若需要 disposition workflow，必须另立领域模型，不能复用 acknowledgement 冒充。

### 11.3 Report / Debug Bundle artifact

```text
PENDING -> GENERATING -> AVAILABLE
                      -> FAILED
                      -> UNKNOWN
```

AVAILABLE 是 terminal immutable version。重生成创建新 report/bundle identity。UNKNOWN 通过同 operation identity reconcile，不得用新 key 绕过。

### 11.4 ReviewSession material source

```text
LEGACY_REVIEW_DOCUMENT: PREPARING_MATERIALS -> READY
PROJECT_FILE_REFERENCE: PREPARING_MATERIALS -> READY
```

同一 Session 的 source kind 不混用；READY 后当前语义不接受材料变更。

## 12. 关键序列

### 12.1 ProjectFile 到 ReviewRun

```text
Client -> Java: POST ProjectFile (existing API)
Java -> DM8/FileStorage: durable upload + immutable parse/OCR metadata
Client -> Java: role/identity assessment and human resolution
Java -> DM8: durable decisions
Client -> Java: candidate confirmation/promotion
Java -> DM8: Contract
Client -> Java: POST ReviewRun(materialFileIds, expectedContractVersion)
Java -> DM8: readiness + frozen Run/Material/Step/Attempt manifest
Java -> Client: durable runId
```

### 12.2 Worker execution

```text
Worker -> DM8: claim candidate by CAS + lease
DM8 -> Worker: attempt identity + fencing version
Worker -> Java executor: load only frozen inputs
Java executor -> FastAPI/rule engine: bounded compute outside write tx
Java executor -> Java validator: schema/ownership/evidence grounding
Worker -> DM8: COMPLETE/FAILED/UNKNOWN using lease owner + version fence
Client -> Java: GET ReviewRun
Java -> DM8: reconstruct stable projection
```

### 12.3 Acknowledge and report

```text
Client -> Java: acknowledge exact finding/version + Idempotency-Key
Java -> DM8: short tx append ack + idempotency + audit
Client -> Java: generate report + Idempotency-Key
Java -> DM8: START report with frozen snapshot hashes
Java -> renderer/FileStorage: render and create-only publish outside tx
Java -> DM8: COMPLETE/FAILED/UNKNOWN report
Client -> Java: GET report / artifact
```

### 12.4 Debug bundle

```text
Authorized caller -> Java: request bundle(scope, contentLevel)
Java -> DM8: verify policy/approval, START + audit
Java -> DM8/FileStorage: collect allowlisted metadata, sanitize, publish
Java -> DM8: COMPLETE manifest/artifact identity or UNKNOWN
Authorized caller -> Java: audited reopen/download
```

## 13. 数据库主图与 Track Ownership

### 13.1 关系图

```text
PROJECT 1---* PROJECT_FILE 1---* PROJECT_FILE_PARSE_RESULT
   |                  |---* ROLE_ASSESSMENT ---* ROLE_EVIDENCE
   |                  |---* ROLE_DECISION
   |                  |---* IDENTITY_ASSESSMENT / IDENTITY_DECISION
   |                  \---* EXTRACTION_RESULT
   |
   |---* CONTRACT_CANDIDATE --- CONTRACT_CANDIDATE_CONFIRMATION
   |            |
   |            \--- PROJECT_CONTRACT --- CONTRACT
   |                                      |
   |                                      |---* CONTRACT_REVIEW_SESSION
   |                                      |       |---* legacy REVIEW_DOCUMENT
   |                                      |       |---* legacy MATERIAL_BINDING
   |                                      |       \---* new PROJECT_FILE_BINDING
   |                                      |
   |                                      \---* CONTRACT_REVIEW_RUN
   |                                              |---* RUN_MATERIAL
   |                                              |---* REVIEW_STEP
   |                                              |       \---* STEP_ATTEMPT
   |                                              |              |--- F02 AUDIT_TASK ---* AUDIT_CHECK_RESULT
   |                                              |              \---* REVIEW_FINDING ---* REVIEW_EVIDENCE
   |                                              |                         \--- 0..1 new FINDING_ACK
   |                                              \---* REVIEW_REPORT
   |
   \---* SANITIZED_DEBUG_BUNDLE

IDEMPOTENCY_RECORD and OPERATION_AUDIT reference commands/resources across all aggregates.
```

### 13.2 Descriptive migration ownership（本轮不生成 SQL）

| Track | 唯一 migration owner | 允许的未来 schema scope |
|---|---|---|
| A | `CONTRACT__review_ack_report.sql` | non-F02 ack、report metadata/index/FK |
| B | `CONTRACT__review_worker_retry.sql` | 仅经证明需要的 claim/retry/recovery additions/index |
| C | `CONTRACT__sanitized_debug_bundle.sql` | bundle metadata/authorization reference/artifact identity |
| D | `CONTRACT__review_session_project_file_binding.sql` | ProjectFile reference binding/source discriminator |
| E | 优先无 migration | 只有 durable config/approval 被证明必要时才拥有独立 CONTRACT file |

所有未来文件必须含：`MIGRATION-NAMESPACE: CONTRACT`、唯一 migration id、精确 dependency、`EXECUTION: CONTROLLED_MANUAL`、`IDEMPOTENCY: NOT_ASSUMED`。禁止新增 V023+、禁止修改 V001–V022、禁止改写已执行的 CONTRACT migration。

每个 migration 执行前必须输出唯一判定：

- `NOT_APPLIED`
- `ALREADY_MATCHING`
- `PARTIAL-SCHEMA_DRIFT`
- `UNKNOWN`

只有 `NOT_APPLIED` 才可按批准 runbook 执行；`ALREADY_MATCHING` 不重复执行；其余两种立即停止。各 Track 不得在同一 table/column 上并行出 SQL；修改既有 review-run/attempt mapper 的最终归属在 integration branch。

## 14. 跨 Track 文件所有权

| 范围 | Track owner | 说明 |
|---|---|---|
| non-F02 ack/report domain、application、repository、API extension、report UI | A | 不改 F02 ack truth |
| worker claim/lease/retry/recovery、attempt executor、async polling adapter | B | 复用现有 Step/Attempt，不建第二 queue |
| debug bundle、quality harness、fixtures、metric/reporting | C | fixtures 不含客户原文 |
| Project-backed ReviewSession binding 与 legacy compatibility adapter | D | 不复制文件、不重解析 |
| capability/profile registry | E/12A | 不改固定五步 enum |
| provider capability adapters/config/approval | E/12B | production route 受 C gate |

以下 shared files 由 integration branch 统一修改，Track 分支只记录所需 adaptation 或提供独立 extension point：

- `ContractReviewRunController` / service / projection DTO；
- `ContractReviewRun`、`ContractReviewStep`、`ContractReviewStepAttempt` 的公共状态聚合；
- OpenAPI/common API response；
- `ContractReviewWorkspace` 和公共 ReviewRun data source/types；
- existing DM8 review-run mappers/repositories；
- Spring Security/CSRF/role configuration；
- README/deployment/runbook 文档。

跨 Track 修改纪律：

1. Track 不为减少 merge conflict 而复制 shared domain；
2. Track 交付 `required integration adaptations` 清单和可执行测试；
3. integration 只做已批准契约的机械接线；若需语义变化，停止为 `CROSS_TRACK_CONTRACT_BLOCKER`；
4. 不允许两个 Track 各自新增同名 DTO/table/API；
5. 每个 commit 可单独回滚，不能夹带另一个 Track 功能。

## 15. 安全、身份与审计

所有新增 API 沿用 Spring Security、Session Cookie、CSRF、RequestId/TraceId 与现有 role guard。写 API 要求 Idempotency-Key，GET/reopen 不要求。通用规则：

- actor/userId 只来自 AuthContext；
- projectId/contractId/runId/fileId/findingId 的归属全部由 Java 从 DM8 证明；
- ownership mismatch 返回安全 NOT_FOUND，不泄漏资源实际归属；
- model/worker/debug request 中的 identity 只是 correlation，不是授权声明；
- ack/report/retry/bundle generation/download 都写长期 Operation Audit；
- business audit、flow/debug observability、technical error log 分离；
- 日志不写 full text、prompt、raw response、secret、cookie 或真实路径；
- 客户策略缺失时，敏感 debug content 默认拒绝。

最低角色仍是现有 `SYSTEM_ADMIN / PROCUREMENT_MANAGER / AUDITOR / LEGAL`，不在本设计中发明新角色。各命令精确角色矩阵属于 Track implementation plan 的安全审查项，但不得弱于当前 controller guard；debug redacted export 与 provider production approval 还需客户治理输入。

## 16. Idempotency、Transaction 与 Concurrency

### 16.1 新 operation scopes

- `CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE`
- `CONTRACT_REVIEW_REPORT_GENERATE`
- `CONTRACT_REVIEW_STEP_RETRY`
- `SANITIZED_DEBUG_BUNDLE_GENERATE`
- Project-backed ReviewSession binding 使用 Track D 独立 scope，不复用 legacy document upload scope。

统一语义：same key + same fingerprint replay；same key + different fingerprint 409；IN_PROGRESS 返回同 durable operation identity；UNKNOWN 必须继续使用原 key 做 reconcile。成功 replay 从 frozen result reconstruction，不重新检查后来变化的 current authority。

### 16.2 外部调用事务规则

任何 FileStorage/render/OCR/model/provider HTTP 都不得在持有 DM8 长写事务时运行。统一结构：

```text
START REQUIRES_NEW tx
  ownership + expected version + idempotency claim + durable PENDING/RUNNING + audit
COMMIT

external/local bounded compute

COMPLETE REQUIRES_NEW tx
  reload + CAS/fence + validate output + durable facts + idempotency/audit
COMMIT

or FAILURE/UNKNOWN REQUIRES_NEW tx
```

START 之前可做只读 validation；但 business decision 必须在 START tx 内重验。rollback-only tx 里不能尝试写 failure closure。

### 16.3 Concurrency adversaries

实施计划必须覆盖：两个 worker 同时 claim、lease 期间 late completion、heartbeat 与 scanner racing、两次 explicit retry、同 report version allocation race、ack concurrent duplicate、artifact 已 publish 而 DB timeout、ProjectFile/role/identity 在 frozen run 后漂移、provider policy version 在 attempt 中途改变。所有情况必须由 unique constraint + CAS + durable idempotency 收口，而不是 JVM lock。

## 17. 前端终态

沿用现有 Project/Contract workspace，不创建第二套审核中心。终态交互：

1. Project 页面逐份显示 parse、suggested role/confidence、role status、identity status。
2. 用户完成 role/identity/candidate confirmation 后进入 Contract。
3. 选择 eligible ProjectFile，创建 ReviewRun 后材料选择冻结。
4. UI 展示五个现有 step 的 PENDING/RUNNING/COMPLETED/FAILED/UNKNOWN；后台 worker 执行，页面 poll/reload。
5. 刷新或重新登录从 DM8 reopen；sessionStorage 仅作为最近 run pointer 优化，指针丢失不丢业务事实。
6. Finding 可下钻到 exact evidence locator；非 F02 显示 acknowledgement/handling note；F02 保持既有 acknowledgement UI/API。
7. 只有 completed run 启用正式报告生成；每个 report version 独立列出和下载。
8. UNKNOWN 显示“结果状态待确认”，只出现有权限的显式处理入口；不显示通用“重试成功”。
9. debug bundle 入口只对授权角色/政策出现；默认 metadata-only。
10. NOT_CONFIGURED capability 不伪装成 failed step；客户界面不展示 raw enum、UUID、hash、policy noise，技术详情放受控详情或 debug bundle。

## 18. 并行分支与工作树

书面规格批准后，所有 Track 必须从同一个批准的 **SPEC SHA** 创建，不能从另一 Track 分支起步：

| Track | branch | worktree |
|---|---|---|
| A | `feature/contract-closeout-ack-report-20260916` | `.worktrees/contract-closeout-ack-report` |
| B | `feature/contract-closeout-worker-recovery-20260916` | `.worktrees/contract-closeout-worker-recovery` |
| C | `feature/contract-closeout-debug-quality-20260916` | `.worktrees/contract-closeout-debug-quality` |
| D | `feature/contract-closeout-session-authority-20260916` | `.worktrees/contract-closeout-session-authority` |
| E | `feature/contract-closeout-capability-provider-20260916` | `.worktrees/contract-closeout-capability-provider` |
| Integration | `feature/contract-closeout-integration-20260916` | `.worktrees/contract-closeout-integration` |

Integration branch/worktree 也从同一 SPEC SHA 开始。任何 Track 不直接 merge main、不建 PR、不修改其他 worktree，除非后续人类明确授权。

并行条件：

- 本 spec 获得 written approval；
- 每条 Track implementation plan 获得 approval；
- migrations/files/shared APIs ownership 已签字冻结；
- 每条 Track 有独立 RED/GREEN/verification gate；
- E/12B 可以实现 seam，但 production activation 必须等待 C harness 与阈值批准。

## 19. 推荐集成顺序

推荐顺序：

1. Track D：先消除新材料双重 authority，减少后续 E2E 输入歧义；
2. Track C：先建立 diagnostics/quality 证明语言和 provider gate；
3. Track A：在稳定 durable results 上增加 ack/report；
4. Track B：把已稳定同步 transaction pattern迁移到 worker/retry；
5. Track E/12A：接入 capability/profile descriptor，不改变固定五步；
6. Track E/12B：接入 provider routing，并最后执行 production approval gate。

该顺序不是让 Track 串行开发；A–D 与 E/12A 在 ownership 冻结后可并行。它规定 integration branch 接纳和最终回归顺序。若依赖审查证明需要调整，只能在 integration plan 中说明证据与风险；不能让后集成 Track 重写先集成 Track 的 durable truth。

每次集成：

- rebase/merge 方式由人工决定，不 force push；
- 先合入 Track-owned isolated files，再由 integration owner 完成 shared adaptation；
- 运行该 Track focused gates 和 accumulated regression；
- 若 schema preflight、状态机、API ownership 或 snapshot identity 不一致，停止集成。

## 20. Implementation Track 验收矩阵

### Track A

- 非 F02 ack append-only、F02 ack 无变更；
- duplicate/concurrent ack 和 fingerprint conflict；
- completed-only official report gate；
- v2 不覆盖 v1；
- frozen snapshot，不读取 current file/contract；
- storage present/missing/unknown recovery；
- report reopen/download ownership。

### Track B

- two-worker claim 只有一个 winner；
- crash before external call、during call、after response/before closure；
- lease heartbeat/expiry/late worker fencing；
- FAILED explicit retry appends attempt；
- UNKNOWN 无 automatic replay；
- same retry key replay / changed fingerprint conflict；
- frontend create+poll+restart recovery；
- existing synchronous compatibility 不旁路 claim。

### Track C

- default bundle exclusion scans；
- malicious filename/error/provider payload 不泄漏；
- policy approval absent/expired/revoked fail closed；
- artifact UNKNOWN recovery；
- fixture provenance/ground-truth version；
- metric reproducibility 与 provider A/B identical input；
- verification labels 不被错误升级。

### Track D

- Project-backed session 只引用 ProjectFile/parse；
- no second FileStorage stage/promote、no reparse/OCR；
- mixed legacy/new binding rejected；
- READY/frozen run 不随 later binding/file authority drift 改写；
- legacy ReviewDocument reopen/download remains green；
- no public destructive delete。

### Track E

- fixed five-step profile regression；
- F09–F15 NOT_CONFIGURED 不创建 step、不影响 completion；
- unknown/unapproved provider cannot be production route；
- model revision change invalidates approval；
- Java ownership/schema/evidence validation cannot be bypassed；
- no RAG/vector/general-agent dependency。

## 21. 最终 E2E Gates

### 21.1 静态与构建

- backend focused Maven tests + `backend/.\mvnw.cmd clean verify`；
- frontend `npm ci`、适用 full tests、build；
- Python unit/integration suite for changed FastAPI seams；
- `git diff --check`；
- V001–V022 unchanged；
- already executed CONTRACT migrations unchanged；
- migration namespace/dependency/structure guards；
- secret/IP/customer-data scan；
- no fake F09–F15 rules/no second storage/no Redis business truth。

### 21.2 真实 DM8 Gate

在代表性、获准环境执行：

1. 每个 CONTRACT migration preflight；
2. controlled manual execution；
3. catalog/FK/index/check postflight；
4. Java repository round-trip；
5. concurrent CAS/lease/idempotency behavior；
6. rollback boundary rehearsal。

没有这些证据时只能写 `REAL DM8 NOT VERIFIED`。

### 21.3 Runtime Gate

验证 Java/FastAPI/frontend/Session-CSRF/FileStorage/OCR/model gateway 的真实组合；每项记录 environment、exact SHA/config version、command/request、observed result。健康检查不能替代业务 E2E。

### 21.4 Synthetic full-flow E2E

用 synthetic/public-safe fixtures 完成：

```text
Project -> one-by-one file upload -> parse/OCR -> role/identity resolution
-> candidate confirm/promote -> Contract -> ReviewRun
-> worker all enabled steps -> Finding/Evidence
-> ack -> report v1 -> reopen
-> new ack/report v2, v1 unchanged
-> metadata-only debug bundle -> quality result manifest
```

### 21.5 Failure E2E

必须注入：worker kill/restart、lease expiry、duplicate claim、provider timeout、invalid JSON、UNKNOWN external outcome、explicit retry、late completion、report storage UNKNOWN、bundle storage UNKNOWN、policy/model revision drift、DM8 concurrency conflict、browser refresh/re-login。

### 21.6 Customer-facing copy gate

普通页面不展示 raw enum、UUID、hash、schema/policy internals或 stack trace；UNKNOWN 与 incomplete report 使用清晰中文；workspace、材料选择和 evidence focus 在 refresh/poll 后保持可恢复。技术标识仅出现在授权详情或 bundle。

## 22. Release Claim Policy

在对应证据产生前，release note 必须明确：

- `REAL DM8 NOT VERIFIED`
- `REAL OCR NOT VERIFIED`
- `REAL MODEL PROVIDER NOT VERIFIED`
- `REAL CUSTOMER MATERIAL NOT VERIFIED`

synthetic E2E 后可写 `LOCAL/TEST SYNTHETIC E2E VERIFIED`；真实 provider 按固定 fixture/threshold 观测后才写 `REAL PROVIDER VERIFIED`；客户现场按授权材料验证后才写 `REAL CUSTOMER MATERIAL VERIFIED`。前一标签不能推导后一标签。

## 23. Customer Input Required

以下输入缺失不阻止本 spec，但阻止相应 production activation：

- F09–F15 每项的正式规则、法律/制度来源、阈值、输入材料、输出语义、blocking policy、版本与批准人；
- 哪些 finding 必须 acknowledgement、handlingNote 是否必填、谁有权确认；
- 正式报告模板、签章/编号/保留期限、PDF 要求、客户可见字段、是否允许 incomplete export；
- debug bundle 的审批角色、exact hash/locator metadata 对外边界、redaction standard、审批有效期和传输方式；
- quality threshold、可接受 false positive/false negative、PUBLIC/客户脱敏 fixture 的授权与保留期；
- UNKNOWN 模型 attempt 的人工 supersede 权限与 provider 成本/副作用接受政策；
- background worker 的期望吞吐、最大并发、lease/heartbeat/timeout 运维参数；
- legacy ReviewSession 保留期和最终退役窗口；
- provider/model revision 的生产批准人、回退顺序和降级政策。

上述事项不得用 demo 文件、开发者猜测或模型建议代替客户批准。

## 24. 风险与控制

| 风险 | 控制 |
|---|---|
| 并行 Track 重定义共同状态机 | Common Model Freeze + shared-file integration ownership + blocker protocol |
| worker 重复执行外部模型 | durable externalCallState、lease fencing、UNKNOWN fail closed、explicit supersede |
| late worker 覆盖新 attempt | owner/version/lease/currentAttemptNo 全链 CAS |
| report 看见 current 而非 frozen data | snapshot identities + exact run/attempt output reconstruction |
| artifact bytes 成功但 DB timeout | deterministic storageKey/expected hash + inspect recovery；UNKNOWN 不当 missing |
| 两套材料继续扩张 | new session reference-only；legacy source kind frozen；no mixed binding |
| debug bundle 泄密 | allowlist manifest、default metadata-only、policy approval、audited download |
| provider 路由被配置即上线 | quality threshold + revision-bound production approval |
| F09–F15 变成假业务 | NOT_CONFIGURED slots，不创建 step，不写规则 |
| 前端制造 runtime truth | DM8 GET projection 为真相，sessionStorage 只作 pointer |
| migration 并行冲突 | table/file owner + preflight state machine + integration-owned existing mapper edits |
| 证明口径膨胀 | 固定 VERIFIED labels 和 NOT VERIFIED 默认值 |

## 25. Explicit Non-goals

本设计不授权：

- 当前轮 implementation、migration、API/code/test/deploy；
- 猜测或交付 F09–F15 业务规则；
- 自动 AI 法律结论、金额决策或项目归属决定；
- RAG、vector database、general agent；
- 从 arbitrary inbox file 自动创建 Project；
- GPU infrastructure、vLLM/SGLang/Qianfan 或特定 topology 部署；
- 把 Redis/TongRDS 当 worker queue 或业务事实；
- 新建第二套 upload/filesystem；
- 删除/重写 legacy ReviewDocument migration/history；
- 把客户真实合同或敏感材料复制进 Git；
- 对未执行的 real DM8/OCR/provider/customer material 宣称 VERIFIED。

## 26. Written Spec Approval Gate

本文件提交并 push 后必须停止。只有 human 明确批准这份 written spec，才允许：

1. 冻结 SPEC SHA；
2. 为 A–E 各自编写 implementation plan；
3. 创建五个 implementation worktree 与 integration worktree；
4. 按 Track TDD 开始实现。

任何“聊天中已批准方案 B”只授权本次规格物化，不等同于批准实现。

## 27. Self-review Checklist

- [x] 没有要求 batch upload；ProjectFile 仍可逐份上传、独立恢复。
- [x] 没有让 AI/OCR 直接创建 authoritative business fact。
- [x] 没有第二套 filesystem；artifact 复用 FileStoragePort。
- [x] 没有把 Redis/TongRDS 作为业务真相或 worker queue。
- [x] 没有在 DM8 长写事务中执行 model/OCR/render/FileStorage。
- [x] ProjectFile/ReviewDocument 双重体系有明确 authority 与渐进兼容方案。
- [x] F02 AuditTask/AuditCheckResult 与现有 acknowledgement 未被新表替代。
- [x] ReviewRun frozen snapshot 与 report snapshot 无 current-state 歧义。
- [x] UNKNOWN 与 FAILED/MISSING/PENDING 不混淆，且无 automatic retry。
- [x] durable run/attempt/finding/ack/report/bundle 都有 reopen path。
- [x] evidence 继续绑定 exact file SHA、parse result/version 与 attempt。
- [x] operation scopes、fingerprint、concurrency、recovery 均已定义。
- [x] Track ownership、migration ownership、integration ownership 无重叠真相。
- [x] F09–F15 仅是 NOT_CONFIGURED extension slots，无假规则。
- [x] provider production route 被 quality harness/approval gate 阻断。
- [x] 未把 synthetic/provider 验证外推为 customer verification。
- [x] 本轮只允许本 spec 文件，不授权 implementation。
