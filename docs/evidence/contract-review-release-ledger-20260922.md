# 合同内审三日收口台账

更新时间：2026-09-22。唯一写入工作区：`fix/p1abc-premerge-hardening-20260922`，起始 HEAD `36a44958d03d48fb9d1c88410bfca8e85fc70091`，工作树起始为 clean。冻结的 P1-C HEAD `b3615b8adf81989c83f7135de1a7061df3636399` 是起始 HEAD 的祖先。此台账区分源码判断、自动化验证和真实运行证据；未运行的验收项均不算通过。

## 范围与执行顺序

1. R01/R02 历史入口与状态：先用行为测试暴露问题，再沿已持久化的 ReviewRun step 输出归属修复。历史读取必须精确到 runId，不能回退 `/latest`。
2. R04/R09：对指定 run 与环境作只读诊断，确认根因和唯一 worker 后才进行任何集成写入。
3. R03/R05/R08：沿现有 typed 输出和冻结证据作有界展示修复；若必须新协议，纳入下方集中变更单。
4. R06：优先做 ParsedProjectDocument 表格/段落与“成交”同义词的通用兼容测试；新 AI 结构化抽取协议需另行批准。
5. R07：现有 ContractLineItem/DM8 非空约束阻止金额分项模型，批准前只作设计与回归，不执行 DDL。
6. 最后执行原件、变体、合成 Contract 三套独立矩阵，自动化、真实 DM8/AI、浏览器、报告与独立审查。

## 问题清单

| ID | 当前证据与原因 | Owner | 预计触及处/测试 | 状态与下一步 | 批准 |
| --- | --- | --- | --- | --- | --- |
| R01 | `App` 把任务行包装为 `DIRECT_F02`，历史 run 归属与冻结显示丢失；源码和 RED 已确认。 | 主会话 | task read model、ReviewRun step/幂等归属、exact run 与冻结显示测试 | 定界修复、聚焦/全量测试及真实 DM8 只读查询 GREEN；刷新/新浏览器/重启的本次真实写入 E2E 尚待唯一 worker | 有界修复已授权 |
| R02 | F02 `DONE`/已知悉被误呈现为整次审核完成；源码和 RED 已确认。 | 主会话 | task/read model、状态映射测试 | 已分开展示 F02 与 ReviewRun 聚合状态；前端聚焦/全量 GREEN，真实浏览器待验证 | 有界修复已授权 |
| R03 | v1 BREACH/TERM finding 已有比例/周期/上限/日期，但前端将序列化摘要覆盖成通用句；源码确认。 | 主会话 | mapper、adapter、render/report tests | 新 finding 可读值、旧 v1 受限回退、状态样式、报告 v2 冻结证据均聚焦 GREEN；真实页面/报告待验 | 有界展示；typed v2 仅设计 |
| R04 | 指定 run `771b826f-27e1-4747-9de5-8bafd8f14470` 在真实 DM8 为 FAILED；MATERIAL_VALIDATION、F02、BREACH、TERM 均完成；SEMANTIC_AI step/attempt 为 FAILED，`WORKER_PROVEN_COMPUTATION_FAILURE`，externalCallState=`COMPLETED`。约五分钟不是已证明超时。 | 只读诊断代理 | exact DM8 SELECT、step/attempt、非敏感配置身份 | 继续定位语义计算/收口层；禁止重试或修改旧记录 | 根因有界修复已授权 |
| R05 | TERM 中存在材料而日期空白/无效时也给 `INSUFFICIENT`，旧 UI 误称“材料依据不足”；一侧已知另一侧未知的双方值在 v1 未持久化。 | 主会话 | adapter/status tests、typed C 设计 | 中性标签“关键事实暂无法确认”聚焦 GREEN；双方值保持 C 设计范围，未伪造比较 | 有界展示；typed v2 仅设计 |
| R06 | 原件 PDF 是无冒号“成交金额/供应商”字段行；旧 extractor 不产出金额，RED 已确认；候选人页/表格误提取由独立审查发现。 | 主会话 | ParsedProjectDocument、extractor/original tests | 无冒号与键值表、同义词、候选语境定界修复；01—05 原件 SHA+只读抽取 GREEN；正式链 E2E 待验证 | AI 新协议仅批准详细设计 |
| R07 | `ContractLineItem.quantity/unitPrice` 和 DM8 列均非空，无法真实表示只有名称/金额的服务分项。已由源码与 DDL 确认。 | 主会话 | Candidate→Contract→snapshot→F02→DM8 | `BLOCKED_BY_SCHEMA_DECISION`；提交集中设计变更单，不填伪 0/1 | 必须批 DDL/模型 |
| R08 | BREACH/TERM 现有 v1 不持久化可靠双方 typed 值，不能证明原件全部条款差异。 | 主会话 | clause result/display tests、C 设计 | 已有值展示修正；责任场景/双方值与未知原因的 typed v2 仅获准详细设计 | 新协议仅批准详细设计 |
| R09 | 配置脚本的 `$ROOT` 指向旧 closeout 测试区、storage 指向 demo worktree；不能直接 dot-source。12:59 +08 DM8 PENDING/RUNNING run、RUNNING attempt、有效 lease 均 0，但无 hardening backend，不能证明未来唯一 worker。 | 只读诊断代理 | 配置、进程、storage key/size/SHA | 真实写入 E2E 暂阻；不迁移、不重传、不停旧服务，先取得独占执行身份 | 环境改动另核授权 |
| R10 | `79546e4` 已修复 PROMOTED Candidate association fail closed。 | 主会话 | progression tests | 只核回归，不重复实现 | 无 |
| R11 | `d6b0d0a` 已刷新静态 OpenAPI，`96b7a28`/`36a4495` 已更新部分文档；当前说明仍需核对。 | 主会话 | OpenAPI、README、architecture、runbook、AGENTS | 核对当前代码与人工约束，不覆盖历史事实 | 新公共协议另批 |
| R12 | 原件完整链、双入口浏览器、未知模板、报告仍缺本次真实验收。 | 主会话 | 原件/变体/合成三套矩阵、最终报告 | 01—05 PDF 与 01 合同 DOCX 只读解析、SHA 已核；后端/前端/FastAPI 自动门禁已跑；真实写入待 R09 | 真实写入待 R09 安全确认 |
| R13 | 上次 hardening 全量 backend 曾有一次 AI 角色来源测试失败、再跑通过；review 指向相同时间戳下随机 UUID 排序使旧 deterministic 结果遮蔽 AI。 | 主会话 | role assessment repo/mapper、定向重复测试 | `NOT_REPRODUCED`：`ProjectIntakeContinuityApiIntegrationTest` 连续 3 次均 3/3、0 failure、0 error、0 skip；原始输出在忽略目录 `backend/.runtime/r13-role-repeat-{1,2,3}.log`。按要求先不改生产代码；全量再观察。 | 有界修复已授权 |

