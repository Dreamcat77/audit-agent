# 合同审核工具化统一收口增量设计

**基线：** `fix/p1abc-premerge-hardening-20260922 @ e320cd072b3be66fd38d9e566c71cad53a0d1b3f`

**任务依据：** `C:\Users\17574\Downloads\合同审核_统一收口设计与Codex执行计划_20260923.md`

**状态：** 2026-09-23 已按书面审查批准 C02/C03、八项投影、F02 定位证据和受控集成环境操作；本版纳入批准时附带的协议修订。C01/C04 已按 RED/GREEN 修复。本批准不包含 DDL、A-mini/B/C 结构实现、客户材料外发或更换 provider。

## 1. 目标与边界

本增量将已有 Project、Candidate、Contract、ReviewRun、F02、SEMANTIC_AI、BREACH、TERM 能力投影成一条面向合同审核人员的流程：

`合同审核中心 → 集中材料核验 → 明确开始审核 → 左侧冻结材料 / 右侧八项 → 复制意见 → exact run 历史重开`

保留以下权威关系：

- Human Role / Identity / Candidate Confirmation 继续决定正式事实；恢复命令不能伪造人工决定。
- Formal Contract 是业务事实；ReviewRun 合同快照和材料清单是该次审核的冻结输入。
- ReviewRun 持久输出是八项读取视图的唯一结果源；展开、定位、复制和刷新均不得执行审核或模型 POST。
- DM8 继续是事实源；本设计不新增表或列。

本增量不改变 Candidate 权威、Promotion 含义、ReviewRun 生命周期、F02 规则、模型风险判断边界、ACK 含义或现有 report/artifact 契约。

## 2. 已确认问题与当前批准状态

| 工作包 | 源码结论 | 设计处理 | 当前状态 |
| --- | --- | --- | --- |
| C01 | `IDENTITY_CONFIRMED` 文案忽略 blocker | blocker 优先展示真实状态与原因 | 普通缺陷，已授权实施 |
| C02 | upload recovery 只恢复上传；机器 role/identity 和 Candidate materialization 没有统一显式接续命令 | 新增单一 progression resume command，只执行该文件最早未完成的机器阶段 | 已批准，须满足 3.2—3.4 的输入身份、回放及单文件边界 |
| C03 | 项目版本变化令旧 identity tuple 失效，但投影仍显示 `IDENTITY_CONFIRMED` 且无动作 | 细分 blocker，并由 resume command 创建当前 identity assessment | 已批准，输入身份不一致必须 fail closed |
| C04 | 无 Candidate、Candidate GET 失败与迟到响应未隔离 | 独立 Candidate 状态和 request generation | 普通缺陷，已授权实施 |
| W2 | 当前右侧仍是 F02、语义、违约、期限等技术块 | 固定八项只读投影与单开折叠面板 | 已批准；只消费同一 exact run 的已验证输出 |
| L01—L12 | 语义证据已有冻结定位；F02 project-backed adapter 未携带 source projection | 先完整 characterization；新 run 的 F02 来源持久化到现有 finding/evidence 表 | 已批准；须同时覆盖完成、读取和恢复协调器；无 DDL |
| A-mini / B / C | 只有详细设计批准；B/C 涉及 schema | 保留在能力矩阵，不在本增量偷偷实施 | 实现与 DDL 未批准 |

## 3. 准备阶段状态与恢复协议

### 3.1 读取投影

`ProjectFileProgressionProjection` 保留现有字段，并新增以下可选动作值：

- `RESUME_ROLE_ASSESSMENT`
- `RESUME_IDENTITY_ASSESSMENT`
- `RESUME_CANDIDATE_MATERIALIZATION`

blocker 使用以下稳定语义：

- `PROJECT_METADATA_CONFIRMATION_REQUIRED`：项目元数据未确认；下一步由现有项目元数据确认表单处理。
- `ROLE_ASSESSMENT_PENDING`：解析已保存但当前机器角色判断不存在；提供 `RESUME_ROLE_ASSESSMENT`。
- `IDENTITY_ASSESSMENT_PENDING`：合法人工角色决定存在但当前项目身份判断不存在；提供 `RESUME_IDENTITY_ASSESSMENT`。
- `IDENTITY_ASSESSMENT_STALE`：项目版本、文件 SHA、parse identity 或角色决定变化使旧 identity tuple 失效；提供 `RESUME_IDENTITY_ASSESSMENT`。
- `NEEDS_IDENTITY_CONFIRMATION`：当前 assessment 已形成；只提供现有人工确认动作。
- `CANDIDATE_MATERIALIZATION_PENDING`：当前人工 SAME_PROJECT 与 CONTRACT_BODY tuple 均合法但 Candidate 缺失；提供 `RESUME_CANDIDATE_MATERIALIZATION`。

