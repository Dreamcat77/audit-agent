# 03 OCR Quality

状态：静态 Python 测试环境不含 Paddle 核心运行时；但已在 26 的真实 PaddleOCR 服务上执行合成扫描件测试，并通过合同应用入口再次验证了解析路径。测试材料为合成文件，不含甲方文件内容。

26 实测：对 `/home/admin/audit-agent-backups/qa26-stage2-20261027/fixtures/contract/rotated-scan/倒置扫描服务合同.pdf` 调用 QA OCR 服务 `127.0.0.1:19153/ai/v1/ocr/extract`，HTTP 200、PaddleOCR 3.7.0、1 页、541 字符、置信度 0.9982、OCR 计时 20.6 秒、客户端总耗时 23.8 秒。输出只记录统计值，没有显示或复制识别正文。随后通过 QA 应用合同文件上传入口处理同一合成扫描文件；`PROJECT_FILE` 原文解析状态 READY，含 text hash，证明应用解析及原文持久化可用。由于应用任务未单独记录 OCR 调用链路，本次不把这条结果单独作为 Java→OCR 调用已验证的证据。该合同任务的模型分析因 HTTP 502 失败，不能将其报告视为 OCR 缺陷。

源码显示公共材料解析由 `OfficeMaterialParseAdapter` 组织，调用 `OfficeDocumentTextReader`；PDFBox 提取原生文本、逐页 usefulness gate 后可能调用 FastAPI OCR；图片直接送 OCR。合同 Project Intake 有独立 parser 和不可变 ParseResult。FastAPI 有 PaddleOCR adapter、方向处理、几何/置信信息、资源池/页并行与检查点能力。代码存在不等于本轮已验证 OCR 版本/准确率。

候选覆盖集应包含原生/扫描/混排/旋转/长 PDF、表格、DOC/DOCX、XLS/XLSX（多 sheet/合并/隐藏/公式）、PPT/PPTX、图片、ZIP、空/损坏/加密/超限。对每页统计覆盖、字符错误、OCR 页数、解析耗时、方向、表格结构、截断及失败原因。没有人工转录真值时只报告可观测覆盖和具体缺陷，不报告 CER/WER。

现有材料及仿真来源：本次是合成旋转扫描合同；未用人工转录真值，未报告 CER/WER。OCR 测试没有改动 26 服务配置或重启服务。本地项目依赖仍安装在专用 Python 3.11 venv；未安装/替换 Paddle 核心包。远端记录位于 `/home/admin/audit-agent-backups/qa26-run-20261111-r9`。