## 原始材料预期矩阵（先于本次 E2E 冻结）

源目录 `D:\code\audit-agent-demo\智慧维修系统_合同审查测试材料_20260917`，只读。材料说明明确其为合成验收样本；不得把后续人工合成完整 Contract 的结果算作原件抽取通过。

| 套件 | 原件所载事实 | 预期重点 | 当前证据 |
| --- | --- | --- | --- |
| 01 一致基线 | 金额 67,000；四项 28,000/16,000/9,000/14,000；服务期限和条款一致 | 已可靠提取且确认后金额/供应商匹配；缺数量单价时行乘法仍不可评估 | 材料说明只读；E2E NOT_RUN |
| 02 金额差异 | 采购/响应/成交 67,000，合同 69,000 | 成交金额匹配规则提示差异，证据指向对应材料 | 材料说明只读；E2E NOT_RUN |
| 03 服务差异 | 截止 2026-06-30 与 2026-07-31；质保 3 年与 1 年；响应/到场 1h/48h 与 4h/72h | 分项展示已知差异及来源，不能宣称全面合规结论 | 材料说明只读；E2E NOT_RUN |
| 04 条款差异 | 2%/周、上限 10%、超过 4 周、服务故障 20%，合同相应为 1%/周、无明示上限、超过 8 周、10% | 逐项核对和中性差异；缺失抽取不等于未约定 | 材料说明只读；E2E NOT_RUN |
| 05 异项目通知 | 通知书属于其他项目 | 不能成为当前 Contract 事实或证据 | 材料说明只读；E2E NOT_RUN |
| 06 扫描/空白 | OCR 扫描文件、空白未签合同 | 显示不确定与缺失来源，不伪造 PASS | 材料说明只读；E2E NOT_RUN |

## 集中结构性变更单（待补完整设计并单独批准）

待决范围：R06 的新 AI 结构化事实协议与授权链、R07 的金额分项模型及 DM8 迁移、R08 的 typed 条款比较输出/版本化存储。如其中一项可在现有协议内有界修复，则无需纳入。批准前不实现相关结构性生产变更；其他已授权工作继续。

### 变更 A：R06 语义候选事实抽取（需批准；当前未实施）

