import json,sys,time,uuid,requests
auth=json.loads(sys.stdin.readline()); s=requests.Session(); base='http://127.0.0.1'
def csrf():
    d=s.get(base+'/api/v1/auth/csrf',timeout=20).json()['data']; s.headers[d['headerName']]=d['token']
csrf(); login=s.post(base+'/api/v1/auth/login',json=auth,timeout=30); login.raise_for_status(); csrf()
batch=str(uuid.uuid4()); rows=[('合成风险项目甲.txt','项目编号：SYNTHETIC-RISK-A；科目名称：电子设备；申报金额：100元；批复金额：120元'),('合成风险项目乙.txt','项目编号：SYNTHETIC-RISK-B；科目名称：电子设备；申报金额：200元；批复金额：180元')]
members=[{'operationKey':str(uuid.uuid4()),'fileName':n,'fileSize':len(t.encode())} for n,t in rows]
r=s.post(base+'/api/v1/budget/material-uploads/batches',json={'batchId':batch,'members':members},timeout=30); print('batch',r.status_code,flush=True)
ids=[]
for m,(name,text) in zip(members,rows):
    r=s.post(base+'/api/v1/budget/material-uploads',headers={'Idempotency-Key':m['operationKey']},data={'batchId':batch},files={'file':(name,text.encode(),'text/plain')},timeout=180); print('upload',r.status_code,flush=True); ids.append(r.json()['data']['id'])
deadline=time.monotonic()+300
while True:
    reviews=[]
    for i in ids:
        r=s.get(base+'/api/v1/budget/reviews/'+i,timeout=30); reviews.append(r.json()['data'])
    if all((x.get('materialReference') or {}).get('status')=='COMPLETED' for x in reviews): break
    assert time.monotonic()<deadline,'WAIT_TIMEOUT'; time.sleep(2)
page=s.get(base+'/api/v1/budget/material-risk/candidates?taskIds='+','.join(ids),timeout=30).json()['data']
print(json.dumps({'candidateCount':len(page.get('items',[])),'hasMore':page.get('hasMore'),'candidateStatuses':[x.get('line',{}).get('status') for x in page.get('items',[]) ]},ensure_ascii=False),flush=True)
op={'id':str(uuid.uuid4()),'selections':[x['source'] for x in page['items']]}; r=s.post(base+'/api/v1/budget/material-risk/analyses',json=op,timeout=60); print('analysis',r.status_code,flush=True)
data=r.json().get('data',{}); print(json.dumps({'projectCount':len(data.get('projects',[])),'opinions':data.get('opinions',[]),'splitAnalysis':data.get('splitAnalysis')},ensure_ascii=False),flush=True)
