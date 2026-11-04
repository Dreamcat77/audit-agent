# 甲方本地模型调用日志排查

日志版的 Java 后端和 Python AI 服务必须一起更新。仅换 JAR、仅运行收集脚本，无法获得完整链路。无需改模型地址、Key、模型名称、Nginx、数据库或 Redis 配置，无需安装新 Python 依赖。甲方环境尚未执行本版本验证，状态为 `NOT VERIFIED`。

## 现场如何提供日志

先复现一次问题，记下服务器上的时间、业务模块和出错材料记录编号。浏览器开发者工具的网络响应头 `X-Request-Id` 是本次请求编号。重试通常会产生新请求编号，材料的 `resourceId` / `material_id` 保持关联。多个上传请求不是一个请求编号，不要只收集最后一份文件的请求。

保留本包的 `scripts`、`tools` 目录，进入更新代码目录运行：

```bash
sudo bash scripts/collect-ai-diagnostics.sh --since '30 minutes ago'
```

针对单个请求可用：

```bash
sudo bash scripts/collect-ai-diagnostics.sh --since '1 hour ago' --request-id '替换为响应头中的编号'
```

输出目录在当前目录下，以 `ai-diagnostics.` 开头。提供 `summary.json`、`pipeline.jsonl`、`service-status.txt` 即可。脚本不会调用模型、写数据库、改配置、读取业务文件或导出原始 journal。目录权限由 `umask 077` 保护。收集最多 100,000 条关联事件，超过时 `truncated=true`，应缩短时间范围后重收。零条记录不代表没有问题，可能版本未更新、未复现、时间不对、请求编号不对或没有 journal 权限。

日志默认 INFO 即可，不必开启 HTTP wire、SQL 参数或全局 DEBUG。服务标准输出由现有 systemd journal 保存；本补丁没有修改全机 journald 的保留策略。现场应在复现后及时导出，系统若使用易失 journal，重启后可能无法找回此前日志。

不能把日志数量当作调用次数：一条请求有多个阶段，每个阶段又有开始和结束。每次模型请求使用独立 `call_id`；Java 每个阶段使用独立 `stageId`。对模型调用计数应按 `PROVIDER_CALL_START` 或唯一 `call_id`。

## 关联链路

Java 的 `requestId` 和 Python 的 `request_id` 表示同一个 `X-Request-Id`。Java 下游请求以及 Python 模型请求均传递该响应编号；模型服务器能否记录此 header 取决于其实现。本系统自己的日志不依赖模型服务器支持此 header。Python ContextVar 在异步请求和工作线程之间传递并隔离上下文，避免并发文件串线。

入口记录 `PIPELINE_STAGE_START/END stage=REQUEST`，Python 记录 `AI_HTTP_IN/OUT`，包含 HTTP 状态与总耗时。Java HTTP 状态 200 不等于业务材料解析成功，还要看 `MATERIAL_PROCESS` 的最终 `PARSED/FAILED/UNKNOWN`。Java 下游 `HTTP_RESPONSE` 只代表收到了 HTTP 响应，不等于 JSON、证据或业务结果已通过校验。

采购依次记录文件暂存、签名校验、DB 开始提交、文件 promotion、存储检查及实际存储状态、文档解析/OCR、AI 字段提取、字段归一化、DB 完成提交和重新读取。`DB_COMPLETE_COMMIT` 在事务代理返回后记录；`STALE_LEASE` 表示本次 lease 不能覆盖当前结果，应读取现有状态。`UNKNOWN` 是存储或事务结果不确定，不能当作文件不存在或随意删库重传。

合同记录文件暂存、开始提交、promotion、文字解析和解析方法、解析字符/段落数量、完成提交；AI 分析记录所选材料读取、输入字符数、分析调用与定位符校验。预算记录上传状态保存、材料解析行数、解析结果保存、读取已持久化明细、逐行/批次 AI、审查进度提交和人工确认暂停。`WAITING_CONFIRMATION` 是规则要求人工确认，不是所有行都已经完成。

Python 提取链路记录分块准备、输入字符/段落数、显式技术要求事实数、当前分块/总分块、每块开始/验证/合并。预算按完整条目分块，并记录漏项、重复项、跨块编号数量。合同保留已有的证据目录、分类覆盖、语义重试、传输重试、纠正和最终拒绝分类。