**现状证据。** `ProjectFactExtractor` 只在现有 ParsedProjectDocument 的 segment 上直接匹配 `中标金额`/`中标供应商`，虽有表格分项扫描，但不构成通用键值表中的最终成交事实识别。`ExtractionResult` 有 file/parse/locator 和版本，但无模型、prompt、schema、请求状态等 provenance。现有 DOCUMENT_ROLE 与 CONTRACT_TEXT_ANALYSIS 的职责不同，不能偷用它们的响应充当权威金额事实。

**最小建议。** 新增独立、严格 typed 的 `AWARD_FACT_EXTRACTION` 候选输出，只限 `AWARD_AMOUNT`、`AWARD_SUPPLIER` 和判定“最终成交而非预算/限价/报价/候选人”的必要上下文。输入限定已验证的 parse manifest 和材料角色/项目身份；输出必须引用现有 segment/cell ID，服务端对照冻结 parse 文本校验 locator、摘录及文档身份。模型只提候选值，不做金额比较、不自动改变 Candidate authority。只有现有 Candidate 专属 support authority 与人工 Confirmation 通过后，才能 Promotion 到 Contract。不可用、超时、证据不符、上下文冲突均保留独立失败/未知状态，不产出空成功。

**协议与持久化待设计。** 需要输入/输出 schema version、模型与 prompt 版本、请求身份、parse/file hash、候选 fingerprint、证据校验结果和晚到响应隔离。可考虑单独 append-only 候选事实记录及来源记录；禁止把模型结果塞进无关旧字段伪称无 schema 变更。若最终设计证明现有 `ExtractionResult` 与 provenance 足以覆盖全部约束，可缩小变更单并重新评估，无授权前不写正式事实。

**验证/回滚。** 12 种冻结变体覆盖同义表达、键值表、列序、无冒号、元/万元、否定句、候选人/最终成交、预算/报价、多标包、部分明细、小计合计、引用旧模板；至少一部分留作实现后盲测。无可信提取应保持 UNKNOWN 和人工确认可用；旧确定性抽取与旧 snapshot 可读。新通道关闭时不能让已经确认的旧 Contract 被模型结果改写。

### 变更 B：R07 金额分项模型（需单独批准 DM8 DDL；当前 BLOCKED_BY_SCHEMA_DECISION）

**现状证据。** Candidate line item 的数量/单价可为空；正式 `ContractLineItem` 构造器两者强制非空，`V003__f02_contract_review.sql` 的 `CONTRACT_LINE_ITEM.QUANTITY` 与 `UNIT_PRICE` 均 `NOT NULL`。原件四项仅有名称和金额，不能用 `quantity=1`、`unitPrice=金额` 或 0 伪造。

**最小建议。** 正式行明确 `QUANTITY_PRICE` 与 `AMOUNT_ONLY` 两种 basis。前者保持现有完整行乘法；后者保留真实未知的数量和单价，只能用经人工确认的 `declaredAmount` 参与合计。F02 行乘法逐行独立评估，缺数量/单价的行保持 NOT_EVALUATED；不能让它使别的完整行失去结果。F02 分项合计只有在行集完整、金额口径和合计范围已证明、排除了小计/合计重复、跨页遗漏和多标包歧义时才执行；部分识别不能产生 PASS。所有数值依旧 `BigDecimal.compareTo`。

**拟需持久化与版本。** 新受控 Contract migration 需增加 basis 列并按 basis 约束允许 `AMOUNT_ONLY` 的 QUANTITY/UNIT_PRICE 为 NULL；旧完整行默认/回填为 `QUANTITY_PRICE`，历史 V003 文件不可改。Candidate、Confirmation payload、Promotion、Contract、DM8 mapper、snapshot canonicalizer/decoder、F02 input/rule、API/OpenAPI 均需新旧兼容。旧 run 的 schema v1/hash 原样保留并按旧逻辑重开；新 run 使用显式 v2 快照/规则版本。真实回滚限制：一旦写入 amount-only 行，旧二进制不能直接读写，须先停止新写入、保留可读版本或执行获批的数据处置方案；不得声称迁移文件删除等于回滚。

**验收。** 原件 28,000/16,000/9,000/14,000 四项的完整行集和 67,000 合计需先证明；无数量/单价的行乘法为 NOT_EVALUATED；原有完整行和旧 snapshot 的结果不变。DM8 catalog preflight、备份/回滚计划、受控 DDL 执行和真实集成回归必须分别获准。当前不得添加 migration 或执行 DDL。

### 变更 C：R08 typed 条款比较（若现有输出不足则需批准；当前未实施）

**现状证据。** `ClauseComparison` 只有 key/label/status/message/sources；`FrozenClauseResultMapper` 将它降为 `key=...; label=...; message=...` 的 summary，`ContractReviewFinding` 只持久化 summary/recommendation/状态/版本，没有具体双方值字段。不能让前端解析任意分号字符串作为长期协议。

