# AGENTS.md — 内审系统智能体 1.0

## 当前角色与范围

本仓库当前是后端 A + 后端 B 的共享执行基线。

当前基线同时保留后端 A 的 F02/Contract/ReviewSession/Project Intake 能力与后端 B 的 F01/F03/F04 能力，并继续保持可持续测试、部署和国产化迁移边界。

用户自 2026-10-22 起负责整个项目，后续维护与回归范围包括合同、预算、采购及现有公共能力，不再默认限定为预算和采购。共享 OCR、模型、文件处理及部署变更必须评估三个业务模块的受影响链路，分别说明真实验证结果；此范围调整不授权扩建未批准的新业务模块。

除非任务明确要求，不要把现有 F01/F02/F03/F04 扩展为 F05-F15 全量业务，也不要主动引入完整系统管理功能。2026-08-31 已批准的 AI/OCR Project Intake demo 仅允许按 `docs/superpowers/specs/2026-08-31-ai-ocr-project-intake-demo-design.md` 增加独立 Python 服务、真实 OCR 适配器和文字语义 AI 辅助；不得扩大为 AI 金额决策、RAG、向量库或通用智能体。Backend B 已合并的 F01/F03/F04 业务语义属于当前基线，后续重构必须另立任务并保留回归证据。

当前产品语义还要求：

* `Project != Contract != ReviewSession != AuditTask`；Project 已在 `contract` bounded context 内作为一对多拥有 Contract 的上游聚合，不得用 `ReviewSession.name`、合同编号或中标通知书编号冒充项目字段。
* ReviewSession 当前只有 `PREPARING_MATERIALS -> READY` 材料流程，不提供公共删除 API 或前端删除操作。V005 tombstone 仅保留已部署 schema、历史数据过滤和内部恢复兼容。
* ZIP 是 Project Intake Package，不是 ReviewDocument 的 `OTHER` 类型。必须复用现有 `ProjectFileIngestionService`、`FileStoragePort`、安全 archive reader、分类、幂等和 recovery；不得建立第二套 ZIP/文件存储子系统。正式模板仍未确认，因此不得把当前安全分类扩大为模板合规结论。

## 当前工作目录

仓库根目录：

`D:\code\audit_agent_1D`

所有项目文件必须位于该目录内。

不得在仓库外修改用户文件。

不得自行安装系统级软件，若缺少 JDK、Git、SSH 等基础环境，应报告并停止相关步骤。

## 采购模块功能编号

采购模块的对外功能编号以排期表为唯一准则。后续开发任务、沟通、代码注释、业务文档和前端展示必须使用以下编号：

| 排期编号 | 功能名称 | 代码内部历史编号 |
| --- | --- | --- |
| F10 | 招标文件合规性审查 | F07 |
| F11 | 围串标/拆分采购辅助识别 | F08 |

`F10` 和 `F11` 是采购业务的规范名称。不得在新增采购代码、注释或业务文档中使用 `F07`、`F08` 指代这两个功能。为保持已有兼容性，不得仅因编号统一而重命名既有后端类、接口路径、数据库表、迁移脚本或规则编码；引用这些历史技术标识时，应同时说明其对应的排期编号。完整对照见 `docs/procurement-feature-numbering.md`。

## 技术基线

* Java：JDK 17。
* Java 后端：Spring Boot，模块化单体。
* 持久化：DM8 是唯一正式关系数据库。
* ORM：MyBatis / MyBatis-Plus。
* 当前缓存：Redis。
* 目标国产缓存：TongRDS。
* 当前 HTTP 入口：Nginx。
* 目标国产 HTTP 服务：TongHttpServer。
* 当前 Java 运行方式：标准可执行 JAR。
* 目标应用服务器：TongWeb，待具体版本和介质到位后执行单独兼容性验证。
* Python/FastAPI/OCR/AI 属于独立服务，通过 HTTP Port 接入 Java。
* 当前阶段不得把 Python 原生依赖嵌入 Java。
* 当前 POM 还包含 `DmJdbcDriver18`、Springdoc、Apache POI 和 PDFBox；依赖可构建不代表真实 DM8 或新的 Java→FastAPI→PaddleOCR 链路已验证。
* 29 代表性目标已验证 Python 3.11.16、FastAPI 0.141.1、Uvicorn 0.52.1、PaddlePaddle 3.3.1、PaddleOCR 3.7.0、OpenCV 4.10.0、模型文件和既往独立 OCR。新链路在真实执行前必须标记 `REMOTE_E2E_PENDING_EXECUTION`，不能降级描述为目标环境阻塞。
* 29 OCR 必须使用 `/opt/python/venvs/audit-ai`，CPU threads 固定为 1，MKLDNN 关闭；不得修改 `/usr/bin/python3`、覆盖现有虚拟环境或默认升级核心 OCR 依赖。

禁止把 MySQL、PostgreSQL、H2 当作正式数据库方言。

