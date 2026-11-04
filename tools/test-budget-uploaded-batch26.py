"""Synthetic budget files only. Prints IDs/counts/status, never configuration or source contents."""
import json
import hashlib
from pathlib import Path
import sys
import time
import uuid
import requests

root=Path(__file__).resolve().parent
auth=json.loads(sys.stdin.readline())
session=requests.Session()
base='http://127.0.0.1'
report=[]

def csrf():
    r=session.get(base+'/api/v1/auth/csrf',timeout=20);r.raise_for_status();data=r.json()['data'];session.headers[data['headerName']]=data['token']

def read(path):
    r=session.get(base+path,timeout=30);assert r.status_code==200, 'READ_HTTP_'+str(r.status_code);return r.json()['data']

csrf()
r=session.post(base+'/api/v1/auth/login',json={'username':auth['username'],'password':auth['password']},timeout=30)
assert r.status_code==200,'LOGIN_HTTP_'+str(r.status_code)
csrf()

def run(label,files,expect):
    batch=str(uuid.uuid4());members=[{'operationKey':str(uuid.uuid4()),'fileName':name,'fileSize':len(data)} for name,data in files]
    request={'batchId':batch,'members':members}
    r=session.post(base+'/api/v1/budget/material-uploads/batches',json=request,timeout=30)
    assert r.status_code==200,'REGISTER_HTTP_'+str(r.status_code)
    assert session.post(base+'/api/v1/budget/material-uploads/batches',json=request,timeout=30).status_code==200
    jobs=[]
    for member,(name,data) in zip(members,files):
        r=session.post(base+'/api/v1/budget/material-uploads',headers={'Idempotency-Key':member['operationKey']},
            data={'batchId':batch},files={'file':(name,data,'application/octet-stream')},timeout=180)
        assert r.status_code==200,'UPLOAD_HTTP_'+str(r.status_code)
        job=r.json()['data'];jobs.append(job)
        print(json.dumps({'label':label,'uploaded':name,'http':r.status_code,'id':job['id']}),flush=True)
    # A different authenticated session can reopen the durable result without browser state.
    deadline=time.monotonic()+1800
    while True:
        jobs=[read('/api/v1/budget/material-uploads/'+job['id']) for job in jobs]
        if all(job['status'] in ('PARSED','FAILED','UNKNOWN') for job in jobs):
            reviews=[read('/api/v1/budget/reviews/'+job['id']) for job in jobs]
            if all((review.get('materialReference') or {}).get('status')=='COMPLETED' for review in reviews):break
        assert time.monotonic()<deadline,'BATCH_DEADLINE'
        time.sleep(3)
    lines=[review['materialReference']['lines'] for review in reviews]
    statuses=[job['status'] for job in jobs]
    expect(jobs,reviews,lines)
    saved=[read('/api/v1/budget/reviews/'+job['id'])['materialReference'] for job in jobs]
    assert saved==[review['materialReference'] for review in reviews],'PERSISTED_REFERENCE_CHANGED'
    row={'label':label,'batchId':batch,'ids':[job['id'] for job in jobs], 'files':len(files),'statuses':statuses,
         'lineCounts':list(map(len,lines)),'referenceSaved':True,'result':'PASS',
         'resultFingerprint':hashlib.sha256(json.dumps(saved,sort_keys=True,ensure_ascii=False).encode()).hexdigest()}
    report.append(row)
    (root/'budget-batch-results.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(row),flush=True)

def complete(jobs,reviews,lines):
    assert all(job['status']=='PARSED' for job in jobs),'PARSE_FAILED_'+str([job['status'] for job in jobs])
    for rows in lines:
        assert rows,'NO_BUDGET_ROWS'
        assert any(row['declaredAmount']==31200 and row['approvalAmount']==32760 and row['status']=='WITHIN_APPROVAL' for row in rows),'WRONG_AMOUNTS'
    assert all(not review['allPassed'] for review in reviews),'REFERENCE_IS_NOT_FORMAL_APPROVAL'

fixtures=Path('/home/admin/office-formats-v9-20261015/fixtures')
selected=['sample.xlsx','sample.xls','sample.docx','sample.doc','sample.pdf','sample-mixed.pdf','sample.ppt','sample.pptx',
          'sample.txt','sample.csv','sample-utf16.txt','sample.rtf','sample.png','sample.jpg','sample.bmp','sample.tiff','sample-large.docx']
mode=sys.argv[1] if len(sys.argv)>1 else 'all'
if mode=='all':
    run('multi-format-large-files',[(name,(fixtures/name).read_bytes()) for name in selected],complete)
elif mode=='formats-smoke':
    run('project-total-isolation-office-pdf-image',[(name,(fixtures/name).read_bytes()) for name in
        ['sample.xlsx','sample.docx','sample.pdf','sample.jpg']],complete)

def encoded(value):return value.encode('utf-8')

def cross(jobs,reviews,lines):
    complete(jobs,reviews,lines)
    assert all(len({e['taskId'] for e in rows[0]['sources']})==2 for rows in lines),'CROSS_FILE_EVIDENCE_MISSING'

run('cross-file-approval', [('申报.txt',encoded('项目编号：SYNTHETIC-CROSS；科目名称：设备；申报金额：31200元')),
    ('批复.txt',encoded('项目编号：SYNTHETIC-CROSS；科目名称：设备；批复金额：32760元'))],cross)

def partial(jobs,reviews,lines):
    assert [job['status'] for job in jobs]==['PARSED','PARSED','FAILED'],'FAILURE_ISOLATION'
    assert lines[0][0]['approvalAmount']==32760 and lines[0][0]['declaredAmount'] is None
    assert lines[0][0]['status']=='INSUFFICIENT' and lines[1][0]['status']=='INSUFFICIENT'
    assert all('依据尚不完整' in review['materialReference']['summary'] for review in reviews)

run('partial-and-corrupt', [('仅批复.txt',encoded('项目编号：SYNTHETIC-PARTIAL；科目名称：设备；批复金额：32760元')),
    ('仅科目.txt',encoded('科目名称：培训')),('损坏.xlsx',b'\x01\x02\x03')],partial)

def conflict(jobs,reviews,lines):
    assert all(job['status']=='PARSED' for job in jobs)
    assert all(rows[0]['status']=='CONFLICT' and rows[0]['approvalAmount'] is None for rows in lines)

run('conflicting-approval-versions', [('申报.txt',encoded('项目编号：SYNTHETIC-CONFLICT；科目名称：设备；申报金额：31200元')),
    ('批复一.txt',encoded('项目编号：SYNTHETIC-CONFLICT；科目名称：设备；批复金额：32760元')),
    ('批复二.txt',encoded('项目编号：SYNTHETIC-CONFLICT；科目名称：设备；批复金额：40000元'))],conflict)

run('real-model-narrative', [('自然语言.txt',encoded('以下为专门构造的预算测试资料。本次测试项目称为仿真设备更新项目，其预算科目为办公设备。该项申报金额为31200元，批复参考金额为32760元。请仅提取原文预算信息。'))],complete)
print('BUDGET_UPLOADED_MATERIAL_REAL_HTTP_AI_DM8=PASS; MODE='+mode,flush=True)
