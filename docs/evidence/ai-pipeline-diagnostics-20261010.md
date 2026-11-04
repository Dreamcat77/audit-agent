# 本地模型关联日志验证（2026-10-10）

## 变更范围

Java 与 Python 的诊断日志覆盖上传入口、文件暂存及 promotion、存储状态、DM8 开始/完成提交与重新读取、文档解析、OCR、Java→FastAPI HTTP、分块准备/验证/合并、模型请求/响应及 JSON/证据校验。使用同一 `X-Request-Id` 贯通 Java `requestId` 与 Python `request_id`，每次模型调用另有 `call_id`，每个 Java 阶段另有 `stageId`。INFO 可见，不依赖全局 DEBUG 或旧 flow-debug 开关。

可观测字段包括输入字节/字符、段落/页/明细数量、分块规模和时间预算、请求输出 token 上限、响应 token usage 和 finish reason、HTTP 状态、请求/响应字节数、异常类、SQLState、DM8 错误码和代码位置。提供方错误只作有限分类，不将其错误原文写入日志。配置仅记录完整标志和模型/地址指纹。

主要文件为 `PipelineDiagnostics.java`、`MaterialIntakeService.java`、`FastApiHttpClientConfiguration.java`、`FastApiMaterialFieldAdapter.java`、`FastApiOcrAdapter.java`、合同/预算编排、请求 filter 与异常 handler；Python `diagnostics.py`、`logging.py`、`main.py`、`errors.py`、`model/gateway.py`、`model/material_fields.py`、`model/router.py`、`ocr/service.py`。收集工具为 `scripts/collect-ai-diagnostics.sh` 和 `tools/ai-log-extract.py`，现场说明见 `docs/ai-local-model-diagnostics.md`。

新日志不打印密钥、密码、完整连接串/模型地址、Cookie、文件名、正文、prompt、回答全文或 SQL 参数。未改变业务金额规则、长期业务审计、幂等身份、lease 和恢复状态。OCR multipart 不添加读取完整 body 的 interceptor，避免因日志增加大文件内存缓冲。

## 本地验证

Windows / 现有 JDK 17 / Python 3.11，仓库实际路径为 `E:\audit-agent-new\.runtime\integration-repo`。沿用已有未提交变更，没有提交、推送或覆盖用户修改。

```powershell
# backend 目录，使用已安装 JDK 17
.\mvnw.cmd clean verify
# services/audit-ai 目录，使用既有 venv 与忽略目录中的测试依赖
python -m pytest -q
# 仓库根目录
git diff --check
```

最终 Java：`2164 tests, 0 failures, 0 errors, 2 skipped`，`BUILD SUCCESS`。Python：`442 passed`。diff 检查通过。本轮未改前端。执行记录为 `.runtime/diagnostics-backend-r6-verify.log`、`.runtime/diagnostics-python-r6-tests.log`。

回归包括 SQL/密码/prompt 不进入日志、提供方 400 上下文超限/401/429 分类、输出截断与 token 用量、请求验证 422 不回显输入、并发 ContextVar 隔离、Java→Python 请求编号透传、OCR 不注册缓冲 interceptor、脱敏 collector 过滤。

真实平台验证发现 Spring Boot 的 Logstash JSON encoder 拒绝 MDC 与 fluent key/value 中重复的 `requestId`，导致新增阶段日志丢失。已避免重复写入 MDC 字段，保留消息内安全请求编号；新增使用真实 `StructuredLogEncoder` 的回归测试。测试和 26 实际 JSON 输出均确认修复，不能仅凭 ListAppender 捕获消息认定生产日志可写。

## 26 部署

代表环境：Kylin V10 x86_64、BiSheng OpenJDK 17.0.19、Python 3.11.16、真实 DM8 与 Redis Session。当前 release 为 `/opt/audit-agent/releases/0b64c63-diagnostics-20261010-r6`，前端复用已部署 r3。

JAR SHA-256：`708828d56a2405df8ca5e8b7bc52a455502b02afb7d964bbd0be5913e9279de7`。

代码归档 SHA-256：`e126c53cb659bbde3be69c4bd8796e730e6904fbd1a125af27b31c788f6f3469`。归档仅含 JAR、Python 源码、收集脚本和说明，不含环境文件、业务文件或数据库导出。发布基于未提交工作树，不是 Git 正式 release。

部署复制旧 release，覆盖代码并原子切换 `current`，仅重启后端和 AI。两份受保护环境文件通过部署前后 `sha256sum -c`；模型地址/密钥/名称、Nginx 和 Redis 配置未修改。本轮没有执行 DM8 迁移、删除业务材料或重新初始化数据库。r3、r4 均保留；r4 存在上述日志编码缺口，需要回滚时优先选择已验证的 r3 基线。回滚代码不能回滚或删除业务数据。

26 的验收脚本位于 `/home/admin/batch12-20261010`，使用虚构材料和随机测试身份；测试密码仅在进程内存和匿名管道中传递。结束后停用测试身份，保留测试业务/审计事实。

```bash
cd /home/admin/batch12-20261010
BATCH12_HTTP_BASE=http://127.0.0.1 bash run-batch12.sh diagnostics-smoke
/opt/python/venvs/audit-ai/bin/python diagnostics-validation-probe.py
/opt/python/venvs/audit-ai/bin/python diagnostics-ocr-probe.py
cd /opt/audit-agent/current
sudo bash scripts/collect-ai-diagnostics.sh --since '30 minutes ago'
```

