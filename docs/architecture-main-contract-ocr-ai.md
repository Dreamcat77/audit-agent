# Main 分支总体架构与 Contract/OCR/AI 设计

## 0. 分析基准

本文基于本次 F01 集成前已成功 fetch 的 `origin/main` 分析：

```text
commit: 55a09f3c1ad248ac3d33a3e1e78800f8fadcf9ff
message: Merge pull request #15 from ztysew2/feature/demo-ui-final-closeout
时间: 2026-09-03 16:39:02 +0800
```

本次集成执行 `git fetch origin` 成功，并确认集成分支 HEAD 与上述 `origin/main` 提交一致。
阅读范围包括仓库入口文档、架构/ADR/运行手册、Java Port/Adapter/Application 代码，以及
`services/audit-ai` Python 服务；F01 增量以 `origin/f01` 的集成候选内容为准。

本文是架构说明，不替代现有 ADR、运行手册或接口实现。

## 1. 一句话结论

当前系统是一个 **Java 17 + Spring Boot 的模块化单体**，通过明确的 Port/Adapter 边界连接
DM8、Redis、文件存储以及独立运行的 Python/FastAPI OCR/AI 服务。

Contract 领域采用以下分层：

```text
Project
  -> ProjectFile / Project Intake
  -> Contract
  -> ContractReviewSession（材料准备与确认过程）
  -> AuditTask（一次具体规则执行）
  -> AuditCheckResult（公共审查结果）
```

其中：

- DM8 是业务事实源，保存项目、合同、任务、规则结果、审计和幂等记录。
- OCR 负责把扫描件或图片转换为可供 Java 保存和后续分析的文本。
- AI 负责合同文本语义总结、提示、证据引用和材料完整性提示。
- F02 的金额、数量乘单价、供应商和金额差异结论由 Java 确定性规则负责。
- OCR/AI 的输出不能直接覆盖正式业务事实，也不能替代 Java 规则。

## 2. 总体技术架构

### 2.1 部署视角

```text
Browser
  |
  | same-origin /api
  v
React + Vite frontend
  |
  | development: Vite proxy
  | production: approved HTTP entry such as Nginx
  v
Spring Boot executable JAR
  |
  +--> Spring Security
  |      Session Cookie + CSRF
  |
  +--> Application / Domain modules
  |      common
  |      security
  |      review
  |      contract
  |      budget
  |      blacklist
  |      procurement
  |
  +--> DM8/MyBatis adapters ------------------> DM8
  |
  +--> Redis adapters ------------------------> Redis
  |       cache and non-local Spring Session
  |
  +--> FileStorage adapter -------------------> filesystem
  |
  +--> FastApiOcrAdapter ---------------------> Python FastAPI OCR
  |
  +--> FastApiAiReviewAdapter ----------------> Python FastAPI AI
                                                    |
                                                    +--> OCR/PaddleOCR
                                                    +--> OpenAI-compatible model API
```

Java 和 Python 是两个独立进程。Python 原生依赖不嵌入 Java，也不由 Java 直接加载 PaddleOCR。
Java 只通过应用层 Port 和基础设施 Adapter 访问外部 HTTP 服务。

### 2.2 Java 内部依赖方向

```text
API/Web
  |
  v
Application
  |
  v
Domain

Application-owned Port <----- Infrastructure Adapter
          ^
          |
       Application
```

约束要点：

- API 负责 HTTP 请求、认证上下文和 DTO 映射。
- Application 负责用例编排、事务边界、输入检查和 Port 调用。
- Domain 负责业务实体、值对象和规则语义。
- Adapter 负责 DM8、Redis、文件系统和 FastAPI 等外部技术细节。
- Domain/Application 不应直接依赖 DM8、MyBatis、Redis、RestClient、FastAPI DTO 或具体入口产品。
- `review` 提供公共审查模型，但不反向依赖 `contract`。

### 2.3 事实源与缓存

```text
正式业务事实:
  DM8
    - 用户、角色和安全审计
    - Project / Contract / ReviewSession
    - AuditTask / AuditCheckResult
    - 幂等记录和长期业务操作审计
    - ProjectFile 元数据、解析状态和 provenance

可丢失或可重建状态:
  Redis
    - Cache
    - local-dev/dev29/customer 的 Spring Session

文件内容:
  FileStorage
    - 不进入 DM8 BLOB
    - 不进入 Redis
    - 浏览器只持有引用，不承担持久化
```

