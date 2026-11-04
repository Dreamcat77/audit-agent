# 当前系统架构

本文以 `integration/pre-main-20261010` 的合同/A-mini 链路与
`fix/budget-procurement-review-20261012` 的预算采购链路受控合并结果为当前源码基线。
历史提交和 evidence 只证明各自记录时的环境，不替代当前 HEAD 的可执行验证。
源码、migration 和可执行验证高于历史计划。未来路线见 [roadmap](roadmap.md)。

## 1. 总体链路

```text
Browser / React 18 / Vite 6
  -> same-origin /api
  -> Nginx（部署层；业务代码不感知）
  -> Java 17 / Spring Boot modular monolith
       -> DM8（System of Record）
       -> Redis / Spring Session（会话、缓存；非业务事实源）
       -> controlled filesystem（内容寻址文件存储）
       -> FastAPI（独立 Python 3.11 服务）
            -> PaddleOCR
            -> OpenAI-compatible model provider
```

Nginx/TongHttpServer 属于 HTTP 部署层；Redis/TongRDS 通过缓存和 Session 边界隔离；
TongWeb 介质与版本未到位前采用 JAR-first。当前代码和文档不能据此宣称任何目标国产组件
已完成兼容认证。

Java 内部统一依赖方向：

```text
API -> Application -> Domain
          |
          v
    Application Port <- Infrastructure Adapter
```

Domain 不依赖 Web、DM8、Redis、文件系统或模型实现。Python 原生依赖只存在于独立
FastAPI 服务，不嵌入 Java 进程。

## 2. 模块地图

| 模块 | 当前职责 |
|---|---|
| `common` | 公共响应、异常、Request ID、金额/校验基础能力、审计日志边界 |
| `security` | Session 登录、CSRF、当前用户、角色、登录审计和安全配置 |
| `review` | `AuditTask`、`AuditCheckResult`、`FindingAcknowledgement` 等公共审查事实 |
| `contract` | Project Intake、Contract、ReviewSession、F02、合同文本/条款审查 |
| `budget` | F01 预算审批/材料审查、后台材料任务和 F04 预算审批程序；当前 `ProcurementDemand` 也在此 |
| `blacklist` | F03 黑名单主数据、规则、告警和操作记录 |
| `procurement` | 采购项目、材料批处理/关联、报价校验、合规审查与人工确认纵向切片 |
| `integration` | DM8、Redis、文件、AI/OCR HTTP 等 Adapter |

## 3. 业务身份与持久化模型

`Project != ProjectFile != ContractCandidate != Contract != ReviewRun != AuditTask`。
`ContractReviewSession` 是独立的材料准备流程，不替代 ReviewRun。

| 类型 | 语义 | 当前 durable 内容 |
|---|---|---|
| `Project` | 项目材料入口与上游聚合 | 项目名、项目号、类别、创建人、版本 |
| `ProjectFile` | 一份上传/解包文件 | 所属项目、路径、类别、SHA-256、存储键、解析状态/文本/provenance |
| `ProjectFileParseResult` | 某次解析的不可变输出 | file/parse version、文本、locator、解析 provenance |
| `RoleAssessment` / `RoleDecision` | 材料角色建议与人工权威决定 | 候选角色、证据、来源、版本与决定历史 |
| `IdentityAssessment` / `IdentityDecision` | 项目归属评估与人工决定 | 冲突证据、归属状态、版本与决定历史 |
| `ExtractionResult` | 从材料提取的 typed fact | 字段、文本/金额值、来源文件/候选、版本、page/paragraph/locator |
| `ContractCandidate` | 尚待人工确认的合同候选 | 合同字段、金额、明细、来源合同文件、可选 Candidate 专属中标依据、DRAFT/PROMOTED |
| `ContractCandidateConfirmation` | 人工确认的正式业务事实输入 | 不可变 payload、来源、fingerprint、版本 |
| `ProjectContract` | 项目与正式合同关联 | projectId、contractId、candidateId |
| `Contract` | F02 的合同结构化事实 | 合同主体、金额、行项目、中标依据和版本 |
| `ContractReviewRun` | 一次可重开的综合审查 | 不可变 Contract 快照与选中材料 manifest、step/attempt、状态、结果关联 |
| `ContractReviewSession` | 某合同的材料准备流程 | 合同版本快照、名称、PREPARING_MATERIALS/READY |
| `ReviewDocument` | Session 下的 append-only 文档元数据 | storage key、摘要、状态、替换关系 |
| `MaterialBinding` | Session 当前材料槽位指针 | 文档类型到当前 ReviewDocument 的绑定 |
| `AuditTask` | 一次确定性规则执行 | source type/id/version、review type、状态、request/trace、版本 |
| `AuditCheckResult` | 一条规则结果 | taskId、规则、风险、证据等 |
| `ContractReviewFinding` / `ContractReviewEvidence` | 非 F02 审查结果 | semantic、breach、term 的 durable finding/evidence |
| `ContractReviewReport` | 审查报告 | 版本化 metadata 与不可变 artifact 路径 |
| `FindingAcknowledgement` | 对具体 finding 的持久确认 | finding/task/user/time/request/trace/version/idempotency |
| `IdempotencyRecord` | F02 写命令 durable claim | scope/key/fingerprint/task/result/status |
| `BusinessMaterialIntake` | 采购材料上传与后台解析任务 | actor/operation/SHA/storage key、队列/租约、解析结果与 UNKNOWN 状态 |
| `BudgetMaterialJob` | 预算材料后台处理任务 | 真实 BudgetReviewTask ID、actor/operation/SHA、队列/租约与终态 |
| `ProcurementDemandMaterial` | 采购需求与多份材料的关联 | demand/material、顺序、主材料标记；兼容旧单材料读路径 |

