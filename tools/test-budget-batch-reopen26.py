"""Reopen synthetic results after backend restart from another authenticated session."""
import hashlib
import json
from pathlib import Path
import sys
import requests

root=Path(__file__).resolve().parent
auth=json.loads(sys.stdin.readline())
session=requests.Session()
base='http://127.0.0.1'

def csrf():
    response=session.get(base+'/api/v1/auth/csrf',timeout=20);response.raise_for_status()
    token=response.json()['data'];session.headers[token['headerName']]=token['token']

csrf()
response=session.post(base+'/api/v1/auth/login',json={'username':auth['username'],'password':auth['password']},timeout=30)
assert response.status_code==200
csrf()
report=json.loads((root/'budget-batch-results.json').read_text())
files=0
for batch in report:
    results=[]
    for task_id in batch['ids']:
        response=session.get(base+'/api/v1/budget/reviews/'+task_id,timeout=30)
        assert response.status_code==200
        result=response.json()['data']['materialReference']
        assert result['status']=='COMPLETED'
        results.append(result);files+=1
    fingerprint=hashlib.sha256(json.dumps(results,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
    assert fingerprint==batch['resultFingerprint'],'PERSISTED_RESULT_CHANGED_AFTER_RESTART'
print(json.dumps({'backendRestartReopen':'PASS','newAuthenticatedSession':True,'batches':len(report),'files':files,'resultFingerprints':'UNCHANGED'}),flush=True)