Redis Session 丢失最多导致重新登录，不能导致业务结果或审计事实丢失。

## 3. Java 模块地图

| 模块 | 主要职责 | 重要边界 |
| --- | --- | --- |
| `common` | 异常、请求上下文、审计、幂等、观察性等横切能力 | 不承载具体 Contract 或 Budget 业务 |
| `security` | Session/CSRF/CORS、认证 Provider、账号安全和登录审计 | 当前不是 JWT/Bearer Token 架构 |
| `review` | `AuditTask`、`AuditCheckResult`、状态、风险等级和公共 Repository Port | 不依赖 `contract`；保留 OCR Port 基线 |
| `contract` | Project、ProjectFile、Contract、ReviewSession、F02 规则、Contract AI 用例 | Contract-specific 结果在本模块映射为公共审查结果 |
| `budget` | F01 预算 API、材料解析/校验和预算审查 | Budget AI 使用独立的 `BudgetSemanticAnalysisPort` |
| `blacklist` | F03 供应商黑名单及告警处理 | 与 F01/F02 保持业务边界分离 |
| `procurement` | 采购相关业务边界和领域能力 | 由具体用例调用，不承担通用基础设施 |
| `integration` | DM8、Redis、文件、FastAPI、审计和幂等 Adapter | 外部技术实现集中在这里 |

当前前端是共享 React/Vite 应用。业务入口通过同源 `/api` 访问后端，开发环境由 Vite
proxy 转发。

## 4. Contract 领域模型

### 4.1 四个身份不能混用

```text
Project
  一个项目及其上传/导入材料的业务入口

Contract
  项目中的合同业务实体

ReviewSession
  针对一份 Contract 的材料准备、绑定和确认过程

AuditTask
  一次具体规则审查执行，拥有自己的状态和结果
```

它们的关系不是简单的“一个表对应一个页面”：

- Project 可以拥有多个 ProjectFile，也可以关联一个或多个 Contract。
- Contract 是合同领域实体，承载合同版本和合同业务身份。
- ReviewSession 只负责为某一份 Contract 准备材料，当前生命周期到 `READY`。
- AuditTask 代表一次真正执行的审查，不由 `READY` 的 ReviewSession 自动合成。
- `AuditCheckResult` 是公共审查结果，Contract finding 需要在 Contract Application 层映射进去。

### 4.2 Contract F02 确定性审查

主流程由 `ContractReviewService` 编排，并使用五条 Java 规则：

1. `AmountWordsMatchRule`：金额数字与大写金额一致性。
2. `LineItemArithmeticRule`：数量乘单价与行金额的算术关系。
3. `LineSumMatchRule`：明细合计与总额关系。
4. `AwardAmountMatchRule`：合同/审查金额与中标金额关系。
5. `AwardSupplierMatchRule`：合同供应商与中标供应商关系。

金额使用 Java 的 `Money`/`BigDecimal` 语义，比较遵循数值相等，不依赖
`BigDecimal.equals()` 的 scale 一致性。

F02 的核心链路：

```text
HTTP request
  -> ContractReviewService
      -> 读取 Contract / AwardNotice / LineItems 等结构化事实
      -> 执行 Java deterministic rules
      -> 生成 Contract findings
      -> 映射为 AuditCheckResult
      -> 持久化 AUDIT_TASK + AUDIT_CHECK_RESULT
  -> HTTP response
```

F02 现有金额和供应商结论不要求每次重新调用 OCR/AI。只要 DM8 中已有可信的结构化事实，
就直接执行 Java 规则。

### 4.3 F02 事务、幂等和失败收口

`ContractReviewTransactionCoordinator` 将审查生命周期拆成短事务：

```text
START transaction
  claim durable idempotency record
  create AUDIT_TASK
  PENDING -> PROCESSING
  write start audit
commit

outside transaction
  execute deterministic Java rules

COMPLETE transaction
  append AUDIT_CHECK_RESULT
  PROCESSING -> DONE
  write completion audit
  mark idempotency succeeded
commit

on failure
  REQUIRES_NEW transaction
  PROCESSING -> FAILED
  write failure audit
  mark idempotency failed
commit
```