没有活跃写操作时，前端不得显示无限“处理中”。每个非终态必须显示原因以及服务器返回的动作；没有动作时显示明确人工处理说明。

### 3.1.1 材料核验区三状态样例

| 页面状态 | 主要文案 | 原因/范围 | 主要动作 |
| --- | --- | --- | --- |
| 可继续 | `待核对材料归属` | `合同正文的当前项目归属需要人工确认` | `确认属于当前项目` / `不属于当前项目`，范围明确展示为当前文件 |
| 机器阶段未完成 | `材料类型判断尚未完成` | `文件已保存并解析，机器判断未形成` | `继续处理`，触发 server-authorized resume command；无活跃请求时不显示无限转圈 |
| 处理异常 | `合同信息暂时无法读取` | `项目资料仍可查看，合同候选读取失败` | `重新读取合同信息`；不要求重新上传，不清空已完成的人工决定 |

Candidate 尚未形成但 progression 给出人工作业时，页面同时展示材料卡片的真实动作。Candidate 尚未形成且只有待批准的机器恢复动作时，在该协议实施前显示“尚未完成，恢复入口待启用”，不得假装后台仍在运行。

### 3.2 单一恢复命令

新增向后兼容端点：

`POST /api/v1/projects/{projectId}/files/{fileId}/progression/resume`

请求：

```json
{
  "expectedBlocker": "IDENTITY_ASSESSMENT_STALE",
  "expectedRecoveryInputFingerprint": "sha256:..."
}
```

请求头继续使用 `Idempotency-Key`。响应返回更新后的 `ProjectFileResponse`，包含最新 stage、blocker、actions 和服务器重新计算的 `recoveryInputFingerprint`。GET 只计算投影和指纹，不创建事实、不 claim 幂等记录、不调用模型。

`recoveryInputFingerprint` 是服务器对当前可恢复输入的 canonical SHA-256。canonical 输入至少包括：

- `actorId`、`projectId`、Project metadata version；
- `fileId`、文件 SHA-256；
- 当前 parseResultId、parseVersion 和 canonical text SHA（如该阶段使用）；
- 当前人工 role decision ID/version/value；
- 当前 identity assessment ID/version、人工 identity decision ID/version/value（如该阶段使用）；
- 当前 blocker 和唯一 server-authorized action。

字段缺失使用显式 `null`，字段顺序和字符串编码固定并版本化；原始文本、密码或 token 不进入指纹。相同 blocker 但上述任一输入变化时指纹必须变化。服务端在执行前同时校验 `expectedBlocker` 和 `expectedRecoveryInputFingerprint`；任一不一致都返回 endpoint-local、机器可读的 conflict，并且不产生副作用。

只执行当前文件的当前最早未完成阶段：

1. `ROLE_ASSESSMENT_PENDING`：调用单文件 role assessment helper；不得调用扫描全项目的 `ensureRoleAssessments`。
2. `IDENTITY_ASSESSMENT_PENDING` / `IDENTITY_ASSESSMENT_STALE`：调用单文件 identity assessment helper，operation identity 继续绑定当前 role decision 与 Project metadata version。
3. `CANDIDATE_MATERIALIZATION_PENDING`：重新验证当前人工 role/identity tuple，调用现有 `ContractCandidateMaterializationService`；Candidate pair key 保证不重复。

现有批量入口改为逐文件复用同一个单文件 helper，保持旧行为和测试。恢复命令不得上传文件、创建人工决定、确认 Candidate、Promotion 或创建 ReviewRun。

### 3.3 Durable idempotency 与重放

幂等 scope 固定为 `PROJECT_FILE_PROGRESSION_RESUME`。durable request identity 为：

`scope + actorId + projectId + fileId + Idempotency-Key`

