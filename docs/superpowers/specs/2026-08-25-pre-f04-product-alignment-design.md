# Pre-F04 Product Alignment Design

## 目标

本设计在当前 A+B shared baseline 上固化 Pre-F04 产品语义，形成三个可独立交付、顺序合入 `main` 的子项目：

1. Review Risk Acknowledgement & UX；
2. Project Intake Core；
3. Project Intake UI & Contract Promotion。

交付顺序固定为：

```text
Plan A merge main
  -> Plan B from latest main
  -> Plan B merge main
  -> Plan C from latest main
  -> Plan C merge main
  -> 才开始正式 F04
```

Pre-F04 不进入 `feature/f04`，三个计划也不得并行共享未合入的业务事实或 migration 假设。

目标是复用当前 Contract/F02、Session/CSRF、DM8、FileStoragePort 和 React/Vite 基线，补齐最小 Project intake、逐条风险知悉、候选合同人工确认及 Project 1:N Contract 关系。所有正式业务事实持久化到 DM8；文件内容继续通过现有文件存储边界保存。

本设计固定以下身份边界：

```text
Project != Contract != ReviewSession != AuditTask
ProjectFile != ReviewDocument
FindingAcknowledgement != OperationAuditEvent
ContractCandidate != Contract
```

本文是 ADR-009 所要求的后续独立架构 gate：人工确认了 v1 不做模板校验、只采用确定性分类，并给出了 archive security 默认边界，因此 Project/ZIP 可以进入分计划实施设计。本文不回写或篡改 ADR-009 当时“信息不足、禁止直接实现”的历史决策。

## 非目标

- 不实现真实 OCR、AI、Qwen、FastAPI Project extraction 或 Prompt。
- 不实现模板引擎、项目包模板校验、工作流引擎、异步队列或后端逐步规则执行。
- 不实现 `.doc` 文本提取、扫描 PDF OCR、Office 在线预览。
- 不实现 Project 自动创建 ReviewSession、ProjectFile 自动绑定 ReviewDocument 或 ReviewSession 驱动 F02。
- 不修改 F01/F03 业务、F01 parser、F02 五条确定性规则、AuditTask 生命周期或 F02 durable idempotency。
- 不建立通用 OperationAudit 查询平台，不用 OperationAudit 代替 acknowledgement 业务事实。
- 不实现风险批准、风险接受、项目审批结论、自动生成正式 Contract。
- 不为页面展示伪造项目类目、供应商手机号、黑名单、围串标或其它风险数据。
- 不实现 Project、Contract、FindingAcknowledgement、ProjectFile 或 ContractCandidate 的删除、软删除、归档与前端删除入口。
- 不实现人工文件分类纠正；只保留未来独立 CAS 命令所需的字段 seam。
- 不实现项目正式状态字典、正式审查报告导出、BI/周月季年分析、供应商手机号主数据、围串标算法、黑名单聚合展示或 F11 能力。
- 不修改 V001-V008，不顺带处理 F01/F03 已知 migration 问题。
- 不创建顶层 `project` bounded context，不创建 `ProjectStoragePort`。

## 现有架构复用

以下能力保持现有所有权和语义：

| Existing capability | Reuse decision |
| --- | --- |
| `FileStoragePort` / `FileSystemStorageAdapter` | 原样复用字节 stage/promote/open/inspect/discard、SHA-256、create-only、root confinement 和 restart rediscovery |
| Session Cookie / CSRF / CurrentUser | 所有新 API 继续使用现有 Spring Security 和 `AuthContext` |
| `ApiResponse` / RequestId / TraceId | 所有响应和写操作审计沿用现有公共边界 |
| `IdempotencyPort` | Project create/upload/promotion 和 acknowledgement 使用独立 scope，不使用 Redis |
| `Money` / `BigDecimal` | 所有人民币候选值、正式 Contract 金额和比较继续使用定点数值语义 |
| 中文大写人民币 parser | 仅用于已定位候选文本的确定性校验，不负责定位字段 |
| PDFBox / Apache POI | 只复用 dependency，Project 建立自己的 parser/extractor |
| Contract direct F02 | candidate promotion 后继续调用现有 `/api/v1/contracts/{id}/agent-review` |
| `AuditCheckResult` | acknowledgement 唯一关联的 finding 身份 |
| React/Vite app shell | 沿用内部 `Page` state navigation，不引入新 router/client 基础层 |
| `OperationAuditPort` / `AUDIT_OPERATION_LOG` | 继续承载关键业务操作的追加式审计证据，不创建第二套 operation audit framework |
| `FlowDebugLogger` | 继续承载可开关的技术流程观测，复用现有字段风格和命名模式 |
| `AUTH_LOGIN_AUDIT` | 继续承载登录、失败、锁定和退出等认证事实 |
| `RequestAuditLoggingFilter` / `GlobalExceptionHandler` | 继续承载 HTTP access 与安全 5xx 诊断，不复制成业务操作审计 |

现有 `ReviewFileValidator` 继续只服务 ReviewSession 的 PDF/PNG/JPEG invariant。Project Intake 使用独立 `ProjectFilePolicy`，不得扩大或绕过 ReviewSession 规则。

## Review UX

F02 后端继续同步、一次性计算并持久化全部规则结果。前端只对已完成结果做 progressive replay，不把 UI 播放步骤持久化为后端流程。

Plan A 增加 contract-owned、仅服务 F02 的只读 `ReviewProgress` projection，其 item 类型为 `ReviewProgressItem`。第一项固定为：

```text
type = SOURCE_PREPARATION
title = 读取合同及比对来源
```

`SOURCE_PREPARATION` 只是 Application/API read model 派生的 presentation item，必须同时满足：

- 不是 `AuditCheckResult`，不写 `AUDIT_CHECK_RESULT`；
- 没有 `RiskLevel`，不允许 acknowledgement；
- 不增加第六条 deterministic rule，不修改五条 F02 规则；
- 不改变 `AuditTask` lifecycle、START/COMPLETE/FAILURE 短事务或 durable idempotency；
- 完成时间只投影真实可证明的任务事实。当前模型没有独立“来源读取完成”事实，因此 DONE F02 优先使用 `AuditTask.startedAt` 作为安全 review-start 时间投影；不得制造另一个完成时间。若历史异常数据连 start time 都不能证明，则时间为空并显示“暂无可靠时间”。

其后每一项才对应一个真实 `AuditCheckResult`。`total = 1 + findings.size`，不得写死 6；典型五 finding 页面可显示 1/6 至 6/6，但 finding 数量变化时总数随服务端事实变化。当前前端固定的“生成审核结论”展示节点必须移除，不能用另一个 presentation step 替代 finding。

每个 `ReviewProgressItem` 至少投影 `type`、nullable `checkResultId`、title、`displayOrder`、status、nullable risk、nullable completedAt 和 nullable acknowledgement。结果展示顺序由 API 提供稳定的只读 `displayOrder`，其计算以 `ruleCode`、nullable `itemKey`、result ID 的确定性排序为回退；不得依赖 DM8 未声明的行顺序或浏览器收到数据的偶然顺序。`displayOrder` 不进入 `AUDIT_CHECK_RESULT`。

SOURCE_PREPARATION 的 `displayOrder=0`，真实 findings 从 1 开始；UI 再把它们渲染为面向用户的 1/total。

模块 ownership 是硬边界：通用 `review` 模块只拥有 `AuditTask`、`AuditCheckResult`、`FindingAcknowledgement` 以及只依赖这些事实的通用 acknowledgement read model。`review` 可以暴露读取 task/result/acknowledgement 的 Port 或 query，但不得拥有 `SOURCE_PREPARATION`、F02 `ruleCode -> title` 映射、F02 step `displayOrder` 或完整 `ReviewProgress` assembly。后四者全部留在 `contract` Application/API，由 contract-owned assembler 消费通用 review 事实并生成 F02 projection；任何实现都不得建立 `review -> contract` import、调用或类型依赖。Acknowledgement 的通用 read projection 通过现有用户事实解析安全 display name，不把用户名冗余写成可变 acknowledgement 状态。

前端根据 `AuditCheckResult + FindingAcknowledgement` 计算播放状态：

