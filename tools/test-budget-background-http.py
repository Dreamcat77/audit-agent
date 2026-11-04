"""Real API, real DM8, existing representative test-server services. Synthetic files only."""
import concurrent.futures
import io
import json
from pathlib import Path
import sys
import time
import uuid
import requests

auth=json.loads(sys.stdin.readline())
smoke=len(sys.argv)>1 and sys.argv[1]=='smoke'
root=Path('/home/admin/batch12-20261010')
base='http://127.0.0.1:8080'
session=requests.Session()
def csrf():
    response=session.get(base+'/api/v1/auth/csrf',timeout=10);response.raise_for_status();token=response.json()['data'];session.headers[token['headerName']]=token['token']
csrf();login=session.post(base+'/api/v1/auth/login',json={'username':auth['username'],'password':auth['password']},timeout=15);assert login.status_code==200
csrf()
def call(method,path,**kw):
    r=session.request(method,base+path,timeout=120,**kw);assert r.status_code==200,(r.status_code,path);return r.json()['data']
def upload(path,content=None):
    key=str(uuid.uuid4());data={'budgetYear':'2026','departmentCode':'PROCUREMENT'}
    binary=content if content is not None else path.read_bytes()
    start=time.monotonic()
    row=call('POST','/api/v1/budget/material-uploads',headers={'Idempotency-Key':key},data=data,files={'file':(path.name,binary,'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')})
    assert row['status'] in ('QUEUED','PROCESSING','PARSED','FAILED')
    return {'name':path.name,'id':row['id'],'key':key,'uploadSeconds':round(time.monotonic()-start,2)}
records=[upload(Path('SYNTHETIC-CORRUPT.xlsx'),b'invalid workbook')]
fixtures=sorted((root/'batch12-fixtures/budget').glob('*.xlsx'))
if smoke:fixtures=fixtures[:1]
for file in fixtures:records.append(upload(file))
assert len(records)==len(fixtures)+1
# Replay the very same operation and bytes; a lost HTTP response must not create another task.
first=records[1];file=root/'batch12-fixtures/budget'/first['name']
replay=call('POST','/api/v1/budget/material-uploads',headers={'Idempotency-Key':first['key']},data={'budgetYear':'2026','departmentCode':'PROCUREMENT'},files={'file':(file.name,file.read_bytes(),'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')})
assert replay['id']==first['id']
session.close()
# A new session models browser closure. The worker proceeds without any browser polling.
time.sleep(4)
session=requests.Session();csrf();assert session.post(base+'/api/v1/auth/login',json={'username':auth['username'],'password':auth['password']},timeout=15).status_code==200;csrf()
deadline=time.monotonic()+1800
while True:
    states=[call('GET','/api/v1/budget/material-uploads/operations/'+row['key']) for row in records]
    counts={state:sum(r['status']==state for r in states) for state in ('QUEUED','PROCESSING','PARSED','FAILED','UNKNOWN')}
    print(json.dumps({'progress':counts}),flush=True)
    if counts['PARSED']+counts['FAILED']==len(records):break
    assert time.monotonic()<deadline,'background deadline exceeded'
    assert counts['UNKNOWN']==0,'unexpected unknown result'
    time.sleep(5)
assert states[0]['status']=='FAILED'
assert all(r['status']=='PARSED' for r in states[1:])
assert all(r['task']['parsedLineCount']==80 for r in states[1:])
assert all(r['task']['status'] in ('WAITING_CONFIRMATION','REVIEWED') for r in states[1:])
for r in states[1:]:
    review=call('GET','/api/v1/budget/reviews/'+r['id']);assert review['task']['taskId']==r['id']
report={'files':len(records),'valid':len(fixtures),'invalid':1,'counts':counts,'browserSessionClosedDuringProcessing':True,'sameOperationReplay':True,'rowsPerFile':80,'results':[{'name':r['fileName'],'id':r['id'],'status':r['status'],'budgetStatus':r['task']['status']} for r in states]}
(root/('budget-background-http-smoke.json' if smoke else 'budget-background-http-result.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'REAL_BUDGET_BACKGROUND_HTTP_DM8':'PASS','counts':counts,'rowsPerFile':80}),flush=True)