**最小建议。** 限定 BREACH/TERM：typed comparison 记录责任场景、责任方、计算基数、比例、周期、累计上限、解除阈值、期限起点和条件；每个双方值附其冻结材料证据。只有相同责任场景/标包/基数/单位时才能比较；工作日与自然日不互换；“未提取到”不等于“未约定”。原件 04 的 2%/周与 1%/周、4 周与 8 周、20% 与 10%、上限是否明示须逐项验证。结论为差异/待复核，不自动认定违法。

**协议/版本/回滚。** 若需 durable typed 字段，建议独立版本化 comparison payload/表或受控新列；旧 `findingSchemaVersion=1` 保留有限摘要展示，新版不改写旧 finding 或快照，不用新解释追溯旧结果。新 API 字段必须 additive 并经 OpenAPI/客户端测试；真实 DDL 依同一受控审批。不能扩展 F05-F15。

**审批请求范围。** 请分别决定 A（新 AI 候选协议及持久化设计）、B（正式金额分项模型及受控 DM8 schema 设计）、C（typed 条款持久化/API 设计）是否允许进入详细实施。该批准不等同于生产部署、客户 DB DDL 执行或历史数据修复授权。任一项未批准时保持对应工作包 BLOCKED/DEFERRED，其余有界修复继续。

## 2026-09-22 真实只读证据补记

R04：只读 JDBC Prepared SELECT 与 `setReadOnly(true)` 连接 Phase 1B 已迁移的集成 DM8；没有执行环境配置脚本或数据库写入。指定 run `771b826f-27e1-4747-9de5-8bafd8f14470` 为 FAILED，SEMANTIC_AI attempt `2cd75ea9-a249-3188-b120-c65b3ecf9ac8` 于 02:17:49 被 worker `worker-7bdaa4c7-d9be-4f26-b482-2fb351393770` 认领，02:22:49 以 `WORKER_PROVEN_COMPUTATION_FAILURE` 收口；providerRequestId/provider/model 均空。四份冻结材料 SHA 与 PROJECT_FILE SHA 一致，数据库 key/size/SHA 与 demo storage 实物逐项吻合，均为 AVAILABLE/PARSED。该 run 没有 sanitized debug bundle，本地现存日志未命中 run/attempt/worker。源码将下层语义异常折叠为通用原因；目前不能证明是 `SEMANTIC_UNAVAILABLE`、`SEMANTIC_RESPONSE_INVALID` 或 timeout。`externalCallState=COMPLETED` 仅证明失败已持久收口。F02 task DONE，金额大写 PASS，另四项因正式 Contract 无行/award 为 NOT_EVALUATED。R04 精确根因仍待安全新运行证据，旧记录不得重试或改写。

## 集中变更单 A/B/C：获准详细设计，结构实现未获准

用户于 2026-09-22 分别批准 A、B、C **进入详细设计**。这不授权新增 migration、执行 DM8 DDL、改变现有 Candidate/Confirmation/Promotion 权威语义、部署或修复旧数据。以下是供下一次结构性实施审批的最小方案；R01/R02/R06 等已授权定界修复继续。

### A / R06：成交候选事实语义抽取协议 v1

**范围与权威。** 新增内部 `AWARD_FACT_EXTRACTION/v1`，输入仅限已持久化、SHA 已验证的 AWARD_NOTICE 文件及其当前 parse batch、经人确认的材料角色和同项目身份。输出只允许 `AWARD_AMOUNT`（十进制金额字符串和币种/单位）与 `AWARD_SUPPLIER`（原文及标准化名称），附最终成交/中标语境、标包范围、确切 segment/cell locator 与短摘录。预算、限价、响应报价、候选人排序和“未成交”均不能提升为最终事实。模型不比较金额、不决定合规、不改变 support authority；已验证候选进入现有 support file/Confirmation 选择链后，才可能成为正式 Contract。

**请求、结果与证据。** 请求身份包含 projectId、fileId、file SHA-256、parseResultId/version、canonicalText SHA-256、roleDecisionId/version、identityDecisionId/version、operation key、请求/输出 schemaVersion、promptVersion、modelPolicyVersion 和调用预算。响应为严格 typed 候选集合及 `SUCCEEDED / UNAVAILABLE / INVALID_RESPONSE / INVALID_EVIDENCE / STALE_INPUT / CONFLICT / UNKNOWN` 状态；每个候选引用服务端发出的 locator ID。Java 端在冻结 parse 文本中核验 locator、摘录、同文件/批次/项目、单位和数值；JSON schema 正确或模型 confidence 高都不能替代这一步。冲突候选保持歧义，不择其一。失败不得返回空成功，历史读取不得发模型调用。

