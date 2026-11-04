"""Generate synthetic office materials with physically rotated raster content."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import textwrap

from PIL import Image, ImageDraw, ImageFont
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.oxml.ns import qn
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from pypdf import PdfReader, PdfWriter


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--font", type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    if root.exists(): raise SystemExit("Preserve existing output")
    budget = root / "01_预算材料"; procurement = root / "02_采购同一项目"
    budget.mkdir(parents=True); procurement.mkdir()
    qa = root.parent / (root.name + "-qa"); qa.mkdir()
    expected = []
    font = ImageFont.truetype(str(args.font), 39)
    heading = ImageFont.truetype(str(args.font), 53)

    def paper(title, lines):
        image = Image.new("RGB", (1654, 2200), "white")
        draw = ImageDraw.Draw(image)
        draw.text((118, 115), title, font=heading, fill="black")
        y = 250
        for text in lines:
            for line in textwrap.wrap(text, 34, replace_whitespace=False) or [""]:
                draw.text((118, y), line, font=font, fill="black"); y += 70
            y += 22
        assert y < 2100, "Fixture text exceeds paper"
        return image

    def pdf(path, images, rotations=None):
        document = canvas.Canvas(str(path), pagesize=(595, 792))
        for image in images:
            width, height = image.size
            scale = min(535/width, 730/height)
            w, h = width*scale, height*scale
            document.drawImage(ImageReader(image), (595-w)/2, (792-h)/2, w, h)
            document.showPage()
        document.save()
        if rotations:
            reader = PdfReader(path); writer = PdfWriter()
            for index, page in enumerate(reader.pages):
                page.rotate(rotations[index]); writer.add_page(page)
            with path.open("wb") as output: writer.write(output)

    def word(path, title, paragraphs, images):
        doc = Document()
        section = doc.sections[0]
        section.page_width = Inches(8.5); section.page_height = Inches(11)
        section.top_margin = section.bottom_margin = Inches(.65)
        section.left_margin = section.right_margin = Inches(.8)
        for name in ("Normal", "Title", "Heading 1"):
            style = doc.styles[name]
            style.font.name = "Microsoft YaHei"; style.font.color.rgb = RGBColor(0, 0, 0)
            style.element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        doc.styles["Normal"].font.size = Pt(12)
        doc.add_paragraph(title, style="Title")
        for text in paragraphs: doc.add_paragraph(text)
        for index, image in enumerate(images):
            if index: doc.add_page_break()
            filename = qa / (path.stem + str(index) + ".png"); image.save(filename)
            # Deliberately rotated source remains rotated in the DOCX. This is
            # an adversarial OCR fixture, not a document layout defect.
            width = min(6.7, 6.6 * image.width/image.height)
            doc.add_picture(str(filename), width=Inches(width))
        doc.save(path)

    directions = [(0,"正向","北楼照明改造"), (90,"左转90度","东楼门禁升级"),
                  (180,"倒置180度","西楼设备更新"), (270,"右转90度","南楼线路整治"),
                  (5.5,"轻微倾斜","档案室环境改善")]
    for number,(angle,label,project) in enumerate(directions,1):
        name = f"{number:02d}_{label}.png"
        image = paper("预算申报及批复核对材料", ["本材料为仿真测试，不属于真实业务。",
            f"项目名称：仿真{project}", f"项目编号：DEMO-ROT-B{number:02d}",
            "预算科目：办公设施维护", "本次申报人民币壹拾贰万元整（120000.00元）。",
            "该项目批复人民币壹拾万元整（100000.00元）。",
            "上述批复仅适用于本项目，未安排其他追加额度。", "请据此核对预算申报与批复的差额。"])
        image.rotate(angle, expand=True, fillcolor="white").save(budget / name)
        expected.append(dict(path=str((budget/name).relative_to(root)).replace('\\','/'),
            module="budget",project="仿真"+project,declared=120000,approved=100000,difference=20000,angle=angle))

    pages=[]
    for index,(subject,declared,approved,angle) in enumerate([
        ("灯具购置",48000,40000,0),("配电施工",36000,30000,90),("安装调试",36000,30000,180)]):
        pages.append(paper("预算明细和批复对照", ["本材料为仿真测试，不属于真实业务。",
            "项目名称：仿真阅览室照明提升", "项目编号：DEMO-ROT-B06", f"预算科目：{subject}",
            f"申报金额：{declared}.00元", f"批复金额：{approved}.00元",
            "本页仅列一个科目，请连同其余两页一并核对。", f"本页为第{index+1}页，共3页。"]).rotate(angle, expand=True))
    pdf(budget/"06_三页不同方向.pdf",pages)
    expected.append(dict(path="01_预算材料/06_三页不同方向.pdf",module="budget",project="仿真阅览室照明提升",
                         declared=120000,approved=100000,difference=20000,rows=[[48000,40000],[36000,30000],[36000,30000]]))
    img = paper("批复金额附件", ["本材料为仿真测试，不属于真实业务。", "项目名称：仿真服务大厅座椅更新",
        "项目编号：DEMO-ROT-B07", "预算科目：办公家具", "申报金额：84000.00元", "批复金额：70000.00元",
        "预算金额仅记载在本张附件图片，正文未另行抄录。"])
    word(budget/"07_正文内含横置图片.docx", "服务大厅座椅更新预算核对",
         ["本材料为仿真测试，不属于真实业务。", "项目名称：仿真服务大厅座椅更新。", "具体申报金额和批复金额见下方附件影像。"], [img.rotate(90,expand=True)])
    expected.append(dict(path="01_预算材料/07_正文内含横置图片.docx",module="budget",project="仿真服务大厅座椅更新",declared=84000,approved=70000,difference=14000))

    identity = ["本材料为仿真测试，不属于真实业务。", "项目名称：仿真公共服务中心办公终端采购",
        "项目编号：DEMO-ROT-P01", "申请部门：信息保障科", "采购类型：货物", "预算金额：100000.00元",
        "预算科目编码：A1001", "预算科目名称：办公设备", "采购方式：公开招标", "申请人：仿真经办员"]
    demand = paper("采购需求附件", identity+[
        "采购内容：办公计算机20台，每台配套显示器。", "要求：按性能与数量验收，交付后三年保修。"])
    word(procurement/"01_需求书内嵌倒图.docx", "办公终端采购需求",
        ["本材料为仿真测试，不属于真实业务。", "本采购项目的完整需求及预算见附件影像。"], [demand.rotate(180,expand=True)])
    tender = paper("招标文件主要条款", identity[:6]+[
        "台式计算机必须使用星桥品牌，仅限星桥品牌参与。", "投标单位须为本地注册企业。",
        "供应商应负责安装、调试并提供三年保修。", "采购数量20台，验收后一次性付款。"])
    pdf(procurement/"02_横向招标扫描.pdf", [tender.rotate(90,expand=True)])
    quote = paper("供应商报价记录", identity[:6]+["供应商：仿真青禾设备有限公司",
        "报价内容：办公计算机20台，含显示器、安装和保修。", "含税报价总额：118000.00元。",
        "交货周期：30个日历日，报价有效期60日。"])
    quote.rotate(270,expand=True).save(procurement/"03_横置报价截图.jpg",quality=93)
    pdf(procurement/"04_带页面旋转标记.pdf",[quote],rotations=[180])
    expected.extend([dict(path="02_采购同一项目/"+name,module="procurement",project=identity[1].split("：")[1])
                     for name in ("01_需求书内嵌倒图.docx","02_横向招标扫描.pdf","03_横置报价截图.jpg","04_带页面旋转标记.pdf")])
    (root.parent/(root.name+"-expected.json")).write_text(json.dumps(expected,ensure_ascii=False,indent=2),encoding="utf-8")
    (root/"测试说明.txt").write_text("全部为仿真材料，不含甲方文件。\n\n预算：打开预算首页，上传01_预算材料内的七个文件，等待解析并查看历史结果。前六份各应识别申报12万元、批复10万元及超出2万元；第七份应为申报8.4万元、批复7万元及超出1.4万元。第六份包含三页不同方向，不能只读第一页。\n\n采购：新建采购需求，一次选择02_采购同一项目内四份文件。核对自动填充的项目名、预算10万元，点击保存并查看结果。应识别需求20台计算机、报价11.8万元，并指出招标文件指定品牌和限制本地注册的问题。第三和第四份为同一份报价的两种方向测试，不代表两个供应商，不应据此认定围串标。\n\n文件特意保留横置、倒置和倾斜，便于观察系统纠正效果。\n",encoding="utf-8")
    checks=[]
    for file in sorted(root.rglob("*")):
        if file.is_file(): checks.append(hashlib.sha256(file.read_bytes()).hexdigest()+"  "+str(file.relative_to(root)).replace('\\','/'))
    (root.parent/(root.name+"-sha256.txt")).write_text("\n".join(checks)+"\n",encoding="utf-8")
    print(json.dumps(dict(files=len(expected),output=str(root)),ensure_ascii=False))


if __name__ == "__main__": main()