单元测试可以使用 Mock/Fake Repository，不要求本地安装 DM8。

## 国产化兼容硬约束

业务代码不得：

* import Tomcat 私有 API；
* 依赖 Nginx 私有行为；
* 依赖 Redis 特有高级模块；
* 将 Redis Streams、Lua、Pub/Sub 当作核心业务前提；
* 将 Redis/TongRDS 作为审查结果唯一事实来源；
* 写死 x86、Windows、LXC 或本地文件绝对路径；
* 写死服务器 IP、数据库账号、密码、Token；
* 假定 TongWeb/TongRDS/TongHttpServer 已完成兼容验证。

Web、数据库、缓存、AI/OCR、认证均必须通过接口/适配器边界隔离。

TongWeb 介质到位前采用 JAR-first。

TongRDS 当前只按 CachePort 目标实现，不作为消息队列实现。

如果以后需要真正消息队列，单独定义 TaskQueuePort，不得复用 CachePort。

TongHttpServer/Nginx 只属于部署层，业务代码不得感知具体产品。

## 数据职责

DM8 是 System of Record。

必须持久化到 DM8 的数据包括：

* 审查任务；
* 任务状态；
* 检查结果；
* 证据；
* 文档元数据；
* 规则版本；
* F02 合同、结构化中标依据、审查结果和 Durable Idempotency 记录；
* F01 预算审批校验、材料审查任务/明细和结果；
* F03 黑名单主数据与报警事实；
* 后续正式业务数据。

Redis/TongRDS 只允许用于：

* Cache Aside 缓存；
* 非业务事实源的短期幂等键；
* 必要的短期锁；
* `local-dev` / `dev29` / `customer` 的 Spring Session；`local` 使用 Servlet 内存 Session；
* 可丢失、可重新计算的临时信息。

写操作优先完成 DM8 事务，事务提交成功后再失效或更新缓存。

Redis 不可用不得导致已持久化审查结果丢失。
F02 写命令的 Durable Idempotency 以 DM8 `IDEMPOTENCY_RECORD` 为事实源，不得通过 `CachePort.get + put` 或 Redis 代替。
F02 必须先以短 START 事务提交幂等 claim、`AuditTask(PROCESSING)` 和 START 审计，再在事务外执行确定性规则；成功结果以独立 COMPLETE 事务提交，普通失败以 `REQUIRES_NEW` FAILURE 事务收口。不得恢复覆盖规则执行的长事务，也不得在 rollback-only 事务中写 FAILED 事实。

系统用户身份、密码 Hash、角色、登录安全审计和长期业务操作审计必须持久化到 DM8。Redis Session 不是事实源；Redis Session 丢失最多导致用户重新登录，不得导致 DM8 业务或审计事实丢失。

`local-dev` / `dev29` / `customer` 不允许关闭长期业务操作审计；若 `audit.operation-audit.enabled=false`，应用必须 fail closed 并拒绝启动。no-op `OperationAuditPort` 只允许用于明确的 `local` / 测试 seam。

## 金额规则

所有金额在 Java 中必须使用 `BigDecimal`。

禁止使用 `double` 或 `float` 表示人民币业务金额。

数据库金额优先使用 `DECIMAL(18,2)`。

零容差金额判断使用数值比较语义，不依赖 `BigDecimal.equals()` 的 scale 一致性。

F02 已实现五条确定性规则；后续金额规则仍必须复用 `Money`、`BigDecimal` 和数值比较语义，不得以 AI、动态脚本或浮点数替代。

## 密钥与账号

Git 中绝对禁止出现真实：

* 数据库用户名/密码；
* Redis/TongRDS 密码；
* SSH 密码；
* JWT Secret；
* API Key；
* 千问模型 Token；
* 甲方真实 IP 和敏感地址。

统一采用环境变量：

* `AUDIT_DB_URL`
* `AUDIT_DB_USERNAME`
* `AUDIT_DB_PASSWORD`
* `AUDIT_REDIS_HOST`
* `AUDIT_REDIS_PORT`
* `AUDIT_REDIS_PASSWORD`
* `AUDIT_SESSION_REDIS_NAMESPACE`
* `AUDIT_SESSION_TIMEOUT`
* `AUDIT_SESSION_COOKIE_SECURE`
* `AUDIT_AI_BASE_URL`
* `AUDIT_OCR_BASE_URL`
* `AUDIT_AUTH_MAX_FAILED_ATTEMPTS`
* `AUDIT_AUTH_LOCK_DURATION`
* `AUDIT_CORS_ALLOWED_ORIGINS`
* `AUDIT_TECH_LOG_LEVEL`
* `AUDIT_TRACE_SAMPLING_PROBABILITY`

local 首账号只允许通过 `AUDIT_LOCAL_ACCOUNT_*` 环境变量受控初始化，其中 `AUDIT_LOCAL_ACCOUNT_PASSWORD_HASH` 必须是离线工具生成的 Spring Security delegating hash；任何 raw password 都不得进入环境模板、Git、日志或 evidence。