1. 先播放 `SOURCE_PREPARATION`，再按 `displayOrder` 播放真实 findings。
2. PASS/GREEN 显示绿色完成标识与 `AuditCheckResult.createdAt`，文案为“核对完成时间”，短暂播放后自动进入下一条。
3. YELLOW 使用黄色感叹号。RED 可在 detail/risk badge 使用红色风险色，但 progress node 必须表达“检测到风险，需要知悉”，不得使用让用户理解为整个项目失败或流程中断的巨大红叉。
4. 遇到尚未 acknowledgement 的非 GREEN 结果时暂停，显示真实“我已知悉”控件。
5. POST acknowledgement 成功后必须重新 GET contract-owned F02 `ReviewProgress` 事实，确认 durable acknowledgement 已可读，再继续后续项；不得只切换 frontend local state。
6. 刷新后查找稳定顺序中的第一条未确认非 GREEN 结果；其之前的 SOURCE_PREPARATION、PASS 或已确认项组成已完成前缀。不存在未确认项时直接显示完成状态。
7. 服务端允许按 finding 独立确认，不持久化或强制校验“当前播放到第几步”。即使存在乱序确认，UI 仍暂停在最早未确认项。

审核记录使用右侧 Drawer，按以下事实组合，不查询通用 OperationAudit：

- `SOURCE_PREPARATION` 的安全时间投影形成“读取合同及比对来源”presentation event；
- `AuditCheckResult.createdAt` 形成“核对完成”记录；
- `FindingAcknowledgement.acknowledgedAt` 形成“用户已知悉”记录；
- 同时刻按 `displayOrder`、事件类型和事实 ID 稳定排序。

Drawer 可显示多条 finding/ack events，示例语义是“核对完成 · 检测到风险”以及“某用户 · 已知悉该风险”。它不直接查询 `AUDIT_OPERATION_LOG`，也不开发通用 OperationAudit 查询平台。

最终标题只能显示“审核完成”。风险汇总只统计 `riskLevel != GREEN` 的结果数量：

| 非 GREEN 数量 | UI summary |
| --- | --- |
| 0 | 无风险 |
| 1-2 | 低风险 / 黄色 |
| >= 3 | 高风险 / 红色 |

该汇总是 review summary，不是项目通过、不通过、批准或审批决定；acknowledgement 不改变原检查状态、风险级别或汇总数量。

## Finding Acknowledgement

`FindingAcknowledgement` 是 `review` 模块拥有的不可撤销 durable business fact，唯一关联 `AuditCheckResult.id`。每条检查结果最多存在一个 acknowledgement，不使用 active flag，不提供 update/revoke/delete。

最小字段：

| Field | Rule |
| --- | --- |
| `id` | 服务端 UUID |
| `checkResultId` | FK `AUDIT_CHECK_RESULT.ID`，唯一 |
| `acknowledgedBy` | 当前 `CurrentUser.userId`，FK `SYS_USER.ID` |
| `acknowledgedAt` | 服务端时间 |
| `requestId` | 当前 HTTP Request ID |
| `traceId` | 当前 Trace ID |
| `ruleVersion` | 从关联 result 复制的不可变快照 |

当前结果没有 result version，因此不伪造该字段；如未来 AuditCheckResult 引入版本，应另行加 migration 和并发语义。

确认资格按持久事实判断：PASS/GREEN 不允许确认；任何非 GREEN 结果需要“已知悉”。当前 F02 合法组合中，这对应 NOT_EVALUATED/YELLOW 与 FAIL/RED。若读取到相互矛盾的 status/risk 组合，服务端 fail closed，不能把异常数据猜成已确认或无需确认。

创建流程使用新的 durable idempotency scope，例如 `REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE`：

1. 指纹绑定 actor、checkResultId、taskId、ruleVersion；
2. 同一 key/fingerprint 成功重放返回原 acknowledgement；
3. key 复用但指纹不同返回 409；
4. 同一 finding 被另一个 key 或并发用户先确认时返回 409，不改写确认人；
5. acknowledgement insert、`REVIEW_FINDING_ACKNOWLEDGED` OperationAudit 和 idempotency success 在同一短事务中提交。

OperationAudit 的 resource type/id 使用 `AUDIT_CHECK_RESULT`/checkResultId，taskId 使用真实 `AuditTask.id`。OperationAudit 仅作为追加式审计证据，不是 UI acknowledgement 的读取来源。

## Project Model

Project 属于现有 `contract` bounded context，推荐在该边界内使用 `contract.domain.project`、`contract.application.project`、`contract.api.project` 组织；DM8/File adapters 仍位于现有 `integration` 边界。不得创建顶层巨大 `project` context。Project 使用 UUID 作为唯一身份，空白创建不要求请求体或业务字段。

用户点击“+ 新建项目”时立即 `POST /api/v1/projects`，成功后直接进入空白 Project detail；不得先弹出大型表单，也不得要求项目名称、项目编号、类目、供应商、合同或金额。初始 header 固定使用真实空值的 presentation：

- 项目名称：待识别；
- 项目编号：待识别；
- 项目类目：待提供；
- 主 CTA：上传项目资料。

最小模型：

| Field | Constraint |
| --- | --- |
| `id` | 非空 UUID |
| `createdBy` | 非空 CurrentUser ID |
| `createdAt` | 非空服务端时间 |
| `updatedAt` | 非空服务端时间 |
| `versionNo` | 非负，初始 0，用于 CAS |
| `projectName` | nullable |
| `projectNo` | nullable |
| `categoryCode` | nullable |
| `categoryName` | nullable |

v1 不持久化 Project status。页面状态从事实投影得到：

- 无 AVAILABLE ProjectFile：待上传；
- 有 AVAILABLE ProjectFile：已上传；
- 至少一个 parseable 文件为 PARSED：已解析；
- candidate count 大于 0：有候选合同；
- unsupported/failed 数量独立展示，不把整个 Project 标为失败。

API detail 返回这些派生计数/布尔值，数据库不保存重复状态。Project 业务编号 v1 不设唯一约束；Project 的稳定身份是 UUID，nullable 或重复候选编号不得阻止空白创建。

Project root 字段不会由不确定提取结果静默覆盖。提取值先作为 ExtractionResult 展示；用户可通过带 expected version 的 Project metadata PATCH 确认 name/no/category。未确认值继续为 null。

`versionNo` 只负责 Project metadata CAS 及确有必要的短事务一致性，不是 upload、ZIP scan 或 parse 的 lease。文件接入不得因 30 秒解析无条件阻塞同一 Project 的其它合法文件接入、读取或候选查看。

## ProjectFile Model

ProjectFile 是 Project 原始资料库存，和 ReviewDocument 完全独立。ProjectFile identity、metadata 和 ingestion/parse 状态进入 DM8；字节进入 FileStoragePort。

最小字段：

| Field | Meaning |
| --- | --- |
| `id` | ProjectFile UUID |
| `projectId` | 所属 Project |
| `originalFileName` | 安全展示名，不参与存储寻址 |
| `relativePath` | 规范化 archive 路径；直接上传使用安全文件名 |
| `storageKey` | opaque storage key |
| `sha256` | FileStoragePort 流式计算的小写 SHA-256 |
| `size` | 字节数 |
| `mimeType` | 服务端规范化 MIME；未知类型使用 `application/octet-stream` |
| `category` | ProjectFileCategory |
| `ingestionStatus` | `UPLOAD_PENDING / AVAILABLE / UPLOAD_FAILED` |
| `parseStatus` | `PENDING / PARSED / UNSUPPORTED / FAILED` |
| `parseDetailCode` | nullable 稳定原因码，不保存异常 message |
| `parentArchiveFileId` | nullable；ZIP child 指向 PACKAGE ProjectFile |
| `classificationVersion` | 确定性分类规则版本 |
| `versionNo` | 非负 CAS，为未来人工分类纠正保留并发边界 |
| `createdAt` | 服务端时间 |

ProjectFileCategory v1：

```text
CONTRACT_BODY
TENDER_DOCUMENT
BID_DOCUMENT
AWARD_NOTICE
OTHER
UNKNOWN
PACKAGE
```

不得复用 ReviewDocumentKind。未来 ProjectFile 进入 ReviewSession 时，只能由显式 promotion/binding 层做分类映射；该映射不在本轮实现范围。

OTHER/UNKNOWN 文件必须可列出、下载，并在格式支持时预览；它们不参与当前 F02 deterministic review。人工分类 correction API 不进入最小 v1，但 category 独立字段、classificationVersion 和 versionNo 使未来 CAS correction 不需要替换文件身份或 ReviewDocument 语义。

`ProjectFilePolicy` 必须把 security admission 与 parse capability 分成两个正交判断：

| Policy decision | v1 default |
| --- | --- |
| `DIRECT_UPLOAD_ALLOWED` | `.zip`、`.pdf`、`.docx` |
| `ARCHIVE_CHILD_ALLOWED` | `.pdf`、`.docx`、`.doc`、`.xls`、`.xlsx`、`.png`、`.jpg`/`.jpeg`、`.txt`；nested `.zip` 仅作为 opaque child 保存且不递归 |

