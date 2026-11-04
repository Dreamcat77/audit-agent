# 预算、采购本地模型输入限制适配

本次仅修改 AI 服务调用、分段、短期重试复用和脱敏诊断；页面不变。不连接、不更新 26，未接触甲方运行配置、模型地址/Key/名称、业务数据或数据库结构，未提交 Git。

## 行为变化

此前材料提取仅对输出 LENGTH 截断缩段；HTTP 400/422 输入上下文拒绝及 HTTP 200 业务错误无法进入缩段。F10 招标审查还会切割序列化 JSON，分段可能丢失完整结构。

现在保留网关安全错误分类，识别输入上下文超限与输出截断，只拆失败段。白名单、认证、限流、协议、服务器和网络错误保持失败，不盲目缩段重试。正文以字符和 UTF-8 字节双重限制，优先行/段落，长行携带同定位符邻接上下文。摘录仍需原文依据，重叠上下文不新增重复预算行；项目身份冲突保留，金额计算仍在 Java。

材料提取有 192000 字正文总上限、最多 512 次分段处理、5 层缩段深度和 900 秒处理预算，无法适配最小分段时明确返回限制错误，不交付部分成功。预算语义分析按完整 itemId 业务行拆批，继续校验缺失/重复/外来 ID；一条原子业务行不能容纳时停止，禁止截断该行 JSON。

F10 招标审查改为完整 JSON 分段，携带页码、定位符和相关规则证据，所有文本均保留；未被 segments 覆盖的 fullText 以不可引用的 supplementaryText 保留。仅允许引用本次真正发送的定位符及原文，不接受其他分段的证据。单文件任意远距离跨页关联能力仍需真实模型实测，不能把有界邻接上下文描述为全文理解保证。

提取提示词版本更新为预算 v5、采购 v4，避免与前一版混淆。

## 短期续处理与隐私

同一材料、SHA、源文本/定位符、业务类型、分段配置、模型配置的重试可复用已校验分段，复用前再校验来源。默认仅在内存复用。可选 `AUDIT_MATERIAL_CHECKPOINT_ROOT` 私有绝对目录支持解释器/服务重启后的重试复用，启动校验专用目录权限；目录包含敏感提取字段与摘录，默认不启用，不纳入诊断导出。

成功后清理本次记录；默认 900 秒后禁止复用，后续缓存活动惰性清除过期记录。记录/总大小有限，缓存损坏、丢失、过期或被淘汰均重新计算，不当成“无字段”。它是可丢失的计算缓存，不替代 DM8 任务、业务结果与操作审计；不自动重新调度失败业务任务。

输入大小是 source envelope 字符/字节数，不是 tokenizer 精确 token 数。提示词、协议开销、保留输出也占模型上下文；提示词本身都无法容纳时仍会明确失败。

## 修改文件

- 配置与装配：`.env.example`、`services/audit-ai/src/audit_ai/config.py`、`main.py`。
- 核心：`model/gateway.py`、`material_fields.py`、`router.py`；新增 `material_chunks.py`、`material_checkpoints.py`、`adaptive_calls.py`、`tender_chunks.py`。
- 日志：`logging.py`、`tools/ai-log-extract.py`，新增缩段原因和续用事件的安全导出，仍不输出原文、提示词、模型响应正文或密钥。
- 验证：`test_material_adaptive_limits.py`、`test_tender_adaptive_limits.py`、`test_adaptive_calls.py` 以及既有材料、预算、配置、诊断回归测试；新增可重复的本机 socket smoke `tools/test-material-model-limits-local.py`。
- 部署参数与边界说明：`services/audit-ai/README.md`。

既有未提交修改全部保留。本轮 diff 用 `git diff --check` 检查通过。

## 本地验证

环境：Windows；隔离 Python 3.11.9，现有 Java 17.0.18。Python 3.11 虚拟环境位于工作区 `.runtime/material-python311`，仅安装 AI Web 运行依赖与 pytest，无 Paddle 或系统依赖改动。真实版本以解释器输出为准。

```powershell
.runtime/material-python311/Scripts/python.exe -m pytest services/audit-ai/tests -q
$env:PYTHONPATH='services/audit-ai/src'
.runtime/material-python311/Scripts/python.exe tools/test-material-model-limits-local.py
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
cd backend
.\mvnw.cmd clean verify
```

Python 全套：519 passed、1 skipped，0 failed；跳过 Windows 无符号链接创建权限的真实 symlink 用例。Starlette TestClient 有上游弃用提示。覆盖 400/413/422/200 业务错误上下文限制、输出截断、白名单/认证不缩段、UTF-8 与 JSON 开销、边界金额/单位、相同金额不同科目、多项目同模型 rowKey、尾部证据、缓存损坏/过期/身份变更/配置变更、实际子解释器重启读取、容量和时间边界。

本机 socket HTTP：真实 Uvicorn API → 真实网关代码 → 本机仿真模型 HTTP 服务，预算/采购各 12 个长短文本输入，约 219～96019 字符。模型服务刻意拒绝每次正文合计超过 750 字符的请求；结果逐一断言首/中/尾字段齐全且不重复。通过合成 transport 发送真实 plain HTTP socket，以避开 Windows 在每次测试调用重复载入 TLS 信任库的额外开销；它不改生产网关，也不验证外网 TLS。

实际输出：`LOCAL_SOCKET_HTTP_24_MATERIALS=PASS {"rejected": 1210, "accepted": 1638}`。这些是解析后的仿真文本，不代表 24 种 Office 文件格式或真实 OCR 大模型准确率已验证。

真实 curl：`curl.exe --fail --silent --show-error http://127.0.0.1:<临时端口>/health`，响应 `{"status":"UP","service":"audit-ai"}`。测试脚本结束会关闭全部本机服务。

后端 clean verify：2296 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。日志 `.runtime/material-adaptive-backend-verify.log`。

Python 全套日志 `.runtime/material-adaptive-python-final.log`；socket 测试日志 `.runtime/material-adaptive-local-http-final.log`。

## 后续部署边界

26 和甲方环境：NOT VERIFIED，本次未尝试远程连接。后续部署必须带全新增 Python 模块与日志导出工具，保留现有 AI 地址/Key/名称；只做源代码刷新，不重建 OCR venv。默认内存续用无需新配置；如启用磁盘续用，由运行账号创建专用私有目录并配置上述变量。更新 AI 服务后，再用真实模型验证可接受输入、输出参数、表格行/跨页材料与最终结果准确性。
