# Customer deployment current runbook

本运行手册只提供脱敏的当前部署/验收框架。它不授权部署、SSH、数据库 repair 或访问客户服务器。
所有示例必须在已批准窗口由现场负责人执行。

## 1. Placeholders and secrets

统一使用：

- `<APP_HOST>`：Java 应用内部主机；
- `<APP_PORT>`：Java 应用端口；
- `<MODEL_HOST>`：FastAPI/模型服务主机；
- `<MODEL_PORT>`：FastAPI 端口；
- `<PUBLIC_HOST>`：公网域名；
- `<PUBLIC_PORT>`：公网 HTTPS 端口。

真实 IP、用户名、密码、数据库 URL、Redis 密码、API Key、证书路径和私钥不能写入 Git、工单
正文、命令回显或 debug bundle。通过现场 secret store/环境注入。

## 2. Current status

源码基线：合同/A-mini 集成线与预算采购线的受控合并结果；准确 commit 以交付报告为准。
历史 LAN 交接基线只作为 evidence 来源。这些变更尚未由本手册部署到客户环境。

| 层 | 当前已知状态 | 证据边界 |
|---|---|---|
| LAN application E2E | login、Project、upload、OCR、promotion、Qwen semantic、evidence drill-down 已通过 | 本任务输入报告；本轮未重新访问环境 |
| P1-B/P1-C integration | P1-B 的 Candidate 权威链与 P1-C 的完整 Contract→freeze→reopen→F02 已在受控集成 DM8 验证 | 见 P1-B/P1-C evidence；不外推为客户库验收 |
| Customer Contract migrations | `NOT_VERIFIED_CUSTOMER` | 每个 `CONTRACT__*.sql` 需逐项只读 catalog preflight 和批准计划 |
| Nginx long request | reverse-proxy read timeout 已修正，约 650s | 必须在配置同步/升级后保留 |
| HTTPS/certificate | certificate sync 已执行 | 不自动证明公网可达 |
| Public mapping | 公网端口到内部 HTTPS 已配置 | 不自动证明入口/ACL/SNI/回程正常 |
| Public reachability | `NOT_WORKING / NOT_ACCEPTED` | 禁止标记 PUBLIC_DEPLOYMENT=VERIFIED |

公网问题是 deployment/network follow-up；在没有证据时不要改应用业务代码来“修复网络”。

## 3. Release gates

发布前现场记录以下信息的脱敏 fingerprint：

- 旧版与新版 application commit/JAR SHA-256；
- 旧版与新版 frontend artifact SHA-256；
- 旧版与新版 Python service commit/image digest 或源文件 manifest；
- migration plan version 与只读 schema diff；
- Nginx config version（不含证书私钥/真实 upstream）；
- model/PaddleOCR/provider capability version；
- rollback artifact 和负责人。

同时记录实际启用的 profile、核心安全开关的非秘密值、日志目录及其 ACL/保留期。不得把环境文件、
Token、完整 URL、cookie 或原始业务材料复制到发布记录。核心 A-mini 失败事件在默认 `INFO` 可见，
不要求修改现有环境变量；`AUDIT_OBSERVABILITY_FLOW_DEBUG_ENABLED` 仍保持现场默认关闭，除非另有
批准的短时诊断窗口。

FastAPI 启动验收还必须确认 `audit_ai` 的 allowlist 事件进入 Uvicorn 现有日志输出，而不只是看到
Uvicorn access log。使用合成请求和指定 Request ID 后，应能检索到对应 `AI_HTTP_COMPLETED`；如果仅有
`POST ... 200 OK` access line，则视为技术日志配置未生效，禁止据此宣称模型阶段可取证。

必须分别通过：

1. 本地/CI build and test；
2. 客户 DM8 schema preflight；
3. 内部 HTTPS/LAN smoke；
4. OCR/AI E2E；
5. 公网路径 smoke；
6. rollback rehearsal/decision。

上一步失败不得把后续项记为 PASS。

## 4. Database preflight

`db/dm8/V001..V051` 是主迁移线；独立的 `CONTRACT__*.sql` 是 P1-A/P1-B 的
`CONTROLLED_MANUAL` Contract 演进线。仓库没有自动迁移框架。文件存在仅表示
`MIGRATION EXISTS`，不表示 `DEPLOYED ON CUSTOMER DM8`。先只读收集：

- migration history（若客户维护）；
- table/column/type/width/nullability/default；
- PK/UK/index；
- V007/V008 重复列风险；
- V016 F04 表/索引/seed；
- V017/V020-V022 字段；
- 是否存在 V006 demo seed；
- 已匹配但 history 未登记、部分匹配或字段宽度 drift；
- 每个 Contract SQL 影响的表、列、类型、宽度、null/default、PK/UK/index 和必要配置。
- V047 的多材料表、历史关联回填 DML 与 V048 的顺序约束替换；
- V049 的两个预算科目字段宽度；V050 的 `QUEUED` 状态约束；V051 的预算后台任务表/索引。

