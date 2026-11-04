"""Synthetic contracts: amounts/terms exist only inside deliberately rotated scans."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from docx import Document
from docx.shared import Inches
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader

p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--font',type=Path,required=True);args=p.parse_args()
root=args.output;root.mkdir(exist_ok=False)
font=ImageFont.truetype(str(args.font),38)
lines=["仿真图书馆办公设备采购合同", "本文件仅供系统测试，不属于真实业务。",
    "项目名称：仿真图书馆办公设备采购", "项目编号：DEMO-ROT-C01", "合同编号：DEMO-HT-C01",
    "甲方：仿真公共文化服务中心", "乙方：仿真青禾设备有限公司",
    "采购内容：办公计算机20台，单价6000.00元。",
    "合同总金额：120000.00元。", "金额大写：人民币壹拾贰万元整。",
    "交货期限：合同生效后30个日历日内完成交付。",
    "付款条件：全部设备验收合格后支付合同价款。",
    "逾期交货：乙方每日按合同总价的万分之五支付违约金。",
    "质保期限：验收合格之日起三年。", "争议处理：双方协商不成，向甲方所在地法院起诉。",
    "合同由双方盖章后生效，一式肆份，双方各执贰份。"]
image=Image.new('RGB',(1800,2300),'white');draw=ImageDraw.Draw(image)
for i,line in enumerate(lines):draw.text((90,90+i*125),line,font=font,fill='black')
image.save(root.parent/(root.name+'-qa.png'))
files=[]
png=root/'01_倒置合同.png';image.rotate(180,expand=True).save(png);files.append(png)
pdf=root/'02_横置合同扫描.pdf';doc=canvas.Canvas(str(pdf),pagesize=(792,612))
doc.drawImage(ImageReader(image.rotate(90,expand=True)),20,20,752,572);doc.showPage();doc.save();files.append(pdf)
word=root/'03_合同正文含横置影像.docx';document=Document()
document.add_heading('合同影像核对材料',0)
document.add_paragraph('本文件为仿真测试。项目名称：仿真图书馆办公设备采购。项目编号：DEMO-ROT-C01。')
document.add_paragraph('合同价款、交付、付款及违约条款仅见下方原始影像，请完整读取。')
with io.BytesIO() as raw:
    image.rotate(270,expand=True).save(raw,format='PNG');raw.seek(0);document.add_picture(raw,width=Inches(6.2))
document.save(word);files.append(word)
(root/'测试说明.txt').write_text('全部为仿真材料。三个文件是同一合同的不同方向与容器测试，请分别建测试项目上传，避免将相同合同当作三份业务。\n合同应识别金额12万元、20台计算机、单价6000元，交货30日、验收后付款、三年质保、逾期每日万分之五违约金。第三份的金额和条款只在图片内，不能仅解析正文。\n本组重点验证方向识别、合同文本读取和模型理解；不代表完整五步审核及正式报告已经通过。\n',encoding='utf-8')
manifest=[dict(file=f.name,sha256=hashlib.sha256(f.read_bytes()).hexdigest()) for f in files]
(root.parent/(root.name+'-expected.json')).write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print('CONTRACT_SYNTHETIC_FIXTURES='+str(len(files)))
