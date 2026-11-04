"""Recheck real model prose on previously persisted synthetic contract files.

Never replays uncertain writes or changes old parse results or review runs.
Credentials cross only the runner's anonymous stdin pipe.
"""
import json
from pathlib import Path
import re
import sys
import uuid
import requests

prior=Path(sys.argv[1]); output=Path(sys.argv[2]); output.mkdir(exist_ok=False)
auth=json.loads(sys.stdin.readline()); client=requests.Session(); base='http://127.0.0.1/api/v1'
journal=[]
def save(name,value):
    (output/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def api(method,path,**kwargs):
    if method=='POST' and not path.startswith('/auth/'):
        item=dict(path=path,key=str(uuid.uuid4()),state='UNCERTAIN');journal.append(item);save('writes.json',journal)
        kwargs['headers']={'Idempotency-Key':item['key']}
    else:item=None
    response=client.request(method,base+path,timeout=1800,**kwargs)
    if response.status_code!=200:
        save('http-failure.json',dict(path=path,status=response.status_code,body=response.json()))
        raise AssertionError('CONTRACT_QUALITY_HTTP_'+str(response.status_code))
    data=response.json();assert data['code']==200
    if item is not None:item['state']='RESPONDED';save('writes.json',journal)
    return data['data']
def csrf():
    token=api('GET','/auth/csrf');client.headers[token['headerName']]=token['token']
csrf();api('POST','/auth/login',json={key:auth[key] for key in ('username','password')});csrf()
for index,record in enumerate(json.loads((prior/'result.json').read_text())['files']):
    prefix='/projects/'+record['projectId'];fid=record['fileId']
    before=next(f for f in api('GET',prefix+'/files') if f['id']==fid)
    result=api('POST',prefix+'/ai/contract-text-analysis',json={'fileIds':[fid]})
    save(str(index)+'-analysis.json',result)
    assert result['available'] and result['hints'],'CONTRACT_MODEL_NO_HINTS'
    for hint in result['hints']:
        assert not re.search(r'(?:项目名称为|乙方（供应商）为|乙方为|采购内容仅列明|仅以|付款以)[\s：:，,、（(]*$',hint['description']),'DANGLING_DESCRIPTION'
        assert len(hint['description'])>=10,'DESCRIPTION_NO_USEFUL_CONTENT'
    assert all(any(h['category']==category for h in result['hints']) for category in
        ('PROJECT_IDENTITY','SUPPLIER_IDENTITY','DELIVERY_TERMS','TERM_TERMINATION')),'REQUIRED_TOPIC_LOST'
    assert any('三年' in h['description'] for h in result['hints']),'WARRANTY_FACT_LOST'
    visible=json.dumps([result['summaries'],[(h['title'],h['description']) for h in result['hints']]],ensure_ascii=False)
    assert not any(word in visible for word in ('字段移除','字段被移除','文本片段断裂')),'MASKING_FALSE_DAMAGE'
    after=next(f for f in api('GET',prefix+'/files') if f['id']==fid)
    assert before['parseResultId']==after['parseResultId'] and before['parsedText']==after['parsedText'],'HISTORICAL_SOURCE_CHANGED'
    print(json.dumps(dict(file=record['path'],result='PASS',completeDescriptions=True,sourceUnchanged=True),ensure_ascii=False),flush=True)
print('CONTRACT_REAL_MODEL_RESULT_QUALITY=PASS FORMAL_REVIEW_RUN=NOT_VERIFIED',flush=True)
