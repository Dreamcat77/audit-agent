"""Synthetic data only. The generated test actor arrives through an anonymous pipe."""
import concurrent.futures
import hashlib
import json
import subprocess
import sys
import time
import uuid
import requests

auth=json.loads(sys.stdin.readline())
base='http://127.0.0.1'
session=requests.Session()
def csrf(client):
    response=client.get(base+'/api/v1/auth/csrf',timeout=20);response.raise_for_status()
    token=response.json()['data'];client.headers[token['headerName']]=token['token']
def login():
    csrf(session)
    assert session.post(base+'/api/v1/auth/login',json={'username':auth['username'],'password':auth['password']},timeout=30).status_code==200
    csrf(session)
def read(path):
    response=session.get(base+path,timeout=30);assert response.status_code==200,'READ_HTTP_'+str(response.status_code)
    return response.json()['data']
login()
batch=str(uuid.uuid4())
files=[('合成风险项目甲.txt','项目编号：SYNTHETIC-RISK-A；科目名称：电子设备；申报金额：100元；批复金额：120元'),
       ('合成风险项目乙.txt','项目编号：SYNTHETIC-RISK-B；科目名称：电子设备；申报金额：200元；批复金额：180元')]
members=[{'operationKey':str(uuid.uuid4()),'fileName':name,'fileSize':len(text.encode())} for name,text in files]
assert session.post(base+'/api/v1/budget/material-uploads/batches',json={'batchId':batch,'members':members},timeout=30).status_code==200
ids=[]
for member,(name,text) in zip(members,files):
    response=session.post(base+'/api/v1/budget/material-uploads',headers={'Idempotency-Key':member['operationKey']},data={'batchId':batch},files={'file':(name,text.encode(),'text/plain')},timeout=180)
    assert response.status_code==200,'UPLOAD_HTTP_'+str(response.status_code);ids.append(response.json()['data']['id'])
deadline=time.monotonic()+240
while True:
    snapshots=[read('/api/v1/budget/reviews/'+task) for task in ids]
    if all((r.get('materialReference') or {}).get('status')=='COMPLETED' for r in snapshots):break
    assert time.monotonic()<deadline,'BACKGROUND_DEADLINE';time.sleep(2)
candidates=read('/api/v1/budget/material-risk/candidates?taskIds='+','.join(ids))
assert not candidates['hasMore'] and len(candidates['items'])==2,'OWNED_CANDIDATES'
assert {r['line']['status'] for r in candidates['items']}=={'WITHIN_APPROVAL','OVER_APPROVAL'},'RISK_FACTS'
operation={'id':str(uuid.uuid4()),'selections':[r['source'] for r in candidates['items']]}
def concurrent_analysis(_):
    client=requests.Session();client.cookies.update(session.cookies);client.headers.update(session.headers)
    response=client.post(base+'/api/v1/budget/material-risk/analyses',json=operation,timeout=60)
    assert response.status_code==200,'CONCURRENT_HTTP_'+str(response.status_code)
    return response.json()['data']
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    results=list(pool.map(concurrent_analysis,range(6)))
assert all(result==results[0] for result in results),'IDEMPOTENT_SNAPSHOT'
result=results[0]
assert len(result['projects'])==2 and result['splitAnalysis']['totalAmount']==300 and result['splitAnalysis']['bandCode']=='BELOW_10K','SELECTED_SUM'
assert not any('均未超过' in line for line in result['opinions']),'FALSE_ALL_CLEAR'
assert sum(item['id']==operation['id'] for item in read('/api/v1/budget/material-risk/analyses'))==1,'HISTORY_DUPLICATED'
assert read('/api/v1/budget/material-risk/analyses/'+operation['id'])==result,'REOPEN'
drift={**operation,'selections':operation['selections'][:1]}
assert session.post(base+'/api/v1/budget/material-risk/analyses',json=drift,timeout=30).status_code==409,'RANGE_REPLACED'
anonymous=requests.Session()
assert anonymous.get(base+'/api/v1/budget/material-risk/analyses/'+operation['id'],timeout=30).status_code==401,'AUTHENTICATION'
before=hashlib.sha256(json.dumps(result,sort_keys=True).encode()).hexdigest()
if '--restart' in sys.argv:
    subprocess.run(['systemctl','restart','audit-backend.service'],check=True)
deadline=time.monotonic()+70
while True:
    try:
        if requests.get(base+'/api/v1/health',timeout=2).status_code==200:break
    except requests.RequestException:pass
    assert time.monotonic()<deadline,'RESTART_DEADLINE';time.sleep(1)
session=requests.Session();login()
after=read('/api/v1/budget/material-risk/analyses/'+operation['id'])
assert hashlib.sha256(json.dumps(after,sort_keys=True).encode()).hexdigest()==before,'RESTART_SNAPSHOT_CHANGED'
assert session.post(base+'/api/v1/budget/material-risk/analyses',json=operation,timeout=30).json()['data']==result,'RESTART_REPLAY_CHANGED'
print(json.dumps({'budgetRiskRealHttp':'PASS','syntheticFiles':2,'concurrentSameIdentityRequests':6,'oneHistoryResult':True,'rangeDriftHttp':409,'anonymousHttp':401,'sameActorRestartReopen':'PASS' if '--restart' in sys.argv else 'NOT_RUN','resultFingerprint':'UNCHANGED','analysisId':operation['id']}),flush=True)
