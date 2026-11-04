import json,sys,requests
auth=json.loads(sys.stdin.readline()); ids=sys.argv[1:]; s=requests.Session(); base='http://127.0.0.1/api/v1'
r=s.get(base+'/auth/csrf',timeout=20); d=r.json()['data']; s.headers[d['headerName']]=d['token']; s.post(base+'/auth/login',json=auth,timeout=30); r=s.get(base+'/auth/csrf',timeout=20); d=r.json()['data']; s.headers[d['headerName']]=d['token']
out=[]
for i in ids:
    d=s.get(base+'/budget/reviews/'+i,timeout=30).json().get('data',{})
    ref=d.get('materialReference') or {}; out.append({'taskId':i,'status':ref.get('status'),'summary':ref.get('summary'),'lines':[{'declaredAmount':x.get('declaredAmount'),'approvalAmount':x.get('approvalAmount'),'status':x.get('status'),'sources':x.get('sources')} for x in (ref.get('lines') or [])]})
print(json.dumps(out,ensure_ascii=False),flush=True)
