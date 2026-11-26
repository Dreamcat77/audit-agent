from dataclasses import replace
import pytest
Image=pytest.importorskip('PIL.Image')
Draw=pytest.importorskip('PIL.ImageDraw')
pytest.importorskip('numpy')
from audit_ai.ocr.compound import split_boxes,recover_compound
from audit_ai.ocr.port import OcrPage
from audit_ai.ocr.geometry import OcrRegion


def pasted_page():
    image=Image.new('RGB',(600,800),'white');draw=Draw.Draw(image)
    for start in (40,450):
        for y in range(start,start+280,20):draw.rectangle((50,y,550,y+6),fill='black')
    return image


def test_detects_two_sheets_and_does_not_split_blank_or_continuous_page():
    assert len(split_boxes(pasted_page()))==2
    assert split_boxes(Image.new('RGB',(600,800),'white'))==[]
    image=Image.new('RGB',(600,800),'white');draw=Draw.Draw(image)
    for y in range(40,760,20):draw.rectangle((50,y,550,y+6),fill='black')
    assert split_boxes(image)==[]


def test_recovery_keeps_physical_page_and_translates_highlight_geometry(monkeypatch):
    def recognize(image,page_num,*args):
        text='独立扫描区域内容预算金额人民币' * 3
        return OcrPage(page_num,text,.98,(OcrRegion(0,len(text),((.1,.1),(.9,.1),(.9,.9),(.1,.9)),.98),)),[.98]
    monkeypatch.setattr('audit_ai.ocr.compound.recognize_page',recognize)
    original=OcrPage(7,'碎片',.3)
    page,scores=recover_compound(pasted_page(),original,[.3],None,None)
    assert page.page_num==7 and len(page.regions)==2 and len(page.text)>len(original.text)
    assert max(y for _,y in page.regions[0].polygon)<min(y for _,y in page.regions[1].polygon)
    assert all(page.text[r.start:r.end] for r in page.regions)
    assert all(0<=x<=1 and 0<=y<=1 for r in page.regions for x,y in r.polygon)


def test_weak_crop_never_replaces_stronger_full_page(monkeypatch):
    monkeypatch.setattr('audit_ai.ocr.compound.recognize_page',lambda *args:(OcrPage(1,'错',.1),[.1]))
    original=OcrPage(1,'原文保留',.9)
    assert recover_compound(pasted_page(),original,[.9],None,None)[0] is original
