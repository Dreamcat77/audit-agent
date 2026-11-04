"""Deployed real HTTP/model/DM8 probe. Only generated synthetic records are read.

Credentials arrive through the existing ephemeral-actor anonymous pipe. This
probe restarts the backend only after its jobs and the global deployment gate
are idle; the actor is disabled by BudgetBackgroundRunner on exit.
"""
import hashlib
from datetime import datetime
import io
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time
import uuid
import zipfile
from xml.sax.saxutils import escape
import requests

root=Path(os.environ.get('AUDIT_SYNTHETIC_RESULTS_ROOT',str(Path(__file__).resolve().parent)))
auth=json.loads(sys.stdin.readline())
base='http://127.0.0.1/api/v1'
client=requests.Session()
run=uuid.uuid4().hex[:8]
paths=[]

def csrf():
    r=client.get(base+'/auth/csrf',timeout=20);assert r.status_code==200,'CSRF_HTTP'
    token=r.json()['data'];client.headers[token['headerName']]=token['token']

def login():
    client.cookies.clear();csrf()
    r=client.post(base+'/auth/login',json={k:auth[k] for k in ('username','password')},timeout=30)
    assert r.status_code==200,'LOGIN_HTTP';csrf()

def api(method,path,**kw):
    r=client.request(method,base+path,timeout=kw.pop('timeout',180),**kw)
    assert r.status_code==200,'HTTP_'+str(r.status_code)+'_'+path
    return r.json()['data']

def fingerprint(value):return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False).encode()).hexdigest()


def dm8_timestamp(value):
    # Real DM8 rounds TIMESTAMP to the nearest microsecond (not truncation).
    # Parse all Java nanoseconds before rounding; datetime would truncate first.
    match=re.fullmatch(r'(.+T\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)?',value)
    assert match,'REPORT_TIMESTAMP_FORMAT'
    base=datetime.fromisoformat(match[1]+(match[3] or '').replace('Z','+00:00'))
    nanos=int((match[2] or '').ljust(9,'0'))
    return int(base.timestamp())*1000000+(nanos+500)//1000

