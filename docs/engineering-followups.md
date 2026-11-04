# 内部工程 Follow-ups

## 1. 文档定位

本文只记录已经知道、已经理解，但当前不阻塞 MVP，因而明确推迟处理的内部工程问题。它不是 Bug 清单、开发排期、Open Questions 副本、ADR、Evidence，也不是没有边界的 TODO 集合。

相关资料的职责如下：

```text
architecture.md
  -> 当前系统是什么
open-questions.md
  -> 尚不知道答案的问题，尤其外部产品、契约和环境问题
engineering-followups.md
  -> 已知但暂缓处理的内部工程债
ADR
  -> 已经正式作出的长期架构决定
evidence
  -> 实际验证过什么
runbook
  -> 实际怎样操作
```

对应入口：[`docs/architecture.md`](architecture.md)、[`docs/open-questions.md`](open-questions.md)、[`docs/adr/`](adr/)、[`docs/evidence/`](evidence/)、[`docs/runbook/`](runbook/)。

AuditTask 并发语义已在 F02 纵向切片中落地：显式创建/查询/条件迁移 Port、`VERSION_NO`、合法状态机、local 原子等价语义和 409 冲突映射均有自动化证据，不在本文重复建立普通 Follow-up。

## 2. 维护规则

1. 每个 Follow-up 必须有责任域、技术触发条件和可验证的关闭条件；不允许永久堆积无 Owner、无触发条件的 TODO。Owner 表示责任域，不要求在本文记录个人姓名。
2. 状态使用 `OPEN`、`IN_PROGRESS`、`RESOLVED`、`DROPPED`：真正开始实现时由 `OPEN` 改为 `IN_PROGRESS`；完成且验证后改为 `RESOLVED`；经评审确认不再需要时改为 `DROPPED`。
3. 优先级使用 `HIGH`、`MEDIUM`、`LOW`，表示工程风险和触发后的处理顺序，不等同于日期排期。
4. 默认按技术条件触发处理，不以某个日期作为处理依据。
5. 如果处理结果形成长期架构决策，应创建或更新 ADR；如果只是局部实现细节，不必创建 ADR。
6. `RESOLVED` 和 `DROPPED` 条目保留简短结论与验证依据，不直接删除，以保留决策轨迹。
7. 条目的风险描述必须与现有证据相称；没有性能、故障或复用证据时，不得把可能性写成已经发生的问题。

## 3. 汇总

| ID | 主题 | 优先级 | 当前阻塞 MVP | 处理触发条件 | 状态 |
| --- | --- | --- | --- | --- | --- |
| ENG-001 | OCR / AI Port 模块归属 | LOW | 否 | 第二个以上业务模块真实使用 OCR / AI，且出现明确跨模块复用 | OPEN |
| ENG-002 | AI Port 强类型业务语义 | MEDIUM | 否 | 真实 FastAPI AI 结果首次进入正式 Java 审查结果模型之前 | OPEN |
| ENG-003 | FastAPI HTTP Transport / Connection Pool | MEDIUM | 否 | 批量 OCR / AI、并发合同审核或正式性能测试开始之前 | OPEN |
| ENG-004 | FastAPI timeout 默认配置单一来源 | LOW | 否 | 下次修改 timeout 配置模型或整理 `ConfigurationProperties` | OPEN |
| ENG-005 | Health 请求日志策略 | LOW | 否 | 实际探活产生明显日志噪音或存储压力 | OPEN |
| ENG-006 | RequestId MDC Key 统一 | LOW | 否 | Round A 修改异常与 correlation 公共代码 | RESOLVED |
| ENG-007 | dev29 / customer 配置模板演进关系 | LOW | 否 | dev29 首次出现 customer 不应拥有的专属配置 | OPEN |

## 4. Follow-up 明细

### ENG-001 OCR / AI Port 模块归属

| 字段 | 内容 |
| --- | --- |
| 状态 | `OPEN` |
| 优先级 | `LOW` |
| Owner | Java 后端（后端 A） |
| 是否阻塞当前 MVP | 否 |

**现状**

