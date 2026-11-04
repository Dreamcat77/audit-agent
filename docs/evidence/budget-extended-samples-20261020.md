# 预算材料扩展样本核对

## 范围和环境

本轮按“再多测一些测试样本”增加验收覆盖，不修改预算业务逻辑、采购、合同或页面布局。当前候选包含此前未提交的预算提取及结果样式修改；本轮没有提交、融合、打包或更新正式服务。

`tools/build-budget-extended-fixtures.mjs` 生成 20 组业务边界、39 份文本材料，并选取既有 13 份格式样本：DOC、DOCX 表格、XLS、XLSX、原生 PDF、扫描 PDF、混合 PDF、PNG、JPEG、RTF、PPTX、UTF-16 文本及损坏 DOCX。所有材料均为仿真数据。业务预期独立保存于 `expected.json`，不得根据模型结果改成通过。

26 代表测试机新建隔离目录，回环端口 18095 启动当前 Python 候选源码；Java 使用当前编译类、真实 HTTP AI Adapter、正式 Office Reader 和预算 Analyzer。模型配置仅从现有环境注入，不打印或复制到仓库。OCR 复用已有服务。内存提取 ledger，不访问业务数据库，不替换正式 release 链接。

`tools/BudgetModelAcceptance.java` 增加读取/提取耗时、在提取异常之前保存原文和 SHA；不改变候选提取和核对算法。每份材料重新读取，不使用前轮模型响应重放。`tools/run-budget-extended-isolated.sh` 完成后自动停止本轮隔离 AI 进程。

## 可复核命令

```powershell
node tools/build-budget-extended-fixtures.mjs .runtime/budget-extended/fixtures
# 在 26 的新隔离目录运行（不在正式 release 目录运行）：
# sudo bash tools/run-budget-extended-isolated.sh
node tools/verify-budget-extended-results.mjs .runtime/budget-extended/actual-results.json .runtime/budget-extended/fixtures/expected.json .runtime/budget-extended/business-checks.json
node tools/verify-budget-extended-formats.mjs .runtime/budget-extended/format-results.json .runtime/budget-extended/format-checks.json
```

业务核对脚本对未达到目标的场景返回非零退出码，保留真实结果，不将读取成功视为业务正确。格式核对区分损坏文件拒绝与正常读取，并检查八行 Word 表格、重复工作表不擅自累加及项目总额不污染科目金额。

## 已完成回归

- Windows / JDK 17：`cd backend; .\mvnw.cmd clean verify`，2437 tests，0 failures、0 errors、2 skipped，BUILD SUCCESS（04:14）。日志 `.runtime/budget-extended-clean-verify.log`。
- Python 3.11：`python -m pytest services/audit-ai/tests/test_budget_partial_evidence.py services/audit-ai/tests/test_budget_partial_checkpoint.py services/audit-ai/tests/test_material_fields.py -q`，30 passed，现有 Starlette/httpx 弃用警告 1 条。日志 `.runtime/budget-extended-python.log`。
- Chrome 109.0.5412.0：既有 17 份次真实结果在 1440px 和 390px 均渲染通过，页面异常为空，无结果容器横向溢出；HTTP 为模拟 API，不能冒充正式后端 E2E。证据 `.runtime/budget-extended/browser-baseline*/browser-check.json`。
- Chrome 109 状态页回归：处理中、无结果、技术失败的说明区分，以及整批结束后的自动跳转检查通过。

## 当前观察

20 组业务测试已完成：18 组达到独立预期，2 组未达到；39 份材料均完成读取和真实模型提取，没有传输或技术异常。业务验收未通过的 JSON 明细见 `.runtime/budget-extended/business-checks.json`，不能用 HTTP 200 或 Java 无异常宣称业务全部正确。

万元换算、两类财务大写、零批复、分币差额、单价与项目总额区分、跨年度/部门/编号隔离、重复副本和批复冲突已获得真实结果；长文首尾字段能够比对。具体结果包括：123456 对 120000，超出 3456；10003.05 对 10000，超出 3.05；1020304.56 对 1000000，超出 20304.56；0.03 对 0.02，超出 0.01。

12 份同项目材料归集出 6 个科目的比对，每个科目申报超过批复 500 元，没有跨科目串额。每份文件各保留其结果来源，共 12 条文件结果；不是重复累加 12 份金额。顺序读取与模型提取共 164.426 秒，不含页面上传或持久化耗时。

39 份文本测试的读取与提取中位耗时为 13.974 秒，最短 1.004 秒，最长 29.794 秒，合计 578.271 秒。该耗时不等于甲方本地模型性能，也不包含文件上传及业务数据库写入。

多科目的纯文本把科目和金额写在不同行时，原生读取保留了文字及金额，模型也返回了科目及金额，但合并阶段没有保留两者的分组关系，最后显示多个无法完整比对的条目，未达到超支/错支/漏项核对目标。原文中的“批复金额：未提供”也被保留为无效金额，而非缺失信息。本轮保留该失败样本。