默认 allowlist 之外没有“任意 OTHER”通道。未来增加其它 ordinary document 类型时，必须通过受审查的 policy 配置显式加入，并定义 extension、MIME、signature/container 验证和安全下载行为。`.exe`、`.dll`、`.com`、`.bat`、`.cmd`、`.ps1`、可执行 `.js`/`.vbs`、`.jar`、Windows shortcut、device/special file、symlink 及其它不能证明为普通安全业务文件的 entry 一律拒绝；`category=OTHER` 不能绕过 security policy。

允许安全保存不等于存在 parser。`.doc`、`.xls`、`.xlsx`、图片、TXT、nested ZIP 以及其它显式允许但 v1 无 Project parser 的 child 都是 `AVAILABLE + UNSUPPORTED`；解析不支持不得反推为安全拒绝，安全允许也不得假装解析成功。

## Storage

Project Intake 继续调用现有 `FileStoragePort.stage(input, sizeLimit)`、`promote`、`open`、`inspect` 和 `discard`。不新增 ProjectStoragePort，不暴露 filesystem path，不改变 FileSystemStorageAdapter 的 create-only 和 root confinement。

Project 使用独立类型安全配置 `audit.project-intake.*` 和 `ProjectFilePolicy`。默认值均可由环境变量覆盖：

| Property | Default |
| --- | --- |
| `max-upload-size` | 100MB |
| `max-extracted-file-size` | 50MB |
| `max-expanded-size` | 500MB |
| `max-archive-entries` | 200 |
| `max-path-depth` | 10 |
| `max-compression-ratio` | 100 |
| `max-relative-path-length` | 1024 characters |

Spring multipart transport ceiling 需要至少容纳 100MB Project upload，但 ReviewSession application policy 继续以现有 50MB 为上限；提高公共 transport ceiling 不得改变 ReviewSession service 的业务限制或 MIME validator。

直接文件与 ZIP 都使用 ProjectFile-owned durable ingestion，而不是 Project aggregate reservation：

1. 上传流先通过 `FileStoragePort.stage(input, projectLimit)` 得到 hash/size/header；
2. START 短事务 claim idempotency，创建 root `ProjectFile(UPLOAD_PENDING)`，写 `PROJECT_FILE_UPLOAD_STARTED`；result-resource identity 从此固定为该 ProjectFile；
3. promote、ZIP scan、classification、parse 和 extraction 在事务外执行；
4. COMPLETE 短事务将 root/children 和 extraction/candidate facts 收口，写 `PROJECT_FILE_UPLOAD_COMPLETED` 并完成 idempotency；
5. 普通失败由独立 FAILURE 短事务写 `UPLOAD_FAILED`、`PROJECT_FILE_UPLOAD_FAILED` 并收口 idempotency；
6. outcome uncertain 时由 `ProjectFile + IdempotencyRecord + ingestionStatus + storage inspect` 恢复，不能刷新后生成新 key。

文件接入不要求 Project expectedVersion，也不推进 Project.versionNo。两个不同文件操作可以并行拥有各自 immutable operation identity；同一幂等操作仍严格单写。Project metadata PATCH 继续独立使用 Project.versionNo CAS。可借鉴 ReviewSession 的 START/external IO/COMPLETE/FAILURE、idempotency、recovery 和 storage inspect，但不得机械复制 session aggregate version reservation。

ProjectFilePolicy 对 direct `.zip`、`.pdf`、`.docx` 以及每种允许的 archive child 校验 extension、declared MIME 和 magic/container structure。未知、disallowed 或 signature 不匹配的 direct upload 必须拒绝；ZIP child 也只按显式 allowlist进入系统。下载默认 `attachment`；只有 PDF/image 可沿用现有 authenticated inline preview，DOCX/DOC/Office/TXT/unknown 不在浏览器内联执行。

## ZIP Security

ZIP v1 不校验业务模板，只负责安全展开、确定性分类和文本解析。archive boundary 在所有 child ProjectFile 业务事实提交前完成全包扫描。

Plan B 在选择或编写 production ZIP adapter 前必须先执行 bounded technical capability check，以可执行 probe/test 和真实构造 archive 验证当前 JDK 17 public archive API 是否能可靠证明：normalized entry names、compressed/uncompressed byte accounting、CRC/truncation detection、encryption rejection、symlink/non-regular metadata detection、per-entry compression ratio 和 archive-wide limits。能力结论必须记录所用 JDK、归档实现、命令、fixture 和 observed result；不能从 API 名称或文档印象推断安全语义。

若 `java.util.zip` 对其中任一 production-critical invariant 无法可靠证明，Plan B 允许增加一个只服务 Project ZIP security/parser 的聚焦 archive 依赖，并优先评估 Apache Commons Compress。新增依赖必须锁定明确版本、记录 dependency reason，并在增加后运行 `clean verify`；它不得替换 `FileStoragePort`、PDFBox 或 POI，也不得顺带引入 Tika 等大而全解析框架，除非另行批准。禁止自行手写 ZIP central-directory binary parser 来规避依赖。最终 ZIP security tests 必须直接驱动选定真实库读取真实 archive bytes，不能只 mock `ZipEntry`、metadata 或 security decision。

规范化规则：

- 路径分隔符统一为 `/`，Unicode 规范化为 NFC；
- 拒绝空路径、NUL/control character、`.`/`..` segment、前导 `/`、UNC、Windows drive prefix；
- 规范化 collision key 使用 `Locale.ROOT` case fold；重复 normalized path 使整个 archive 失败；
- 每个 entry 都计入 max entries，目录层级和路径长度均受配置约束；
- 单 entry 展开字节、总展开字节和实时 compression ratio 在 streaming 过程中累计，超限立即停止；
- CRC、截断、加密或无法证明结构完整的 archive fail closed；
- 不按 entry path 在 filesystem 创建目录、文件或链接。entry bytes 只进入 FileStoragePort staging；
- 选定且已通过 capability check 的 archive 实现必须把 symlink 或其它非普通文件 entry 识别并直接拒绝。即使恶意 entry 不会被物化为 filesystem link，也不能用“未物化”替代 metadata detection 与 fail-closed rejection；
- nested ZIP 作为普通 child ProjectFile 保存，category 为 OTHER 或 UNKNOWN、parseStatus 为 UNSUPPORTED，不递归展开。
- executable/script/JAR/shortcut、symlink、device/special file 和任何不在 archive-child allowlist 的 entry 在分类前拒绝，不能因为名称含“附件”而降级为 OTHER 保存。

处理顺序：

1. 原始 ZIP stage，START 短事务创建 PACKAGE ProjectFile `UPLOAD_PENDING` 并 claim idempotency；
2. promote PACKAGE，随后只通过 storage stream 读取；
3. 单次 streaming scan 中校验 archive，并把 entry bytes 放入 staging；此时不创建 child ProjectFile 事实；
4. 全包安全校验通过后 promote child objects；
5. COMPLETE 短事务一次性创建 child metadata、解析事实/candidates，标记 PACKAGE/children AVAILABLE，并完成 idempotency/audit；
6. 普通失败以独立 FAILURE 短事务把 PACKAGE 标为 UPLOAD_FAILED；所有尚未 promote 的 staging handle discard，已存在的内容寻址对象按现有 retention 处理。

崩溃恢复从 idempotency result-resource PACKAGE ProjectFile 和 storage inspect 重新打开原始 ZIP，重复确定性 scan/parse；不依赖浏览器保留文件字节或新幂等 key。

## Classification

分类输入只包括 normalized archive relative path、目录名、文件名和 extension，不读取 AI 结果。规则版本化并采用固定优先级，避免多个关键词命中时因遍历顺序漂移：

1. top-level `.zip` → PACKAGE；nested ZIP → OTHER/UNKNOWN 且不展开；
2. `中标通知书`、明确的中标通知目录/文件名 → AWARD_NOTICE；
3. `投标`、`响应文件` → BID_DOCUMENT；
4. `招标`、`采购文件` → TENDER_DOCUMENT；
5. `合同`、`协议` → CONTRACT_BODY；
6. `补充材料`、`附件`、`其他` → OTHER；
7. 无确定命中 → UNKNOWN。

匹配前只做 Unicode、空白、大小写和分隔符 normalization，不做模糊语义推断。更高优先级命中覆盖低优先级命中，分类结果保存 classificationVersion。未来人工 correction 必须是独立 CAS 命令并写审计；v1 不实现该命令。

## Parsing