request fingerprint 由 endpoint/version、`expectedBlocker`、`expectedRecoveryInputFingerprint` 和规范化 path identity 组成。START 记录还保存原操作的 action、输入指纹和可验证结果身份；结果身份是该阶段产生或确认的 assessment/decision/Candidate ID 及其版本，而不是摘要字符串。

处理顺序固定为：

1. 完成鉴权并核对 actor、project、file 路径身份；
2. 按 scope/actor/key 读取 durable idempotency 记录；
3. 同 key 但 request fingerprint 不同：`IDEMPOTENCY_REQUEST_MISMATCH`，fail closed；
4. 已 COMPLETE：验证已存结果身份仍属于该 project/file 后重放原响应；即使 blocker 已推进也不能误判为新冲突；
5. START/OUTCOME_UNCERTAIN：保留原命令和 key，按原输入与持久事实进行恢复核对；不得生成新 key；
6. 没有记录时，才校验当前 blocker 和 recovery input fingerprint，随后以短 START 事务 claim；
7. 阶段计算在事务外执行；发布成功后以短 COMPLETE 事务保存结果身份和响应投影。

确定拒绝只有在证明命令没有产生任何阶段事实后，才允许 guarded-clear 客户端 operation identity。HTTP timeout、当前 blocker 已推进或无法证明结果都属于 `OUTCOME_UNCERTAIN`，客户端保留同 key。身份或结果归属无法验证时返回 endpoint-local 的 `RECOVERY_IDENTITY_UNPROVEN`；UNKNOWN 不等于 FAILED。

### 3.4 单文件执行、事务与失败恢复

- authority fence 只覆盖本文件的输入重新读取、指纹校验和结果发布条件；不得持有数据库行锁或长事务等待远程模型。
- START claim、远程/确定性计算、结果发布、COMPLETE 各有明确边界；普通失败以独立失败收口记录保留可诊断 reason，OUTCOME_UNCERTAIN 不被写成确定失败。
- A 文件恢复不得读取后决定或触发 B 文件的 role/identity 判断，也不得产生 B 文件的模型调用。
- “前一步已提交、下一步失败”时，重读投影只授权下一未完成阶段；已经提交的 assessment/decision/Candidate 不重复创建。
- 双击或并发只允许一个 claim 执行；其他请求重放完成结果或得到同一进行中/不确定状态。
- 前端按 actor/project/file/action/input fingerprint 保存 operation identity；项目切换和请求代次隔离迟到响应。刷新只 GET，GET 不自动 resume。

错误码限于该 endpoint，例如 `RECOVERY_INPUT_CHANGED`、`RECOVERY_ACTION_NOT_ALLOWED`、`RECOVERY_IN_PROGRESS`、`RECOVERY_OUTCOME_UNCERTAIN`、`RECOVERY_IDENTITY_UNPROVEN`、`IDEMPOTENCY_REQUEST_MISMATCH`；不得改变其他 endpoint 的错误分类。

## 4. 八项只读业务投影

新增前端纯函数 `createEightCategoryReviewProjection(run, fileNames, f02Sources)`。输入只来自 exact `ReviewRunDto` 和该 run 的持久输出，不调用网络或模型。

每项固定结构：

```ts
interface ContractReviewCategoryView {
  id: "QUANTITATIVE" | "DRAFT_STRUCTURE" | "BREACH" | "TERM"
    | "PARTY_QUALIFICATION" | "ATTACHMENT_COMPLETENESS"
    | "EFFECTIVENESS_COPIES" | "CLAUSE_RISK";
  order: 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8;
  title: string;
  coverage: "COVERED" | "PARTIALLY_COVERED" | "NOT_COVERED" | "UNFINISHED";
  judgement: "NORMAL" | "ATTENTION" | "INSUFFICIENT" | "UNAVAILABLE";
  statusLabel: string;
  attentionCount: number;
  summary: string;
  items: readonly ContractReviewCategoryItemView[];
}
```

`NOT_COVERED` 绝不显示为通过。缺少步骤、步骤失败、UNKNOWN、缺失业务事实分别保留真实含义。

### 4.1 现有结果映射

