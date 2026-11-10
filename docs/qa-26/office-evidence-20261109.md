# Word / Excel 原文件证据页

## 改动与边界

用户要求三模块原文展示整页及高亮，明确指出 Word、Excel 只展示几句话。根因是共享 AnalysisEvidenceDialog 按扩展名排除了 Office，自动进入短引文回退。

- 新增共享 OfficeEvidencePreview / officeEvidenceDom：DOCX 直接读取授权原件，以固定版本 docx-preview 0.4.1 保留段落、样式、表格、图片及已保存分页。无分页提示时按原件块级布局分页。仅展示包含本项证据的页面；不添加全文件浏览或模式选择。
- 二进制 DOC 使用已有 Apache POI 原生 WordToHtmlConverter，三模块 office-html 只读接口共用 renderer 的原件大小和 SHA256 核对，输出原件内容的布局预览，不使用 OCR/模型重新构造正文。
- XLS/XLSX 使用已有 SheetJS 0.20.3，从原工作簿展示证据所在完整工作表，保留格式化值、合并单元格和行列坐标。保留冻结 sheet/row 定位以排除其他同文工作表；无定位时只能使用全工作簿唯一匹配。
- 高亮须同时通过冻结文本精确偏移/引文校验及原件渲染文字唯一匹配。只忽略 Office run/单元格空白差异，不纠正 OCR 错字、标点或金额。重复、冲突或原件不一致时不编造高亮。
- 原件在本系统内渲染，无第三方在线预览、无新 OCR 或模型调用。iframe 隔离样式及滚动；禁脚本、表单、外链与远程资源，内置本地中文字体，退出取消读取。Header/Body 保持兄弟结构。
- 预算补充授权原件只读下载；合同复用已有 Project 原件读取，采购复用已有批次原件读取。没有表结构、分析规则或历史报告修改。

限制必须保留：Word 属于浏览器原件排版预览，不承诺与 Microsoft Office 固定页码像素一致；复杂浮动对象、缺字体、超长不可分段块可能改变分页。Excel 展示证据工作表，并非打印机纸张分页；嵌入图表/图片不是本轮复现目标。超过预览容量、旧文件损坏或无法可靠定位时提供原件下载，不伪造原页和高亮。PDF/图片仍使用已有真实像素原页。

## 主要文件

- frontend/src/features/contract/components/OfficeEvidencePreview.tsx
- frontend/src/features/contract/officeEvidenceDom.ts
- frontend/src/features/contract/components/AnalysisEvidenceDialog.tsx
- frontend/src/features/contract/components/ContractAnalysisWorkspace.tsx
- frontend/src/features/contract/contractAnalysisEvidenceView.ts
- frontend/src/features/procurement/procurementSourceView.ts
- frontend/src/features/budget/BudgetBatchReport.tsx
- frontend/src/styles/source-evidence-dialog.css
- frontend/package.json / package-lock.json
- backend/src/main/java/com/fuquan/audit/common/material/visual/MaterialPageRenderer.java
- backend/src/main/java/com/fuquan/audit/integration/material/OriginalPageRenderer.java
- 三模块 VisualController
- OriginalVisualEvidenceTest / VisualOwnershipTest
- frontend/tests/evidence-viewer/*

## 验证

运行环境为本机 Java17、Chrome109，以及默认26。最终运行记录在 `.runtime/office-evidence-20261109/` 和 `.runtime/office-evidence-*.log`。

- 前端 `node --experimental-strip-types --test "src/**/*.test.ts"`：875通过。
- `npm run build:customer`：通过；Chrome109颜色转换完成。
- Chrome109三套浏览器测试：36通过。覆盖12页DOCX仅证据页、无分页DOCX自动排版、完整表格上下文、合并单元格/格式化金额、二进制DOC/XLS、跨run高亮、重复/错引/404不伪造高亮、关闭重开、iframe Escape、三视口及滚动不穿标题；原PDF/历史页/处理中展示回归通过。
- Java定向21项：通过，包括真实Apache POI二进制DOC表格样本、原件SHA/长度错误反例，以及三模块越权/批次外文件拒绝读取。原valid.doc是安全格式探测夹具，不是真实可排版Word；本轮另用Apache官方simple-table.doc，来源及Apache-2.0许可与文件同行保存。
- 最终 Java17 `backend/.\mvnw.cmd clean verify`：3644项，0失败、0错误、24跳过。最终构建与已部署原r2比较，仅6个主程序class不同（原页接口、renderer及其接口），没有审查分析类/配置差异，记录backend-diff.json。
- 26真实Chrome109：已有Word合同、12份预算批次中的Word、新上传Excel采购/预算批次四条结果路径均能从页面查看原件完整上下文及高亮，8个合同审查模块仍在；1440×1000和760×560滚动不穿标题，零pageerror。只读已有历史结果，没有重跑原报告模型。
- 新Excel仿真批次真实进入既有解析处理：采购终态DATA_INSUFFICIENT、预算REVIEWED。两者都能查看有原件依据的结果与工作表。不能把预览验收描述为这些样本获得完整采购审查结论；原样本无供应商报价等材料，本轮未调整业务分析规则。
- 26跨账号：三模块office-html、两份预算original共6请求返回404，不泄露原件。现有三模块PDF真实像素原页/高亮/重开/两视口/Header边界/合同八项回归通过。