Project parser 归 `contract.application.project`，不得 import 或调用 `budget.application`、`budget.domain`、`BudgetMaterialParser`。允许复用 POI/PDFBox dependency、Money 和无业务归属的纯 normalization。

格式行为：

| Format | v1 behavior |
| --- | --- |
| text PDF | PDFBox 按页提取；保留 pageNumber |
| scanned/empty-text PDF | 文件 AVAILABLE，parseStatus UNSUPPORTED，显示“当前版本暂不支持文本解析” |
| DOCX | POI XWPF 提取 paragraphs/tables；paragraph 可保留 paragraphIndex，pageNumber 为 null |
| DOC | 文件 AVAILABLE，parseStatus UNSUPPORTED |
| nested ZIP / other | 文件 AVAILABLE，parseStatus UNSUPPORTED，不递归 |
| corrupt parseable file | 文件 AVAILABLE，parseStatus FAILED，Project 仍可使用 |

直接上传可大于 50MB、但不得超过 100MB；超过 Project parser 的 50MB 单文件限制时仍安全保存，标记 UNSUPPORTED。ZIP 内单 entry 超过 50MB 属于 archive security violation，整个 ZIP 拒绝。

解析和 extraction 在数据库事务外执行。SUPPORTED、UNSUPPORTED 和普通 parse failure 都能完成 upload 的 durable success；只有 archive/storage/integrity 失败使 upload command 失败。解析器不得访问网络，不记录文档全文、source excerpt、绝对路径或异常 message 到技术日志。

Project detail 的“提取文本”必须来自上述真实 parser 输出，文本可选择、复制；不得显示 mock 文本。PDF/image 原件使用现有 authenticated content preview，DOCX 只提供安全 attachment 下载，不在线执行 Office 内容。

## ExtractionResult

ExtractionResult 是 Project intake 候选事实及来源，不是正式 Contract 字段。每个可用候选值单独保存一条 result；同一 field 出现多个不同 normalized value 时全部保留，由 candidate detail 标记 conflict，不静默选择。

最小字段：

| Field | Rule |
| --- | --- |
| `id` | UUID |
| `projectId` | Project identity |
| `candidateId` | nullable；无法确定归属时保持 project-level |
| `sourceFileId` | 必填 ProjectFile identity |
| `extractionKey` | 必填；由确定性 extraction identity 生成，数据库唯一 |
| `factType` | 稳定字段代码 |
| `groupKey` | nullable；用于同一 line item 的字段分组 |
| `valueText` | nullable；原始/结构化文本值 |
| `valueAmount` | nullable DECIMAL(18,2)，金额不得使用浮点数 |
| `normalizedValue` | nullable 确定性 normalization 结果 |
| `sourceText` | 必填最小 excerpt，不保存无界全文 |
| `pageNumber` | nullable；PDF 可用 |
| `paragraphIndex` | nullable；DOCX paragraph 可用 |
| `sourceLocator` | 必填；parser traversal 中稳定的 source/occurrence locator |
| `extractionVersion` | 必填规则版本 |
| `createdAt` | 服务端时间 |

`ExtractionResult.id` 仍是业务行 identity，但 random UUID 本身不得承担 replay 去重。`extractionKey` 使用稳定 canonical encoding（含明确的 null token 与长度边界）绑定 `sourceFileId + extractionVersion + factType + nullable groupKey + sourceLocator`，再生成固定格式 SHA-256 key。`sourceLocator` 必须来自确定性 traversal，例如 PDF page/文本 occurrence ordinal，或 DOCX part/block/table-row-cell/paragraph/occurrence ordinal；不得使用随机数、当前时间、数据库自增顺序或本次运行的遍历对象 identity。`candidateId` 不进入 key，后续可靠归属不能改变同一 source finding 的身份。

数据库以 `UNIQUE(EXTRACTION_KEY)` 执行该 invariant。Recovery/replay 遇到已有 key 时必须读取并逐字段验证上述 identity 输入；相同则复用现有 result，不同则按 collision/integrity error fail closed，不能覆盖。改变 extraction 算法或 locator 语义必须增加 `extractionVersion`。

v1 fact types 至少覆盖：

```text
PROJECT_NAME, PROJECT_NO,
CONTRACT_NO, CONTRACT_NAME, PARTY_A, PARTY_B,
SUPPLIER_NAME, CREDIT_CODE,
AMOUNT_NUMERIC, AMOUNT_UPPERCASE,
LINE_ITEM_KEY, LINE_ITEM_NAME, LINE_ITEM_QUANTITY,
LINE_ITEM_UNIT_PRICE, LINE_ITEM_DECLARED_AMOUNT,
AWARD_AMOUNT, AWARD_SUPPLIER, AWARD_SUPPLIER_CREDIT_CODE
```

提取只使用明确 label、表头、邻接文本和 BigDecimal/uppercase parser。字段缺失表现为 candidate nullable/MISSING；不存在的值不生成 result，不用空字符串、默认金额或推测供应商伪造事实。UNKNOWN、MISSING、CONFLICT 和 FAILED 必须保持不同语义。

## ContractCandidate

每个确定分类为 CONTRACT_BODY 且成功取得文本的 ProjectFile 最多创建一个 ContractCandidate；一个 Project 因而可以拥有 0..N candidates，但同一 CONTRACT_BODY `ProjectFile` 在 restart/recovery/replay 后仍是同一候选来源，不得追加第二个 candidate。候选有自己的 UUID、projectId、sourceContractFileId、nullable 合同字段、versionNo、createdAt/updatedAt 和 promotionState：

```text
DRAFT -> PROMOTED
```

PROMOTED 只表示已由用户确认并产生一个正式 Contract，不表示合同审核通过。candidate 保存 promotedContractId、promotedBy、promotedAt，且不可二次 promotion。

Candidate 可以包含 missing/conflicting facts。line item 候选使用独立 candidate line item 结构；award amount/supplier/credit code 是 nullable candidate facts。每个 candidate detail 返回相关 ExtractionResult，使 UI 能展示证据和冲突。

数据库必须执行 candidate source identity：若 `PROJECT_FILE.ID` 在实际 DM8 schema 中是全局 ProjectFile identity，使用回归面更小的 `UNIQUE(SOURCE_CONTRACT_FILE_ID)`；只有当实际 schema 证明 file identity 仅在 Project 内唯一时，才使用 `UNIQUE(PROJECT_ID, SOURCE_CONTRACT_FILE_ID)`。Plan B 的 Implementation Plan 必须在读取当时 main 的实际 DM8 schema 后选择其中一种并说明理由，不得仅靠 application pre-check 声称 invariant 已成立。

支持材料只有在存在明确的稳定关联时才能归 candidate，例如材料中提取到与 candidate 完全一致的 contractNo。仅凭“Project 只有一个 candidate”、目录邻近或供应商名称相似不得猜测。无法确定时 ExtractionResult.candidateId 为 null，保留 project-level relationship，供 promotion 时人工选择/确认。

Candidate 与 ProjectFile 都是保留证据的 durable facts；Pre-F04 不提供 delete。若以后需要归档，由甲方明确 retention/archival policy 后单独设计，不能以“清理列表”为由追加 soft delete。

## Project-Contract Relationship

v1 正式决定采用 `PROJECT_CONTRACT` association；不再保留“association 或 Contract.projectId”二选一，也不给现有 `CONTRACT` 表、Contract record/constructor/mapper/API 增加 `PROJECT_ID`。

当前 Contract 是 F02 必填结构化快照，Repository 只有读取语义，DM8 mapper、local fixture、F02 rule fixtures 和 ReviewSession 创建都依赖现有构造形态。association 能在不改变这些读取和规则路径的前提下建立真实 1:N 关系，回归面最小。

概念约束：

- PK `(PROJECT_ID, CONTRACT_ID)`；
- `CONTRACT_ID` 唯一，保证一份正式 Contract 至多属于一个 Project；
- `CANDIDATE_ID` 非空且唯一，记录 promotion 来源；
- FK 指向 PROJECT、CONTRACT、CONTRACT_CANDIDATE；
- 保存 `createdBy`、`createdAt`；
- relation 和 Contract/candidate promotion 在同一事务提交。

现有 Contract 查询仍返回原聚合。Project detail 通过独立 ProjectContractQueryPort 读取关联 Contract summary，不把 Project 数据注入 F02 Contract domain。

## Promotion

Promotion 是用户确认后的唯一 Contract 写入路径，不在 extraction 时自动调用。

当前 `ContractRepository`/`ContractMapper` 只有读取语义。Plan C 应增加 promotion application 所拥有的最小 Contract creation Port/Adapter，只暴露“创建完整 Contract 快照”的 insert-only 语义；不得把 generic save/update/merge 加到现有读取 Port，也不得改变 direct F02 的读取聚合。