`OcrPort` 和 `AiReviewPort` 当前位于 `review.application.port`。现阶段这是已经落地并受 ADR-001 约束的 Application-owned Port 边界。

**风险**

如果以后 `contract`、`budget`、`procurement` 等多个业务模块都直接使用 OCR / AI，`review` 可能逐渐同时承担业务审查模块和通用 document/AI capability 的职责。当前尚未形成这种真实复用场景，因此这只是需要观察的模块边界张力。

**当前决定（为什么现在不做）**

不提前迁移。当前只有 baseline，为目录理论纯度搬迁会制造没有业务收益的重构，也无法凭现有证据在候选边界之间作出稳定选择。

**触发处理条件**

第二个以上业务模块开始真实使用 OCR / AI，且出现明确的跨模块复用、依赖方向或命名职责问题时重新评估。

**预期处理方向**

- 将文档相关 Port 抽到独立 `document` 模块；或
- 正式确认 `review` 是共享审查能力中心。

当前不预先选定最终方案。

**关闭条件**

完成跨模块依赖评审，并通过代码结构和测试落实选定边界；如果结论属于长期架构决定，同时创建或更新 ADR。

**相关源码 / ADR**

- [`OcrPort.java`](../backend/src/main/java/com/fuquan/audit/review/application/port/OcrPort.java)
- [`AiReviewPort.java`](../backend/src/main/java/com/fuquan/audit/review/application/port/AiReviewPort.java)
- [`ADR-001`](adr/ADR-001-infrastructure-adapter-boundary.md)

### ENG-002 AI Port 强类型业务语义

| 字段 | 内容 |
| --- | --- |
| 状态 | `OPEN` |
| 优先级 | `MEDIUM` |
| Owner | Java 后端（后端 A，与后端 B 共同评审契约） |
| 是否阻塞当前 MVP | 否 |
| 是否阻塞正式 AI 业务接入 | 是 |

**现状**

`AiReviewFinding.level` 和 `AiReviewItem.status` 当前都是自由 `String`。`FastApiAiReviewAdapter` 只校验它们非空，然后将下游字符串直接放入 Application Port 结果类型。

**风险**

FastAPI 草案中的 `red`、`warning`、`review`、`error`、`ok` 等 HTTP 表示如果未经显式映射进入正式 Java 模型，会使下游词汇成为 Java 业务状态，并使未知值的处理语义不清晰。

**当前决定（为什么现在不做）**

F02 正式模型已经使用 `RiskLevel`、`CheckStatus` 等强类型业务语义。现有 FastAPI AI 草案类型仍未进入该正式结果模型；本轮不重构 AI 草案类型，以免把未定稿下游词汇固化为正式 Java 审查语义。

**触发处理条件**

真实 FastAPI AI 结果第一次进入正式 Java 审查结果模型之前。

**预期处理方向**

```text
HTTP String
  -> FastAPI Adapter 显式映射
  -> Java enum / typed domain value
```

禁止静默透传未知字符串。未知值必须明确映射失败，或者进入经过业务定义的 `UNKNOWN` / `NOT_EVALUATED` 语义；不得自动当作 `PASS`。

**关闭条件**

正式结果模型不再直接依赖下游自由字符串，Adapter 映射和未知值策略均有自动化测试，并在真实 AI 业务入口启用前完成验证。

**相关源码 / ADR**

- [`AiReviewFinding.java`](../backend/src/main/java/com/fuquan/audit/review/application/port/AiReviewFinding.java)
- [`AiReviewItem.java`](../backend/src/main/java/com/fuquan/audit/review/application/port/AiReviewItem.java)
- [`FastApiAiReviewAdapter.java`](../backend/src/main/java/com/fuquan/audit/integration/ai/fastapi/FastApiAiReviewAdapter.java)
- [`ADR-004`](adr/ADR-004-fastapi-http-adapters.md)

### ENG-003 FastAPI HTTP Transport / Connection Pool

| 字段 | 内容 |
| --- | --- |
| 状态 | `OPEN` |
| 优先级 | `MEDIUM` |
| Owner | Java 后端（后端 A） |
| 是否阻塞当前 MVP | 否 |

