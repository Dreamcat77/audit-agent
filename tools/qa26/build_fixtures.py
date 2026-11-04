"""Transport only explicitly synthetic office fixtures with independent expectations."""
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
from docx import Document
from openpyxl import Workbook

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / ".test-runtime/qa26/fixtures"
OUT.mkdir(exist_ok=False)

def text(folder, name, value):
    p = OUT / folder / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text("合成测试材料，仅供系统验收。\n" + value, encoding="utf-8")
    return p

def word(folder, name, value):
    p = OUT / folder / name
    p.parent.mkdir(parents=True, exist_ok=True)
    doc = Document()
    doc.add_paragraph("合成测试材料，仅供系统验收。")
    for line in value.splitlines():
        doc.add_paragraph(line)
    doc.save(p)
    return p

budgets = []
def budget(label, declarations, approvals, expected):
    folder = "budget/" + label
    declaration = word(folder, "项目预算申报.docx", declarations)
    approval = text(folder, "预算批复.txt", approvals)
    budgets.append({"id": label, "paths": [str(p.relative_to(OUT)).replace("\\", "/") for p in (declaration, approval)], "expected": expected})

budget("normal", "项目编号：QA26-B01\n项目名称：办公设备更新\n预算年度：2026\n预算科目：办公设备\n申报金额：31200元。",
       "项目编号：QA26-B01\n项目名称：办公设备更新\n预算年度：2026\n预算科目：办公设备\n批复金额：32760元。",
       {"status": "WITHIN_APPROVAL", "declared": "31200", "approval": "32760"})
budget("over", "项目编号：QA26-B02\n项目名称：档案扫描设备购置\n预算科目：专用设备\n申报金额：35000元。",
       "项目编号：QA26-B02\n项目名称：档案扫描设备购置\n预算科目：专用设备\n批复金额：32760元。",
       {"status": "OVER_APPROVAL", "declared": "35000", "approval": "32760"})
budget("uppercase", "项目编号：QA26-B03\n项目名称：会议终端更新\n预算科目：办公设备\n本次申报金额为人民币叁万壹仟贰佰元整。",
       "项目编号：QA26-B03\n项目名称：会议终端更新\n预算科目：办公设备\n批复金额为人民币叁万贰仟柒佰陆拾元整。",
       {"status": "WITHIN_APPROVAL", "declared": "31200", "approval": "32760"})
budget("missing-item", "项目编号：QA26-B04\n项目名称：行政办公更新\n预算科目：办公设备\n申报金额：18000元。",
       "项目编号：QA26-B04\n项目名称：行政办公更新\n第一项预算科目：办公设备，批复金额：20000元。\n第二项预算科目：家具用具，批复金额：5000元。",
       {"status": "WITHIN_APPROVAL", "declared": "18000", "approval": "20000", "missingApproval": "5000"})
folder = OUT / "budget/excel"
folder.mkdir()
book = Workbook()
sheet = book.active
sheet.title = "合成预算表"
sheet.append(["项目编号", "项目名称", "预算科目", "申报金额", "批复参考金额"])
sheet.append(["QA26-B05", "办公设备更新", "办公设备", 31200, 32760])
book.save(folder / "预算申报及批复.xlsx")
budgets.append({"id": "excel", "paths": ["budget/excel/预算申报及批复.xlsx"], "expected": {"status": "WITHIN_APPROVAL", "declared": "31200", "approval": "32760"}})
partial = text("budget/partial", "有效预算.txt", "项目编号：QA26-B06\n项目名称：培训\n预算科目：培训费\n申报金额：10000元。\n批复金额：12000元。")
corrupt = partial.with_name("损坏附件.xlsx")
corrupt.write_bytes(b"not-an-excel-file")
budgets.append({"id": "partial", "paths": [str(p.relative_to(OUT)).replace("\\", "/") for p in (partial, corrupt)],
                "expected": {"status": "INSUFFICIENT", "declared": "10000", "approval": "12000", "partial": True}})

original = Path("D:/UserData/Desktop/审计结果测试材料_20261026/采购/批次01_完整项目多材料")
names = sorted(p for p in original.iterdir() if p.suffix.lower() in (".pdf", ".docx"))
assert len(names) == 12
for p in names:
    dest = OUT / "procurement/normal" / p.name
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(p, dest)
conflict = word("procurement/conflict", "补充要求_与原付款无法同时执行.docx",
                "办公终端及打印设备采购项目澄清答复\n项目编号：FZ-CG-2026-001\n"
                "本答复不是替换条款，原招标文件付款比例继续有效，同时必须执行以下新增比例：\n"
                "合同生效支付50%，验收合格支付40%，质保期结束支付10%。\n"
                "原招标文件30%、65%、5%的付款比例仍必须同时执行，二者没有优先顺序。")
contracts = json.loads((ROOT / ".test-runtime/contract-fixtures/golden.json").read_text(encoding="utf-8"))
for spec in contracts:
    paths = []
    for raw in spec["paths"]:
        p = Path(raw)
        dest = OUT / "contract" / spec["id"] / p.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, dest)
        paths.append(dest.relative_to(OUT).as_posix())
    spec["paths"] = paths
manifest = {"synthetic": True, "budget": budgets, "contract": contracts,
            "procurement": [{"id": "normal", "paths": ["procurement/normal/" + p.name for p in names]},
                            {"id": "conflict", "paths": ["procurement/normal/" + p.name for p in names if not p.name.startswith("10_")] + [conflict.relative_to(OUT).as_posix()]}]}
(OUT / "golden.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
archive = OUT.parent / "fixtures.tar.gz"
with tarfile.open(archive, "w:gz") as tar:
    for p in OUT.rglob("*"):
        if p.is_file():
            tar.add(p, arcname=p.relative_to(OUT).as_posix(), recursive=False)
print(json.dumps({"archive": str(archive), "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "budget": len(budgets), "contract": len(contracts), "procurement": 2}))