具体证据：`13-2.txt` 的模型响应已经分别将“照明维护/批复12000”和“消防检验/批复5000”分成两行；Java snapshot 却仍是各自单独的科目行、金额行。`BudgetUploadedMaterialReader` 将与原生字段重复的模型字段跳过，随后 Analyzer 只合并能够证明同一科目的行；原生读取在此未保存跨行科目边界，导致配对丢失。界面因而显示“信息不足”，但该样本原文并不缺少这些信息。

同项目编号但正文名称不同的两份材料仍被隔离，没有自动形成对应比对；不能宣称已实现该项关联能力。

## 最终格式及页面结果

13 组格式核对中 11 组达到目标，2 组未达到。12 份有效文件均完成读取及模型提取，1 份故意损坏的 DOCX 正确拒绝，原因为 `DOCUMENT_SIGNATURE_INVALID`。损坏文件不算读取成功，拒绝行为符合该负向样本的预期。

Word 表格 8 条明细均确认 31200 对 32760。XLS/XLSX 两张重复工作表均保留原始字段，但最终按重复明细无法确认处理，没有擅自相加为 62400；这只算安全处理通过，并不代表系统已能自动判定重复副本。Word 页眉的孤立科目还额外生成一个无金额条目，结果有冗余。

纯扫描 PDF 和 JPEG 提取出正确的 31200 元申报及项目编号；PNG 虽保留 31200 元，但部分项目编号为 `SYN-0FFICE-2026`，把原文字母 O 识别成数字 0，没有达到身份正确的预期。混合 PDF 的重复页、O/0 编号差异和重复明细导致最终金额未确认，未达到实用金额结果目标。这两组保留未通过，不自动修正来源身份。

实测纯扫描 PDF 总耗时 121.786 秒；PNG 94.534 秒；JPEG 91.467 秒；混合 PDF 314.784 秒，其中读取 207.632 秒。混合 PDF 的三页包括原生文字、扫描页和文字加图片页，逐页读取/OCR 后又执行分段模型提取；不能把上述总耗时当作某一次模型调用耗时。

合计 33 组、52 份次，29 组达到各自独立核对目标，4 组未达到。正式 AI 服务最后健康检查 HTTP 200，隔离端口 18095 已停止，业务数据库写入为 0。实际结果、原文及模型响应在 `deliverables/Budget_Extended_Review/预算实测结果.html` / `actual-results.json` 中保留。结果页和该报告经真实 Chrome 109.0.5412.0 验证，1440px 与 390px 下 52 份次均可渲染，页面异常为空，无结果容器横向溢出；详情原文展开可用。浏览器 API 为模拟传输已有真实结果，不是正式上传、任务持久化的 E2E 验收。

## 修复后复核（2026-10-20）

根据以上失败样本，对预算材料读取逻辑增加了分行字段关联、跨材料同项目匹配、未提供金额占位处理、模型回声金额去重、PDF重复页及原生文字/OCR回声去重；扫描编号遇到 O/0 冲突时不改写来源。若同一扫描材料有唯一项目名称，去掉不确定编号但保留可独立定位的金额；项目名称也不唯一时继续保留原始候选并标记不确定，不猜测归属。

JDK 17 下 `cd backend; .\\mvnw.cmd clean verify`：2446 tests，0 failures、0 errors、2 skipped，BUILD SUCCESS。预算读取与归集目标测试 76 项通过。Python AI 测试此前记录为 30 passed；本轮未改 Python 源码且未能重跑，因为本机当前只有 Python 3.10.11、未安装 FastAPI/httpx，不能将此前测试结果算作本轮验证。

用保存的真实 OCR 文本及已确认模型响应重放（不重新请求模型）后，新增重点业务 12/12、扩展格式 13/13 达到独立验收断言。PNG 扫描样本保留 31200 元申报金额、将存在 O/0 混淆的项目编号留空并提示核对；混合 PDF 的重复页不再重复累计，同页 OCR 回声去重，若金额不同则保留冲突供核对。重放结果位于 `.runtime/budget-final-replay-20261020/`；重放耗时不代表 OCR/模型实时性能。

重放来源包含此前在隔离 26 测试环境对仿真材料进行的真实 OCR/模型结果；本轮重放只验证当前 Java 读取/分析逻辑，未接入甲方模型、真实业务数据库或上传持久化全链路。本轮合成材料不能替代甲方真实材料验收，甲方现场效果仍为 NOT VERIFIED。`node --check tools/verify-budget-extended-formats.mjs` 与 `git diff --check` 均通过。所有变更仍未提交。

本轮不涉及甲方 ARM64 和甲方本地模型、正式业务数据库写入、任务恢复/刷新/上传全链路验收，这些仍为 NOT VERIFIED。