Contract 演进线的文件与受控依赖如下；V004/V005/V009/V010/V011/V017 等前置
版本也必须先按客户实际 schema 核验。确切的每表效果见 [DM8 migration map](../../db/dm8/README.md)：

```text
CONTRACT__project_file_parse_result.sql
  -> CONTRACT__project_file_role_assessment.sql
  -> CONTRACT__contract_candidate_authority.sql
  -> CONTRACT__project_file_identity_assessment.sql
       -> CONTRACT__contract_candidate_support_authority.sql
            -> CONTRACT__review_session_project_file_binding.sql
       -> CONTRACT__contract_candidate_confirmation.sql
            -> CONTRACT__review_run.sql
            -> CONTRACT__review_run_results.sql
            -> CONTRACT__sanitized_debug_bundle.sql
            -> CONTRACT__review_ack_report.sql
```

`project_file_parse_result` 依赖既有 V010/V017；`review_session_project_file_binding`
还依赖 V004/V005/V011；`review_ack_report` 还依赖 V009。图表示依赖边，
不是按文件名、字母序或 glob 执行的脚本。每一步需要单独记录 catalog 判断、批准、
备份、执行者、执行结果及回滚决定。不得在本 hardening 中运行 migration。

分类后再另立 repair：

```text
NOT_APPLIED          -> approved migration plan, backup and rollback before execution
ALREADY_MATCHING     -> DO_NOT_REAPPLY; reconcile history separately
PARTIAL/SCHEMA_DRIFT -> stop; approved repair + backup + rollback as a separate task
UNKNOWN              -> fail closed; resolve catalog/history uncertainty first
```

本手册不包含 repair SQL。禁止让应用使用 SYSDBA/SYSAUDITOR 业务连接账号。

V047-V051 不得按文件名盲目重放。V047 含历史关联 `INSERT`；V048/V050 含
`DROP CONSTRAINT`，必须通过对应 helper/受控计划识别部分状态、核对行数并取得单独批准。
迁移及验证完成前，保持 `AUDIT_PROCUREMENT_MATERIAL_WORKER_ENABLED=false` 和
`AUDIT_BUDGET_MATERIAL_WORKER_ENABLED=false`。迁移完成后也应先以只读 Catalog 和零业务写入
smoke 验证，再逐个开启 worker。

## 5. Application and dependency preflight

- Java 为 JDK 17 executable JAR；TongWeb 介质未验证前保持 JAR-first。
- `local-dev`/`dev29`/`customer` 的 operation audit 必须开启。
- DM8、Redis Session、storage root、AI/OCR base URL 只能由环境变量注入。
- Java 到 FastAPI 的 health/contract 路径应分别验证，不输出模型 Token。
- Redis 不可用不得损坏 DM8 结果；Session 丢失最多要求重新登录。
- FastAPI 原生 PaddleOCR/模型依赖独立运行，不安装进 Java 主机/JAR。

## 6. Reverse proxy contract

Nginx 只做代理/静态前端/HTTPS，不承载业务事实。长 OCR/AI 同步请求的当前兼容配置必须保持约
`650s` reverse-proxy read timeout 语义，并同时核对 connect/send/client timeout、上游 Java timeout
和模型服务 timeout，避免某层更早截断。

升级或证书同步后，以配置检查和一次受控长请求确认该值仍生效。已有 ReviewRun 的
durable server worker、HTTP 202 与 polling；同步 timeout 只服务仍存在的同步路径。

## 7. Sanitized smoke sequence

在 `<PUBLIC_HOST>` 测试前，先从 LAN/内部 HTTPS 执行同一最小 fixture：

1. `GET /api/v1/health`，记录 HTTP status、requestId、commit；
2. 获取 CSRF、登录，确认 Session Cookie 属性，不记录 cookie 值；
3. 创建 Project；
4. 上传已批准的非敏感 fixture；
5. 观察 parse/OCR provenance；
6. 核对角色评估、人工 RoleDecision、项目身份评估/IdentityDecision 和 Candidate 的材料归属；
7. 如需 AWARD_NOTICE support authority，核对 Candidate 专属选择、冲突及人工确认；
8. 人工 Confirmation 后 Promotion，核对正式 Contract 与 ProjectContract 关联；
9. 创建 ReviewRun，核对冻结 Contract/材料 manifest 和 F02 五条规则结果及其来源；
10. 核对 semantic/breach/term finding/evidence，并验证 evidence drill-down；
11. 刷新页面及重启应用后按精确 runId/`/latest` 重开，核对 F02、非 F02 结果和
    acknowledgement/report 的持久状态；
12. 记录脱敏 status、duration、requestId/traceId，不记录正文、cookie 或 raw model response。

本 hardening 分支只读解析了 01—05 套合成原始成交通知书，确定性 extractor 已能从
无冒号字段行提取成交金额和供应商，并保留文件/页来源；真实上传、角色/归属、Candidate、
人工确认和 Promotion 的全链验收仍须单独完成。P1-B 非空 AWARD_* 验证采用的
派生合成 fixture 仍按历史证据标注，不能回填为原件自动抽取通过。