请求包含 candidate expectedVersion 和用户确认后的 Contract payload。最低校验与现有 Contract invariant 一致：

- contractNo、contractName、contractType、partyA、partyB 非空白；
- contractAmount 非空且非负，使用 BigDecimal；
- line items 可为空，但已提供项必须具有合法 key/name/quantity/unitPrice/declaredAmount；
- award notice 可为空；提供时金额非负，supplier 等按现有 domain 校验；
- amountInWords、partyBCreditCode 可为空；
- 系统设置 Contract status=`READY_FOR_REVIEW`、versionNo=0、createdAt/updatedAt；
- 用户确认的 payload 不反向覆盖 ExtractionResult，只形成正式 Contract 快照。

Promotion 使用 durable idempotency scope，例如 `PROJECT_CONTRACT_CANDIDATE_PROMOTE`，在一个短事务内：

1. 校验 Project/candidate identity、candidate DRAFT 和 expectedVersion；
2. claim idempotency 并绑定 actor/project/candidate/version/confirmed payload fingerprint；
3. insert Contract、line items、award notice；
4. insert PROJECT_CONTRACT association；
5. CAS candidate DRAFT → PROMOTED；
6. append `PROJECT_CONTRACT_CANDIDATE_PROMOTED` OperationAudit；
7. mark idempotency SUCCEEDED。

相同 key/fingerprint 成功重放返回同一 Contract。contractNo 已存在、candidate 已被其它操作 promotion、candidate version stale、association 冲突或相同 key 不同 payload 均返回稳定 HTTP 409；任何冲突都不得 UPDATE/MERGE/覆盖既有 Contract。

`Contract + line items + award notice + PROJECT_CONTRACT + candidate PROMOTED + PROJECT_CONTRACT_CANDIDATE_PROMOTED + idempotency completion` 必须属于同一业务提交结果；任一 insert/audit/idempotency completion 失败都整体回滚。

## F02 Integration

Promotion 成功后前端使用返回的 Contract ID 调用现有 direct F02 API。该路径保持：

```text
Project candidate confirmation
  -> Contract + PROJECT_CONTRACT committed
  -> POST /api/v1/contracts/{contractId}/agent-review
  -> existing synchronous five deterministic rules
  -> AuditTask + AuditCheckResult
  -> frontend progressive replay + acknowledgement
```

不修改 `ContractReviewService` 的 START/COMPLETE/FAILURE 边界，不引入 workflow，不从 ProjectFile 自动构造 ReviewDocument。ReviewSession 仍只允许 `PREPARING_MATERIALS -> READY`，不创建 AuditTask、不触发 F02。

Plan C 可增加只读 `FindingSourceProjection`，但不修改 F02 rule persistence，也不向 `AUDIT_CHECK_RESULT` 伪造 evidence。投影链固定为：

```text
AuditCheckResult(ruleCode, itemKey)
  -> AuditTask.sourceId = promoted Contract
  -> PROJECT_CONTRACT.candidateId
  -> matching ExtractionResult
```

只有在能够确定性证明当前正式 Contract value 与 promotion 时对应的 extracted/confirmed value 一致时才返回 source anchor。规则映射至少覆盖金额大写/数字金额、line item quantity/unitPrice/declaredAmount、award amount、partyB/creditCode 与 award supplier/creditCode。若 promotion payload 修改了值、候选来源冲突、支持材料无法可靠归候选、Contract 后续版本已变化，或旧 Contract 不来自 Project，则 `source=unavailable`，UI 显示“暂无可靠来源定位”。禁止根据相似文本、Project 只有一个 candidate 或 UUID 形状猜测来源。

## API

所有 API 使用 `/api/v1`、Session Cookie、CSRF、CurrentUser、ApiResponse、RequestId/TraceId 和现有角色授权；不引入 JWT。所有有副作用命令要求 `Idempotency-Key`，客户端在 outcome uncertain/IN_PROGRESS 时保持 operation identity 不变。

### Review acknowledgement and F02 progress ownership

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/audit-check-results/{checkResultId}/acknowledgement` | 创建不可撤销 acknowledgement；body 为空 |
| GET | `/api/v1/audit-tasks/{taskId}/finding-acknowledgements` | 批量读取该 task 的 acknowledgements |

上述 acknowledgement POST/GET 继续由 `review` API 拥有，因为其语义只依赖 `AuditCheckResult`、`FindingAcknowledgement` 和 task ownership。Plan A 实施时必须重新检查当时 main 的 F02 Controller/API 风格，由 `contract` API/Application 暴露只服务 F02 的 ReviewProgress route；本设计不强迫注册 generic `GET /api/v1/audit-tasks/{taskId}/review-progress`，该路由不得成为把 F02 presentation 逻辑下沉到 `review` 的理由。最终 route naming 是对现有 F02 API 风格的兼容性决定，不改变本设计已经固定的 projection 字段与语义。

现有 F02 FindingResponse 增加 `createdAt`；`displayOrder`、SOURCE_PREPARATION 和 acknowledgement 由 contract-owned F02 review-progress projection 返回。前端以 result ID join，不建立通用 OperationAudit timeline endpoint。Plan C 只在可靠时为 finding progress/detail 增加 nullable `source`；无可靠来源时明确返回 unavailable reason，不省略成“可能存在”。

### Project

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/projects` | 无业务 body，立即创建空白 Project |
| GET | `/api/v1/projects?page=&size=&keyword=` | 稳定分页 Project list |
| GET | `/api/v1/projects/{projectId}` | Project detail 和派生 intake facts |
| PATCH | `/api/v1/projects/{projectId}` | 用户确认 nullable metadata；包含 expectedVersion |
| POST | `/api/v1/projects/{projectId}/files` | 统一 multipart package/file upload；不使用 Project expectedVersion 作为 ingestion lease |
| GET | `/api/v1/projects/{projectId}/files` | ProjectFile list |
| GET | `/api/v1/projects/{projectId}/files/{fileId}/content` | 身份/归属校验后的 preview/download |
| GET | `/api/v1/projects/{projectId}/contract-candidates` | candidate list |
| GET | `/api/v1/projects/{projectId}/contract-candidates/{candidateId}` | candidate facts/conflicts/evidence |
| POST | `/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote` | 用户确认并创建正式 Contract |

POST/PATCH 成功响应继续使用当前 ApiResponse 风格。列表 page 从 1 开始，size 上限 100。所有 nested resource 必须验证其 project/task ownership，禁止仅因 UUID 存在就跨项目读取。

Pre-F04 不提供任何 Project/ProjectFile/Candidate/Contract/Acknowledgement DELETE API，不提供 archive endpoint，也不扩展 ReviewSession public delete。Project upload 只接受 direct `.zip/.pdf/.docx`；archive child policy 是服务端内部 intake rule，不产生“上传危险任意 OTHER”的旁路。

## Frontend

App shell 继续使用现有内部 state navigation，最小增加 Project list、Project detail 和 activeProjectId，不引入 React Router。

Review/F02：

- 使用真实 SOURCE_PREPARATION 时间投影、`AuditCheckResult.createdAt`、acknowledgedBy/At；
- 逐项 progress 显示 `current / total`，其中 total 始终为 `1 + findings.size`；
- PASS 使用绿色完成视觉；YELLOW 用黄色感叹号；RED 的 progress node 仍表达“风险待知悉”，红色只用于 detail/risk badge，不能显示整个项目失败的大红叉；
- acknowledgement POST 成功后重新读取 facts，再继续播放；
- 右侧 Drawer 展示结果/确认组合的审核记录；
- 最终只显示“审核完成”和风险 summary。

页面不得出现“审核通过”“项目通过”“项目不通过”“批准”“不批准”。0 个非 GREEN 显示“无风险”，1-2 个显示“低风险/黄色”，3 个及以上显示“高风险/红色”；这是 review summary，不是审批结论。

Project：