**拟议持久化与发布。** 需要独立 append-only attempt、候选与发布归属记录，保存输入身份、模型/prompt/schema 版本、调用结果、验证状态和来源；拟用 `PROJECT_AWARD_FACT_ATTEMPT`、`PROJECT_AWARD_FACT_CANDIDATE`、`PROJECT_AWARD_FACT_PUBLICATION`，发布表指向现有 `PROJECT_EXTRACTION_RESULT.ID`，不挪用 DOCUMENT_ROLE 或 CONTRACT_TEXT_ANALYSIS 字段。只将通过服务端证据校验且仍是当前 parse/角色/身份决议的候选，以现有 ExtractionResult 形状发布给 Candidate；重复 key 幂等，晚到结果保留审计但不得发布到新批次或覆盖已确认 Candidate。若确定性与 AI 候选值冲突，现有 assembler 的多值歧义为 null，待人核对。具体外键、唯一索引和 DM8 DDL **尚未获批**。

**兼容、验证与回滚。** 新内部端点版本化并可关闭；旧 deterministic extractor、旧 Candidate/Contract/ReviewRun 可读。关闭新通道只停止新请求/发布，不删除已确认事实。至少冻结 12 类变体与反例，其中保留未调参盲测；原件 01/02/05 要验证角色、项目身份、金额/供应商来源和错项目排除。需另外审批 AI 数据授权、持久化 schema 与新协议实施，当前仅设计。

### B / R07：金额分项两种依据及完整行集

**模型。** 复用同一 ContractLineItem，新增 `basis=QUANTITY_PRICE | AMOUNT_ONLY`。前者 quantity、unitPrice、declaredAmount 必须存在；后者仅 declaredAmount 必须存在，quantity/unitPrice 保持 null。声明金额绝不以 0、1 或金额自身伪造单价。Candidate 行本来允许数量/单价为空；Confirmation payload 必须显式携带 basis，并由人工对金额行的范围、小计/合计排除、标包、跨页完整性作确认。另需 Contract 级行集完整性事实及其来源/确认身份，不能从“识别到四行”推断完整；其准确持久化形状仍需在实施审批时确定。

**F02 语义。** 行乘法按 itemKey 独立评估：完整数量单价行用 BigDecimal 乘法及 compareTo；AMOUNT_ONLY 行返回有原因的 NOT_EVALUATED，不阻断其他行。LINE_SUM_MATCH 只在声明金额逐行完整、行集合及总计范围已经确认、无重复小计/合计或跨标包歧义时计算；否则保持 NOT_EVALUATED。原件 28,000/16,000/9,000/14,000 能否合成 67,000，须先有上述完整性事实。金额容差仍为零，不使用浮点或 AI 决策。

**拟议 schema/version。** 新受控 DM8 migration 在 `CONTRACT_LINE_ITEM` 增 basis、按 basis 放宽 QUANTITY/UNIT_PRICE nullability 并保留非负约束；Contract 行集完整性另需受控字段/表。旧 V003 不改。旧完整行回填 `QUANTITY_PRICE`，旧 frozen run 的 schema v1/hash、旧规则版本原样读；新事实使用 snapshot/rule v2，decoder 同时支持两版。Candidate、Confirmation、Promotion、Contract mapper、DTO/OpenAPI、快照、F02 与报告一并做兼容测试。既有完整行数据可读，不等于写入 AMOUNT_ONLY 后旧二进制可回滚：须先停止新写、保留双版本读取或获批数据处置。当前 R07=`BLOCKED_BY_SCHEMA_DECISION`；不得新增 migration 或执行 DDL。

### C / R08：BREACH/TERM typed 双方比较 v2

**结构与判断。** 每个 comparison 有 `scenarioKey`、标包/责任场景、责任方、计算基数、比例及单位、周期、累计上限、解除阈值、期限起点/条件；双方值各有 `KNOWN / NOT_EXTRACTED / NOT_APPLICABLE / CONFLICT`、原文、标准化值、冻结证据 ID。只有场景、标包、基数、单位与起点可比时，应用层才给 `MATCH / DIFFERENT`；其他为 `NOT_EVALUATED / CONFLICT`。自然日和工作日不互换，未提取到不等于未约定，差异只能提示复核而非自动断言违法。

**持久化/API。** v1 ClauseComparison/summary 和旧 findingSchemaVersion=1 保持原样，不解析分号来补旧事实。拟以独立版本化 `CONTRACT_REVIEW_CLAUSE_COMPARISON` 行或经批准的 canonical payload 存储 v2，关联 run/step/attempt/finding 与现有冻结 evidence，记录 payload SHA 和 policyVersion；公共 read model 只增加可选 typedComparisonsV2 字段，旧客户端继续消费 summary。新版本只用于新 run/attempt，不能改写旧 finding 或旧快照。具体 DM8 DDL、API shape 和模型输出协议均需单独实施审批。