| 八项 | 第一版 exact-run 来源 | 第一版口径 |
| --- | --- | --- |
| 1 量化一致性 | `F02_DETERMINISTIC` 的全部 checkResult | 展示 PASS/FAIL/NOT_EVALUATED；规则定义为五类，不假定固定五条结果；多行 item 结果完整保留；B 未实施时如实标明分项覆盖限制 |
| 2 草本结构对标 | `SEMANTIC_AI` 的 `CONTRACT_STRUCTURE` | 有真实 finding 才显示；未指定对照模板时不得宣称结构对标完成，只能部分覆盖或未覆盖 |
| 3 违约责任量化 | `BREACH`，并引用 semantic `BREACH_LIABILITY` | 展示已持久化值、差异与材料不足；不计算实际累计违约金 |
| 4 期限与终止格式 | `TERM`，并引用 semantic `TERM_TERMINATION` / `DELIVERY_TERMS` | 相对工期、工作日、事件起点和生效事件保持原 finding 语义 |
| 5 主体资格 | semantic `PROJECT_IDENTITY` / `SUPPLIER_IDENTITY` | 名称或项目身份基础核对不等于主体资格合格；无外部资格事实不得绿灯 |
| 6 附件清单 | dedicated attachment reference/match results（当前若无则无来源） | 冻结材料清单和 `SCOPE_AND_SUBCONTRACTING` 不能证明附件完整性；没有实际附件引用/匹配结果时为 `NOT_COVERED` |
| 7 生效条件与份数 | `TERM` 的生效条件及相关 semantic finding；合同份数的 dedicated result（如有） | 生效条件和合同份数分别计算覆盖；未持久化份数检查时份数明确未覆盖，不能自定份数 |
| 8 条款风险 | 其余 semantic findings；3/4 已主归类 finding 只引用不重复计数 | 不编造法规条号；模型辅助结论标明需人工核验 |

每个 finding/checkResult 有一个 primary category。相关项可显示引用，但 `attentionCount` 和综合意见按 `runId + outputType + stableOutputId` 去重，不能依靠摘要文本或数组位置。同一 finding 在多个类别引用时仍保留一个问题身份。

### 4.2 状态规则

- `COMPLETED` 且存在该类别定义的输出：按 finding/checkResult 得出 `NORMAL`、`ATTENTION` 或 `INSUFFICIENT`。
- 对应 step `FAILED`：`UNFINISHED + UNAVAILABLE`。
- 对应 step `UNKNOWN`：`UNFINISHED + UNAVAILABLE`，文案为“结果状态待确认”。
- 当前 profile 没有该类别检查：`NOT_COVERED + INSUFFICIENT`。
- 只有基础信息、没有完整业务标准：`PARTIALLY_COVERED + INSUFFICIENT`。
- 类别内已有持久差异但整体覆盖不全：保留差异项的 `ATTENTION` 并另列未覆盖范围，不能用统一“资料不足”隐藏差异。

## 5. 页面与交互

页面骨架固定为左侧材料、右侧八项；右侧采用“概览 → 类别 → 问题”三级层次。右侧保留简短审核对象摘要，后接八项 accordion：默认全部折叠，一次只展开一个一级项。折叠行只显示标题、主要状态和关注数量。不重做左侧查看器，不新增领导版报告主流程。

展开内容依次为：本项总结、具体检查项、双方事实/差异、来源依据、处理建议、查看原文。本项和综合意见各提供复制按钮；复制失败必须可见。

复制文本由纯函数从当前持久投影生成，不调用模型、不改变 ACK、不创建 report。综合意见必须列出 `NOT_COVERED`、`UNFINISHED` 和 `INSUFFICIENT` 范围，不能生成“全部合规”。

旧 F02、Semantic、Breach、Term 组件在迁移期间仅作为投影数据源与兼容回归，不再作为甲方主界面的一级结构。ACK、report/artifact 与重试入口保留在八项面板之外的次级区域。

## 6. 原文定位门禁