- list 顶部提供“+ 新建项目”；成功后立即进入返回 Project ID 的 detail；
- 不弹创建表单；空白 detail 显示“项目名称：待识别、项目编号：待识别、项目类目：待提供”，主操作只有“上传项目资料”；
- 一个入口接受 ZIP/PDF/DOCX，不再要求分别进入合同、招标、投标、中标通知书上传入口；
- 上传后按“合同正文、招标文件、投标文件、中标通知书、其他材料”展示真实分类，并单独显示 UNKNOWN/unsupported/failed 提示计数；
- candidate detail 显示 missing/conflicting/source evidence，promotion 前让用户确认正式 Contract 必填字段；
- Project header 固定为项目名称、项目编号、项目类目；Contract summary 固定为合同编号、合同名称、甲方、乙方/供应商、统一社会信用代码；
- Project category、供应商手机号、黑名单、围串标/关联分析等没有事实时只显示“暂无”“待提供”或“待确认”，不得构造手机号、黑名单命中或串标结果，也不得引入 F11；
- 原始文件与提取文本分栏/切换必须语义分离。PDF/image 使用现有 authenticated preview；DOCX 提供 attachment；提取文本来自真实 parser、可选择复制；
- 结构化信息按“项目信息、合同基本信息、合同金额、合同明细、中标信息、供应商信息”分组，每个字段展示 value、nullable source 和状态；金额检查明确展示系统取值、来源和比较目标；
- source anchor 可用时定位到 ProjectFile/page/paragraph/excerpt；不可证明时显示“暂无可靠来源定位”；
- 不从 showcase/demo 数据填充真实 Project。

金额 presentation 统一：阿拉伯数字人民币显示为 `¥128,000.00`，不改变 `BigDecimal`/domain value；中文大写金额展示已有正式文本，不人为增加“人民币”前缀。若正式原值本身含“人民币”，raw fact 不被静默修改，presentation formatter 可另产生符合产品文案的 `displayValue`，并保留 raw/source evidence。

Project Intake 分类区域不显示红星、红点、“重点材料”或“特别关注”等未经批准的业务结论。若 Plan A 触及当前 ReviewSession 材料页面，可去掉其未经确认的红色星号 presentation，但服务器端 `CONTRACT_BODY + AWARD_NOTICE` READY invariant 保持不变。

Review record 只使用右侧 Drawer/aside，不用覆盖整页的大 Modal。Plan A/Plan C 涉及页面采用政企/内审系统的中性浅色、蓝灰内容面板，减少相关区域的纯黑/霓虹视觉；不全局重做 Dashboard、F01、F03 或主题系统，保持现有 shell 兼容。

列表同时出现创建时间和更新时间时必须使用两个明确字段，不能合并成“时间”。Review finding 显示核对完成时间；acknowledgement 显示确认人和确认时间。

## Persistence

概念表及关键约束：

| Table | Key constraints |
| --- | --- |
| `REVIEW_FINDING_ACKNOWLEDGEMENT` | PK ID；UNIQUE CHECK_RESULT_ID；FK result/user；immutable |
| `PROJECT` | PK ID；nullable name/no/category；FK createdBy；VERSION_NO >= 0 |
| `PROJECT_FILE` | PK ID；FK project；same-project composite self-FK parentArchive；category/ingestion/parse checks；storage/hash/size metadata |
| `PROJECT_EXTRACTION_RESULT` | PK ID；FK project/sourceFile/nullable candidate；deterministic EXTRACTION_KEY；UNIQUE EXTRACTION_KEY；source locator/version；amount DECIMAL(18,2) |
| `CONTRACT_CANDIDATE` | PK ID；FK project/source file；UNIQUE sourceContractFileId，或在 file identity 仅 project-scoped 时 UNIQUE(projectId, sourceContractFileId)；DRAFT/PROMOTED check；CAS version；nullable facts |
| `CONTRACT_CANDIDATE_LINE_ITEM` | PK ID；FK candidate；stable item/group ordering；BigDecimal columns |
| `PROJECT_CONTRACT` | PK project+contract；UNIQUE contractId/candidateId；FK all identities |

PROJECT_FILE 应有 `(PROJECT_ID, ID)` 唯一键以支持 same-project parent archive FK；archive child 在同一 parent 下的 normalized relative path 唯一。Storage object 继续不进入 DM8 BLOB。

Candidate/Extraction 的 application lookup 是优化和错误表达，不是唯一性证明；最终防线必须是上述 DM8 UNIQUE constraints。COMPLETE transaction 必须按 candidate source identity 与 extractionKey 读取或插入：已存在且 immutable identity/facts 一致时复用，缺失时插入；唯一冲突后重新读取并验证。任何同 key 不同 identity/facts 都 fail closed，不允许 UPDATE/MERGE 成另一结果。

Project create、upload、metadata update、promotion 和 acknowledgement 使用现有 IDEMPOTENCY_RECORD 的新 scope；Redis/TongRDS 不作为事实源。每次 implementation branch 都以当时 main 的实际 schema 和 DM8 能力复核 DDL/check/FK/index，而不是由本设计声称真实 DM8 已验证。

## Security/Audit

- 沿用现有 Session Cookie、CSRF、role authorization 和 SpringSecurityAuthContext；无 JWT。
- Project/File/Candidate nested API 必须先验证父子归属，防止 IDOR。
- 上传不信任客户端文件名、MIME、路径、size 或 archive metadata。
- 只有安全 PDF/image 可 inline；DOCX/DOC/unknown 使用 attachment 和安全 content type。
- Archive rejection、parse unsupported 和 parse failed 使用稳定 safe reason；unsupported 不是 Project failure。
- Pre-F04 不增加任何删除授权或删除路由；现有 ReviewSession public delete withdrawal 保持不变。

## Audit and Observability Architecture

Pre-F04 严格分成以下五层。任一层都不能替代另一层。

### A. Durable Business Facts

DM8 中用于刷新、重启和追责后恢复当前业务状态的正式事实包括：

- `AuditCheckResult`；
- `FindingAcknowledgement`；
- `Project`；
- `ProjectFile`；
- `ExtractionResult`；
- `ContractCandidate`；
- `ProjectContract`。

Contract promotion 产生的现有 Contract/line/award facts 仍是 DM8 正式事实。播放 cursor、console log、FlowDebug 或 OperationAudit detail 都不能代替上述业务状态；Drawer 也不得从日志反推 acknowledgement。

### B. Durable Operation Audit

继续复用现有 `OperationAuditPort` 和 `AUDIT_OPERATION_LOG`，不得建立第二套 OperationAudit abstraction。它回答 who、when、what operation、which resource、result、requestId、traceId，但不是业务详情查询表。

Pre-F04 最少定义这些事件：

```text
REVIEW_FINDING_ACKNOWLEDGED
PROJECT_CREATED
PROJECT_METADATA_CONFIRMED
PROJECT_FILE_UPLOAD_STARTED
PROJECT_FILE_UPLOAD_COMPLETED
PROJECT_FILE_UPLOAD_FAILED
PROJECT_CONTRACT_CANDIDATE_PROMOTED
```

只有未来真正实现人工分类纠正时才增加 `PROJECT_FILE_CLASSIFICATION_CHANGED`；本轮不得为不存在的用户动作写事件或 UI。

关键业务事实与对应 OperationAudit 必须在能够保证一致性的同一短事务提交。Acknowledgement 的 `FindingAcknowledgement insert + REVIEW_FINDING_ACKNOWLEDGED + idempotency completion` 原子提交。Upload START/COMPLETE/FAILURE 各自把 ProjectFile/idempotency 状态与对应 audit 原子提交。Promotion 的 `Contract + line/award + ProjectContract + Candidate PROMOTED + PROJECT_CONTRACT_CANDIDATE_PROMOTED + idempotency completion` 原子提交。Project create 和 metadata confirmation 也分别把业务事实、audit、幂等 completion 置于同一提交结果。

### C. Authentication Audit

继续使用现有 `AUTH_LOGIN_AUDIT` 保存认证安全事实，不拿 OperationAudit 或 FlowDebug 代替。概念上覆盖 login success、login failure、account locked、logout；实现时复用当前源码事件命名 `LOGIN_SUCCESS`、`LOGIN_FAILED`、`ACCOUNT_LOCKED`、`LOGOUT`，不为 Pre-F04 重命名或复制认证框架。

### D. FlowDebugLogger

`FlowDebugLogger` 是 technical flow observability，不是 durable business audit。实施时复用现有 `event/module/layer/operation + additionalFields` 风格和现有 logger abstraction，不另造 logger。

覆盖点包括 Application entry、Repository read/write、Storage stage/promote、transaction enter/commit、ZIP scan、classification、parse、extraction、recovery、candidate promotion 和 acknowledgement transaction。requestId/traceId 继续由现有 MDC 关联；userId 仅在安全可得时记录，resource identity 必须使用真实语义 ID。

建议的安全 flow events：