**验收/回滚。** 原件 04 的 2%/周对 1%/周、4 周对 8 周、20% 对 10%、上限明示与否逐项核实场景和来源；一侧已知另一侧未知时只展示已验证一侧与未完成比较。旧 v1 结果继续受限展示，新通道关闭不影响历史读取。R08 当前仅详细设计，不能宣称覆盖所有条款。

## 本次自动化与只读集成门禁（中间快照）

| 验证 | 环境/命令 | 已观察结果 | 限制 |
| --- | --- | --- | --- |
| Backend full | 本 worktree/JDK 17，`backend/.\mvnw.cmd -Daudit.original.materials.root=<只读原件目录> clean verify -q` | 审查修复后 275 suites、1790 tests、0 failure/error/skip | 包含原件只读解析；普通 verify 不替代真实 DM8/浏览器 |
| Frontend full default | `node --test --experimental-strip-types "src/**/*.test.ts"` | 592/592，0 failure/skip | server-side rendering 与单元，不代替真实浏览器 |
| Frontend full shared | 上式加 `--test-isolation=none` | 592/592，0 failure/skip | 同上 |
| Frontend build | `npm.cmd run build` | 2408 modules、exit 0；既有大 chunk warning | 非部署证据 |
| FastAPI full | 已验证 Python 3.11 venv，从 hardening 源码运行 `python -m pytest -q` | 340 passed、1 Starlette deprecation warning | 首次沙箱尝试因 pytest temp 目录 WinError 5 无有效结论；重跑获准执行成功 |
| 原件只读解析 | `OriginalAwardMaterialCharacterizationTest`，显式传原件根目录 | 01—05 通知书 SHA/金额 67,000/供应商 locator；01 合同四项金额、无数量单价提取断言通过 | 只证明 parse→extraction，不证明角色/身份/Confirmation/Promotion |
| DM8 read-only catalog | `ContractCandidateSupportAuthorityRepresentativeDm8IT#exactReadOnlyCatalog`，环境配置固定键解析到子进程 | DM8 major 8，exit 0 | 无 DDL/写入；不是客户库 |
| DM8 read-only navigation | `ContractAuditTaskNavigationRepresentativeDm8IT`，同一集成库 | 已有 run 的 F02 step→task、幂等 claim 与历史列表 SQL exit 0 | 只读，尚无本次真实浏览器重开 |

两次 DM8 测试均只读取 `local-demo.env.integration.ps1` 的固定 DB 键，并未执行该脚本。原件和旧 demo worktree 未修改。R04 失败原因仍未被现有 durable reason 精确区分；不能把约五分钟推定为 timeout。12:59 +08 的零 lease 是瞬时观察，不能证明唯一 worker，故未启动真实写入 E2E。

## 一次独立集成审查与 Important 修复

独立只读审查发现：`ContractReviewRunController` 的精确 GET 已限制 `requestedBy`，但 closeout 的报告和 finding ACK 只核对 project/contract/run/finding UUID，同角色其他用户可通过已知 UUID 读取/下载报告，甚至写入 ACK。审查未确认其他 Critical/Important；没有修改文件或写入数据库。修复前新增 `CloseoutAcknowledgementTest#anotherUserCannotReadOrAcknowledgeTheRunOwnersFinding` 与 `ReportArtifactTest#anotherUserCannotReadDownloadOrGenerateTheRunOwnersReport`，两项都以“本应抛 NOT_FOUND，但未抛”RED。随后在 `CloseoutOwnership` 对 ACK 的读写核对 run 发起人；报告 `get/list/download` 读取核对发起人，生成事务在幂等 claim 和报告插入前核对发起人。定向四类 closeout 测试 GREEN；后端全量 `clean verify` 为 275 suites、1790 tests、0 failure/error/skip。该策略与精确 ReviewRun GET 一致，不改已有 task-center 按角色共享列表的语义；该列表本身不授权读取他人的冻结 run 或 closeout 事实。

验收报告见 [合同内审三日收口验收记录](contract-review-release-acceptance-20260925.md)。

## 原件 SHA-256 清单（只读，2026-09-22）

下表的路径均相对于 `D:\code\audit-agent-demo\智慧维修系统_合同审查测试材料_20260917`；同一 SHA 表示原件字节相同，不能把别套原件的派生副本当独立抽取证据。

