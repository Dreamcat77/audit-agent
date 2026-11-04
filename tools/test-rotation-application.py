"""Synthetic budget/procurement acceptance through the deployed Java application."""
import hashlib
import json
import mimetypes
from pathlib import Path
import sys
import time
import uuid
from decimal import Decimal
import requests

root=Path(sys.argv[1]); output=Path(sys.argv[2]); output.mkdir(exist_ok=False)
auth=json.loads(sys.stdin.readline()); client=requests.Session(); base='http://127.0.0.1/api/v1'

def csrf():
    response=client.get(base+'/auth/csrf',timeout=15);response.raise_for_status()
    token=response.json()['data'];client.headers[token['headerName']]=token['token']

def api(method,path,**kwargs):
    response=client.request(method,base+path,timeout=kwargs.pop('timeout',900),**kwargs)
    assert response.status_code==200,'HTTP_'+str(response.status_code)+'_'+path
    data=response.json();assert data['code']==200,'BUSINESS_REJECTION'
    return data['data']

def login():
    client.cookies.clear();csrf();api('POST','/auth/login',json={k:auth[k] for k in ('username','password')});csrf()

def upload(path,endpoint,data=None):
    content=path.read_bytes()
    return api('POST',endpoint,headers={'Idempotency-Key':str(uuid.uuid4())},data=data or {},
               files={'file':(path.name,content,mimetypes.guess_type(path.name)[0] or 'application/octet-stream')})

login()
expected=json.loads(root.with_name(root.name+'-expected.json').read_text(encoding='utf-8'))
procurement_only = len(sys.argv) > 3 and sys.argv[3] == '--procurement-only'
budget_cases=[case for case in expected if case['module']=='budget' and not procurement_only]
batch=str(uuid.uuid4()); keys=[str(uuid.uuid4()) for _ in budget_cases]
if budget_cases:
    api('POST','/budget/material-uploads/batches',json=dict(batchId=batch,members=[dict(operationKey=key,
        fileName=(root/case['path']).name,fileSize=(root/case['path']).stat().st_size) for case,key in zip(budget_cases,keys)]))
jobs=[]
for case,key in zip(budget_cases,keys):
    path=root/case['path'];content=path.read_bytes()
    job=api('POST','/budget/material-uploads',headers={'Idempotency-Key':key},data={'batchId':batch},
        files={'file':(path.name,content,mimetypes.guess_type(path.name)[0] or 'application/octet-stream')})
    jobs.append((case,job['id']))
print('ROTATION_BUDGET_UPLOAD_ACCEPTED files='+str(len(jobs)),flush=True)
deadline=time.monotonic()+3600; completed=set(); saved=[]
while len(completed)<len(jobs):
    for case,identifier in jobs:
        if identifier in completed:continue
        state=api('GET','/budget/material-uploads/'+identifier)
        if state['status'] in ('FAILED','UNKNOWN'):
            (output/('failed-'+identifier+'.json')).write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
            raise AssertionError('BUDGET_TERMINAL_'+state['status']+'_'+case['path'])
        review=api('GET','/budget/reviews/'+identifier);ref=review.get('materialReference') or {}
        if ref.get('status')!='COMPLETED':continue
        (output/(identifier+'.json')).write_text(json.dumps(review,ensure_ascii=False,indent=2),encoding='utf-8')
        lines=ref.get('lines',[])
        pairs=[(Decimal(str(row['declaredAmount'])),Decimal(str(row['approvalAmount']))) for row in lines
               if row.get('declaredAmount') is not None and row.get('approvalAmount') is not None]
        assert pairs,'BUDGET_AMOUNTS_MISSING_'+case['path']
        wanted=[tuple(map(Decimal,map(str,row))) for row in (case['rows'] if 'rows' in case else [[case['declared'],case['approved']]])]
        for pair in wanted:assert pair in pairs,'BUDGET_AMOUNT_CHANGED_'+case['path']
        assert all(row.get('status')=='OVER_APPROVAL' for row in lines if row.get('declaredAmount') and row.get('approvalAmount')),'BUDGET_COMPARISON_MISSING'
        completed.add(identifier);saved.append((identifier,ref))
        print(json.dumps(dict(case='budget',file=case['path'],id=identifier,result='PASS',amountPairs=[list(map(str,p)) for p in pairs]),ensure_ascii=False),flush=True)
    assert time.monotonic()<deadline,'BUDGET_QUEUE_DEADLINE'
    time.sleep(3)

