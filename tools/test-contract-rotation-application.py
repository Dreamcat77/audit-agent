"""Real deployed contract uploads and model analysis using synthetic fixtures only.

Writes have one immutable operation key journaled before sending; uncertain writes
are never replayed. This does not claim a full formal five-step ReviewRun.
"""
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid
import requests

root=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(exist_ok=False)
auth=json.loads(sys.stdin.readline());client=requests.Session();base='http://127.0.0.1/api/v1';journal=[]
def save(name,data):
    (out/name).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
def api(method,path,**kw):
    if method in ('POST','PATCH') and not path.startswith('/auth/'):
        key=str(uuid.uuid4());kw.setdefault('headers',{})['Idempotency-Key']=key
        record=dict(method=method,path=path,key=key,state='UNCERTAIN');journal.append(record);save('writes.json',journal)
    else:record=None
    response=client.request(method,base+path,timeout=kw.pop('timeout',900),**kw)
    if response.status_code not in (200,202):
        save('http-failure.json',dict(path=path,status=response.status_code,body=response.json()))
        raise AssertionError('CONTRACT_HTTP_'+str(response.status_code))
    data=response.json();assert data['code']==200
    if record is not None:record['state']='RESPONDED';save('writes.json',journal)
    return data['data']
def csrf():
    token=api('GET','/auth/csrf');client.headers[token['headerName']]=token['token']
def login():
    client.cookies.clear();csrf();api('POST','/auth/login',json={k:auth[k] for k in ('username','password')});csrf()
login();records=[]
for path in sorted(root.iterdir()):
    if path.suffix not in ('.png','.pdf','.docx'):continue
    project=api('POST','/projects');pid=project['id'];prefix='/projects/'+pid
    api('PATCH',prefix,json=dict(expectedVersion=project['versionNo'],name='仿真图书馆办公设备采购',projectNo='DEMO-ROT-C01',category='GOODS'))
    mime={'.png':'image/png','.pdf':'application/pdf','.docx':'application/vnd.openxmlformats-officedocument.wordprocessingml.document'}[path.suffix]
    content=path.read_bytes();accepted=api('POST',prefix+'/file-jobs',files={'file':(path.name,content,mime)})
    fid=accepted['fileId'];save(path.stem+'-accepted.json',accepted)
    assert accepted['file']['fileSha256']==hashlib.sha256(content).hexdigest()
    deadline=time.monotonic()+1800
    while True:
        state=api('GET',prefix+'/files/'+fid+'/processing')
        if state['status']=='SUCCEEDED':break
        assert state['status'] in ('QUEUED','PROCESSING'),'CONTRACT_PARSE_'+str(state)
        assert time.monotonic()<deadline,'CONTRACT_PROCESSING_DEADLINE';time.sleep(3)
    file=next(f for f in api('GET',prefix+'/files') if f['id']==fid);save(path.stem+'-parsed.json',file)
    text=''.join((file.get('parsedText') or '').split()).replace(',','').replace('，','')
    assert '120000' in text and '6000' in text,'CONTRACT_IMAGE_AMOUNT_LOST'
    assert all(word in text for word in ('30','三年','万分之五','验收')),'CONTRACT_IMAGE_TERMS_LOST'
    assert file['parseMethod']=='OCR' and file['parseResultId'],'CONTRACT_OCR_PROVENANCE_LOST'
    # Parsed persisted text is the source for this real contract model endpoint.
    result=api('POST',prefix+'/ai/contract-text-analysis',json={'fileIds':[fid]},timeout=1800)
    save(path.stem+'-analysis.json',result)
    assert result['available'] and result['summaries'] and result['hints'],'CONTRACT_MODEL_NO_CONTENT'
    assert any(hint.get('evidenceItems') for hint in result['hints']),'CONTRACT_MODEL_NO_EVIDENCE'
    visible=json.dumps([result['summaries'],[(h['title'],h['description']) for h in result['hints']]],ensure_ascii=False)
    assert not any(word in visible for word in ('字段移除','字段被移除','文本片段断裂','采购内容疑似不完整')),'CONTRACT_REDACTION_FALSE_OMISSION'
    records.append(dict(projectId=pid,fileId=fid,parsed=file,path=path.name,model=result['model']))
    print(json.dumps(dict(case='contract',file=path.name,result='PASS',originalBytes=True,ocrAmount=True,ocrTerms=True,realModel=True),ensure_ascii=False),flush=True)
login()
for record in records:
    file=next(f for f in api('GET','/projects/'+record['projectId']+'/files') if f['id']==record['fileId'])
    assert file['parsedText']==record['parsed']['parsedText'] and file['parseResultId']==record['parsed']['parseResultId'],'CONTRACT_REOPEN_CHANGED'
save('result.json',dict(result='PASS',scope='PERSISTED_OCR_AND_CONTRACT_MODEL_NOT_FORMAL_REVIEW_RUN',files=[{k:v for k,v in r.items() if k!='parsed'} for r in records]))
print('ROTATION_CONTRACT_PERSISTED_OCR_REAL_MODEL=PASS FORMAL_REVIEW_RUN=NOT_VERIFIED',flush=True)
