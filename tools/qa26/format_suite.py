"""Real office/scanned input suite on isolated QA26 only. Original fixture files are read-only."""
import argparse
import contextlib
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import re
import time
import uuid

spec=importlib.util.spec_from_file_location('qa','/home/admin/qa26-acceptance.py')
qa=importlib.util.module_from_spec(spec);spec.loader.exec_module(qa)
REVISION="r2"
SOURCE=Path('/home/admin/audit-soak-20261021.lwAmqs/fixtures')
EXTRA=qa.BACKUP/'format-fixtures-r2';EXTRA.mkdir(exist_ok=True)

def extras():
    from PIL import Image
    import zipfile
    for degrees in (90,180,270):
        target=EXTRA/('办公需求旋转'+str(degrees)+'.png')
        if not target.exists():
            with Image.open(SOURCE/'sample.png') as source:source.rotate(degrees,expand=True).save(target)
    target=EXTRA/'正文及倒置图片.docx'
    if not target.exists():
        from docx import Document
        from docx.shared import Inches
        doc=Document();doc.add_paragraph('合成测试材料。附件图片为本项目的采购需求原始材料，请完整读取。')
        doc.add_picture(str(EXTRA/'办公需求旋转270.png'),width=Inches(6));doc.save(target)

CORE=['sample.doc','sample.docx','sample.pdf','sample.xls','sample.xlsx','sample.ppt','sample.pptx',
      'sample.txt','sample.csv','sample.rtf','sample-utf16.txt','sample-table.docx','sample-large.docx']
SCANS=['sample.png','sample.jpg','sample.bmp','sample.tiff','sample-mixed.pdf','sample-scan.pdf']

def amount(value):
    m=re.search(r'[0-9][0-9,]*(?:\.[0-9]+)?',str(value or ''))
    return Decimal(m[0].replace(',','')) if m else None

def budget():
    extras();paths=[SOURCE/n for n in CORE+SCANS]+sorted(EXTRA.glob('*.png'))+[EXTRA/'正文及倒置图片.docx',SOURCE/'expected-limit.txt']
    rows=[]
    for path in paths:
        state=qa.identity('format-budget-'+REVISION+'-'+path.name,{'key':str(uuid.uuid4())})
        result=qa.CLIENT.call('GET','/api/v1/budget/material-uploads/operations/'+state['key'])
        if not result:
            with path.open('rb') as f:
                result=qa.CLIENT.call('POST','/api/v1/budget/material-uploads',headers={'Idempotency-Key':state['key']},
                            files={'file':(path.name,f,'application/octet-stream')},data={'budgetYear':'2026','departmentCode':'PROCUREMENT'})
        rows.append({'file':path.name,'bytes':path.stat().st_size,'id':result['id']})
        print(json.dumps({'formatUpload':'budget','file':path.name,'bytes':path.stat().st_size}),flush=True)
    until=time.monotonic()+7200;pending=list(rows)
    while pending:
        for row in list(pending):
            job=qa.CLIENT.call('GET','/api/v1/budget/material-uploads/'+row['id'])
            if job['status'] in {'QUEUED','PROCESSING'}:continue
            review=qa.CLIENT.call('GET','/api/v1/budget/reviews/'+row['id']);reference=review.get('materialReference') or {}
            if job['status']=='PARSED' and reference.get('status')=='PENDING':continue
            failures=[];lines=reference.get('lines') or []
            if row['file'].startswith('expected'):
                if job['status']!='FAILED':failures.append('RESOURCE_LIMIT_NOT_EXPLICITLY_FAILED')
            else:
                if job['status']!='PARSED':failures.append('VALID_OFFICE_FILE_NOT_PARSED')
                if review['task']['status']!='REVIEWED':failures.append('NO_COMPLETED_BUDGET_PROJECTION')
                if not any(amount(e.get('value'))==Decimal('31200') for line in lines for e in line.get('sources',[]) if e.get('field')=='declaredAmount'):
                    failures.append('EXPECTED_DECLARATION_NOT_EXTRACTED')
            row.update(status=job['status'],lineCount=len(lines),referenceStatus=reference.get('status'),failures=failures,realModel=True,realDm8=True,synthetic=True)
            qa.save('format-budget-'+row['id']+'-raw',{'job':job,'review':review})
            pending.remove(row);qa.save('formats-budget-'+REVISION,rows);print('QA26_FORMAT '+json.dumps(row,ensure_ascii=False),flush=True)
        assert time.monotonic()<until,'FORMAT_BUDGET_DEADLINE'
        if pending:time.sleep(4)
    return rows