| Plan | Events |
| --- | --- |
| A | `REVIEW_ACK_START`, `REVIEW_ACK_TX_ENTER`, `REVIEW_ACK_TX_COMMIT`, `REVIEW_ACK_CONFLICT` |
| B | `PROJECT_CREATE_START/COMPLETE`, `PROJECT_UPLOAD_STAGE_START/COMPLETE`, `PROJECT_UPLOAD_START_TX_ENTER/COMMIT`, `PROJECT_ARCHIVE_SCAN_START/COMPLETE/REJECTED`, `PROJECT_PARSE_START/COMPLETE/UNSUPPORTED/FAILED`, `PROJECT_UPLOAD_COMPLETE_TX_ENTER/COMMIT`, `PROJECT_UPLOAD_FAILURE_TX_ENTER/COMMIT`, `PROJECT_UPLOAD_RECOVERY_*` |
| C | `PROJECT_PROMOTION_START`, `PROJECT_PROMOTION_TX_ENTER`, `PROJECT_PROMOTION_TX_COMMIT`, `PROJECT_PROMOTION_CONFLICT` |

可记录：Project ID、ProjectFile ID、Candidate ID、真实 AuditTask ID、AuditCheckResult ID、文件分类、文件 size、hash 是否匹配的 boolean、entry count、parsed count、duration、version、status/outcome 和 stable safe reasonCode。`TASK_ID` 字段只能放真实 AuditTask ID，不能用 Project/File/Candidate/trace UUID 冒充。

禁止记录：密码、Cookie、Session ID、CSRF Token、Authorization、数据库凭据、完整 JDBC URL、API Key、Idempotency-Key 原值、完整 ZIP entry 内容、完整文档正文、无界 sourceText、文件 bytes、filesystem absolute path、原始 exception message。安全异常诊断只保留类型、稳定 reasonCode 和既有 safe stack frame。

### E. Technical HTTP / Error Logs

继续复用 `RequestAuditLoggingFilter`、`GlobalExceptionHandler` 和标准 SLF4J error logging。HTTP access 记录 method、path、status、duration、安全可得的 user identity、requestId/traceId；5xx 记录 exception type、root cause type 和 safe stack frame。不得因一个请求已有 `HTTP_OUT` 就再写一条内容重复的 OperationAudit；只有发生已定义的关键业务动作才写 durable OperationAudit。

## Error Handling

继续通过当前 GlobalExceptionHandler 和 ApiResponse 表达错误，不增加新的 envelope：

| Condition | HTTP semantics |
| --- | --- |
| Project/file/candidate/result 不存在或父子不匹配 | 404，不泄露其它资源存在性 |
| 非法字段、文件名、MIME/magic、unsafe archive | 400 |
| upload/entry/expanded/count/depth/ratio limit | 413 |
| stale version、idempotency conflict/in-progress、candidate already promoted、contractNo/association conflict、finding already acknowledged | 409 |
| 安全允许的 `.doc`、扫描 PDF 或 archive child 无 parser | HTTP 200；文件 AVAILABLE + parseStatus UNSUPPORTED |
| direct 非 ZIP/PDF/DOCX、危险或无法证明安全的 archive child | 400 拒绝，不创建 AVAILABLE child |
| 文本 parser 普通失败 | HTTP 200；文件 AVAILABLE + parseStatus FAILED，保留安全原因码 |
| storage integrity/unknown | fail closed，不猜成 MISSING 或成功 |

同一 candidate promotion 绝不通过 UPDATE、MERGE 或 retry 覆盖既有 Contract。UNKNOWN、FAILED、MISSING 和 UNSUPPORTED 分别展示，不互相替代。

## Restart/Recovery

- Acknowledgement 和 AuditCheckResult 位于 DM8；刷新/重启后通过 task results + acknowledgements 重建 UI，无 playback cursor。
- Project 和所有 metadata/candidate/extraction facts位于 DM8；文件通过 storageKey reopen/inspect。
- Project upload 使用 durable idempotency result-resource 指向 root ProjectFile。START 后进程终止时，恢复逻辑验证 object size/SHA，再完成或安全失败；storage UNKNOWN 保持 IN_PROGRESS。
- 恢复只根据 ProjectFile/idempotency/ingestion/storage facts，不持有或恢复 Project.versionNo lease，也不阻止其它独立文件操作。
- ZIP child/extraction 可以从已持久化 PACKAGE object 重新确定性展开/解析；恢复不要求浏览器重传或生成新 key。
- Parseable file 的 Unsupported/Failed 是终态事实；PENDING 只有尚未收口的命令。恢复重复解析不得重复创建 candidates/results，完成事务必须按 result-resource、candidate source unique identity 和 extractionKey unique identity 幂等。
- 对“第一次 COMPLETE outcome unknown -> process restart -> reopen source -> reparse”的路径，recovery 必须先按 `sourceContractFileId` 读取/复用 ContractCandidate，再按每个 deterministic `extractionKey` 读取/复用 ExtractionResult；若第一次 COMPLETE 已提交，第二次不得新增行，若第一次未提交，则只完成一次缺失事实。读取到相同 key 但 immutable identity/facts 不一致时保持 UNKNOWN/失败收口并告警，不能覆盖或猜测成功。
- `local` memory repository 仍是 process-scoped 测试 seam，不作为 durability 证明。

## Test Strategy

所有生产 critical invariant 都要有 happy-path 与 adversarial/regression test。

Review acknowledgement：

- PASS/GREEN 拒绝确认；NOT_EVALUATED/YELLOW、FAIL/RED 可确认；
- 一 result 一 acknowledgement 唯一约束、双用户并发竞争、同 key replay、不同 fingerprint 409；
- acknowledgement、OperationAudit、idempotency 同事务成功/回滚结构；
- `SOURCE_PREPARATION` 不是 result、无 risk/ack、不写表，total=`1+findings.size`，0/1/N findings 都不写死六步；
- SOURCE_PREPARATION 时间只来自 AuditTask 安全投影，Finding createdAt/displayOrder 稳定；
- 模块依赖检查证明 `review` 不 import/call `contract`；F02 rule title/order、SOURCE_PREPARATION 和完整 ReviewProgress 只由 contract Application/API assembly，acknowledgement POST/GET 仍为 review-owned；
- refresh 后 progressive state、POST 后强制 GET、无 cursor、乱序 ack、0/1/2/3 个非 GREEN 风险边界；
- Drawer timeline 只来自 source presentation/result/ack，不调用 OperationAudit query，文案不出现批准/通过/不通过；
- `REVIEW_FINDING_ACKNOWLEDGED` audit 与 business fact 同事务；
- FlowDebug 覆盖 ack application/transaction/conflict，字段安全且不含 idempotency key、security token 或 detail/sourceText。

Project Intake Core：

- 空白 Project nullable fields、CurrentUser、CAS、分页稳定性；
- FileStoragePort reuse、Project policy 与 ReviewFileValidator 隔离；
- direct allowlist 只接 ZIP/PDF/DOCX；archive-child safe allowlist 与 executable/script/JAR/shortcut/symlink/special-file 拒绝；
- ZIP capability gate 用真实 archive fixtures 验证选定 JDK/第三方库对 normalized names、compressed/uncompressed accounting、CRC/truncation、encryption、symlink/non-regular metadata、ratio 和 archive-wide limits 的实际行为，不允许 mock metadata 代替；
- PDF/DOCX success，安全允许的 DOC/scanned PDF/XLS/XLSX/image/TXT/nested ZIP 保存为 UNSUPPORTED，parse failure 不使 Project 失败；
- ZIP traversal、absolute/drive/UNC path、duplicate normalized path、symlink/non-regular、nested ZIP、entry/depth/path/single/total/ratio limits、CRC/truncation/encryption；
- ZIP 任一晚到恶意 entry 时不产生任何 child business fact；
- crash points：START 前后、PACKAGE promote 后、scan 中、child promote 后、COMPLETE outcome unknown；
- 多个独立 upload 不使用 Project.version 长 lease，metadata CAS 与 ingestion identity 互不覆盖；
- deterministic classification priority/version；
- missing/conflicting extraction、不伪造、source file/page/paragraph/version；
- 多 CONTRACT_BODY 产生多个 candidates，无法确定 supporting association 时保持 project-level；
- 第一次 COMPLETE outcome unknown 后 restart/reparse，按 sourceContractFileId 与 extractionKey 证明 candidate/result 行数不增长、内容不重复；并覆盖并发 recovery 的 UNIQUE conflict-read-verify 路径；
- `PROJECT_CREATED`、`PROJECT_FILE_UPLOAD_STARTED/COMPLETED/FAILED` OperationAudit 覆盖并与相应事务一致；
- FlowDebug 覆盖 stage/scan/parse/recovery 和 stable reasonCode，断言不含文档正文、excerpt、绝对路径、原始异常 message 或 Idempotency-Key。

Contract Promotion/UI：

