"""Synthetic-only HTTP/DM8/restart checks; print status/counts, never credentials or document contents."""
import hashlib
import io
import json
import subprocess
import sys
import time
import uuid
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape
import requests

auth=json.loads(sys.stdin.readline())
base='http://127.0.0.1'
client=requests.Session()
CHUNK=4*1024*1024

def csrf():
    r=client.get(base+'/api/v1/auth/csrf',timeout=20);assert r.status_code==200,'CSRF_HTTP'
    value=r.json()['data'];client.headers[value['headerName']]=value['token']
def login():
    csrf();r=client.post(base+'/api/v1/auth/login',json={'username':auth['username'],'password':auth['password']},timeout=20);assert r.status_code==200,'LOGIN_HTTP';csrf()
def get(path):
    r=client.get(base+path,timeout=60);assert r.status_code==200,'GET_HTTP_'+str(r.status_code);return r.json()['data']
def post(path,body=None,expected=200):
    r=client.post(base+path,json=body,timeout=180);assert r.status_code==expected,'POST_HTTP_'+str(r.status_code);return r.json().get('data')
def restart():
    subprocess.run(['systemctl','restart','audit-backend'],check=True)
    deadline=time.monotonic()+90
    while True:
        try:
            if requests.get(base+'/api/v1/health',timeout=2).status_code==200:break
        except requests.RequestException:pass
        assert time.monotonic()<deadline,'RESTART_HEALTH';time.sleep(1)
    client.cookies.clear();login()
def workbook(rows,padding=False):
    headers=['项目编号','项目名称','科目编码','科目名称','申报金额','批复参考金额','预算年度','申报部门']
    sheet=['<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>']
    for n,row in enumerate([headers]+rows,1):
        cells=''.join('<c r="'+chr(65+i)+str(n)+'" t="inlineStr"><is><t>'+escape(str(v))+'</t></is></c>' for i,v in enumerate(row))
        sheet.append('<row r="'+str(n)+'">'+cells+'</row>')
    sheet.append('</sheetData></worksheet>')
    entries={
      '[Content_Types].xml':'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="bin" ContentType="application/octet-stream"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
      '_rels/.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
      'xl/workbook.xml':'<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="合成预算" sheetId="1" r:id="rId1"/></sheets></workbook>',
      'xl/_rels/workbook.xml.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
      'xl/worksheets/sheet1.xml':''.join(sheet)}
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        for name,value in entries.items():z.writestr(name,value.encode())
        if padding:z.writestr('synthetic-padding.bin',b'\0'*(80*1024*1024+97),compress_type=zipfile.ZIP_STORED)
    return out.getvalue()