**现状**

`FastApiHttpClientConfiguration` 当前使用 `SimpleClientHttpRequestFactory` 创建 OCR 和 AI `RestClient`，分别设置有限的连接和读取超时。现有 baseline 验证的是 HTTP 契约、超时配置和错误映射，不包含批量负载或连接资源测试。

**风险**

进入批量 OCR / AI 后，当前 Transport 对并发、连接复用、TIME_WAIT、延迟、连接池和资源上限的表现尚无性能证据。本文不据此声称已经存在性能问题，也不声称连接一定无法复用。

**当前决定（为什么现在不做）**

不提前替换 HTTP Client。当前没有代表性负载或 Benchmark，单纯为了“连接池”增加依赖无法证明收益，还会扩大国产化兼容验证范围。

**触发处理条件**

开始批量 OCR / AI、并发合同审核或正式性能测试之前。

**预期处理方向**

先执行 Benchmark / Load Test 并记录连接与资源指标，再基于证据选择保留现状、JDK HttpClient、Apache HttpClient，或其他符合当前国产化约束的标准 HTTP 实现。

**关闭条件**

完成有代表性的负载测试并记录结论；若更换 Transport，需补充契约回归、资源上限和性能验证证据。

**相关源码 / ADR**

- [`FastApiHttpClientConfiguration.java`](../backend/src/main/java/com/fuquan/audit/integration/http/FastApiHttpClientConfiguration.java)
- [`ADR-004`](adr/ADR-004-fastapi-http-adapters.md)
- [`docs/source-baseline.md`](source-baseline.md)

### ENG-004 FastAPI timeout 默认配置单一来源

| 字段 | 内容 |
| --- | --- |
| 状态 | `OPEN` |
| 优先级 | `LOW` |
| Owner | Java 后端（后端 A） |
| 是否阻塞当前 MVP | 否 |

**现状**

`application.yml` 为 OCR/AI 声明独立超时：连接默认 `3000 ms`，OCR 读取默认 `120000 ms`，AI 读取默认 `150000 ms`；`FastApiHttpClientConfiguration` 的 `@Value` 表达式中也保留了相同默认值。两处当前一致。

**风险**

以后只修改其中一处时，默认值可能发生漂移，使配置文件与 Java fallback 表达不同事实。

**当前决定（为什么现在不做）**

不为当前一致且已有测试边界的默认值单独修改 baseline。此问题风险低，适合在下一次配置模型演进时一起处理，避免孤立重构。

**触发处理条件**

下一次修改 FastAPI timeout 配置模型，或开始将相关配置整理为 `ConfigurationProperties` 时。

**预期处理方向**

只保留一个默认值事实来源，并保持环境变量覆盖、正数校验和 OCR/AI 独立配置能力。

**关闭条件**

源码与配置不再重复声明同一默认值，并由配置绑定/校验测试证明实际默认行为。

**相关源码 / ADR**

- [`application.yml`](../backend/src/main/resources/application.yml)
- [`FastApiHttpClientConfiguration.java`](../backend/src/main/java/com/fuquan/audit/integration/http/FastApiHttpClientConfiguration.java)
- [`ADR-004`](adr/ADR-004-fastapi-http-adapters.md)

### ENG-005 Health 请求日志策略

| 字段 | 内容 |
| --- | --- |
| 状态 | `OPEN` |
| 优先级 | `LOW` |
| Owner | Java 后端（后端 A，与部署/运维共同观察） |
| 是否阻塞当前 MVP | 否 |

**现状**

`RequestAuditLoggingFilter` 当前对所有经过过滤器的请求记录 INFO，包括 `/api/v1/health` 和 `/actuator/health`；源码没有按健康路径跳过或降级日志的策略。

**风险**

未来负载均衡器或编排平台高频探活时，health 日志可能形成低价值噪音并增加存储量。当前没有实际探活频率、日志量或存储压力证据。

**当前决定（为什么现在不做）**

MVP 保持现状。统一记录请求便于当前开发诊断，在没有实际噪音证据时提前增加路径特例没有明确收益。