DM8 的 `IDEMPOTENCY_RECORD` 是 F02 幂等事实源。不能使用 Redis 的 `get + put` 代替持久化
幂等，也不能因为审查失败而遗留一个永远处于 `PROCESSING` 的任务。

## 5. Contract 材料与 Project Intake

### 5.1 Project 是材料入口

Project Intake 把一个项目及其材料作为长期业务对象处理。ZIP 是 Project Intake Package，
不是普通的 `OTHER` 文件。

`ProjectFileIngestionService` 负责：

- 接收和记录文件元数据。
- 对 ZIP 做安全校验和解包。
- 分类项目文件。
- 解析 DOCX/PDF/图片。
- 提取合同候选。
- 保存解析状态和来源 provenance。
- 建立 Project 与 Contract 的关联。

### 5.2 ZIP 安全边界

解包前拒绝以下情况：

- 路径穿越。
- 绝对路径。
- 符号链接。
- 标准化后的重复路径。
- 过多 entry。
- 目录层级过深。
- 解压后体积过大。
- 异常压缩比。
- 不支持的文件类型。

这部分属于文件摄取安全边界，不是 AI 逻辑。AI 只接收通过边界、已经持久化并且可读取的
材料文本。

### 5.3 文件、绑定和 Session

```text
ReviewDocument
  append-only 文件元数据和状态

MaterialBinding
  某个材料 Slot 当前绑定的不可变 Document

ReviewSession
  某份 Contract 的材料准备过程
  PREPARING_MATERIALS -> READY
```

绑定更新使用版本控制/CAS。上传过程大致为：

```text
staging
  -> 文件校验
  -> 幂等 claim
  -> 预留 Session 版本
  -> storage promote
  -> complete / failure
  -> CAS 更新绑定
  -> 必要时 recovery
```

### 5.4 解析文本读取的 fail-closed 边界

`SelectedProjectMaterialReadService` 是 Contract AI 读取材料的统一入口：

1. 校验 Project ID 和选中的 file IDs。
2. 要求 file ID 属于当前 Project，且没有重复。
3. 要求文件不是 ZIP package 本身。
4. 要求 ingestion 和 parse 状态均为可用/已解析。
5. 通过 `ProjectParsedTextReadService` 检查文件存储的 size 和 SHA-256。
6. 要求持久化 parsed text 存在且非空。
7. 返回不可变的已选材料列表。

如果所有权、文件完整性、解析状态或文本持久化无法证明，流程失败，不把空文本当成成功。

## 6. OCR 架构

### 6.1 Java Port

`review/application/port/OcrPort.java` 是 Java 应用层拥有的出站端口。它提供：

- `OcrRequest`
- `OcrDocumentSource`
- `OcrResult`
- `OcrPage`

Application 只看到文件源、文本、页码、置信度和引擎等业务语义，不接触
Spring `RestClient`、HTTP 请求对象或 Python DTO。

### 6.2 Java FastAPI Adapter

`integration/ocr/fastapi/FastApiOcrAdapter.java` 负责把 Port 调用转换为外部 HTTP：

```text
OcrPort request
  -> 从 FileStoragePort 获取可重新打开的文件源
  -> Resource / multipart field "file"
  -> POST /ai/v1/ocr/extract
  -> 校验外部 response envelope
  -> 映射 OcrResult / OcrPage
```

实现特征：

- 使用独立的 OCR `RestClient`。
- 发送 multipart 的 `file` 字段。
- 不使用 `readAllBytes()`、全文件 byte array 或 Base64 作为生产路径。
- 发送 MDC 中的 request ID 到 `X-Request-Id`。
- 校验 MIME 语法、HTTP 响应、`code`、分页结构、engine 和 duration。
- 外部错误统一映射为 OCR 类型的 `ExternalServiceException`。
- 不记录文档内容、OCR response body、凭据或 Authorization。
- 当前没有自动重试或断路器。

### 6.3 Python OCR 服务

Python 服务位于 `services/audit-ai`，路由为：

```text
GET  /health
POST /ai/v1/ocr/extract
```

主要组件：

```text
FastAPI route
  -> OcrApplicationService
      -> PaddleOCR adapter
          -> bounded private temporary file
          -> OCR extraction
      -> OcrExtraction
  -> response envelope
```