def transfer(kind,key,name,data,context=None,interrupt=False):
    path='/api/v1/material-transfers/'+kind+'/'+key
    chunks=[data[i:i+CHUNK] for i in range(0,len(data),CHUNK)]
    spec={'fileName':name,'contentType':'application/octet-stream','size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'partHashes':[hashlib.sha256(c).hexdigest() for c in chunks],**(context or {})}
    state=post(path,spec);assert state['received']==[],'NEW_TRANSFER_NOT_EMPTY'
    assert post(path,{**spec,'fileName':'替换.xlsx'},409) is None,'MUTABLE_SPEC'
    def part(index,content=None,expected=200):
        r=client.put(base+path+'/parts/'+str(index),files={'file':('part',chunks[index] if content is None else content,'application/octet-stream')},timeout=90)
        assert r.status_code==expected,'PART_HTTP_'+str(r.status_code)
    part(0);part(0)
    if len(chunks)>1:post(path+'/complete',expected=409)
    if interrupt:
        assert get(path)['received']==[0],'BEFORE_RESTART_PARTS'
        restart();assert get(path)['received']==[0],'PARTS_LOST_AFTER_RESTART'
        r=requests.get(base+path,timeout=20);assert r.status_code==401,'ANONYMOUS_TRANSFER'
    missing=[i for i in reversed(range(len(chunks))) if i not in get(path)['received']]
    if len(chunks)>1:part(1,b'X'*len(chunks[1]),409)
    for index in missing:part(index)
    result=post(path+'/complete');again=post(path+'/complete');assert result==again,'DELIVERY_REPLAY_CHANGED'
    assert get(path)['status']=='COMPLETED','TRANSFER_NOT_COMPLETED'
    print(json.dumps({'kind':kind,'bytes':len(data),'parts':len(chunks),'resumedMissingParts':len(missing),'restart':interrupt,'result':'PASS'}),flush=True)
    return result

login()
project='SYN-DIFF-'+str(uuid.uuid4())[:8]
d=workbook([[project,'合成差异项目','S1','设备','120','','2026','合成部门'],[project,'合成差异项目','S2','培训','50','','2026','合成部门']],True)
a=workbook([[project,'合成差异项目','S1','设备','','100','2026','合成部门'],[project,'合成差异项目','S3','维修','','80','2026','合成部门']])
batch=str(uuid.uuid4());keys=[str(uuid.uuid4()),str(uuid.uuid4())]
names=['合成分片申报.xlsx','合成差异批复.xlsx']
post('/api/v1/budget/material-uploads/batches',{'batchId':batch,'members':[{'operationKey':k,'fileName':n,'fileSize':len(data)} for k,n,data in zip(keys,names,[d,a])]})
job=transfer('budget',keys[0],names[0],d,{'batchId':batch},True)
r=client.post(base+'/api/v1/budget/material-uploads',headers={'Idempotency-Key':keys[1]},data={'batchId':batch},files={'file':(names[1],a,'application/octet-stream')},timeout=60);assert r.status_code==200,'APPROVAL_UPLOAD'
ids=[job['id'],r.json()['data']['id']]
deadline=time.monotonic()+240
while True:
    reviews=[get('/api/v1/budget/reviews/'+i) for i in ids]
    if all((r.get('materialReference') or {}).get('status')=='COMPLETED' for r in reviews):break
    assert time.monotonic()<deadline,'BUDGET_RESULT_DEADLINE';time.sleep(2)
statuses=[line['status'] for r in reviews for line in r['materialReference']['lines']]
assert set(statuses)=={'OVER_APPROVAL','UNAPPROVED_SUBJECT','MISSING_DECLARATION'},'DIFFERENCE_STATUSES'
saved=[r['materialReference'] for r in reviews];restart();assert [get('/api/v1/budget/reviews/'+i)['materialReference'] for i in ids]==saved,'PERSISTED_DIFFERENCES_CHANGED'
print('BUDGET_WRONG_OMITTED_EXCESS_DM8_REOPEN_AFTER_RESTART=PASS',flush=True)

# Real procurement intake using the already validated synthetic full-field workbook.
fixture=Path('/home/admin/office-formats-v9-20261015/fixtures/sample.xlsx').read_bytes()
buffer=io.BytesIO()
with zipfile.ZipFile(io.BytesIO(fixture)) as original,zipfile.ZipFile(buffer,'w',zipfile.ZIP_DEFLATED) as z:
    for info in original.infolist():
        content=original.read(info.filename)
        if info.filename=='[Content_Types].xml':content=content.replace(b'</Types>',b'<Default Extension="bin" ContentType="application/octet-stream"/></Types>')
        z.writestr(info.filename,content)
    z.writestr('synthetic-padding.bin',b'\0'*(9*1024*1024),compress_type=zipfile.ZIP_STORED)
intake=transfer('procurement',str(uuid.uuid4()),'合成采购分片.xlsx',buffer.getvalue())
deadline=time.monotonic()+1200
while True:
    parsed=get('/api/v1/procurement/materials/'+intake['id'])
    if parsed['status'] in ('PARSED','FAILED','UNKNOWN'):break
    assert time.monotonic()<deadline,'PROCUREMENT_PARSE_DEADLINE';time.sleep(3)
assert parsed['status']=='PARSED','PROCUREMENT_PARSE_'+parsed['status']
created=post('/api/v1/procurement/projects',{'sourceProjectId':intake['id'],'projectName':'合成报告分片测试-'+project,'departmentName':'合成部门','procurementType':'GOODS','budgetAmount':50000,'subjectCode':'SYN','subjectName':'设备','procurementMethod':'PUBLIC_BIDDING','requirementDescription':'专门构造的测试数据','applicantName':'合成测试','submitForApproval':False})
pid=created['id'];report=post('/api/v1/procurement/projects/'+pid+'/review-report')
path='/api/v1/procurement/projects/'+pid+'/review-report/export?reportId='+report['id']
export=client.get(base+path,timeout=60);assert export.status_code==200 and 'wordprocessingml.document' in export.headers.get('Content-Type',''),'DOCX_HTTP'
with zipfile.ZipFile(io.BytesIO(export.content)) as z:
    text=z.read('word/document.xml').decode()
    for heading in ['智能体预审','询比价及报价异常','供应商黑名单比对','招标文件合规审查','围串标辅助识别','拆分采购辅助识别']:assert heading in text,'DOCX_SECTION'
assert get('/api/v1/procurement/projects/'+pid+'/review-report')['id']==report['id'],'EXPORT_REGENERATED_REPORT'
assert client.get(base+path.replace(report['id'],str(uuid.uuid4())),timeout=30).status_code==409,'STALE_EXPORT_NOT_REJECTED'
print('PROCUREMENT_INTAKE_DOCX_SIX_SECTIONS_SAVED_REPORT_NO_REGENERATION=PASS',flush=True)
transfer('project',str(uuid.uuid4()),'合成大报价.xlsx',d,{'projectId':pid})
print('MATERIAL_TRANSFER_REAL_HTTP_DM8_RESTART_BUDGET_PROCUREMENT_PROJECT=PASS',flush=True)
