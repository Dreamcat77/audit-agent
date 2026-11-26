"""Pixel-space regressions: boxes must follow original pixels, never inferred text."""
from dataclasses import replace
from types import SimpleNamespace
import math
import pytest
Image=pytest.importorskip('PIL.Image')
ImageDraw=pytest.importorskip('PIL.ImageDraw')
from audit_ai.ocr.geometry import OcrRegion, restore_regions, recheck_critical_regions
from audit_ai.ocr.port import OcrPage
from audit_ai.ocr.orientation import recognize_page


@pytest.mark.parametrize('rotation', [0, 90, 180, 270])
@pytest.mark.parametrize('tilt', [0, -3, 3])
def test_inverse_transform_returns_exact_original_region(rotation, tilt):
    original=(160,240)
    points=((25.,40.),(105.,40.),(105.,70.),(25.,70.))
    steps=[];size=original;current=points
    for angle in (rotation,tilt):
        with Image.new('RGB',size) as image, image.rotate(angle,expand=True) as rotated:
            after=rotated.size
        a=math.radians(angle);c,s=math.cos(a),math.sin(a)
        current=tuple((c*(x-size[0]/2)+s*(y-size[1]/2)+after[0]/2,
                       -s*(x-size[0]/2)+c*(y-size[1]/2)+after[1]/2) for x,y in current)
        steps.append((angle,size,after));size=after
    page=OcrPage(1,'预算金额',.95,(OcrRegion(0,4,current,.95),))
    restored=restore_regions(page,original,steps)
    for got,want in zip(restored.regions[0].polygon,points):
        assert got==pytest.approx((want[0]/160,want[1]/240),abs=1e-8)


@pytest.mark.parametrize('rotation',[0,90,180,270])
def test_actual_rotated_ink_is_inside_returned_box(rotation,monkeypatch):
    monkeypatch.setattr('audit_ai.ocr.orientation.deskew_angle',lambda image:0)
    image=Image.new('RGB',(60,100),'white')
    ImageDraw.Draw(image).rectangle((10,20,45,30),fill='black')
    image=image.rotate(rotation,expand=True)
    def predict(array):
        good=array.shape[:2]==(100,60) and array[20,10,0]==0 and array[30,45,0]==0
        return ((OcrPage(1,'预算金额' if good else '错',.99 if good else .1,
            (OcrRegion(0,4,((10,20),(46,20),(46,31),(10,31)),.99),) if good else ()),),[.99 if good else .1])
    page,_=recognize_page(image,1,predict,lambda value:value)
    assert page.text=='预算金额'
    poly=page.regions[0].polygon
    left=min(p[0] for p in poly)*image.width;right=max(p[0] for p in poly)*image.width
    top=min(p[1] for p in poly)*image.height;bottom=max(p[1] for p in poly)*image.height
    ink=[(x,y) for x in range(image.width) for y in range(image.height) if image.getpixel((x,y))==(0,0,0)]
    assert all(left-1<=x<=right+1 and top-1<=y<=bottom+1 for x,y in ink)


@pytest.mark.parametrize('second,verification',[('预算金额10000元','MATCHED'),('预算金额90000元','UNCERTAIN'),('', 'UNCERTAIN')])
def test_independent_read_never_replaces_original_amount(second,verification):
    page=OcrPage(1,'预算金额10000元',.8,(OcrRegion(0,10,((.1,.2),(.8,.2),(.8,.3),(.1,.3)),.8),))
    result=recheck_critical_regions(page,Image.new('RGB',(100,200)),lambda crop:OcrPage(1,second,.95))
    assert result.text==page.text and result.regions[0].verification==verification


def test_rechecks_are_bounded_and_failure_is_uncertain():
    text='金额1元\n金额2元\n金额3元\n金额4元';regions=[]
    for i in range(4):regions.append(OcrRegion(i*5,i*5+4,((.1,.1),(.9,.1),(.9,.3),(.1,.3)),.7))
    page=OcrPage(1,text,.7,tuple(regions));calls=[]
    def broken(crop):calls.append(crop.size);raise RuntimeError('private detail must not be logged')
    result=recheck_critical_regions(page,Image.new('RGB',(100,200)),broken)
    assert len(calls)==2 and [r.verification for r in result.regions]==['UNCERTAIN']*2+['NOT_RECHECKED']*2


def test_outside_polygon_is_omitted_but_text_survives():
    page=OcrPage(1,'文字',.9,(OcrRegion(0,2,((-10,0),(10,0),(10,10),(-10,10)),.9),))
    result=restore_regions(page,(100,100),[])
    assert result.text=='文字' and not result.regions


def test_http_span_offsets_are_utf16_and_worker_roundtrip_keeps_geometry():
    from audit_ai.ocr.port import OcrExtraction
    from audit_ai.ocr.router import _response
    from audit_ai.ocr.process_adapter import _result
    import json
    page=OcrPage(1,'😀金额',.9,(OcrRegion(1,3,((.1,.1),(.9,.1),(.9,.2),(.1,.2)),.9,'UNCERTAIN'),),100,200)
    extraction=OcrExtraction(page.text,.9,(page,),'TEST',1)
    wire=_response(extraction).model_dump(by_alias=True)
    assert wire['data']['pages'][0]['regions'][0]['start']==2
    assert wire['data']['pages'][0]['regions'][0]['end']==4
    # IPC stays Python codepoints. HTTP alone converts to Java/JS UTF-16 offsets.
    from dataclasses import asdict
    assert _result(json.dumps(asdict(extraction)).encode()).pages[0]==page
