"""Real upload/model/OCR/DM8 checks with generated synthetic office files only.

Credentials arrive through the existing runner's anonymous stdin pipe. Output
contains only synthetic names, IDs, counts and outcomes; never source text.
"""
import json
import mimetypes
import os
from pathlib import Path
import sys
import time
import uuid
import requests

root=Path(__file__).resolve().parent
auth=json.loads(sys.stdin.readline())
base='http://127.0.0.1'
session=requests.Session()

def csrf():
    r=session.get(base+'/api/v1/auth/csrf',timeout=15);r.raise_for_status()
    token=r.json()['data'];session.headers[token['headerName']]=token['token']

def login():
    csrf();r=session.post(base+'/api/v1/auth/login',json={'username':auth['username'],'password':auth['password']},timeout=20)
    assert r.status_code==200,'login HTTP='+str(r.status_code)
    csrf()

def get(path):
    r=session.get(base+path,timeout=30);assert r.status_code==200,'read HTTP='+str(r.status_code)
    return r.json()['data']

kind=sys.argv[1] if len(sys.argv)>1 else 'all'
modules=['budget','procurement'] if kind=='all' else [kind]
label=os.environ.get('AUDIT_SYNTHETIC_OFFICE_LABEL',kind)
assert label and all(c.isascii() and (c.isalnum() or c=='-') for c in label)
report_path=root/f'results-{label}.json'
records=[]
login()

def save():report_path.write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')

