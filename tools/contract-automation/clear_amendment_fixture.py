"""Add an unambiguous amendment without overwriting any earlier golden material."""
import json
import shutil
from pathlib import Path
from docx import Document
from fixtures import MARK

root = Path(__file__).resolve().parents[2] / ".test-runtime/contract-fixtures"
golden = root / "golden.json"
specs = json.loads(golden.read_text(encoding="utf-8"))
scenario = "valid-amendment-clear"
folder = root / scenario
folder.mkdir(exist_ok=True)
main = folder / "采购主合同.docx"
supplement = folder / "明确替换原付款条款的补充协议.docx"
if not main.exists():
    original = next(s for s in specs if s["id"] == "normal-procurement")["paths"][0]
    shutil.copyfile(original, main)
if not supplement.exists():
    doc = Document()
    for line in (MARK + "\n补充协议\n关联合同编号：SYN-GOODS-2026-01\n"
                 "甲方：澄川测试服务中心\n乙方：澄川星河设备有限公司\n"
                 "第一条 双方一致将主合同第五条的付款比例调整为50%、40%、10%，分别在生效、全部验收合格和质保届满时支付。\n"
                 "第二条 本协议第一条完整替换原合同第五条的付款比例，原付款比例30%、65%、5%不再适用，不重复支付。\n"
                 "第三条 除付款比例外，主合同的发票要求、各期付款期限及其他条款继续适用；本协议与原付款比例不一致时以本协议为准。\n"
                 "第四条 本协议经双方签字盖章生效，双方各持一份。").splitlines():
        doc.add_paragraph(line)
    doc.save(supplement)
if not any(s["id"] == scenario for s in specs):
    specs.append({"id": scenario, "paths": [str(main), str(supplement)], "synthetic": True,
                  "expectedRisks": [], "noCrossConflict": True, "requiredCrossReview": True,
                  "clausesMin": 5})
for spec in specs:
    if spec["id"] == scenario:
        spec["comparisonsMin"] = 1
    if spec["id"] == "supplement-conflict":
        spec["requiredCrossReview"] = True
        spec["forbiddenFindingPatterns"] = ["乙方.{0,5}(?:需要|应当|须|应).{0,5}支付"]
golden.write_text(json.dumps(specs, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"scenarioAdded": scenario, "earlierFilesOverwritten": False}))