`ReviewSession.name`、合同编号、中标通知书编号都不能冒充项目字段。ReviewSession 当前只支持
`PREPARING_MATERIALS -> READY`，没有公共 Session 删除 API；V005 tombstone 仅用于既有 schema、
历史过滤与内部兼容。

## 4. Project Intake 与文件解析

### 4.1 当前数据流

```text
Project Intake
  -> ProjectFile
  -> safe staged upload
  -> SHA-256 + content-addressed controlled storage
  -> PDFBox / POI / OCR -> immutable ParseResult + typed ExtractionResult
  -> deterministic role assessment -> bounded AI role fallback（需要时）
  -> human RoleDecision -> project identity assessment -> human IdentityDecision
  -> ContractCandidate materialization
  -> optional Candidate-specific AWARD_NOTICE support authority
  -> human Contract Confirmation -> Promotion -> Contract + ProjectContract
  -> durable ReviewRun -> frozen F02 / semantic / breach / term
```

上传、文件声明、幂等 claim 和文件 promotion 采用短事务/补偿与 reconciliation 设计。ZIP 是
Project Intake Package，不是 ReviewDocument 的 `OTHER`。归档处理会检查路径穿越、绝对路径、
重复规范路径、符号/非普通文件、加密项、CRC、深度、条目数、单项/总解压大小、压缩比和文件名长度。

### 4.2 允许上传不等于能解析

| 位置 | 允许类型 | 当前 parser capability |
|---|---|---|
| 根上传 | ZIP、PDF、DOCX、PNG、JPG/JPEG | PDF、DOCX、PNG、JPG/JPEG；ZIP 只负责安全解包 |
| ZIP 子项 allowlist | PDF、DOCX、DOC、XLS、XLSX、PNG、JPG/JPEG、TXT、ZIP | 仍只有 PDF、DOCX、PNG、JPG/JPEG |

因此 DOC、XLS/XLSX、TXT 或嵌套 ZIP 通过 archive allowlist 不表示会被解析；它们会保持可解释的
unsupported/未解析状态。文件名/路径分类是安全入口；解析后的正文、结构和 typed facts 进入
确定性角色评估，必要时使用有界 AI role fallback，再由人工 RoleDecision 确认。
项目归属也有 assessment/人工 IdentityDecision；混合项目或不明归属不能被自动确认为同一项目。

当前 `ProjectFactExtractor` 识别“中标金额/中标供应商”，P1-B 真实材料使用“成交金额/成交供应商”。
因此当时非空 AWARD_* 权威测试使用明确标记的派生合成 fixture；真实模板词汇兼容仍未完成。

### 4.3 OCR 决策