for module in modules:
    selected=set(sys.argv[2:])|set(filter(None,os.environ.get('AUDIT_SYNTHETIC_OFFICE_FILES','').split(',')))
    for path in sorted((root/'fixtures').iterdir()):
        if not path.is_file() or not path.name.startswith(('sample','expected')):continue
        if selected and path.name not in selected:continue
        key=str(uuid.uuid4());start=time.monotonic()
        endpoint='/api/v1/budget/material-uploads' if module=='budget' else '/api/v1/procurement/materials'
        data={'budgetYear':'2026','departmentCode':'PROCUREMENT'} if module=='budget' else {}
        row={'module':module,'file':path.name,'bytes':path.stat().st_size,'key':key}
        with path.open('rb') as source:
            r=session.post(base+endpoint,headers={'Idempotency-Key':key,'X-Request-Id':'synthetic-office-'+key},
                data=data,files={'file':(path.name,source,mimetypes.guess_type(path.name)[0] or 'application/octet-stream')},timeout=180)
        row['uploadHttp']=r.status_code
        if r.status_code==200:
            result=r.json()['data'];row['id']=result['id'];row['status']=result['status']
        else:row['status']='REJECTED'
        row['uploadSeconds']=round(time.monotonic()-start,2);records.append(row);save()
        print(json.dumps({'uploaded':module,'file':row['file'],'http':row['uploadHttp'],'bytes':row['bytes']}),flush=True)
    # Poll each independent job; one bad file never prevents later uploads.
    deadline=time.monotonic()+3600
    last_counts=None
    while True:
        pending=False
        for row in records:
            if row['module']!=module or row['status'] not in ('QUEUED','PROCESSING','UNKNOWN'):continue
            prefix='/api/v1/budget/material-uploads/' if module=='budget' else '/api/v1/procurement/materials/'
            result=get(prefix+row['id']);row['status']=result['status'];row['errorCode']=result.get('errorCode')
            if row['status'] in ('QUEUED','PROCESSING'):pending=True
            elif row['status']=='UNKNOWN':row['unexpectedUnknown']=True
            elif row['status']=='PARSED':
                if module=='budget':
                    review=get('/api/v1/budget/reviews/'+row['id'])
                    reference=review.get('materialReference')
                    if reference is not None:
                        until=time.monotonic()+30
                        while reference['status']=='PENDING' and time.monotonic()<until:
                            time.sleep(1);review=get('/api/v1/budget/reviews/'+row['id']);reference=review['materialReference']
                        lines=reference.get('lines') or []
                        evidence={(e['locator'],e['value']) for line in lines for e in line.get('sources',[]) if e.get('field')=='declaredAmount'}
                        row.update(resultFamily='UPLOADED_MATERIALS',taskStatus=review['task']['status'],
                            referenceStatus=reference['status'],rowCount=len(lines),
                            correctDeclaration=bool(evidence) and all(v in ('31200','31200元') for _,v in evidence),
                            amountEvidenceRows=len({locator for locator,_ in evidence}),
                            statuses=sorted({line['status'] for line in lines}))
                        expected=8 if row['file']=='sample-table.docx' else 2 if row['file'] in ('sample.xls','sample.xlsx') else 1
                        row['rowCoverageCorrect']=row['amountEvidenceRows']>=expected
                        # Repeated identical spending rows lack a detail identity.
                        # Preserving their evidence and refusing an invented sum is correct.
                        row['uncertainRepeatedAmounts']=any(line['status'] in ('INVALID_AMOUNT','CONFLICT') for line in lines)
                        if row['file']=='sample.xlsx':
                            row['duplicateWorksheetsNotSilentlyMerged']=len({locator for locator,_ in evidence})>=2 and all(
                                line.get('declaredAmount') is None and line['status'] in ('INVALID_AMOUNT','CONFLICT') for line in lines)
                        continue
                    row['taskStatus']=review['task']['status'];row['rowCount']=len(review['items'])
                    row['correctDeclaration']=bool(review['items']) and all(str(i['declaredAmount']) in ('31200','31200.0','31200.00') for i in review['items'])
                    expected_rows=8 if row['file']=='sample-table.docx' else 2 if row['file'] in ('sample.xls','sample.xlsx') else 1 if row['file'] in ('sample.doc','sample.docx','sample-large.docx','sample.pdf','sample.ppt','sample.pptx','sample.txt','sample.csv','sample-utf16.txt','sample.rtf','sample.png','sample.jpg','sample.bmp') else None
                    row['expectedRows']=expected_rows
                    row['rowCoverageCorrect']=expected_rows is None or row['rowCount']==expected_rows
                    row['semanticSuccessCount']=sum(bool((i.get('aiSemanticAnalysis') or {}).get('available')) for i in review['items'])
                else:
                    fields=result.get('fields') or {};evidence=fields.get('evidence') or {}
                    row['projectNameRecognized']=bool(fields.get('projectName'))
                    row['fieldCount']=len(evidence);row['evidenceCount']=sum(len(v) for v in evidence.values())
                    row['requirementEvidenceCount']=len(evidence.get('requirementDescription') or [])
                    row['parseMethod']=result.get('parseMethod')
        save()
        counts={s:sum(r['module']==module and r['status']==s for r in records) for s in ('QUEUED','PROCESSING','PARSED','FAILED','UNKNOWN','REJECTED')}
        if counts!=last_counts:print(json.dumps({'progress':module,'counts':counts}),flush=True);last_counts=counts
        if not pending:break
        assert time.monotonic()<deadline,'background deadline exceeded'
        time.sleep(5)
    expected=[r for r in records if r['module']==module and r['file'].startswith('expected')]
    good=[r for r in records if r['module']==module and r['file'].startswith('sample')]
    failures=[r for r in good if r['status']!='PARSED' or module=='budget' and (not r.get('correctDeclaration') or not r.get('rowCoverageCorrect') or r.get('taskStatus')!='REVIEWED') or module=='procurement' and (not r.get('projectNameRecognized') or r.get('fieldCount',0)<10 or r['file']=='sample-long.txt' and r.get('requirementEvidenceCount',0)<120)]
    failures.extend(r for r in expected if r['status'] not in ('FAILED','REJECTED'))
    failures.extend(r for r in good if module=='budget' and r['file']=='sample.xlsx' and not r.get('duplicateWorksheetsNotSilentlyMerged'))
    if module=='budget' and os.environ.get('AUDIT_SYNTHETIC_REQUIRE_SEMANTIC')=='1':
        failures.extend(r for r in good if r.get('resultFamily')!='UPLOADED_MATERIALS' and r.get('semanticSuccessCount',0)!=r.get('rowCount',-1))
    print(json.dumps({'module':module,'total':len(expected)+len(good),'valid':len(good),'failures':len(failures),'unexpectedFiles':[r['file'] for r in failures]}),flush=True)
    if failures:sys.exit(1)
print('REAL_OFFICE_UPLOAD_MODEL_DM8=PASS',flush=True)
