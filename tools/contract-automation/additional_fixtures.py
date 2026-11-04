"""Synthetic rotated scan and multi-row table; expectations remain independent of AI."""
import json
import os
from pathlib import Path
import textwrap
from PIL import Image, ImageDraw, ImageFont
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from docx import Document
from fixtures import MARK, SERVICE, PROCUREMENT

root=Path(__file__).resolve().parents[2]/".test-runtime/contract-fixtures"
specs=json.loads((root/"golden.json").read_text(encoding="utf-8"))
font=ImageFont.truetype(str(Path(os.environ["WINDIR"])/"Fonts/simsun.ttc"),28)
image=Image.new("RGB",(1700,2300),"white");draw=ImageDraw.Draw(image)
y=75
for paragraph in (MARK+"\n"+SERVICE).splitlines():
    for line in textwrap.wrap(paragraph,54):
        draw.text((85,y),line,font=font,fill="black");y+=43
    y+=9
assert y<2200,"scan text must not be clipped"
folder=root/"rotated-scan";folder.mkdir(exist_ok=True)
preview=folder/"synthetic-upright.png";image.save(preview)
image=image.rotate(180);image.save(folder/"synthetic-inverted.png")
pdf=folder/"倒置扫描服务合同.pdf"
page=canvas.Canvas(str(pdf),pagesize=(612,792));page.drawImage(ImageReader(image),0,0,width=612,height=792);page.save()
specs=[s for s in specs if s["id"] not in {"rotated-scan","complex-table"}]
specs.append({"id":"rotated-scan","paths":[str(pdf)],"profile":"SERVICE","amount":"86000","clausesMin":5,"expectedRisks":[],"synthetic":True})
folder=root/"complex-table";folder.mkdir(exist_ok=True)
doc=Document();doc.add_paragraph(MARK)
for paragraph in PROCUREMENT.splitlines():
    doc.add_paragraph(paragraph)
    if "完整合同价款明细表" in paragraph:
        table=doc.add_table(rows=1,cols=4);table.style="Table Grid"
        for c,v in zip(table.rows[0].cells,["设备名称","数量","含税单价（元）","金额（元）"]):c.text=v
        for i in range(12):
            # Repeated item names represent distinct physical rows and cannot be deduplicated.
            for c,v in zip(table.add_row().cells,["办公终端",str(i+1),"1000.00",str((i+1)*1000)]):c.text=v
        for c,v in zip(table.add_row().cells,["合计","","","78000.00"]):c.text=v
path=folder/"十二行同名设备明细.docx";doc.save(path)
specs.append({"id":"complex-table","paths":[str(path)],"amount":"120000","profile":"PROCUREMENT","riskTopics":["LINE_SUM"],"clausesMin":4,"physicalRows":12,"synthetic":True})
(root/"golden.json").write_text(json.dumps(specs,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps({"newScenarios":2,"scanLinesBottom":y,"totalScenarios":len(specs)},ensure_ascii=False))
