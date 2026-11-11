# 02 Test Dataset

## 现有材料目录只读盘点

对 `test-materials/`、`v15-fixtures/`、`quality/` 递归文件仅统计扩展名，不读取正文或文件名：总计 39 个文件，PDF 20、ZIP 3、JSON 6、DOC 1、CSV 2、TXT 1、Markdown 4、无扩展名 2。该磁盘清单未看到 DOCX/XLS/XLSX/PPT/PPTX/PNG/JPG 固定样本，部分测试通过代码生成内存 fixture。存在场景 manifest（`test-materials/contract-customer-20261109/manifest.json`），其键对应合同场景类别；未确认逐条人工专家真值，不作为 gold set。

## 当前状态

| 要求 | 状态 |
|---|---|
| 文件 ID / SHA256 / 模块分类 / 页数清单 | 未建立统一评测 manifest |
| 逐页 OCR 真值与表格单元格 | 未确认 |
| 预算/采购/合同字段与问题真值 | 未确认人工标注 |
| 原文证据页码/区域真值 | 未确认 |
| 标注人及复核状态 | 未发现统一维护流程 |
| 客户真实材料授权 | 本次不使用客户材料 |

## 待人工填写的真值模板

```json
{
  "caseId": "",
  "fileSha256": "",
  "module": "BUDGET | PROCUREMENT | CONTRACT",
  "format": "",
  "pageCount": 0,
  "truthFields": [],
  "truthFindings": [
    {"findingId": "", "severity": "", "statement": "人工标注", "evidence": [{"page": 0, "region": "", "verbatim": "人工核对原文"}]}
  ],
  "review": {"annotator": "", "secondReviewer": "", "status": "UNREVIEWED", "notes": ""}
}
```

此模板不含任何预填答案；双人复核后才可纳入准确率统计。

## 安全执行约定

后续先使用合成或明确授权的仿真材料；只保存必要的文件 hash/格式/页数/标签，不复制真实文件正文进入报告或日志。任何准确率统计必须基于独立人工 truth，AI 自评不能作为真值。