公网 smoke 对 `<PUBLIC_HOST>:<PUBLIC_PORT>` 重复 health、TLS/SNI 和登录入口检查。只有从批准的公网
网络真实完成访问与业务 smoke 后，才能把 reachability 标记为 VERIFIED。

## 8. Public HTTPS failure isolation

按层定位并保留 safe evidence：

```text
DNS resolution
  -> public listener / port
  -> firewall / ACL / NAT mapping
  -> return route
  -> TLS certificate + SNI + chain
  -> Nginx listener/vhost
  -> internal HTTPS/upstream
  -> application health
```

内部 LAN E2E 已通过时，公网连接失败优先检查入口到 Nginx 的网络/TLS 层。每层记录时间、测试点、
目标 placeholder、结果/reason code；不提交真实地址和证书路径。

## 9. Rollback and acceptance

应用、前端、Python、Nginx 和数据库分别定义 rollback。数据库有写入/DDL 时必须先有备份与明确
逆向方案；UNKNOWN 状态停止。验收报告至少包含命令、执行位置、时间、artifact SHA、HTTP/业务
观察结果、失败层和负责人。

当前可接受表述：

```text
LAN_E2E=REPORTED_PASS
HTTPS_CERTIFICATE_SYNC=REPORTED_COMPLETED
PUBLIC_MAPPING=REPORTED_CONFIGURED
PUBLIC_HTTPS_CONNECTIVITY=NOT_WORKING / NOT_ACCEPTED
```

在公网实际成功前，禁止写 `PUBLIC_DEPLOYMENT=VERIFIED`。

## 10. A-mini 409/502/503/504 现场取证

同一次操作只按真实关联键收集：操作时间、Request ID、projectId、fileId、HTTP method/route/status、
安全 classification、Java 最后可证明阶段、FastAPI 是否收到请求、模型外呼已知状态、Fact Attempt
状态、外层及 Stage 幂等状态。禁止把不同文件、不同 Request ID 或不同 operation identity 拼成一次
故障，也不收集正文、prompt、raw completion、provider body、cookie、Authorization 或数据库秘密。

建议的只读判别顺序：

1. 浏览器 Network 先区分自动 `GET .../contract-fact-extractions/latest` 与用户主动
   `POST .../progression/resume`；两者即使同为 409 也不是同一分类。
2. 在 Java 技术日志按 Request ID 查 `PROGRESSION_PRECHECK`、`AUTHORITY_CHECK`、
   `FACT_SEGMENTATION`、`AI_HTTP_*`、`DM8_COMPLETION`、`RECOVERY_TERMINAL`。
3. 只有 Java 记录 `AI_HTTP_STARTED` 后，才继续在 FastAPI 以同一 Request ID 查 HTTP 接收、模型
   gateway 和 `FACT_RESULT_VALIDATION`；`NOT_CALLED` 表示确定未外呼，单纯缺日志表示未知。
4. FastAPI 200 后仍需在 Java 确认结果校验和 `DM8_COMPLETION`。complete 失败或事务结果不可证明时，
   保留 `UNKNOWN`，不得写成模型失败或自动重试。
5. 以批准的 SELECT-only 工具核对 Fact Attempt 与两层幂等记录；技术日志不能代替 DM8 事实。

常见分流：

```text
GET latest 409                 -> Authority/read precondition；通常未调用 FastAPI
POST progression/resume 409    -> 读取 response classification，再核对外层/Stage 状态
FastAPI 200 + Java validate失败 -> 下游已返回，Java 未接受结果
FastAPI 200 + Java complete失败 -> 下游已返回，DM8 提交结果需按 UNKNOWN 取证
502/503/504                    -> 分别核对 Java adapter、FastAPI gateway 与 provider 已知状态
```

`RECOVERY_OUTCOME_UNCERTAIN` 必须保留原 Idempotency-Key 对应的操作身份，禁止现场人员重复点击
或清理幂等记录。

`FACT_SEGMENT_BUDGET_EXCEEDED` 是切段器在创建 Fact Attempt 和调用 FastAPI 之前产生的确定性容量
拒绝。当前实现仅捕获这一精确异常类型，将外层与 Stage 幂等记录收口为 FAILED，并返回同名分类；
普通 RuntimeException 仍保持 `RECOVERY_OUTCOME_UNCERTAIN`。同 key 重放遵循既有 FAILED 契约，
不会再次执行；缩小材料后应使用新的人工操作身份。

## 11. Diagnostic release rollback

发布前必须保留已验证的旧 JAR、旧 Python artifact/manifest、旧前端构建物及各自 SHA-256，并确认
启动 profile 与 secret 注入方式不变。若新版健康检查、登录、受控 fixture、日志安全扫描或 durable
状态核对任一失败：停止新操作，保存 Request ID 与脱敏日志，停用新版进程，按现场既有受控脚本恢复
三个旧 artifact，再重复 health/login 最小 smoke。日志版本回滚不授权数据库 migration、幂等记录清理、
业务记录修复或模型配置变更；发现 UNKNOWN 时保留现场并另行审批。
