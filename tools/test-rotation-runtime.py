"""Live OCR and real-model acceptance on synthetic material only."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import time
import uuid
import zipfile
import requests


def main():
    p = argparse.ArgumentParser()
    p.add_argument("fixtures", type=Path)
    p.add_argument("--base", default="http://127.0.0.1:18001")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--start", type=int, default=0)
    p.add_argument("--reuse-ocr", type=Path)
    args = p.parse_args()
    expected = json.loads(args.fixtures.with_name(args.fixtures.name+"-expected.json").read_text(encoding="utf-8"))
    records=[]
    args.output.mkdir(exist_ok=False)
    for index, case in enumerate(expected):
        if index < args.start: continue
        path = args.fixtures / case['path']
        sources=[]
        if path.suffix == '.docx':
            with zipfile.ZipFile(path) as archive:
                sources=[(name, archive.read(name), 'image/png') for name in archive.namelist() if name.startswith('word/media/')]
        else:
            sources=[(path.name,path.read_bytes(),{'.png':'image/png','.jpg':'image/jpeg','.pdf':'application/pdf'}[path.suffix])]
        pages=[]; started=time.monotonic()
        cached = args.reuse_ocr / (str(index)+'-ocr.json') if args.reuse_ocr else None
        if cached and cached.is_file():
            previous=json.loads(cached.read_text(encoding='utf-8'))
            assert previous['file']==case['path']
            pages=previous['pages']
        for number,(name,content,mime) in enumerate([] if pages else sources):
            response=requests.post(args.base+'/ai/v1/ocr/extract', files={'file':(name,content,mime)},
                headers={'X-Request-ID':'rotation-ocr-'+uuid.uuid4().hex},timeout=900)
            assert response.status_code==200, 'OCR_HTTP_'+str(response.status_code)+'_'+path.name
            data=response.json()
            # OCR router has its own documented DTO, no Java envelope.
            data=data.get('data',data)
            pages.extend(data['pages'])
        text='\n'.join(page['text'] for page in pages)
        (args.output/(str(index)+'-ocr.json')).write_text(json.dumps(dict(file=case['path'],pages=pages),ensure_ascii=False,indent=2),encoding='utf-8')
        normalized=''.join(text.split()).replace(',','').replace('，','')
        if case['module']=='budget':
            if 'rows' in case:
                assert len(pages)==3, 'MIXED_PAGE_COVERAGE'
                for declared,approved in case['rows']:
                    assert str(declared) in normalized and str(approved) in normalized, 'OCR_MISSING_PAGE_AMOUNT'
            else:
                assert str(case['declared']) in normalized and str(case['approved']) in normalized, 'OCR_MISSING_AMOUNT_'+path.name
        else:
            assert '100000' in normalized, 'OCR_PROCUREMENT_BUDGET_MISSING'
            if index==8: assert '星桥' in text and '本地' in text, 'OCR_TENDER_CLAUSES_MISSING'
            if index>=9: assert '118000' in normalized, 'OCR_QUOTE_MISSING'
        material=str(uuid.uuid4())
        segments=[dict(locator='synthetic/page:'+str(n+1),text=page['text']) for n,page in enumerate(pages) if page['text'].strip()]
        response=requests.post(args.base+'/ai/v1/materials/extract-fields',json=dict(
            businessType='BUDGET' if case['module']=='budget' else 'PROCUREMENT_REVIEW',materialId=material,
            sha256=hashlib.sha256(path.read_bytes()).hexdigest(),segments=segments),
            headers={'X-Request-ID':'rotation-model-'+material},timeout=900)
        (args.output/(str(index)+'-model-response.json')).write_text(response.text,encoding='utf-8')
        assert response.status_code==200, 'MODEL_HTTP_'+str(response.status_code)+'_'+path.name
        fields=response.json(); fields=fields.get('data',fields)
        (args.output/(str(index)+'-model.json')).write_text(json.dumps(fields,ensure_ascii=False,indent=2),encoding='utf-8')
        facts=[fact for row in fields.get('rows',[]) for fact in row.get('facts',[])]
        assert facts, 'MODEL_NO_FACTS_'+path.name
        values={}
        for fact in facts: values.setdefault(fact['field'],[]).append(fact['value'])
        if case['module']=='budget':
            assert 'declaredAmount' in values and 'approvalAmount' in values, 'MODEL_BUDGET_AMOUNTS_MISSING_'+path.name
        else:
            assert 'projectName' in values, 'MODEL_PROCUREMENT_IDENTITY_MISSING_'+path.name
            if index>=9: assert 'quoteAmount' in values, 'MODEL_QUOTE_MISSING_'+path.name
        record=dict(file=case['path'],pages=len(pages),ocrChars=len(text),seconds=round(time.monotonic()-started,2),
                    ocrText=text,model=fields,result='PASS')
        records.append(record)
        (args.output/'results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:v for k,v in record.items() if k not in ('ocrText','model')},ensure_ascii=False),flush=True)
    print('ROTATION_REAL_OCR_AND_MODEL=PASS files='+str(len(records)),flush=True)


if __name__=='__main__': main()