- minimum validation、BigDecimal、contractNo conflict 409、stale candidate、double/concurrent promotion；
- Contract + line/award + PROJECT_CONTRACT + candidate state + audit + idempotency 原子性；
- 一个 Project 关联多个 Contract，一份 Contract 不可关联多个 Project；
- promotion replay 返回同一 Contract，任何失败不污染 CONTRACT；
- `PROJECT_CONTRACT_CANDIDATE_PROMOTED` audit 与 promotion business transaction 同进退，rollback 不留下孤立 audit 或 relation；
- FlowDebug promotion start/tx/commit/conflict 可追踪且只含安全字段；
- FindingSourceProjection 只在 promoted/confirmed value 可证明相等时返回 anchor，修改值、冲突、legacy Contract 和后续版本变化均 unavailable；
- 现有 Contract constructor/mapper/F02/ReviewSession 回归保持通过；
- frontend create→detail、统一 upload、无红星/假重点、nullable placeholders、真实原件/提取文本、candidate evidence、promotion→direct F02；
- Session/CSRF、401/403、IDOR、multipart 413 和 userFacingError。

每个计划至少运行 `backend\mvnw.cmd clean verify`；涉及 frontend 的计划同时运行 `frontend\npm.cmd run build`。真实 DM8 DDL/事务、目标 filesystem 和浏览器 runtime 在对应 implementation 完成前保持 NOT VERIFIED，并按环境条件单独取证。

## Migration Policy

设计和实现计划不得预占固定版本号。统一使用：

```text
NEXT_AVAILABLE_VERSION_AT_IMPLEMENTATION_TIME
```

每个实施分支创建 migration 前必须从已更新的 main 重新扫描 `db/dm8/V*.sql`，协调 F03 和并行 feature 的编号，只增加新 migration，禁止修改 V001-V008。Plan A、B、C 顺序合入后，后一个计划从最新 main 重新取号，不沿用本设计中的假设。

## Regression Protection

实施过程必须保持以下不变量：

- F01 business rules、F01 parsers、F03 business 不修改；
- F02 deterministic rules、AuditTask lifecycle、durable idempotency 和 short transaction structure 不修改；
- ReviewSession 仍为 `PREPARING_MATERIALS -> READY`；
- ReviewDocument append-only、MaterialBinding current-slot 和 ReviewSession MIME invariant 不修改；
- ProjectFile 与 ReviewDocument 不合并；ZIP 不映射成 ReviewDocument OTHER；
- ReviewProgress assembly、SOURCE_PREPARATION、F02 title/order mapping 留在 contract，review 不依赖 contract；
- FileSystemStorageAdapter 不增加 overwrite/copy fallback，不泄露路径；
- login/session/CSRF/CORS 基线不替换为 JWT；
- OperationAudit 不成为 acknowledgement/query UI 的唯一事实；
- FlowDebug 只用于可开关技术观测，OperationAudit 只记录 durable operation evidence，两者都不替代 Project/Candidate/Extraction/Acknowledgement business facts；
- Project parser 不依赖 budget 模块；
- 不引入 workflow、真实 OCR/AI、模板校验或隐式 Contract promotion；
- 历史 migration 不修改，Backend B known issues 不夹带修复。

高风险共享改动包括 global multipart ceiling、Contract write path、Idempotency scope、F02 FindingResponse、App.tsx/api types 和 migration allocation；必须由对应计划提供定向回归测试。

## Engineering Safety Self-Review

- `ProjectFile != ReviewDocument`；Project ZIP/child 不进入 ReviewDocument OTHER。
- Project parser 独立于 Budget parser，只复用无业务归属的 library/value object。
- `review` 只拥有通用 review/acknowledgement facts 与 read model；F02 ReviewProgress/SOURCE_PREPARATION/title/order/assembly 全部 contract-owned，不存在 `review -> contract` dependency。
- `SOURCE_PREPARATION` 是只读 presentation item，不是 AuditCheckResult。
- ContractCandidate 以 source Contract ProjectFile 的数据库 UNIQUE identity 防止恢复重复。
- ExtractionResult 以绑定 source/version/fact/group/locator 的 deterministic extractionKey 和数据库 UNIQUE constraint 防止恢复重复。
- ZIP production adapter 必须先通过真实库 capability gate；不可证明的 JDK 能力由聚焦 archive dependency 补足，禁止手写 central-directory parser。
- FlowDebug、OperationAudit 和 durable business facts 三者职责分离。
- Project.versionNo 只用于 metadata CAS，不是 upload/parse 长时间 lease。
- v1 只采用 `PROJECT_CONTRACT` association，不保留第二种关系模型。
- 不提供 delete/soft delete/archive 操作，不引入 OCR/AI、Workflow Engine 或固定 migration version。

## Implementation Decomposition

三个计划严格串行：每个计划从最新 main 建独立短生命周期 branch，拥有独立 tests、`clean verify`、review 和 merge；前一个计划合入 main 后才开始下一个。本文只定义交付边界，不创建具体 implementation plan 文件。

固定顺序为 A merge main → B from latest main → B merge main → C from latest main → C merge main → 正式 F04；不得把任一 Pre-F04 内容塞进 `feature/f04`。

### PLAN A — Review Risk Acknowledgement & UX

交付：

- review-owned FindingAcknowledgement domain/Port/local+DM8 adapter/API 与通用 acknowledgement read model；
- durable idempotency、唯一约束和 `REVIEW_FINDING_ACKNOWLEDGED` OperationAudit；
- contract-owned F02 ReviewProgress Application/API assembly：SOURCE_PREPARATION、F02 rule title/order、真实 findings、createdAt、displayOrder、acknowledgements；实施时按当前 F02 API 风格确定 route，不创建 review -> contract 依赖；
- acknowledgement application/transaction/conflict 的安全 FlowDebug events；
- 前端 `1/(1+N)` progressive replay、绿色完成、黄色提醒/风险待知悉、真实“我已知悉”、确认人/时间、只写“审核完成”的 risk summary 和右侧 Drawer；
- 金额 `¥` 与中文大写 presentation、可靠来源的 nullable presentation seam、移除项目通过/审批语义；
- 若触及 ReviewSession 页面，仅移除未经确认的红星 presentation，不改变 READY server invariant；
- acknowledgement/并发/事务/audit/log safety/API/UI 回归。

不包含 Project、ZIP、parser 或 Contract promotion。完成并合入 main 后开始 Plan B。

### PLAN B — Project Intake Core

交付：

- contract-owned Project、ProjectFile、ExtractionResult、ContractCandidate 及 repository/API；
- ContractCandidate source-file UNIQUE identity、ExtractionResult deterministic extractionKey/UNIQUE constraint 及 unknown-outcome recovery deduplication；
- 空白 Project create/list/detail/metadata confirmation；
- ProjectFilePolicy 的 direct/archive-child 两级安全 allowlist、FileStoragePort reuse、统一 upload、authenticated content read；
- ZIP archive-library capability gate、真实 archive security tests、deterministic classification、PDF/DOCX parser/extractor；若 JDK 17 public API 不足，按聚焦依赖规则评估 Apache Commons Compress；
- candidate persistence、source/conflict/missing projection、restart recovery；
- `PROJECT_CREATED`、`PROJECT_METADATA_CONFIRMED`、`PROJECT_FILE_UPLOAD_STARTED/COMPLETED/FAILED` OperationAudit 与安全 FlowDebug technical trace；
- local/DM8 structural、archive adversarial、parser、idempotency、recovery、audit/log safety 和 API tests。

不包含 Project frontend、PROJECT_CONTRACT association、正式 Contract 写入或 promotion。完成并合入 main 后开始 Plan C。

### PLAN C — Project Intake UI & Contract Promotion

交付：

- PROJECT_CONTRACT association 与 ProjectContractQueryPort；
- candidate promotion command/API、minimum validation、insert-only Contract creation Port、Contract/line/award insert 和冲突语义；
- Project list、+ 新建项目、空白 detail、统一资料上传、五类文件展示、原始文件/提取文本、结构化字段/source anchor、candidate review；
- promotion audit 与安全 FlowDebug，同事务提交 PROJECT_CONTRACT/Candidate/Contract facts；
- promotion 后复用现有 direct F02，并接入 Plan A progressive UX；
- FindingSourceProjection 只在可靠来源存在时展示；
- association/concurrency/rollback/audit/log safety/F02/ReviewSession/frontend production build 回归。

不实现 Project→ReviewSession binding 或 ReviewSession→F02。Plan C 合入后仍以现有 direct F02 为唯一 Project candidate review 路径。

DESIGN_GATE = PASS

REMAINING_AMBIGUITIES = NONE
