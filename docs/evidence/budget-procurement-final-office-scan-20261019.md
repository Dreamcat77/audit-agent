# 预算与采购最终使用流程及扫描件验收

范围：预算、采购需求导入、采购审查材料入口、现有 OCR/模型/DM8 链路。测试资料全部为合成文件；不采集甲方材料正文或模型配置，不改合同业务。

## 本轮发现及修复

采购需求入口已支持常见 Office 和图片，但采购审查入口仍限制 DOCX/PDF/XLS/XLSX。现在该入口复用现有 `DocumentTextPort`，接受 DOC/DOCX、XLS/XLSX、PDF、PPT/PPTX、TXT/CSV/RTF、PNG/JPG/JPEG/BMP/TIF/TIFF；保留签名、哈希、容器、安全资源上限检查，压缩包不支持。

原采购审查 PDF 读取按整份文本判断是否 OCR，可能遗漏有文字页的 PDF 中其他扫描页。现在使用现有共用读取器逐页判断/处理，图片逐帧处理；不建立新的 OCR 或存储系统。

保留 V035 的原有解析方式约束；其他 Office 文本的实际读取方式写在已有 PARSE_ENGINE 中，不增加未经迁移允许的枚举值。仅修改原有选择文件类型和说明文字，页面布局不变。

并发实测额外发现：预算图片 OCR 运行期间，另一份采购需求在 `/ai/v1/materials/policy` 的只读前置读取处发生 10 秒 `DOWNSTREAM_TIMEOUT`，尚未发送该次模型提取请求。现在该只读读取使用有限的 120 秒等待；不缓存旧策略、不重发模型操作，策略格式和漂移检测继续 fail closed。新真实 HTTP 回归覆盖延迟 11 秒仍仅请求一次、错误策略拒绝、503 不重试。此调整缓解忙时短超时，不声称已经隔离原生 OCR 的 CPU/GIL 争用。

扩大预算材料实测后还发现模型重复事实与行归属保护之间的误拦截：本地已从明确标签读出精确事实，模型又以跨行宽摘录返回完全一致事实，导致 `BUDGET_AI_ROW_AMBIGUOUS`。新处理只忽略同字段、同值、同来源且模型摘录覆盖本地标签证据的冗余事实，保留本地物理行与原始证据；新增事实、金额角色/值或来源不同仍执行原有严格校验。补充“申请部门”明确标签。对已确认的真实模型 checkpoint 进行只读回放，九份此前失败的合成 TXT/UTF16/CSV/DOC/PDF/PPT/PPTX/RTF 均可通过新读取器；该回放不修改任务，也不重发模型调用。最终部署后的新任务结果另列。

修改文件：

- `backend/src/main/java/com/fuquan/audit/procurement/application/ProcurementTenderDocumentService.java`
- `backend/src/main/java/com/fuquan/audit/procurement/application/ProcurementMaterialBatchService.java`
- `frontend/src/features/procurement/ProcurementTenderReviewPanel.tsx`
- 对应 `ProcurementTenderDocumentServiceTest` 和 `ProcurementMaterialBatchServiceTest`。
- `FastApiMaterialFieldAdapter` 和 `FastApiMaterialPolicyHttpTest`。
- `BudgetUploadedMaterialReader` 和对应回归：宽摘录重复事实保留本地证据，金额角色/值漂移仍拒绝。

## 本地检查

Windows、JDK 17：最终 `cd backend; .\mvnw.cmd clean verify`，2411 tests，0 failures，0 errors，2 既有 contract fixture skips，BUILD SUCCESS。日志 `.runtime/final-office-clean-verify-r4.log`。其中读取器回归 17 项通过，真实 HTTP 策略延迟/反向测试 3 项通过。此前 2406/2409 项构建不作为最终产物证据。

`cd frontend; node --experimental-strip-types --test @tests`：748 passed，0 failed；日志 `.runtime/final-office-frontend-tests-r2.log`。

`cd frontend; npm run build`：PASS，保留既有大 chunk 提示。`node tools/build-procurement-browser.mjs frontend/dist .runtime/final-office-browser109`：PASS，保留原始布局与已验证的 Chrome109 颜色兼容处理。

`node .runtime/spreadsheet-ui.mjs`：真实 Chromium109.0.5412.0、生产采购组件及受控 HTTP 响应，状态区分、失败提示、无报告不跳转、刷新保持状态、无横向溢出，pageErrors=[]。此项不冒充真实后端浏览器 E2E。

新扫描 PDF 用既有合成混合 PDF 的纯图像页生成，重新打开确认只有一页且 PDFTextStripper 返回空白；渲染 PNG 已人工视觉检查，文字和金额清晰，不是带文本层的伪扫描样本。其他扫描图片包括大写金额及三个明确的采购风险条款，也已视觉检查。

## 26 代表平台

最终业务验收：被测常见办公文件和清晰扫描样本 PASS，下述多页扫描吞吐/HTTP 等待限制仍保留。当前运行 `/opt/audit-agent/releases/office-scan-v5-20261019.RC1WLR`；回退记录 `/opt/audit-agent/update-backups/office-scan-v5-20261019.peovi7`。

最终后端 SHA-256 `e216b366139d4cdfb00b90838464a2169f0c7e80ed6f3eacfdbc75d1cfb354b4`；内部部署包 SHA-256 `60a9dc9246789c512fd6df0f8e11d53229ff98a92491411c6a2187b86d74eff7`。逐项比较 v4/v5 的应用 class，只有 `BudgetUploadedMaterialReader.class` 内容变化；采购类未变化。