仓库只允许提交 `.env.example` 或配置模板。

真实 `.env`、`*.local.yml`、密钥文件必须加入 `.gitignore`。

29 测试机真实应用程序不得使用 SYSDBA 或 SYSAUDITOR 作为业务连接账号。

## 推荐代码边界

推荐：

`common`
公共响应、异常、RequestId、配置、基础值对象。

`review`
AuditTask、AuditCheckResult、CheckStatus、RiskLevel、Evidence、DocumentMeta 等公共审查模型及其公共 Repository Port；不得反向依赖 contract。

`contract`
合同领域，当前承载 F02 的 API、Application 编排、事务协调、确定性规则、合同 Port 和 local Adapter；contract-specific finding 到公共 AuditCheckResult 的映射留在 contract Application，DM8 Adapter 保持在 `integration/dm8`。

`budget`
F01 预算审批校验、材料解析/审查和 Backend B 当前 ProcurementDemand 实现。

`blacklist`
F03 供应商黑名单与报警业务边界。

`procurement`
采购业务边界。

`security`
AuthContext、CurrentUser、安全适配器。

`integration`
DM8、Redis/TongRDS、AI、OCR、文件、外部 HTTP 等基础设施适配器。

优先按业务模块组织，再在模块内部划分 api/application/domain/infra。

禁止建立全仓库 controller/service/mapper 三个巨大目录。

## 当前 API 基线

统一前缀：

`/api/v1`

公共响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

必须支持 Request ID。

至少提供：

`GET /api/v1/health`

健康响应不得返回用户名、密码、完整 JDBC URL、Token 等敏感信息。

## 本地与环境 Profile

至少区分：

* `local`
* `local-dev`
* `dev29`
* `customer`

`local` 默认允许 Mock 外部依赖，不要求本机安装 DM8、Redis、OCR 或千问。

`local-dev` 使用 DM8、Redis Session、内存 Cache 和 Fake AI/OCR；数据库凭据、Redis 与 storage root 只允许环境变量注入。

`dev29` 用于真实连接 29 测试机环境。

`customer` 仅提交配置模板，不填写甲方真实信息。

## Git 工作规则

默认分支 `main` 必须保持可构建。

采用短生命周期 feature 分支。

提交信息优先使用：

* `feat:`
* `fix:`
* `test:`
* `docs:`
* `refactor:`
* `chore:`

每次大改前先检查 `git status`。

不得覆盖用户已有未提交修改。

不得执行 `git reset --hard`、强制 push 或删除分支，除非用户明确要求。

Shell 脚本必须以 LF 提交。

## Production Invariant Verification

For concurrency, idempotency, durable workflows, filesystem operations,
database state transitions, and recovery logic:

1. Do not infer semantics from API names. Verify platform behavior when
   correctness depends on it.

2. Every critical invariant requires at least one adversarial/regression test,
   not only a happy-path test.

3. Distinguish:
   - durable business audit,
   - flow/debug observability,
   - technical error logging.

4. IDs are semantic types:
   TASK_ID may only contain a real AuditTask ID; trace/resource/document/session
   IDs must not be substituted merely because they are UUIDs.

5. UNKNOWN is not FAILED and UNKNOWN is not MISSING.
   Recovery logic must fail closed when durable/storage state cannot be proven.

6. Any durable resource must have a rediscovery/reopen path after refresh or
   process restart.

7. A VERIFIED claim must include:
   - executable test or command,
   - environment,
   - observed result.
   Otherwise mark it DESIGNED or NOT VERIFIED.

8. Before merge, production-critical infrastructure semantics must be checked
   on at least one representative real platform where practical.

9. A client idempotent operation identity must remain immutable while its outcome is uncertain or
   in progress; refreshing server state must not silently create a new key for that operation.

## 测试与完成定义

每个任务完成前至少运行：

```powershell
.\mvnw.cmd clean verify
```

如果项目不在仓库根目录，则在 backend 目录运行。

“完成”必须有真实证据，至少包含：

* 修改文件；
* Git diff；
* 测试命令；
* 测试结果；
* 若涉及 HTTP，提供真实 curl 响应；
* 若涉及 DM8/Redis，提供真实连接验证；
* 未验证的外部环境必须明确标记 `NOT VERIFIED`。

禁止因为脚本已经生成就声称部署成功。

禁止因为配置存在就声称 TongWeb/TongRDS/TongHttpServer 已兼容。

## 当前最高优先级

优先级顺序：

1. 工程可构建；
2. 工程可测试；
3. local 可启动；
4. 公共响应/异常/Request-Id/安全边界稳定；
5. DM8/Redis 适配边界稳定；
6. 29 可部署和回滚；
7. F01/F02/F03 与 ReviewSession 纵向切片保持可回归，后续业务按明确任务继续演进。

如果发现要求与上述目标冲突，先报告冲突，不要擅自扩大范围。
