import json,sys,requests
auth=json.loads(sys.stdin.readline()); s=requests.Session(); base='http://127.0.0.1/api/v1'
r=s.get(base+'/auth/csrf',timeout=20); d=r.json()['data']; s.headers[d['headerName']]=d['token']; s.post(base+'/auth/login',json=auth,timeout=30); r=s.get(base+'/auth/csrf',timeout=20); d=r.json()['data']; s.headers[d['headerName']]=d['token']
seen=set(); cursor=None; pages=[]
for n in range(100):
    url=base+'/budget/material-risk/browse'+(('?cursor='+cursor) if cursor else '')
    r=s.get(url,timeout=30); data=r.json()['data']; keys=[(x['source']['taskId'],x['source']['lineIndex']) for x in data.get('items',[])]
    dup=[k for k in keys if k in seen]; seen.update(keys); pages.append({'page':n+1,'count':len(keys),'duplicate':len(dup),'cursor':data.get('nextCursor')})
    cursor=data.get('nextCursor')
    if cursor is None: break
print(json.dumps({'pages':pages,'total':len(seen)},ensure_ascii=False),flush=True)