```text
DOCX -> Apache POI -> parsed text

PDF -> PDFBox page text
    -> PdfTextUsefulness
       -> useful: PDF_TEXT
       -> unusable/scanned: OCR

PNG/JPG/JPEG -> OCR

Java ProjectFileIngestionService
  -> OcrPort
  -> FastApiOcrAdapter
  -> POST /ai/v1/ocr/extract
  -> FastAPI OcrApplicationService
  -> PaddleOcrAdapter / PaddleOCR
```

PDFBox 保存逐页 locator；POI 保存段落/表格单元 locator。`PdfTextUsefulness` 要求最低有效字符数与
可见有效字符比例，失败才回退 OCR。Python OCR 只接受 PDF/PNG/JPEG，以受限临时目录处理，
在线程池调用串行保护的 PaddleOCR，完成后清理临时文件。

`ProjectFile` 当前持久化：parsed text、parse method、engine、confidence、page count、duration、
safe reason code，以及解析状态。`confidence` 是解析/OCR 元数据，不是材料角色或业务结论置信度。

## 5. AI 与合同智能审查

### 5.1 当前语义链路

```text
durable ReviewRun selected-material manifest + frozen Contract snapshot
  -> FrozenReviewInputLoader
     (exact parse identity/text hash + Contract snapshot verification, fail closed)
  -> ContractAiTextRedactor
  -> ContractTextAnalysisPort
  -> FastApiAiReviewAdapter
  -> POST /ai/v1/agent/contract-text-analysis
  -> FastAPI evidence catalog
  -> bounded chunks
  -> OpenAI-compatible model
  -> structured response validation
  -> limited correction/retry
  -> server-owned evidence reconstruction
  -> Java exact/unique locator validation
  -> durable finding/evidence -> frontend reopen
```

独立同步语义入口仍经 `SelectedProjectMaterialReadService` 验证项目归属、解析状态和选中材料；
ReviewRun 执行以冻结输入为准，不追逐当前可变的 ProjectFile/Contract 读投影。

Java 在外呼前移除明确的金额字段和金额表格列；真实金额、行项目算术、中标金额和供应商一致性
继续由 Java `BigDecimal`/structured facts 处理。模型不能覆盖确定性结果。

Python 从经脱敏文本构建请求级 evidence catalog，再做有上限的 chunking、模型调用和结构化解析。
它过滤强断言和金额结论，校验 category/evidence，限制 transport/semantic correction 次数，并以
server-owned evidence catalog 重建公开 evidence；不会直接信任模型生成的引用文本。Java 再验证
evidence excerpt 能在原始选中材料中唯一规范化匹配，才标记 locator 可用。

AI 的角色是 `NON_AUTHORITATIVE SEMANTIC ASSISTANCE`。当前环境契约是 OpenAI-compatible provider；
已知当前 `TEXT_SEMANTIC` 使用 Qwen，不能据此把其他模型或 GPU 拓扑写成当前验收事实。

### 5.2 违约与期限/解除

`BreachClauseReviewUseCase` 与 `ContractTermClauseReviewUseCase` 读取正式 Contract、Project 关联和
经验证的 ProjectFile parsed text。违约比例/金额使用 `BigDecimal`，日期/生效/解除条款由规则提取
和比较。当前 ReviewRun executor 会把两类 finding/evidence 持久化到 DM8；独立同步 API 仍存在，
其存在不代表客户特定阈值或条款语义已验收。

### 5.3 当前 ReviewRun durability

DM8 已有 durable `ContractReviewRun`、不可变 Contract snapshot 和 selected-material manifest，
以及 step/attempt 状态。semantic、breach、term finding/evidence 均持久化；F02 step 指向真实
`AuditTask` / `AuditCheckResult`。精确 run ID 与 `/latest` 都可重开，已在集成环境验证刷新和后端
重启后的读取。server worker 执行独立于浏览器会话；`UNKNOWN` 与 `FAILED`/`MISSING` 分开处理。
本 hardening 分支的历史任务列表由已持久化 step 输出和 F02 幂等记录证明其精确
ReviewRun 归属；缺失或歧义时不回退 `/latest`，历史展示从冻结 Contract/材料读取。

非 F02 finding 的持久 acknowledgement、版本化报告 metadata/artifact 路径和仅含脱敏 metadata 的
debug bundle 已实现。报告 artifact 存在不等于客户签发或客户环境验收。
新生成报告的内部快照 schema v2 展示已冻结 finding 状态、来源类型和证据摘录；
旧 schema v1 的生成中报告继续按原模板渲染，避免既有 artifact SHA 失配。