每次模型调用记录 `PROVIDER_CALL_START`、`PROVIDER_HTTP_SEND`、`PROVIDER_HTTP_RESPONSE`、`PROVIDER_ENVELOPE` 和 `PROVIDER_CALL_END`。可见 prompt/user 字符数、实际请求/响应字节数、连接/读取超时、请求输出 token 上限、响应格式、是否关闭 thinking、提供方返回的 token 用量和 `finish_reason`。`max_output_tokens=0` 表示没有显式指定上限；`remaining_budget_ms=0` 在未传入累计时限的调用中表示没有该项信息，不能据此认定模型允许零输出或预算已耗尽。缺失 token 字段表示提供方未返回，不用字符数冒充 token 数，也不能仅凭模型名称推断上下文窗口。

`endpoint_fingerprint`、`model_fingerprint` 是配置字符串的 SHA-256 前 24 位，可用于判断运行进程是否加载了同一配置；不输出地址、认证信息。日志里的配置完整 `YES/NO` 只表明必需字段存在，不能证明连接可达、Key 有效或模型存在。

## 按失败位置判断

- 入口没有 Java 请求：先检查访问地址、Nginx 上传限制或代理状态。本补丁不修改 Nginx，也不导出其包含客户端 IP 的原始 access/error log。若 Java 仍在处理但浏览器报 504，核对现有代理读取时限。
- `DOCUMENT_PARSE` / `OCR_CALL` 失败：检查格式、容器安全检查、文字/页数上限、OCR 容量或运行环境。OCR 成功不代表后面的模型成功。
- `PROVIDER_CALL_START configuration_ready=NO`，没有 `PROVIDER_HTTP_SEND`：进程未加载完整模型配置。
- `CONNECT_TIMEOUT/CONNECT_ERROR`：请求未得到模型 HTTP 响应。附加 `DNS_RESOLUTION_ERROR/TLS_ERROR/CONNECTION_REFUSED` 仅在异常链能证明时输出；无法证明时保持 `CONNECT_ERROR`。
- `READ_TIMEOUT`：已发起请求但未在记录的读取时限内收到完整响应。结合分块数、输入大小、剩余预算与模型负载排查。
- HTTP 400 / 413 且 `provider_error_kind=CONTEXT_LIMIT`：提供方错误文本命中了上下文超限特征。此分类是有限特征判断，不是对甲方模型设置的直接读取；需要对照其服务器日志确认。
- HTTP 400 / 422 且 `UNSUPPORTED_RESPONSE_FORMAT/UNSUPPORTED_PARAMETER`：提供方可能不支持 `response_format/json_schema/reasoning_effort` 等参数。日志不会自动更改传输协议或关闭校验。
- HTTP 401 / 403：认证或权限；HTTP 404：路径或模型；HTTP 429：限流；5xx：网关或模型服务异常。
- `finish_reason=LENGTH`、`OBJECT_TRUNCATED`、`UNTERMINATED_STRING`：输出可能达到模型上限或被截断；查看输出 token 上限及提供方 token 用量。
- HTTP 200 但 `INVALID_PROVIDER_JSON/INVALID_PROVIDER_RESPONSE`：网关响应或 `choices/message/content` 不符合协议；有响应字节数、JSON 错误类型和位置，不导出原始正文。
- `MATERIAL_AI_INVALID_RESULT/UNGROUNDED`、`BUDGET_ID_COVERAGE_REJECTED`：模型完成了输出，但结构、原文证据或条目编号校验失败。不会用空成功掩盖失败。
- `DATABASE_ERROR`：查看 `sqlState/sqlErrorCode/stackClass/stackMethod/stackLine`，再运行现有只读 schema 对比工具。数据库缺表、列宽和约束问题应分别核对，不能凭一个“AI失败”提示判断。

不记录文件名、正文、prompt、模型回答全文、HTTP Authorization、Cookie、数据库连接串、密码或完整模型地址。原始异常消息和 SQL 参数也不进入新增日志。诊断日志不是长期业务审计的替代品，不修改现有审计事实、幂等身份、lease 或恢复语义。

甲方本地模型的真正上下文窗口、服务端 token 限制、代理超时、排队时延，以及失败请求的服务端原因，需要现场日志才能确认。此版本提供证据，不预先断定一定是模型输入输出限制。