```text
836c1397727bf1f5501f47c421469cc9c4b4845e1a5fa663d568f16435818fc6  00_测试材料索引.csv
1d3536ebcaa6493a1244533863675f3086de2fc0ddb74ae8c41c1410e1763cb4  00_测试说明与预期结果.md
efa99cc88dff3838cf332151d112713f572582c0795790892f0338a5f7cc4ee9  01_基准一致项目\01_智慧维修系统竞争性磋商采购文件.docx
4dbfcd4e22f77db1d92ec38662b022bff3e0757309a7cd251f68e828b1bcc750  01_基准一致项目\02_上海具维数智科技有限公司_响应文件.docx
07d81f08f86b7ac4190c3012e98fb3dda6f067db84b3319ca54301932ed19a64  01_基准一致项目\03_智慧维修系统成交通知书.pdf
1d4c66f148d97420e81bbe5804fd4d67167498b1b45442156660515994caef0a  01_基准一致项目\04_智慧维修系统软件采购合同.docx
961dad720db61ed57e985f30315d7591abd1e2c4730b9bd228b7faa83ef38650  01_基准一致项目\05_项目需求澄清与补充说明.docx
efa99cc88dff3838cf332151d112713f572582c0795790892f0338a5f7cc4ee9  02_金额冲突项目\01_智慧维修系统竞争性磋商采购文件.docx
4dbfcd4e22f77db1d92ec38662b022bff3e0757309a7cd251f68e828b1bcc750  02_金额冲突项目\02_上海具维数智科技有限公司_响应文件.docx
e8a10b7b80b630ca052b77503dbff3a315c846316f06ccda20e747c2d80b2efd  02_金额冲突项目\03_智慧维修系统成交通知书.pdf
93f5338e3dc5b0915d57d8847ab3bf4eb4de8bc40e3ed02998fde15a2757d1d1  02_金额冲突项目\04_智慧维修系统软件采购合同.docx
efa99cc88dff3838cf332151d112713f572582c0795790892f0338a5f7cc4ee9  03_履约条款冲突项目\01_智慧维修系统竞争性磋商采购文件.docx
4dbfcd4e22f77db1d92ec38662b022bff3e0757309a7cd251f68e828b1bcc750  03_履约条款冲突项目\02_上海具维数智科技有限公司_响应文件.docx
b4623aa54104ab0ec961837de8e2960872ec135eeeac08082fcaa6d511c7ccf4  03_履约条款冲突项目\03_智慧维修系统成交通知书.pdf
05232778e3ba4156d4f34feaaca2b0b3b25527f9d2f8eb08467d74d8c1ab7265  03_履约条款冲突项目\04_智慧维修系统软件采购合同.docx
1a889fb849d717cfb525ae443ba2c750736d23fbd8058033f7ceb94918199db8  04_违约责任冲突项目\01_智慧维修系统竞争性磋商采购文件.docx
8b053616796eb45f1d0abfd42fdece65e5a6c22eaa3d7aa670f55544289d20d2  04_违约责任冲突项目\02_上海具维数智科技有限公司_响应文件.docx
8887025548bf61b554c62c63b6971f52ff34a1f55dba30259ba9eb21a867f5d1  04_违约责任冲突项目\03_智慧维修系统成交通知书.pdf
ef06cd78ca66f1da33a22dc032af2859358b819f65d6b06a85ccf079c9e84ca4  04_违约责任冲突项目\04_智慧维修系统软件采购合同.docx
efa99cc88dff3838cf332151d112713f572582c0795790892f0338a5f7cc4ee9  05_混入其他项目\01_智慧维修系统竞争性磋商采购文件.docx
4dbfcd4e22f77db1d92ec38662b022bff3e0757309a7cd251f68e828b1bcc750  05_混入其他项目\02_上海具维数智科技有限公司_响应文件.docx
12f5758232fb126ea0591dbec86010dba2a5ac8c87fa1d1fe50d6a734c4ff3ab  05_混入其他项目\03_智慧维修系统成交通知书.pdf
1d4c66f148d97420e81bbe5804fd4d67167498b1b45442156660515994caef0a  05_混入其他项目\04_智慧维修系统软件采购合同.docx
8630b2be34830f432aa820222269d72ebaf0d67d9765c8ee790e0ae8301aa571  05_混入其他项目\05_校园访客预约系统成交通知书.pdf
efa99cc88dff3838cf332151d112713f572582c0795790892f0338a5f7cc4ee9  06_OCR与资料不完整\01_智慧维修系统竞争性磋商采购文件.docx
4dbfcd4e22f77db1d92ec38662b022bff3e0757309a7cd251f68e828b1bcc750  06_OCR与资料不完整\02_上海具维数智科技有限公司_响应文件.docx
9f29c7726a653ce63f007c81c748f920b2b0f36724a7d23337a001978e19bc93  06_OCR与资料不完整\03_IMG_20260618_092114.pdf
b66f4e31ef28b8d6392adb523c127b92a4200fe300bd8933fb93029238baa9c7  06_OCR与资料不完整\04_智慧维修系统软件采购合同_待签版.docx
bca120f091a1132695ffffa70457b0aa0998d7b5e889a3d3f6aa421eab6c0731  06_OCR与资料不完整\05_验收与上线条件补充说明_待确认.docx
```