PaddleOCR 延迟加载，导入配置、Schema 或路由不会立即加载原生 OCR 依赖。真正首次 OCR
请求时才加载 Paddle。

29 环境的运行约束：

```text
Python 3.11
AUDIT_OCR_CPU_THREADS=1
AUDIT_OCR_ENABLE_MKLDNN=false
```

不能通过修改系统 Python、覆盖既有虚拟环境或盲目升级 Paddle/OpenCV 来解决部署问题。

### 6.4 PDF/DOCX/图片解析决策

```text
DOCX
  -> Apache POI
  -> DOCX_TEXT

PDF
  -> PDFBox
  -> PdfTextUsefulness 判断文本是否有用
       |
       +-- 有用 -> PDF_TEXT，不调用 OCR
       |
       +-- 空或明显不可用 -> OCR

PNG/JPG/JPEG
  -> OCR
```

`PdfTextUsefulness` 是确定性的门控器：它检查可见字符、字母/数字数量和有意义字符比例，
决定 PDFBox 文本是否足够支撑后续流程。

解析后保存 parse method、engine、confidence、page count、duration 和 parsed text，
后续重启/读取优先使用已经持久化的解析结果，不因为刷新页面重复跑 PDF/OCR。

## 7. AI 架构

### 7.1 Contract AI 应用端口

`contract/application/ai/ContractTextAnalysisPort.java` 定义 Contract 文本分析能力：

```text
ContractTextAnalysisRequest
  projectId
  materials[]
    fileId
    category
    text
```

Application 层不依赖 HTTP DTO。对应结果 `ContractTextAnalysisResult` 可包含：

- summaries
- hints
- 语义 category
- attention
- title / description
- evidence refs / evidence items
- model
- duration

### 7.2 Contract AI 用例

`ContractTextAnalysisUseCase` 的职责是编排和保护边界：

```text
读取当前 Project 选中的材料
  -> 保存真实原文，供 locator 校验
  -> ContractAiTextRedactor 脱敏金额/数值字段
  -> 调用 ContractTextAnalysisPort
  -> 校验 AI 返回的 evidence locator
  -> 在原文中检查引用是否唯一可定位
  -> 返回带 locatorAvailable 的结果
```

这里有一个重要的“原文与发给模型的文本”区分：

- 模型侧使用受限、脱敏后的文本，减少金额和数值被模型重新计算的机会。
- 服务端保留原文，用于验证 AI 给出的证据定位是否真的存在。
- 服务端拥有最终 evidence text 的重建权，模型不能自行伪造材料引用。

### 7.3 Contract AI Controller

对外接口：

```text
POST /api/v1/projects/{projectId}/ai/contract-text-analysis
```

请求只选择当前 Project 的材料文件 ID。Controller 读取材料、调用 UseCase，并返回：

- operation
- requestId
- available
- summaries
- hints
- model
- duration

AI 响应是“辅助分析”结果，不是新的 Contract、金额或审计事实。

### 7.4 共享的 FastAPI AI Adapter

`FastApiAiReviewAdapter` 同时实现两个应用端口：

```text
ContractTextAnalysisPort
BudgetSemanticAnalysisPort
```

使用独立的 AI `RestClient`，按 operation 访问不同路径：

```text
POST /ai/v1/agent/contract-text-analysis
POST /ai/v1/agent/budget-semantic-analysis
```

适配器负责：

- 把 Java Application request 映射为 JSON。
- 发送 `X-Request-Id`。
- 校验 operation、model、duration、hints、evidenceRefs 等返回字段。
- 把 FastAPI envelope 映射回 Application result。
- 不自动重试。

共享 Adapter 不等于共享业务语义：Contract 和 Budget 仍由不同的 Port、UseCase、请求模型
和结果模型隔离。

### 7.5 Python Model Gateway

Python 侧的 Model Gateway 使用 OpenAI-compatible Chat Completions：

```text
{AUDIT_MODEL_BASE_URL}/chat/completions
```

主要约束：

- Bearer API key 从环境变量读取。
- `response_format=json_object`。
- `temperature=0`。
- 连接和读取超时有限。
- 输入按 bounded chunk 拆分，有最大 chunk 数。
- Contract 和 Budget 是两个 typed operation，不提供无边界的通用 `ask(prompt)`。
- 对返回结构、分类、attention、evidence refs 和安全结论进行服务端校验。
- 不记录 prompt、合同文本、模型 response body、API key 或 Authorization。