materials=[]
for path in sorted((root/'02_采购同一项目').iterdir()):
    materials.append((path,upload(path,'/procurement/materials')['id']))
print('ROTATION_PROCUREMENT_UPLOAD_ACCEPTED files='+str(len(materials)),flush=True)
deadline=time.monotonic()+2400
while True:
    rows=[api('GET','/procurement/materials/'+identifier) for _,identifier in materials]
    for (path,_),row in zip(materials,rows):
        if row['status'] in ('FAILED','UNKNOWN'):
            (output/('failed-'+row['id']+'.json')).write_text(json.dumps(row,ensure_ascii=False,indent=2),encoding='utf-8')
            raise AssertionError('INTAKE_'+row['status']+'_'+path.name)
    if all(row['status']=='PARSED' for row in rows):break
    assert time.monotonic()<deadline,'INTAKE_DEADLINE'
    time.sleep(3)
for (path,identifier),row in zip(materials,rows):
    (output/(identifier+'-intake.json')).write_text(json.dumps(row,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(dict(case='procurement-intake',file=path.name,result='PASS'),ensure_ascii=False),flush=True)
project=api('POST','/procurement/projects',json=dict(sourceProjectId=materials[0][1],sourceMaterialIds=[x[1] for x in materials],
    projectName='仿真公共服务中心办公终端采购',departmentName='信息保障科',procurementType='货物',budgetAmount='100000.00',
    subjectCode='A1001',subjectName='办公设备',procurementMethod='公开招标',applicantName='仿真经办员',
    requirementDescription='采购办公计算机20台，含显示器、安装和三年保修。',submitForApproval=False))
pid=project['id']; result=api('POST','/procurement/projects/'+pid+'/review-materials/from-intake/analyze',timeout=1800)
(output/'procurement-report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
assert result.get('report'),'REPORT_MISSING'
assert len(result['files'])==len(materials),'SOURCE_COVERAGE_LOST'
for path,_ in materials:
    row=next(f for f in result['files'] if f['fileName']==path.name)
    assert row['document']['sha256']==hashlib.sha256(path.read_bytes()).hexdigest(),'ORIGINAL_BYTES_CHANGED'
findings=result['report']['tender']['findings']
assert any('品牌' in json.dumps(f,ensure_ascii=False) for f in findings),'BRAND_RISK_MISSING'
assert any('本地' in json.dumps(f,ensure_ascii=False) or '地域' in json.dumps(f,ensure_ascii=False) for f in findings),'REGIONAL_RISK_MISSING'
quotation_files=[row for row in result['files'] if row['fileName'] in ('03_横置报价截图.jpg','04_带页面旋转标记.pdf')]
assert len(quotation_files)==2 and all(row['kind']=='BIDDER' and row['status']=='BIDDER_RECOGNIZED'
    for row in quotation_files),'SCANNED_QUOTATION_ROUTING_LOST'
assert result.get('collusion') is None and result['report']['collusion'] is None,'SAME_SUPPLIER_FALSE_COLLUSION'
assert any('两家不同' in notice for notice in result['notices']),'SUPPLIER_COVERAGE_NOTICE_MISSING'
login()
for identifier,ref in saved:
    assert api('GET','/budget/reviews/'+identifier)['materialReference']==ref,'BUDGET_REOPEN_CHANGED'
reopened=api('GET','/procurement/projects/'+pid+'/review-report')
assert reopened['id']==result['report']['id'],'REPORT_REOPEN_CHANGED'
print(json.dumps(dict(case='procurement-review',projectId=pid,reportId=reopened['id'],files=len(materials),result='PASS',
    brandRisk=True,regionalRisk=True,originalBytes=True,persistedReopen=True),ensure_ascii=False),flush=True)
print('ROTATION_DEPLOYED_APPLICATION_BUDGET_PROCUREMENT=PASS',flush=True)