## 2026-09-25 统一收口 fresh integration evidence

候选 worktree 为 D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923，branch feature/contract-final-closeout-20260923，HEAD e86e6dc59409e0d7e3785882f5b4e1c87e0f07df，仍为未提交 WIP。本轮未 push、merge 或部署客户环境。实际 Backend JAR SHA-256 为 085954318B987B014D6F6D702901E234C7EEBE9555EA05DCE26995625185C4F9，Frontend bundle 为 index-BYklJkJy.js，SHA-256 为 918B6ADF4ABC90CCFF010DBAD212BF625856DB695E110BC5FF0B431B5D567A59。

在已核实的 Phase 1B 集成 DM8、独立 Redis session namespace 和本 worktree 隔离 storage 上，使用原始只读 01—06 材料创建了六个正式 Project/Contract/ReviewRun。六个 run 全部 COMPLETED，FAILED 0，UNKNOWN 0。02 的正式合同金额 69,000 对通知 67,000，F02 award amount 为 FAIL；其余套件 award amount 为 PASS。六套 supplier 均 PASS，amount words 均 PASS，line sum 均 PASS。每套四条 line arithmetic 因 AMOUNT_ONLY 的 quantity/unitPrice 真实未知而保持 NOT_EVALUATED。每 run 为 8 条 AuditCheckResult、8 条 navigation finding、8 条 relation；人工 Confirmation 的正式数值没有可靠原文 locator，evidence=0 并诚实标为 SOURCE_UNAVAILABLE。

05 的异项目通知在真实链中暴露 material selection 缺陷。TDD 修复后，effectiveIdentityStatus、identityCurrent、excludedFromProjectUse 共同决定 eligibility，最终 run 只冻结 4 份同项目材料。随后真实 retry 又暴露 retained create key 阻断变化后正确命令的问题。修复绑定 exact operation 和 active pointer identity，确定拒绝后才允许新命令使用 fresh key。独立 reviewer 首轮发现两个 Important，唯一 fix round 后复审无 Critical/Important；focused recovery 86/86，material identity 19/19。

06 的扫描通知由 PADDLEOCR_3.7.0 完成 OCR。DM8 只读调用汇总为 deterministic role assessment 31、批准模型的 AI role assessment 8、同模型 semantic operation 6、OCR 1；private provider inference operation 共 14，A-mini contract fact extraction 为 0。模型名只在本轮 PowerShell 会话覆盖，reference env、仓库默认配置和客户配置均未修改。

真实浏览器已验证左材料右八项、默认折叠、一次展开一项、单项/综合意见复制，以及合同/响应/采购三个来源点击到正确 frozen file 且 highlightCount=1。项目入口、历史入口、刷新、第二浏览器和 Backend restart 后均保持 exact run 与 frozen input。匿名 exact-run GET 返回 401；第二低权限用户的跨 owner 真实 E2E 未执行。

fresh gates：Backend clean verify 1831 tests，0 failure/error，2 skipped；Frontend 两种 runner 各 641/641；Frontend build 2407 modules 成功；FastAPI 346/346；git diff --check 通过。 Backend 首次与其他 full gates 并发运行时有 1 项 role proposal 测试波动；同方法 1/1、整类 4/4、随后单独 clean verify 1831/1831 通过，未做代码修改。FastAPI 首次 sandbox 运行因 Windows temp PermissionError 出现 2 failed/3 errors，允许访问该测试目录后同代码全绿，不记作产品失败。

最终分项口径：CODE_REVIEW、UI_REQUIREMENTS、PREPARATION_FLOW 为 PASS；MATERIAL_EXTRACTION、EIGHT_CATEGORY_COVERAGE、HISTORY_SECURITY 为 PARTIAL；EVIDENCE_NAVIGATION 为 PASS WITH HONEST LIMIT；ORIGINAL_E2E 为 01—06 PASS WITH DOCUMENTED HUMAN CONFIRMATION；OA 和客户环境未验证。A-mini 与 C 保持 DEFERRED，B amount-only 已实际使用但通用行集完整性事实仍是已知限制。详细证据见 contract-eight-category-acceptance-20260925.md、contract-evidence-navigation-regression-20260925.md、contract-review-integration-operation-sheet-20260923.md 和 contract-review-self-verification-guide-20260925.md。