P1-B 的 Candidate 权威模式为 `PRIMARY_ONLY_COMPAT` 和 `EXPLICIT_SUPPORT_AUTHORITY`。
显式 support 只接纳 Candidate 专属、已确认的 `AWARD_NOTICE`，只贡献 `AWARD_AMOUNT`、
`AWARD_SUPPLIER`、`AWARD_SUPPLIER_CREDIT_CODE`。同一字段零个 distinct 值为 missing、一个为
resolved、多个为 conflict/null，不自动挑选赢家。Human Confirmation 最终决定正式 Contract 事实；
Project 材料提供 evidence/provenance，F02 不回读 Candidate 来覆盖 frozen Contract。

## 6. F02 确定性审查

五条稳定规则：

1. `F02_AMOUNT_WORDS_MATCH`：合同金额大小写一致；
2. `F02_LINE_ITEM_ARITHMETIC`：数量 × 单价与行金额一致；
3. `F02_LINE_SUM_MATCH`：明细合计与合同金额一致；
4. `F02_AWARD_AMOUNT_MATCH`：合同金额与中标金额一致；
5. `F02_AWARD_SUPPLIER_MATCH`：合同乙方与中标供应商一致。

所有人民币金额使用 `BigDecimal` / `Money`，零容差相等用数值比较而不是 scale-sensitive
`BigDecimal.equals()`。F02 先以短 START 事务提交 DM8 idempotency claim、`AuditTask(PROCESSING)`
和 START 审计，在事务外运行规则，再以独立 COMPLETE 事务持久化结果；普通失败由
`REQUIRES_NEW` FAILURE 事务收口。Redis 不参与 durable idempotency。

## 7. 数据、文件、缓存与恢复

- DM8：任务、状态、检查结果、证据、规则版本、合同、项目、材料元数据、抽取、候选、确认、
  认证与长期审计的 System of Record。
- controlled filesystem：文件二进制内容；DM8 保存 storage key、SHA-256 和可恢复状态。持久资源
  必须有 refresh/restart 后的 rediscovery/reopen 路径。
- Redis/TongRDS：cache-aside、可丢失短期锁/键和非 `local` Spring Session。写操作先提交 DM8，
  再失效或更新缓存。
- UNKNOWN 不等于 FAILED 或 MISSING；不能证明 durable/storage 状态时必须 fail closed。

## 8. Security、CSRF 与可观测性

当前认证是服务器端 Session Cookie：

- 前端同源请求使用 `credentials: include`；
- 写请求先访问 `/api/v1/auth/csrf`，再发送 `X-CSRF-TOKEN`；
- Spring Security 使用 `IF_REQUIRED` Session 和 session-fixation ID change；
- credentialed CORS 必须使用精确 origin，不允许通配；
- `local` 使用 Servlet 内存 Session；`local-dev`、`dev29`、`customer` 使用 Redis Spring Session；
- Redis Session 丢失最多使用户重新登录，不能丢失 DM8 业务/审计事实。

可观测性必须区分：durable business audit、flow/debug observability、technical error log。
Request ID 在 HTTP、Java→FastAPI 和安全诊断字段间传播；日志不能包含正文、prompt、模型 raw
response、密码、cookie、Token、私钥或完整敏感连接信息。

A-mini 关键链路在默认 `INFO` 下输出 allowlist 限定的诊断事件：`PROGRESSION_PRECHECK`、
`AUTHORITY_CHECK`、`FACT_SEGMENTATION`、`AI_HTTP_STARTED`、`AI_HTTP_COMPLETED`、
`FACT_RESULT_VALIDATION`、`DM8_COMPLETION` 和 `RECOVERY_TERMINAL`。事件携带 schema version、
安全 reason code、真实 request/trace/project/file/attempt 标识（存在时）以及幂等键摘要；不伪造缺失
ID，也不记录幂等键原值。Flow Debug 仍只补充可选中间轨迹，不能作为关键失败原因的唯一来源。
诊断事件只陈述可证明的阶段状态：`NOT_CALLED`、`REQUEST_STARTED`、`HTTP_RESPONSE_RECEIVED`、
`TIMEOUT`、`CONNECTION_FAILED`、`RESULT_INVALID` 等不能互相推断，`UNKNOWN` 也不能降格为
`FAILED` 或 `MISSING`。技术日志和 metadata-only debug bundle 都不是 DM8 业务事实源。