- semantic/BREACH/TERM 继续使用已持久化 `ContractReviewEvidence` 和 `createDurableEvidenceFocus`。
- F02 的 `AuditTask` / `AuditCheckResult` 继续是业务结果权威；新 finding/evidence 仅是定位与展示载体，不参与 F02 判定、不创建第二套检查结果或第二套必须知悉动作。
- F02 新 run 将每条 checkResult 的来源投影写入现有 `ContractReviewFinding` / `ContractReviewEvidence` 存储；不新增 schema。`ReviewStepCompleteTransactionCoordinator.validateResult`、`ReviewStepOutputReadService.output/validateFindings` 和 `ReviewWorkerTransactionCoordinator.recover` 必须一并支持版本化的新 F02 证据，不能只修改 executor/mapper。
- 使用 step output discriminator `AUDIT_TASK_F02_NAVIGATION_V2`、finding schema version `2` 与 locator schema version `1` 明确新格式。旧 `AUDIT_TASK` run 的空证据继续合法；新格式宣称成功时必须满足完整映射和证据校验，不能在证据缺失时静默降级成旧格式成功。
- `checkResultId → findingId → evidenceId` 关系稳定、唯一且可验证。finding 使用独立稳定 ID，显式保存可解析的 checkResult 关系；不能复用 checkResultId 充当 findingId，不能靠 summary 字符串或数组位置关联。多行 F02 结果逐条映射且不丢失。
- F02 双侧来源分别持久化为独立 evidence，保留 frozen file ID/SHA、parseResultId/version、role、excerpt 和可用 locator。来源必须来自该 run 的 frozen Contract/材料/权威身份；不得无条件读取当前 Candidate 或 support 实时投影。无法验证 locator 时保存真实的 locator-unavailable 状态，不造 excerpt、页码、offset 或 bbox。
- mapper 写入前验证现有表字段的非空、最大长度、来源角色、source type 和精确 parse tuple；任何必要事实无法在现有 schema 中无损表达时停止 Task 6，报告具体列/约束和所需最小 schema，不把结构塞进 summary，也不执行 DDL。
- 正常完成与“内部 F02 已提交、外层 step 未完成”的恢复路径使用同一个映射版本及稳定身份。recover 读取既有 AuditTask/AuditCheckResult 后补齐同一外层输出，不重新计算 F02，不新建第二个 AuditTask。
- report、ACK 和列表投影按稳定关系避免同时重复展示原 checkResult 与载体 finding；保持旧报告和权限兼容。finding ACK 仍服从原 run owner/权限规则，不能形成第二套 F02 必须知悉语义。
- 历史 run 只使用该 run 的冻结 file IDs；旧 run 没有持久 F02 evidence 时显示“暂无可靠来源定位”，不得回读当前 Candidate 冒充历史来源。
- 快速 A→B、轮询、折叠、ACK、复制不得改变当前选中证据；切换 run/项目时清理旧焦点并阻止迟到响应覆盖。

Task 6 的完成证据必须包含：真实 repository persistence 后 GET、新浏览器会话、后端重启、内部 F02 已提交后的中断恢复、新旧 run 共存，以及 owner/非 owner 的 run、ACK、report、artifact 和原件权限回归。只测 mapper 不算完成。

## 7. 兼容性与非目标

- API 只做新增端点和枚举值；现有消费者继续接受原字段。
- 不做 DM8 DDL，不重跑 migration。
- 不实现 A-mini/B/C，不扩 F05—F15，不增加动态规则引擎、RAG、向量库或通用工作流。
- 不改变预算、采购、黑名单业务页面。
- 不把本轮统计、OA 或客户模型联调标成已完成；它们按真实环境分别报告。

## 8. 批准记录与保留门禁

2026-09-23 已批准：

1. 按 3.2—3.4 修订后的 C02/C03 `progression/resume` 命令、action/blocker 和 durable idempotency 协议。
2. 八项 exact-run 只读投影、统一 accordion、复制意见及 P1/P2/P2a 页面收口。
3. 按第 6 节完整写入/读取/恢复协议，使用现有 finding/evidence 表保存新 run 的 F02 定位载体；无 DDL。
4. 按操作单建立隔离 storage/temp/log/session namespace，启动本轮服务并通过正常 API 创建隔离测试数据；enabled worker 启动前必须完成数据库任务和其他 worker 前置核查。
5. 仅允许原件 01—06 中已确认的非敏感测试材料经本机 FastAPI 发往参考配置中身份核对一致的私网 provider。host/port/服务或模型身份不一致时暂停外发并报告。

A-mini、B、C 的结构实现及任何 DDL 仍未批准。客户真实机密材料、账号、token、数据库内容、其他项目文件和替换 provider 均不在批准范围内。