**触发处理条件**

部署实际健康探测后，观测到明显日志噪音、检索干扰或存储压力。

**预期处理方向**

根据观测结果再选择 `shouldNotFilter()` 排除健康路径、降低 health 日志等级，或建立独立探活日志策略；当前不预选最终方案。

**关闭条件**

基于实际日志指标完成策略评审；选定策略已实现并通过日志/过滤器测试，或者以证据确认保留现状并记录结论。

**相关源码 / ADR**

- [`RequestAuditLoggingFilter.java`](../backend/src/main/java/com/fuquan/audit/common/web/RequestAuditLoggingFilter.java)
- [`docs/architecture.md`](architecture.md)

### ENG-006 RequestId MDC Key 统一

| 字段 | 内容 |
| --- | --- |
| 状态 | `RESOLVED` |
| 优先级 | `LOW` |
| Owner | Java 后端（后端 A） |
| 是否阻塞当前 MVP | 否 |

**现状**

Round A 已将 `GlobalExceptionHandler` 中的 Request ID MDC 读取统一为 `RequestIdFilter.MDC_KEY`。`RequestIdFilter` 继续维护既有 key 和校验语义；Micrometer Tracing 另行维护 `traceId` / `spanId`，没有混用三者含义。

**风险**

如果以后调整 MDC key，只修改常量引用而遗漏字面量，会造成部分异常日志缺少 Request ID。这是维护一致性问题，不是当前运行故障证据。

**处理结论**

已在认证/可观测性横切改造中完成等价替换，并由 Request ID、异常与 trace/MDC 回归测试覆盖。

**触发处理条件**

下一次修改 `GlobalExceptionHandler`、`RequestIdFilter` 或其他 RequestId 公共代码时。

**预期处理方向**

所有 MDC key 读取统一引用 `RequestIdFilter.MDC_KEY`，并保留异常日志的 Request ID 测试。

**关闭依据**

生产源码中的异常处理不再重复声明 Request ID MDC 字面量；全量 Maven 测试覆盖合法透传、非法替换、MDC 清理以及 trace/span/requestId 同时存在。

**相关源码 / ADR**

- [`GlobalExceptionHandler.java`](../backend/src/main/java/com/fuquan/audit/common/exception/GlobalExceptionHandler.java)
- [`RequestIdFilter.java`](../backend/src/main/java/com/fuquan/audit/common/web/RequestIdFilter.java)

### ENG-007 dev29 / customer 配置模板演进关系

| 字段 | 内容 |
| --- | --- |
| 状态 | `OPEN` |
| 优先级 | `LOW` |
| Owner | Java 后端（后端 A，配置 Review 责任域） |
| 是否阻塞当前 MVP | 否 |

**现状**

`application-dev29.yml` 与 `application-customer.yml` 当前配置主体高度相似，均通过环境变量提供 DM8、Redis、OCR 和 AI 连接信息。两个文件表示不同运行环境，当前相似不属于 Bug。

**风险**

以后为 dev29 调试加入 Debug 日志、临时 timeout、开关或测试配置时，可能在复制配置过程中误带入 customer 模板。

**当前决定（为什么现在不做）**

继续保留两个独立 Profile 文件。当前没有环境专属配置需要抽象；仅为 DRY 强行合并会弱化环境边界，也不能消除后续 Review 责任。

**触发处理条件**

dev29 第一次出现 customer 不应拥有的环境专属配置时。

**预期处理方向**

在配置 Review 中明确检查两个 Profile 的差异和传播方向，确保 dev29 临时项不会进入 customer；是否抽取共享配置应由真实差异决定，不预先强制合并。

**关闭条件**

首次真实分化时建立并验证清晰的差异审查方式，或形成经过评审的 Profile 配置策略；customer 模板不包含 dev29 专属配置。

**相关源码 / ADR**

- [`application-dev29.yml`](../backend/src/main/resources/application-dev29.yml)
- [`application-customer.yml`](../backend/src/main/resources/application-customer.yml)
- [`ADR-001`](adr/ADR-001-infrastructure-adapter-boundary.md)