### 7.6 BudgetSemanticAnalysisPort 的语义

预算 AI 请求项可以包含：

```text
itemId
description
currentCategory
analysisType
subjectCode
categoryCode
declaredSubjectName
approvedSubjectName
exceptionType
exceptionData
```

预算 AI 结果可以包含：

```text
suggestedCategory
hint
confidence
judgment
similarityScore
enhancedDescription
suggestion
```

这些字段用于辅助提示、分类建议、相似度参考和异常描述增强。它们不能直接改变
F01/F02/F04 的金额、差异、阈值或确定性通过/异常结论。

## 8. Contract + OCR + AI 端到端链路

### 8.1 材料进入和解析

```text
用户上传 Project Intake Package
  -> Java 校验 ZIP 安全边界
  -> 保存 ProjectFile 元数据和文件
  -> 分类/解包
  -> DOCX 走 POI
  -> 文本 PDF 走 PDFBox
  -> 扫描 PDF/图片走 OcrPort
  -> 保存 parsed text + provenance
  -> 提取/关联 Contract candidate
```

### 8.2 Contract 文本 AI 分析

```text
用户在 Project 中选择已解析材料
  -> ContractTextAnalysisController
  -> SelectedProjectMaterialReadService
  -> 校验 file ownership / parse status / storage hash
  -> ContractTextAnalysisUseCase
  -> 脱敏文本
  -> ContractTextAnalysisPort
  -> FastApiAiReviewAdapter
  -> FastAPI contract-text-analysis route
  -> Model Gateway
  -> OpenAI-compatible model
  -> Python typed response
  -> Java 校验 evidence locator
  -> 返回 summaries / hints / evidence
```

### 8.3 F02 确定性审查

```text
Contract / award / line item structured facts
  -> ContractReviewService
  -> five Java deterministic rules
  -> Contract findings
  -> AuditCheckResult
  -> DM8 AuditTask + AuditCheckResult
```

这条链路不需要因 Contract AI 可用就把金额规则改成模型判断。

## 9. 数据、事务和恢复原则

### 9.1 解析数据

解析结果和 provenance 应持久化在 ProjectFile 相关数据中，至少能说明：

- 解析状态。
- 解析方法：`PDF_TEXT`、`DOCX_TEXT` 或 `OCR`。
- 解析引擎。
- 页数。
- 置信度（如果引擎提供）。
- 耗时。
- parsed text 是否存在。

### 9.2 AI 数据

AI 返回的 summaries、hints、suggestions 和 evidence locator 属于辅助结果。是否长期保存、
保存在哪里，需要由具体用例的持久化设计决定，不能默认把模型返回当成业务主数据。

如果 AI 结果写入审查结果，建议保持来源字段或独立 AI 辅助字段，使用户能够区分：

```text
确定性规则来源: Java / DM8 structured facts
AI 辅助来源: FastAPI / model operation
```

### 9.3 失败和降级

推荐的语义边界：

```text
OCR 失败
  -> 解析失败或材料不可用
  -> 不能静默生成成功的空文本

AI 不可用或返回非法结果
  -> Contract 文本辅助分析不可用
  -> 不覆盖已有 Java 确定性结果

F02 Java 规则失败
  -> 通过独立失败事务把任务收口为 FAILED
  -> 保留失败审计和幂等状态
```

对于 Budget/F01 的 AI 辅助，应沿用“AI 失败不影响确定性校验”的原则：
有限超时、服务不可达、Schema 错误都应记录安全日志并降级，不把 AI 失败变成整条
预算校验失败。

## 10. HTTP、配置和 Profile

### 10.1 两个独立的 HTTP Client

`FastApiHttpClientConfiguration` 创建两个独立的 Spring `RestClient`：

```text
ocrRestClient
  audit.ocr.base-url
  audit.ocr.connect-timeout-ms
  audit.ocr.read-timeout-ms

aiRestClient
  audit.ai.base-url
  audit.ai.connect-timeout-ms
  audit.ai.read-timeout-ms
```

OCR 和 AI 的耗时特征不同，不能共用一个不透明的超时配置。

`FastApiContractPaths` 集中定义：

