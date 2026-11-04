# 预算四页扫描PDF：现场脱敏诊断

用户反馈：甲方预算模块约17点上传四页扫描PDF，文件存在预算金额但结果没有相应信息。尚未拿到该份现场原始任务的诊断结果，根因未确认。

## 一次执行

将 `E:/audit-agent-new/deliverables/ScanDiag_v2.tar.gz` 传到现场，在包所在目录执行：

```bash
tar -xzf ScanDiag_v2.tar.gz && sudo bash ScanDiag_v2/run.sh --ocr-probe --model-probe
```

默认服务器当天及前后各一天、最近100份预算任务。附带最多20批采购/合同技术状态。不是只取17点附近；范围达到上限会记录limitReached。需要指定上传日期可加 `--day YYYY-MM-DD`，预算上限可改 `--limit 300`。

数据库只执行SELECT，连接readOnly且结束rollback；不升级、不重启、不修改数据库、业务材料或原结果。使用现有Python3.11虚拟环境及运行JAR的DM/Jackson依赖，交付Java17 class，现场不用编译或安装依赖。

## 观察范围

1. 预算上传任务、读取状态、金额提取数量、原文核验被拒原因、最终申报/批复金额是否存在。
2. 原PDF页数与旋转标签；最多40个来源、每份最多8页原生文字采样，四页文件完整检查。
3. OCR和模型原调用的脱敏技术日志、分段进度及终态原因。
4. 对最近最多3份四页预算PDF做独立逐页复读，调用正在运行的OCR服务；不另起Paddle实例。导出逐页字数、固定预算/申报/批复/金额线索数量、大写数字数量和置信度。
5. 优先对复读所得预算附近小段做最多2次BUDGET模型对照，分别记录模型返回数量及原文支持数量。不是整份正式分析重跑，也不据此改写历史结果。

预算数据库FACTS_JSON是提取后的信息及引用片段，不是完整OCR正文。脚本明确记录这一边界，不把片段少或没有片段当成原OCR空白；独立复读成功也不证明原任务当时读取成功。

## 导出与隐私

`OUTPUT=` 指向唯一回传压缩包，含8个成员：database.json、pdf.json、ocr.json、model.json、events.jsonl、runtime.json、summary.json、findings.json。

不含正文、文件名、PDF、图片、截图、单位/人员名称、实际金额、原始日志、访问地址、密钥或模型原回答。服务器本地临时读取原文件/正文，仅生成技术计数及固定枚举；私有探测片段不进入export目录，结束后清理。

`DIAG=COLLECTED` 仅表示收集完成，失败/超时/不可用保留为技术状态，不解释成材料没有金额。findings中的提示不冒充已经确认的根因。

## 验证

- Windows：33项诊断测试通过，包含四页PDF四方向真实渲染及本地HTTP对照、隐私canary、引用片段和全OCR覆盖区分、模型金额类型投影、路径穿越/符号链接拒绝、失败状态及端到端归档。日志 `.runtime/scan-diag-v2-python-tests.log`。
- Java17 UTF-8编译及FieldDiag隐私自检通过；class major61。最终包13个成员，SHA清单、LF、shell/Python语法通过。
- backend下 `.\mvnw.cmd clean verify`：3445项，0失败、0错误、24跳过。日志 `.runtime/scan-diag-v2-java-verify.log`。业务源码未因本诊断工具修改。
- 26：最终包独立暂存后验证，真实四页0/90/180/270度扫描图片PDF→HTTP OCR全部有文字，并取得预算附近金额线索；真实DM8预算查询及最终采集归档结果见 `evidence/scan-diag-v2/`。
- 甲方实际任务根因和现场模型识别质量尚未验证，等待用户回传输出包。未提交Git，未更新业务服务。