def docx(lines):
    xml='<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
    xml+=''.join('<w:p><w:r><w:t>'+escape(v)+'</w:t></w:r></w:p>' for v in lines)+'</w:body></w:document>'
    b=io.BytesIO()
    with zipfile.ZipFile(b,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        z.writestr('word/document.xml',xml)
    return b.getvalue()

def workbook(code):
    values=[['项目编号','项目名称','科目名称','申报金额','批复参考金额'],[code,'合成设备更新','办公设备','壹拾贰万元整','壹拾万元整']]
    data='<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
    for n,row in enumerate(values,1):
        data+='<row r="'+str(n)+'">'+''.join('<c r="'+chr(65+i)+str(n)+'" t="inlineStr"><is><t>'+escape(v)+'</t></is></c>' for i,v in enumerate(row))+'</row>'
    data+='</sheetData></worksheet>'
    b=io.BytesIO()
    with zipfile.ZipFile(b,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
        z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="预算" sheetId="1" r:id="rId1"/></sheets></workbook>')
        z.writestr('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
        z.writestr('xl/worksheets/sheet1.xml',data)
    return b.getvalue()

def remember(path,projection=None):
    value=api('GET',path)
    if projection:value=value[projection]
    paths.append(dict(path=path,projection=projection,fingerprint=fingerprint(value)))

def budget12():
    files=[]
    for i in range(12):
        code='SYN-RELIABILITY-'+run+'-'+str(i)
        lines=['项目编号：'+code,'项目名称：合成设备更新','科目名称：办公设备','申报金额：壹拾贰万元整；批复金额：壹拾万元整。']
        if i<6:files.append(('合成预算'+str(i)+'.docx',docx(lines)))
        elif i<10:files.append(('合成预算'+str(i)+'.xlsx',workbook(code)))
        else:files.append(('合成预算'+str(i)+'.txt','\n'.join(lines).encode()))
    batch=str(uuid.uuid4())
    members=[dict(operationKey=str(uuid.uuid4()),fileName=n,fileSize=len(b)) for n,b in files]
    api('POST','/budget/material-uploads/batches',json=dict(batchId=batch,members=members))
    ids=[]
    for member,(name,data) in zip(members,files):
        job=api('POST','/budget/material-uploads',headers={'Idempotency-Key':member['operationKey']},data={'batchId':batch},files={'file':(name,data,'application/octet-stream')})
        ids.append(job['id'])
    deadline=time.monotonic()+900
    while True:
        reviews=[api('GET','/budget/reviews/'+i) for i in ids]
        if all((r.get('materialReference') or {}).get('status')=='COMPLETED' for r in reviews):break
        assert time.monotonic()<deadline,'BUDGET_12_DEADLINE';time.sleep(2)
    for r in reviews:
        lines=r['materialReference']['lines']
        assert lines and all(x['declaredAmount']==120000 and x['approvalAmount']==100000 and x['status']=='OVER_APPROVAL' for x in lines),'BUDGET_12_AMOUNTS'
    for i in ids:remember('/budget/reviews/'+i,'materialReference')
    print(json.dumps(dict(case='budget12-mixed-docx-xlsx-txt',files=12,result='PASS')),flush=True)

def segmented_budget():
    # Narrative fields require AI; notes cross multiple persisted transport units.
    text=('项目编号：SYN-SEGMENT-'+run+'；项目名称：合成设备更新；科目名称：办公设备。\n'
          +'材料背景：设备使用年限已长，应检查到货数量与维保记录，不构成任何新增预算额度。\n'*36
          +'本次申请人民币壹拾贰万元整，经研究核准人民币壹拾万元整。\n'
          +'后续说明：请保留设备验收记录及维护资料，本文仅为合成测试。\n'*36)
    batch=str(uuid.uuid4());key=str(uuid.uuid4());content=text.encode();name='合成分段预算.txt'
    api('POST','/budget/material-uploads/batches',json=dict(batchId=batch,members=[dict(operationKey=key,fileName=name,fileSize=len(content))]))
    job=api('POST','/budget/material-uploads',headers={'Idempotency-Key':key},data={'batchId':batch},files={'file':(name,content,'text/plain')})
    path='/budget/reviews/'+job['id'];deadline=time.monotonic()+900
    while True:
        review=api('GET',path);ref=review.get('materialReference') or {}
        state=api('GET','/budget/material-uploads/'+job['id'])['status']
        assert state not in ('FAILED','UNKNOWN'),'SEGMENTED_BUDGET_'+state
        if ref.get('status')=='COMPLETED':break
        assert time.monotonic()<deadline,'SEGMENTED_BUDGET_DEADLINE';time.sleep(3)
    assert any(x['declaredAmount']==120000 and x['approvalAmount']==100000 and x['status']=='OVER_APPROVAL' for x in ref['lines']),'SEGMENTED_BUDGET_AMOUNTS'
    remember(path,'materialReference')
    print(json.dumps(dict(case='budget-persisted-segmented-narrative',id=job['id'],chars=len(text),result='PASS')),flush=True)
    return job['id']

def reopen():
    login()
    for row in paths:
        data=api('GET',row['path'])
        if row['projection']:data=data[row['projection']]
        assert fingerprint(data)==row['fingerprint'],'REOPEN_RESULT_CHANGED'

login()
budget12()
segmented_id=segmented_budget()
for file in root.glob('????????-procurement-*.json'):
    if file.stem.endswith(('-intake','-observed')):continue
    saved=json.loads(file.read_text(encoding='utf-8'))
    report=saved['report'];path='/procurement/projects/'+report['projectId']+'/review-report'
    reopened=api('GET',path)
    # Isolated comparison on this platform observed +374/+380 nanoseconds;
    # allow nearest-microsecond rounding only, never a different report time.
    assert dm8_timestamp(report['generatedTime'])==dm8_timestamp(reopened['generatedTime']),'PROCUREMENT_GENERATION_TIME_CHANGED'
    expected={**report,'generatedTime':reopened['generatedTime']}
    assert reopened==expected,'PROCUREMENT_REPORT_CHANGED_BEFORE_RESTART'
    remember(path)
reopen()
subprocess.run(['/opt/java/current/bin/java','-cp',os.environ['AUDIT_TEST_CP_PATH'],'MaterialDeploymentReadyCheck'],check=True)
subprocess.run(['systemctl','restart','audit-backend'],check=True)
deadline=time.monotonic()+120
while True:
    try:
        if requests.get(base+'/health',timeout=2).status_code==200:break
    except requests.RequestException:pass
    assert time.monotonic()<deadline,'BACKEND_RESTART_HEALTH';time.sleep(1)
reopen()
(root/'reliability-budget-results.json').write_text(json.dumps(dict(runId=run,segmentedId=segmented_id,paths=paths),indent=2),encoding='utf-8')
print(json.dumps(dict(case='budget-new-session-and-backend-restart',results=len(paths),fingerprints='UNCHANGED',result='PASS')),flush=True)