## 9. 异步状态审计

当前 production code 已有 DM8-backed ReviewRun server worker，以及预算/采购材料的
claim/lease/heartbeat 与恢复扫描。待执行状态、所有权和结果保存在 DM8；进程内线程池只承载
有界计算，不是事实源。预算和采购材料 worker 默认关闭，分别只在
`AUDIT_BUDGET_MATERIAL_WORKER_ENABLED=true`、
`AUDIT_PROCUREMENT_MATERIAL_WORKER_ENABLED=true` 时启用；必须先完成 V047-V051 的逐项
Catalog 预检、批准迁移和验证，禁止用启用 worker 代替 schema migration。
外呼结果不明时 `UNKNOWN` 保持独立状态，不能改写成 `FAILED` 或 `MISSING`。
当前没有引入 `TaskQueuePort`、Redis Stream/PubSub/TongRDS MQ；也不能据此宣称目标队列产品已验证。

## 10. 功能状态矩阵

业务实现与验证层级分别记录：`IMPLEMENTED`、`VERIFIED_LOCAL`、`VERIFIED_INTEGRATION`、
`NOT_VERIFIED_CUSTOMER`。部分能力仍用 `PARTIAL` / `BLOCKED_BY_CUSTOMER_INPUT` 描述范围。

| 能力 | 状态 | 当前依据与限制 |
|---|---|---|
| F01 | IMPLEMENTED | API/Application、批量材料后台任务与历史结果导航；V051/字段宽度必须按环境另行迁移，客户 schema 另标 NOT_VERIFIED |
| F02 | IMPLEMENTED | 五规则、durable task/result/idempotency/acknowledgement |
| F03 | IMPLEMENTED | 黑名单主数据/规则/告警/操作日志及 V018/V019 |
| F04 | PARTIAL | demo rule set、校验与 persistence 存在；正式权限阈值待甲方输入 |
| Procurement review | IMPLEMENTED | 多材料上传/恢复、需求关联、报价与合规审查；V047-V050 未经环境预检不得启用后台 worker |
| Project Intake | IMPLEMENTED | 安全存储/ZIP、不可变 ParseResult、角色和身份评估/决定、解析与恢复路径 |
| OCR | IMPLEMENTED | PDF fallback 与图片 OCR 全链路；当前客户外部环境不在本次复验 |
| Contract Candidate | IMPLEMENTED | typed extraction、可选 Candidate 专属中标依据、冲突/null 与 provenance |
| Contract Confirmation / Promotion | IMPLEMENTED | 人工确认、ProjectContract、正式 Contract；Confirmation 是最终业务事实权威 |
| ReviewSession | IMPLEMENTED | 仅材料准备到 READY；不包含 integrated review run |
| ReviewRun / server worker | IMPLEMENTED | 不可变 Contract/材料快照、step/attempt/result、claim/lease、精确与 `/latest` 重开 |
| F02 deterministic review | IMPLEMENTED | 五条规则；ReviewRun step 引用 durable AuditTask/AuditCheckResult |
| Finding acknowledgement | IMPLEMENTED | F02 及非 F02 finding 的 durable acknowledgement |
| Contract Semantic AI | IMPLEMENTED | 非权威语义 finding/evidence 随 ReviewRun 持久化 |
| Breach review | IMPLEMENTED | Java 规则结果随 ReviewRun 持久化；客户阈值另验 |
| Term/termination review | IMPLEMENTED | Java 规则结果随 ReviewRun 持久化；客户语义另验 |
| Contract structure review | PARTIAL | AI 提供非权威语义提示，未形成 durable 确定性结构规则 |
| Qualification | NOT_IMPLEMENTED | 无正式外部资格数据源与规则 |
| Attachment completeness | PARTIAL | UI 可选择/提示材料，后端无正式完整性规则 |
| Effective condition / copy count | PARTIAL | term 分析覆盖部分生效语义；份数规则不存在且结果不持久 |
| Cross-document review | PARTIAL | 现有语义/条款结果持久化；尚无完整 typed F15 matrix |
| Final report | IMPLEMENTED | 版本化 metadata/不可变 artifact 路径；客户签发验收未验证 |
| Sanitized debug bundle | IMPLEMENTED | metadata-only bundle，不包含原文、prompt 或模型 raw response |
| Public HTTPS reachability | NOT_VERIFIED | 当前已知事实为 NOT_WORKING / NOT_ACCEPTED |