## 26发布

发布前真实DM8各活动队列归零，停止backend后再次检查，复制当前发布并切换为 `office-evidence-20261109-r1`。没有DDL，现场配置及AI源文件保持，AI进程未重启。后续Excel行号列宽修正仅静态更新为 `office-evidence-20261109-r2`，backend/AI PID保持。最终默认80的HTML与全部9项资源逐一HTTP SHA核对通过；8001、8080返回UP。

- JAR SHA256：`9988415b91b84ca5f5865804b9a37a9e8c4e2bab82a3c224269fbd18fcbc6cf2`
- 最终index SHA256：`36d1de3b61d7044984fb2499b8b42dd71755a5c67cbe6d5e6b40121725761a4a`
- 已融合GitHub截止仍为 `0f61b868575482574c593c646a1ff5f9228cd157`，原工作区未提交修改保留。
- 行号列宽最后修正后Chrome109原文18项再验通过；前述三套36项中的历史页和处理中组件未变化。

甲方ARM64、客户真实文件为 NOT VERIFIED；本任务不制作甲方更新包、不推Git、不宣称重新审核了历史结论。

## 用户反馈旧展示后的复查

用户确认环境为26、模块为合同，但尚未指定记录。默认80入口HTTP主脚本仍为index-DsOJdLwb.js，入口SHA与发布记录一致。重新执行Chrome109真实四路径检查通过；随后运行 `.runtime/office-evidence-20261109/browser-run.py audit-contract.mjs`，逐一打开test_admin两条Word记录的100个、hetong一条Word记录的18个“查看原文”，共118个入口均进入Office原文件页面，未出现旧摘录容器或Office读取错误。记录为contract-entry-audit.json。该结果仅证明这三条记录，不能证明用户未指定的记录或浏览器已有标签页；未把缓存推断当作根因，未为未复现问题修改业务代码或重跑模型。

## 2026-11-10 三模块托管回归

- 环境为26默认80、Chrome109。真实历史页依次覆盖预算/采购/合同：关键词与日期筛选、空结果、重置、正确结果打开、分页控件；1440/1024/768/375宽度均无横向溢出，筛选框40px，页面异常0。预算20条、本地采购10条、合同15条记录页均能正常列示；本次采购真实列表只有10条，未声称真实跨页翻页，历史页专项测试覆盖其分页交互。
- 真实结果打开回归：三模块均显示“已完成”，已进入结果后上传入口隐藏；合同自动分析保留八大项；三个结果页无浏览器错误。原文回归三模块直接显示证据原页/高亮，关闭重开及标题边界通过。
- 账号隔离复查：用采购账号读取其他模块记录的6个原文/导出接口均返回404。已有合成Excel验收记录的最终API状态为采购 `DATA_INSUFFICIENT`、预算 `REVIEWED`；采购材料不足状态仍是已结束的可读报告，不等同于结论充分。没有新上传或重跑模型。
- 本轮未复现产品缺陷，不改业务代码、不重新部署26。浏览器记录为 `.runtime/history-polish-20261109/live-history.json`、`result-workflow.json`，原文复归记录为 `.runtime/office-evidence-20261109/browser-verification.json` 及 `contract-entry-audit.json`。甲方实机与客户材料未验证。