```text
OCR_EXTRACT              = /ai/v1/ocr/extract
CONTRACT_TEXT_ANALYSIS   = /ai/v1/agent/contract-text-analysis
BUDGET_SEMANTIC_ANALYSIS = /ai/v1/agent/budget-semantic-analysis
```

地址由环境变量注入，不应硬编码在业务源码或提交到仓库的客户配置中。

### 10.2 Profile 装配

当前重要 Profile 语义：

| Profile | DM8/Redis | OCR/AI | 用途 |
| --- | --- | --- | --- |
| `local` | 不启用 JDBC/Redis；内存实现 | Fake | 纯本地开发和单元/集成测试 |
| `local-dev` | DM8/MyBatis、Redis Session、内存 Cache | Fake | 本机真实基础设施开发，但不是远程 FastAPI 证明 |
| `dev29` | DM8、Redis Cache、Redis Session | FastAPI HTTP Adapter | 29 测试机/集成环境 |
| `customer` | 客户 DM8、Redis 或经验证替代品 | FastAPI HTTP Adapter | 生产配置模板 |

因此，配置文件里出现 FastAPI 地址，或者 Adapter 单测通过，都不能单独证明真实
Java -> FastAPI -> OCR/AI -> model 链路已经打通。必须在目标环境执行健康检查、接口冒烟和
实际业务操作验证。

## 11. 安全与可观测性

### 11.1 数据保护

日志中不得出现：

- 合同全文或 OCR 全文。
- 模型 prompt 或 response body。
- API key、Authorization header。
- 客户数据库密码、Redis 密码。
- 未脱敏的材料内容。

允许记录的内容应限于：

- requestId / traceId。
- operation。
- model 名称（按产品需要）。
- duration。
- 结果数量和 Schema 形状。
- 错误类型和安全错误码。

### 11.2 Request ID

浏览器请求进入 Java 后，Java 生成/接收 requestId，并通过 `X-Request-Id` 传给 FastAPI。
这样可以在不记录材料内容的情况下串联：

```text
Browser request
  -> Java request log
  -> FastAPI request log
  -> model gateway operation log
```

### 11.3 Evidence locator

Contract AI 的 evidence locator 必须经过服务端验证：

- 引用的 file ID 必须来自当前选择的 Project 材料。
- excerpt 必须能在服务端原文中找到。
- 需要唯一定位时，不能接受无法唯一匹配的引用。
- 材料缺失提示需要使用允许的材料类别，不能由模型随意创造。

这使 AI 的“提示”仍然绑定在用户可以打开的真实材料上。

## 12. 当前实现状态与边界

### 已实现或已有代码边界

- Java 17/Spring Boot 模块化单体。
- React/Vite 共享前端和 Session/CSRF。
- Project Intake、ZIP 安全解包和 Project-to-Contract 关系。
- append-only ReviewDocument、MaterialBinding、Session/Binding CAS。
- Project 文件解析和持久化 provenance。
- Java OCR Port 和 FastAPI OCR Adapter。
- ContractTextAnalysisPort、UseCase、Controller。
- `FastApiAiReviewAdapter` 同时承载 Contract AI 和 Budget AI 外部调用。
- Python FastAPI OCR 路由和 typed Contract/Budget AI 路由。
- OpenAI-compatible Model Gateway、bounded chunk 和安全过滤。
- F02 五条 Java 确定性合同规则、公共 AuditTask/AuditCheckResult 和 DM8 幂等协调。

### 不应误读为已完成的内容

- 有 Adapter 代码不等于目标环境真实 E2E 已验证。
- 有 `health` 200 不等于 Java 业务接口成功。
- 有模型配置不等于模型返回被 F02 正确使用。
- Contract AI 返回 hint 不等于合同已经自动判定合规/违规。
- F02 的金额结论不应交给 OCR/AI。
- ReviewSession 到 `READY` 不等于自动创建并执行了 AuditTask。
- Fake AI/OCR 的输出不能作为真实模型或 PaddleOCR 证据。

当前基线中，29 环境基础软件和部分独立服务证据与当前 Java 应用真实远程 E2E
必须分开记录。部署、评审或验收时应注明具体环境、具体 commit 和验证范围。

## 13. 后续开发应遵循的接入方式

如果要扩展 Contract 或 Budget 的 AI：