P1-B/P1-C 在受控集成 DM8 的结果标 `VERIFIED_INTEGRATION`；客户 schema、供应商平台与公网
验收仍标 `NOT_VERIFIED_CUSTOMER`。证据分别见
[Candidate authority](evidence/candidate-cross-material-authority-verification-20260920.md) 和
[F02 completeness](evidence/phase1c-f02-structured-data-completeness-20260921.md)。

## 11. Optional future AI capability routing

以下仅是 `FUTURE / OPTIONAL ARCHITECTURE`，不是当前甲方验收要求：

```text
AiCapabilityPort / provider capability routing
  TEXT_SEMANTIC          -> current Qwen
  DOCUMENT_VISION        -> optional after fixture evidence
  HANDWRITING_OCR        -> optional after fixture evidence
  STRUCTURED_EXTRACTION  -> optional after fixture evidence

OCR                      -> current PaddleOCR
```

只有统一 fixture 和验收指标证明当前能力不足时，才评估视觉模型、手写 OCR、MinerU、其他 reasoning
provider、vLLM/SGLang 或多 GPU 调度。特定 GPU 数量/型号、DeepSeek/Qwen-VL 部署、云服务和 scheduler
都不进入当前 P0，也不由应用仓库替甲方 AI 基础设施作未验证承诺。

## 11.1 合同文件异步处理与业务意见版本

新增 `POST /api/v1/projects/{projectId}/file-jobs` 只接收并发布已校验原件，以短事务提交
上传操作、文件和 DM8 Job 后返回 202。旧 `/files` 同步契约保留。复用原 FileStorage、
安全 archive reader 和分类；ZIP 父任务仅安全展开，各子文件有独立 Job，不建立第二套存储。
DM8 `PROJECT_FILE_PROCESSING_JOB/ATTEMPT` 保存 claim、lease、heartbeat、原始 actor、文件 SHA
与 ParseResult 身份。Redis 不是队列，也不是事实源。GET 可重新发现原任务；显式重试仅允许
已证明 FAILED 且没有已发布解析的任务，保留 file/job/operation 身份并写长期业务审计。
UNKNOWN 不自动重试；失效 actor 不冒用管理员身份，任务收口为 UNKNOWN 并退出队首。

文件 Worker 与合同 ReviewRun Worker 分别 opt-in；开启前必须检查受控迁移和实际约束。
OCR 仍为 Java/Python 双重单容量保护；忙时文件 Job 保留 QUEUED。原生 PDF 每页读取，
扫描页独立 OCR，真正空白页保留页序，覆盖不足/外部结果未知不得伪装整份成功。
DOCX 段落和表格保持原正文交错顺序。完成输出用租约锁和短事务 CAS 发布，旧 owner 不得
覆盖新 attempt；解析结果与已完成 Run/报告保持不可变。

A-mini 单请求仍为 32×240 Unicode 码点；工程总上限为 30,720 码点、256 个物理行片段、
每 scope 最多 8 批，共享原总墙钟预算。该上限不是客户业务规则。服务端物理行身份、
批次审计序号和精确证据约束跨批不变；已知拒绝与 OUTCOME_UNKNOWN 分开处理。

八项意见由确定性 `BusinessReviewProjection` v1 产生，页面、复制意见与新报告快照 schema3
共享同一投影，不新增自由模型总结。未覆盖的资格、签章、份数等检查不得标为正常；旧
schema1/2 报告仍按原 renderer 读取，不重写旧制品。所有人工权威门禁继续有效。

## 12. 相关 current 文档

- [源码与环境基线](source-baseline.md)
- [兼容性矩阵](compatibility-matrix.md)
- [路线图](roadmap.md)
- [材料测试与脱敏调试策略](material-test-and-debug-strategy.md)
- [AI/OCR 质量策略](ai-ocr-quality-strategy.md)
- [当前客户部署运行手册](runbook/customer-deployment-current.md)
- [DM8 schema map](../db/dm8/README.md)