只读预检 V052–V056 匹配；切换后 backend/AI HTTP200、匿名采购401、前端3项资源逐字节一致，配置哈希 UNCHANGED。没有依赖升级、数据库结构或 Nginx 配置修改。

采购需求导入：`AUDIT_SYNTHETIC_OFFICE_LABEL=final-procurement AUDIT_SYNTHETIC_OFFICE_FILES=... java -cp "$office_cp4" BudgetBackgroundRunner "$office_v4/test-office-formats26.py" procurement`，exit 0，`REAL_OFFICE_UPLOAD_MODEL_DM8=PASS`。14 份有效 DOC/DOCX/XLS/XLSX/PDF/PPT/PPTX/TXT/CSV/RTF、UTF16、多行表格、26 MiB DOCX、120 条要求长文本均 PARSED，项目名及至少 10 个字段有依据；120 条要求全部保留。损坏 DOCX 和超限 TXT 正确失败/拒绝；无 UNKNOWN。日志 `/home/admin/office-scan-v4-20261019.ULx83r/final-procurement.log`。

采购审查读取：`java -cp "$office_cp" BudgetBackgroundRunner "$office_v3/final-office-workflow.py"` 中 16 个读取用例通过，包含 DOC/DOCX/XLS/XLSX/PDF/PPT/PPTX/TXT/CSV/RTF/PNG/JPG/BMP/TIFF、无文字层扫描 PDF、文字与扫描混合 PDF。检查原文件 SHA、OCR 方法、混合 PDF 第 2 页、多页 TIFF 第 2 帧和新登录重开 SHA；未漏读这些被测扫描页。Excel 在该组只验证保存，报价分析另见本轮前序大表验收。该首次综合脚本在后续重复创建项目时遇到已记录的并发策略短超时，因此不把整次脚本 exit 计为通过。

采购扫描件分析：`java -cp "$office_cp" BudgetBackgroundRunner /home/admin/final-office-complete.py`，exit 0，`FINAL_SCAN_RISK_ONLY=PASS`。真实 OCR/模型/规则链路检出指定品牌、地域限制、排他性条件，命中内容有原文支持；报告导出为有效 DOCX，新登录重开同 reportId。日志 `/home/admin/office-scan-v3-20261019.aBO6DE/final-scan-risk.log`。

并发复测：预算、采购两组后台任务期间额外 OCR 请求 HTTP200、49.04 秒；采购有效文件无失败/UNKNOWN，后台健康接口 HTTP200、2.72 毫秒。此观察仅说明本次并发样本通过，不声称任意负载稳定。

预算最终新任务复测：`AUDIT_SYNTHETIC_OFFICE_LABEL=final-budget-fixed AUDIT_SYNTHETIC_OFFICE_FILES=... java -cp "$office_cp5" BudgetBackgroundRunner "$office_v5/test-office-formats26.py" budget`，exit 0，`REAL_OFFICE_UPLOAD_MODEL_DM8=PASS`。同一组 14 份有效文件全部 PARSED、任务 REVIEWED；申报金额 31200、原文金额依据及多行表格覆盖检查通过。损坏 DOCX、超限 TXT 正确失败；无 UNKNOWN。日志 `/home/admin/office-scan-v5-20261019.6iHWG7/final-budget.log`，逐项结果 `results-final-budget-fixed.json`。此前 v4 的九份失败没有被回写成成功，而是使用新任务验证修复。

真实 Chromium 109.0.5412.0 连 26：`node .runtime/final-office-live-browser.mjs`，exit 0，pageErrors=[]。生产页面上传大写金额 PNG → 真实 OCR/模型/DM8 → 自动结果页 → 申报 123456.78、材料批复 100000、差额 23456.78 红色提示 → XLSX 导出 → 刷新后预算首页 → 按材料名搜索历史重开；采购重新打开保存的扫描风险报告 → 指定品牌/地域限制有依据 → 切换复制后旧按钮恢复 → DOCX 导出。结果 `.runtime/final-office-live-browser-result.json`，截图 `.runtime/final-office-live-budget109.png` 已查看，文字及差额可读。调试探针曾选到无对应报价快照的旧合成报告、以及误用 page=0，均已修正探针；不把这些失败计为通过，也不为此改产品。

收尾再次核对 current 运行 JAR 与最终构建哈希一致、backend/AI health HTTP200、配置哈希 UNCHANGED。临时测试身份由 runner 停用，合成任务、模型 checkpoint 和业务审计保留。没有提交 Git、输出桌面交付物或接触甲方业务材料。

## 验收边界

500 MiB 是原始文件上传边界，不代表无限文字、页数、解压内容或模型调用预算。读取器保留全文/页数/资源限制，超过限制必须明确失败，不能只读前半份就发布完整结论。压缩包不在本轮支持范围。

26 实测图片 OCR 约每页 70–100 秒，混合 PDF 样本为 211.3 秒。采购审查材料暂仍在上传请求内执行 OCR；大批、多页扫描件可能超过现有 HTTP 等待时间，不能据本轮小样本承诺任意页数扫描件都能一次处理完成。预算及采购需求导入使用后台任务；本轮不通过扩大并发或修改 Nginx 等待时间掩盖差异。

通过的清晰扫描样本不等于任意模糊、旋转、手写、盖章遮挡的扫描材料都能准确识别。加密、损坏、超出安全资源上限、不能证明 OCR 完整性的材料必须保留失败/未确认状态，不作为干净业务结论。现场甲方 ARM、网关白名单和本地模型尚需现场验收，NOT VERIFIED。