1. 先定义业务模块自己的 Application Port 和结果类型。
2. 在 UseCase 中编排输入读取、脱敏、超时、降级和结果校验。
3. 复用已有 `FastApiAiReviewAdapter` 的 HTTP 适配能力，但不要把两个业务的请求模型混成一个通用对象。
4. 新增路径时集中放入 `FastApiContractPaths`。
5. 使用 `FastApiHttpClientConfiguration` 的 AI Client 和独立超时配置。
6. 把 AI 字段作为辅助来源展示，不能覆盖 Java/DM8 的确定性事实。
7. 对 evidence、模型返回 Schema 和安全内容做服务端校验。
8. 为服务可用、服务不可用、超时、非法 JSON、错误 operation 和证据无法定位分别准备测试。
9. 记录真实 E2E 证据时写明 commit、Profile、服务地址类型、Request ID 和结果范围，不记录敏感正文。

## 14. 参考源码与文档

### 总体架构和工程约束

- [`README.md`](../README.md)
- [`AGENTS.md`](../AGENTS.md)
- [`docs/architecture.md`](architecture.md)
- [`docs/compatibility-matrix.md`](compatibility-matrix.md)
- [`docs/source-baseline.md`](source-baseline.md)

### 架构决策和运行手册

- [`docs/adr/ADR-004-fastapi-http-adapters.md`](adr/ADR-004-fastapi-http-adapters.md)
- [`docs/adr/ADR-009-project-intake-and-pm-product-alignment.md`](adr/ADR-009-project-intake-and-pm-product-alignment.md)
- [`docs/superpowers/specs/2026-08-31-ai-ocr-project-intake-demo-design.md`](superpowers/specs/2026-08-31-ai-ocr-project-intake-demo-design.md)
- [`docs/runbook/ai-ocr-project-intake-dev29.md`](runbook/ai-ocr-project-intake-dev29.md)
- [`services/audit-ai/README.md`](../services/audit-ai/README.md)

### Java 关键实现

- [`OcrPort.java`](../backend/src/main/java/com/fuquan/audit/review/application/port/OcrPort.java)
- [`FastApiOcrAdapter.java`](../backend/src/main/java/com/fuquan/audit/integration/ocr/fastapi/FastApiOcrAdapter.java)
- [`ContractTextAnalysisPort.java`](../backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisPort.java)
- [`BudgetSemanticAnalysisPort.java`](../backend/src/main/java/com/fuquan/audit/budget/application/ai/BudgetSemanticAnalysisPort.java)
- [`FastApiAiReviewAdapter.java`](../backend/src/main/java/com/fuquan/audit/integration/ai/fastapi/FastApiAiReviewAdapter.java)
- [`FastApiHttpClientConfiguration.java`](../backend/src/main/java/com/fuquan/audit/integration/http/FastApiHttpClientConfiguration.java)
- [`FastApiContractPaths.java`](../backend/src/main/java/com/fuquan/audit/integration/http/FastApiContractPaths.java)
- [`ContractReviewService.java`](../backend/src/main/java/com/fuquan/audit/contract/application/ContractReviewService.java)
- [`ContractReviewTransactionCoordinator.java`](../backend/src/main/java/com/fuquan/audit/contract/application/ContractReviewTransactionCoordinator.java)
- [`ProjectFileIngestionService.java`](../backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionService.java)
- [`ProjectParsedTextReadService.java`](../backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectParsedTextReadService.java)
- [`SelectedProjectMaterialReadService.java`](../backend/src/main/java/com/fuquan/audit/contract/application/project/SelectedProjectMaterialReadService.java)
- [`PdfTextUsefulness.java`](../backend/src/main/java/com/fuquan/audit/contract/application/project/parse/PdfTextUsefulness.java)

### Python 关键实现

- [`services/audit-ai/src/audit_ai/model/router.py`](../services/audit-ai/src/audit_ai/model/router.py)
- [`services/audit-ai/src/audit_ai/model/gateway.py`](../services/audit-ai/src/audit_ai/model/gateway.py)
- [`services/audit-ai/src/audit_ai/ocr/router.py`](../services/audit-ai/src/audit_ai/ocr/router.py)
- [`services/audit-ai/src/audit_ai/ocr/paddle_adapter.py`](../services/audit-ai/src/audit_ai/ocr/paddle_adapter.py)
