# 扫描采购材料读取修复与验证（2026-10-30）

## 现场原因与本轮改动

依据 ScanDiag-20261030-141838：162 页扫描 PDF 因 100 页上限被拒绝；两页扫描 PDF 遇到现场 120 秒 OCR HTTP 等待限制；61 页材料仅保存前 3 页 375 字符，模型没有拿到完整材料。不能把这些情况解释为原材料没有金额。没有磁盘或内存不足的证据。

本轮不改前端、不新增数据库表或迁移、不清历史任务、不改模型地址及密钥。

- 采购自动入口读取上限调整为 512 页、768000 字符，保留原 500 MB 限制。预算业务上限本轮未扩大。
- PDF 先读取各页原生文字，再逐页 OCR。采购将确认完成页面存入已有 AUDIT_CASE_FILE CLOB，重启后复用，校验源 SHA、页码、策略和执行者。不能将模型调用 UNKNOWN 盲目重发。
- 超过 96000 字符的采购正文分成不超过 48000 字符的窗口，稳定窗口标识、逐窗持久化，不截掉尾部。仍受有限调用和时间预算约束。
- 后续审查优先封面及已核验事实对应原文，再补正文。每文件 12000 / 合计 54000 字符上下文限制保留，不冒称长文件全部条款均完成语义分析。
- OCR HTTP 等待至少 1900000 毫秒，让 Python 隔离工作进程的 1800 秒期限明确返回；旧现场 120000 配置自动提升且告警，用户更长值保留。模型等待不变。
- 离线轻量模型采用 PP-OCRv5_mobile_det + PP-OCRv4_mobile_rec。缺少完整离线权重时保持原精度路径。低置信页使用原精度模型复读，保留质量更好的整页，不拼接冲突金额。短标题“合同书”不因字数少重复四向识别；弱探测片段不覆盖完整页。
- CPU threads=1、MKLDNN=false、进程隔离及有效单 OCR 槽不变。不安装或升级 OCR 核心依赖。

## 本地执行证据

Windows / Java 17 / 项目 Python 测试虚拟环境。

```powershell
# backend 目录
.\mvnw.cmd clean verify

# 仓库根目录，PYTHONPATH=services/audit-ai/src
.test-runtime/contract-auto-python/Scripts/python.exe -m pytest services/audit-ai/tests -q -o addopts=--basetemp=.test-runtime/scan-python-final
```

- Java 最终 clean verify：3435 tests、0 failures、0 errors、24 skipped，BUILD SUCCESS，5 分 24 秒。日志 `.runtime/scan-repair-final-verify.log`。
- Python 最终全量：2049 passed、14 skipped、43 subtests passed；1 条既有 TestClient 弃用警告。日志 `.runtime/scan-python-final.log`。
- 对抗回归：162 页第 80 页模拟崩溃恢复，不重复已确认 OCR、定位不漂；原生/扫描混排恢复一致；错误源 SHA/策略拒绝；提交失败/执行者失效停止；后窗失败保留前窗事实；尾页金额原文进入上下文；精度复读空白/碎片不能覆盖完整页。

## 26 代表性实测

所有文件均为专门制作的合成材料，没有导出甲方正文。

- 原精度模型：两页密集扫描件 218.377 秒；侧向短标题 62.612 秒。
- 最终轻量配置：同两页内容 41.192 秒、1363 字符、预算 2200000 存在；侧向“合同书”8.702 秒且标题存在。
- 四页预算批复/报价/中标/合同，采用不同字体与 0/90/180/270 度，41.358 秒、1001 字符。人工核对：预算大写贰佰贰拾万元、报价 1987654.32、中标大写壹佰玖拾万元、合同 2200000、日期和万分之五条款均存在。不能仅用字符数或内部方向评分证明准确率。
- 曾试验 v5 mobile 识别模型但出现重要标签遗漏，未采用；最终采用 v4 mobile 识别模型。
- 独立 QA 真实 DM8（QA26_FULL2）：实际 Dm8AuditCaseRepository / AutoCaseMapper 大中文 CLOB 检查点保存、新 Session 重开、旧版本写入拒绝、事务失败回滚后检查点保留均 PASS。无 DDL，只新增专属合成终态记录。执行器 `.runtime/ScanCheckpointDm8.java`，26 独立测试目录保留脱敏结论及本机私有明细。

162 页四方向合成扫描 PDF 使用真实 PaddleOCR 隔离进程读取，1074.625 秒，162 页全部非空、页码连续，最后一页预算 2200000 存在；逐页核对合成页码也完整。仅此样本耗时约 17 分 55 秒。它采用每页少量文字，不能套用到密集扫描件。

Java → 真实 HTTP multipart → Python 隔离 OCR 的四方向文件：第 2 页确认后模拟中断，序列化保存检查点并重开，共只调用 4 页；预算、报价、中标、合同、大写金额和日期均保留。QA 入口及默认入口分别执行通过，不调用模型。

26 默认入口已更新至 `/opt/audit-agent/releases/scan-20261030-r1`。实际 backend / AI 进程工作目录、JAR 及 AI 源码哈希均与候选一致。JAR SHA-256：`1a0d7d64137ca84170d15ab4fa5ad4afeac988c59bf6f5d97a1aa593cea2be43`。前端入口哈希维持 `be33d0929cfca0a212a8283ef20f3ab4e0e0afedf2e85f869e3a5f391ff51a2d`，页面没有更新。配置与数据库结构未改，旧 release 保留。

默认入口四页 HTTP 恢复测试 48.14 秒，识别期间 48 次页面及健康查询 HTTP 200，最长 7.53 毫秒。这是合成小负载下的可用性结果，不是并发压力验收。

实际命令 `curl --noproxy '*' -fsS http://127.0.0.1:8080/api/v1/health` 返回 `{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`；AI 的 `/health` 返回 `{"status":"UP","service":"audit-ai"}`。

最终结构化结果见 `evidence/scan-20261030/verification.json`。模型分析部分依然单独标记未验证。

## 验证边界与后续制包

现有模型网关连接约 5 秒超时，测试 HTTP 502、脱敏原因 CONNECT_TIMEOUT。用户确认服务暂不可用。本轮不切换外部模型。真实模型提取及最终分析结果验收为 **NOT VERIFIED**；Fake 回归、OCR 或 HTTP 健康成功不能代替。

甲方真实文件准确率、真实 162 页密集扫描件耗时及 ARM64 现场均未验证。本轮长页数样本是每页少量文字的合成扫描件，不能套用其耗时估算真实文件。

本轮未制甲方更新包、未提交 Git。后续制包使用最终验收 JAR，并携带两套轻量离线模型各自 inference.json / inference.yml / inference.pdiparams，保留原方向模型、原精度模型缓存及现场配置，禁止现场上传时在线下载权重。共享 OCR 改动须保留预算/采购/合同回归。本轮不需要新 schema 判定、恢复 JSON 或备份证明步骤。