def procurement():
    extras();results=[]
    groups=[('office',[SOURCE/n for n in CORE]),('scans',[SOURCE/n for n in SCANS]+sorted(EXTRA.glob('*.png'))+[EXTRA/'正文及倒置图片.docx',SOURCE/'expected-limit.txt'])]
    for label,paths in groups:
        state=qa.identity('format-procurement-'+REVISION+'-'+label,{'key':str(uuid.uuid4())})
        case=qa.CLIENT.call('GET','/api/v1/procurement/cases/operations/'+state['key'])
        if not case:
            with contextlib.ExitStack() as stack:
                files=[('files',(p.name,stack.enter_context(p.open('rb')),'application/octet-stream')) for p in paths]
                case=qa.CLIENT.call('POST','/api/v1/procurement/cases',headers={'Idempotency-Key':state['key']},files=files)
        until=time.monotonic()+7200;last=None
        while case['status'] in {'UPLOADING','QUEUED','RUNNING'}:
            marker=(case['status'],sum(d['status']=='EXTRACTED' for d in case['documents']))
            if marker!=last:print(json.dumps({'formatProgress':label,'state':marker}),flush=True);last=marker
            assert time.monotonic()<until,'FORMAT_PROCUREMENT_DEADLINE'
            time.sleep(4);case=qa.CLIENT.call('GET','/api/v1/procurement/cases/'+case['id'])
        for d in case['documents']:
            row={'file':d['name'],'caseId':case['id'],'fileId':d['id'],'status':d['status'],'failures':[],'realModel':True,'realDm8':True,'synthetic':True}
            if d['name'].startswith('expected'):
                if d['status']!='FAILED':row['failures'].append('RESOURCE_LIMIT_NOT_EXPLICITLY_FAILED')
            else:
                if d['status']!='EXTRACTED':row['failures'].append('VALID_FILE_NOT_EXTRACTED')
                ev=qa.CLIENT.call('GET','/api/v1/procurement/cases/'+case['id']+'/files/'+d['id']+'/evidence')
                good=[f for f in ev['facts'] if f['verification']=='SOURCE_VERIFIED']
                row['facts']=len(good);row['sourceChars']=sum(len(s['text']) for s in ev['segments'])
                if not any(f['type']=='budgetAmount' and amount(f['value'])==Decimal('200000') for f in good):row['failures'].append('EXPLICIT_MATERIAL_BUDGET_MISSING')
                if any(f['type']=='quoteAmount' for f in good):row['failures'].append('DECLARATION_OR_BUDGET_MISTAKEN_AS_QUOTE')
                if not any(f['type']=='projectNo' and 'SYN-OFFICE-2026'==re.sub(r'\s+','',f['value']) for f in good):row['failures'].append('PROJECT_ID_MISSING')
            results.append(row);print('QA26_FORMAT '+json.dumps(row,ensure_ascii=False),flush=True)
        if case.get('report',{}).get('offers'):results.append({'file':label,'failures':['INVENTED_QUOTATION']})
        qa.save('format-procurement-'+label+'-'+REVISION+'-raw',case);qa.save('formats-procurement-'+REVISION,results)
    return results

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('module',choices=['budget','procurement']);p.add_argument('--revision',choices=['r2','r6'],default='r2');args=p.parse_args();REVISION=args.revision
    rows=globals()[args.module]();failures=[r for r in rows if r.get('failures')]
    print(json.dumps({'formatSuite':args.module,'samples':len(rows),'failures':len(failures),'synthetic':True}),flush=True)
    raise SystemExit(bool(failures))