Python 的格式错误和重复定位符探针分别得到 HTTP 422 / `REQUEST_VALIDATION_FAILED` 和 `MATERIAL_AI_DUPLICATE_SOURCE`，响应编号正确。独立 OCR 的虚构 PNG 得到 HTTP 200、59 字符、1 页，响应编号正确，导出 `AI_HTTP_IN/OUT`、`OCR_START/UPLOAD_READY/END` 五条关联日志。此 OCR 探针在 r4 Python 代码执行，r5 Python 源码相同；没有宣称扫描 PDF 经完整 Java 业务链路验收。

r5 采购上传得到 HTTP 200 / `PARSED`，耗时 26.52 秒；按响应编号导出 43 条事件（Java 28、Python 15），单请求筛选、manifest、模型独立调用编号验证通过。Java 暂存/签名/提交/解析/AI/归一化/完成提交/重新读取与 Python 分块/提供方调用均出现。日志编码器没有再报告重复字段错误。

r5 合同复测暴露真实语义失败：请求 `diag-diagnostics-smoke-98a65e8b-15d1-41ce-a288-dd8e0e97de7a`，两次模型 HTTP 均为 200、finish reason 均为 STOP；首次 81.734 秒，修复调用 100.056 秒，最终接口 HTTP 502 / `MODEL_INVALID_RESULT`。日志明确第一次 `MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE`，继而 `CORRECTION_EVIDENCE_INVALID`。这次失败不能归因于网络、输入超限或缺表，也不代表合同所有材料都能通过模型校验。未降低证据校验标准或修改模型来让测试变绿。

r6 collector 扩充已有安全事件的映射和计数字段，解决旧收集器遗漏纠正失败分类的问题。同一失败请求重新导出 44 条事件，输出目录为 `/home/admin/batch12-20261010/ai-diagnostics.btFTWB9N`。输出包含 `CONTRACT_CORRECTION_REJECTED` 与独立 provider 调用。r6 还增加预算存储、重读、审查读取/失败提交，以及通用数据库异常的诊断；模拟缺表异常回归确认 SQLState/DM8 错误码存在，SQL 文本不输出。未在真实 DM8 上制造缺表或执行破坏性探针。

### 最终 r6 验收

最终采购上传 HTTP 200 / PARSED（25.27 秒）；预算上传 200（0.20 秒），review 200（21.94 秒），AI 200 / available=true（17.56 秒）；合同上传 200（40.02 秒），AI 200 / available=true（152.41 秒）。预算 review 仍按既有规则暂停待人工确认，不能当作 80 行全部审批完成。合同分析日志包括首次分析与证据修复两次调用，定位符校验完成。

执行以下关联核验，而非只检查日志文件存在：

```bash
/opt/python/venvs/audit-ai/bin/python /home/admin/batch12-20261010/diagnostics-log-acceptance.py \
  /home/admin/batch12-20261010/ai-diagnostics.wrxRCTes \
  /home/admin/batch12-20261010/results-diagnostics-smoke.json
cd /home/admin/batch12-20261010/ai-diagnostics.wrxRCTes
sha256sum -c manifest.sha256
```

输出 `REAL_PIPELINE_LOG_CORRELATION=PASS`，六个 HTTP 请求分别关联 43/10/27/21/25/44 条事件，每个 Java stageId 开始/结束一致，模型 call_id 开始/结束一致，必需 Java 阶段、Python 入口与 provider HTTP 响应均存在。完整时间窗口导出 188 条事件，无截断；manifest 三个文件均 OK。独立请求筛选与真实合同失败分类也已验证。

最后通过 protected 环境调用既有 `Batch12StateCheck results-final.json`，观察到真实 DM8 重新读取 `PARSED=12`、`TAIL_EVIDENCE=12`，测试身份恢复检查通过，`SYNTHETIC_ACTIVE_TEST_ACCOUNTS=0`。r6 smoke 输出 `SYNTHETIC_TEST_IDENTITY_DISABLED`。登录成功同时验证现有 Redis Session 链路。

外部 Nginx 入口 `curl http://127.0.0.1/api/v1/health` 得到 `{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}` / HTTP 200；未登录采购列表得到 `{"code":401,"message":"authentication required","data":null}` / HTTP 401。backend/AI/Nginx 三个服务均 active，两份环境文件 hash 校验仍为 OK，实际 JAR hash 与本地一致。r6 时间窗口内 JSON appender failed 计数为 0。

## 外部边界

甲方 ARM64、旧浏览器以及甲方本地模型的真实上下文限制、代理读取时限、输出限制和服务端排队仍为 `NOT VERIFIED`。本版本提供失败证据，不推断模型支持的上下文窗口，不改变模型配置，也不解除已有业务安全边界。此前合同 12 份全文联合分析超出受控预算的边界仍存在，详见独立批量测试报告。

现场需要同时更新 Java 和 Python，复现后及时运行 collector。输出仅包含白名单 metadata 与固定服务状态，不导出原始 journal/env/unit/业务文件。最多 100,000 条，截断标志必须检查。没有修改系统 journald 保留策略，零条记录不等于无故障。
