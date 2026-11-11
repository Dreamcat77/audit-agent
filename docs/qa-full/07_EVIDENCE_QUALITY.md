# 07 Evidence Quality

状态：合同合成扫描件报告的 11 项引用一致性断言已由验收脚本执行；本轮没有逐项检查 PDF 原始像素页与高亮坐标，因此原文件视觉定位仍未验收。

源码层已发现合同事实发布会绑定 source file、ParseResult/version、hash 和 offsets，并校验 excerpt 与 canonical text 子串相等。此校验可以证明引文来自解析文本，不能证明 OCR 文本与原始页面像素内容一致，也不能单独证明引文充分支持结论。Word/Excel 的分页/坐标能力与 PDF 不同；公共 Office adapter 会记录 Word 页面布局不可用的 coverage gap。

本轮脚本检查仅能验证被接受的引文和 locator 与 canonical text/源 hash 一致，不等于视觉坐标检查，也不等于审计结论被原文件充分支持。仍需分别对原生 PDF、扫描 PDF、混排 PDF、DOCX 表格和 XLSX 单元格核对原始文件，检查引用真值、页码、坐标、高亮范围、语义支持关系。OCR 错字需回看原始页面图像。若不能从原文件定位，状态应为未通过/未测试，不能以解析文本高亮替代原文件证据。
