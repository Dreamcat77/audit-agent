# 采购扫描PDF现场取证工具 ScanDiag_v1

## 本次用途

现场反馈同批两页扫描PDF与约162页PDF均没有关键数据。先区分读取/OCR、模型提取、原文核验和报告归集的断点，再根据现场证据改动业务代码。本次只增加诊断工具，没有修改应用、提高页数上限、清理历史或部署服务。

当前源码及本地构建JAR的 `AuditCaseMaterialLimits` 默认 maxPages=100、maxChars=192000、maxBytes=524288000。162页可能触发页数限制，但现场配置是否覆盖和具体失败原因仍需诊断包；不能据此解释两页扫描件的失败，也不能把没有结果当作源材料没有金额。

## 执行

将 `E:/audit-agent-new/deliverables/ScanDiag_v1.tar.gz` 上传至现场，在包所在目录执行：

```bash
tar -xzf ScanDiag_v1.tar.gz && sudo bash ScanDiag_v1/run.sh
```

自动使用运行服务的DM8/存储配置和现有Java17、audit-ai Python。查询服务器当前日期及前后一天的最近20批采购数据。不需要重新上传或重启。非当天加 `--day YYYY-MM-DD`，最多可加 `--limit 50`。

默认不重新OCR或调用模型。`--model-probe` 是明确选择的可选对照，最多2次当前已配置模型调用，单次180秒，只对已保存的小段文字进行探测，不补写结果。

## 导出边界

- DM8只SELECT，启用只读连接，结束rollback；无DDL、数据修改或服务重启。
- 原文件和保存文字仅在服务器本地读取；不打包PDF、图片、正文、文件名、单位名、实际金额、模型原始回答、凭据或原始日志。
- PDF最多8份/每份512页，单份原生文字与旋转标签检查60秒，不渲染图片。PDFium优先；未安装时使用已存在的pypdf，不安装依赖。
- 日志仅导出固定技术事件、计数、有限原因枚举、时间和UUID。补齐OCR文字、空页、模型分段、累计耗时等指标，按真实文件或提取操作UUID关联。
- 保存文字页码不等于所有页面已成功OCR；扫描图像朝向也不能由PDF旋转标签直接断言。原生页面探测失败不会当作“扫描件无文字”。
- 运行类默认值和可观测覆盖项分别记录；不冒称已完整解析Spring所有外部配置。
- `DIAG=COLLECTED`只表示收集结束；不可见的表、失败查询、日志不可用、探测超时均保留状态，不当作材料本身无信息。

导出包只含 database.json、pdf.json、model.json、events.jsonl、runtime.json、summary.json、findings.json。只需回传 `OUTPUT=` 指向的 ScanDiag-时间.tar.gz。

## 本地验证证据

- Windows：27项Python测试通过，日志 `.runtime/scan-diag-tests.log`。包括真实162页PDF、两页图片PDF及180度标签、加密PDF、页探测失败、未知状态、跨文件日志隔离、未完成分段、隐私探针和完整采集流程。完整采集用Fake DM8/systemd与真实生成PDF，不能当作现场数据库验收。
- Java17：UTF-8 / `--release 17` 编译；`FieldDiag --self-test` 通过，覆盖中文金额统计和页码投影不泄露正文。交付预编译class，现场不需要javac。
- `backend/.\\mvnw.cmd clean verify`：3427项，0失败、0错误、24跳过；BUILD SUCCESS。日志 `.runtime/scan-diag-java-verify.log`。
- 最终包11个成员，清单SHA、LF、Java major61和shell语法通过。源工具目录 `tools/scan-diagnostics/`。
- 本轮真实DM8、甲方文件和现场脚本执行：NOT VERIFIED。收到现场诊断包后才判断根因与修复方案。未提交Git。

包：54,867字节；SHA256：`f1f0ffef019fdb584d500b83b467fc8292cddc479c7725ebbbf3f03644353f4a`。
