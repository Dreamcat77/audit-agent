"""Acceptance scenarios run in the coordinator's isolated-DB session."""
import json, time, uuid, hashlib, zipfile
from pathlib import Path
import requests

def run(request,g):
    action=request['test']; evidence=g['EVIDENCE']; session=g['SESSION']
    url='http://127.0.0.1:19131/api/v1/procurement/cases'
    def get(id):
        r=session.get(url+'/'+id,timeout=20);r.raise_for_status();return r.json()['data']
    def save(name,data):
        (evidence/name).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    if action=='validate':
        label=request.get('label','dm8-actual')
        case=json.loads((evidence/(label+'-result.json')).read_text(encoding='utf-8'))['data']
        sources={}
        for doc in case['documents']:
            r=session.get(url+'/'+case['id']+'/files/'+doc['id']+'/evidence',timeout=15);r.raise_for_status();sources[doc['id']]=r.json()['data']
            assert hashlib.sha256((Path(g['CONFIG']['fixtures'])/doc['name']).read_bytes()).hexdigest()==sources[doc['id']]['sha256']
        count=0
        for module,review in case['report']['reviews'].items():
            for item in review['findings']+review['checks']:
                for source in item['evidence']:
                    actual=sources[source['documentId']]
                    assert source['sha256']==actual['sha256']
                    assert any(s['locator']==source['locator'] and source['excerpt'] in s['text'] for s in actual['segments'])
                    count+=1
        f10=case['report']['reviews']['TENDER_COMPLIANCE']
        tech=[s for s in f10['scope'] if s['role']=='TECHNICAL']
        assert len(tech)==1 and tech[0]['suppliedChars']>0
        assert tech[0]['sourceChars']==tech[0]['suppliedChars']
        assert {'TENDER','TECHNICAL','EVALUATION','CLARIFICATION'} <= {s['role'] for s in f10['scope']}
        report=case['report']
        assert report['budget']==222600 and report['awardAmount']==212000
        assert sum(len(o['lines']) for o in report['offers'])==9
        assert report['modules']['BLACKLIST']==report['modules']['COLLUSION']==report['modules']['SPLIT_PURCHASE']=='NOT_EXECUTED'
        result={'caseId':case['id'],'mode':g['MODE'],'repository':'REAL_DM8','validatedCitations':count,
                'allCitationsExactSource':True,'moduleStatuses':report['modules'],
                'technicalDocumentScope':tech,'factsPerFile':{d['name']:d['factCount'] for d in case['documents']},
                'observations':{m:r['checks'] for m,r in report['reviews'].items()},
                'findings':{m:r['findings'] for m,r in report['reviews'].items()}}
        save(label+'-semantic-validation.json',result)
        print('CITATION_VALIDATION_PASS '+json.dumps({'case':case['id'],'mode':g['MODE'],'citations':count,'modules':report['modules']}),flush=True)
    elif action=='reopen':
        label=request.get('label','dm8-actual')
        old=json.loads((evidence/(label+'-result.json')).read_text(encoding='utf-8'))['data']
        # Reload final prompt sources only between completed operations, never during an extraction.
        g['stop']('ai');g['start_ai']()
        g['start_backend'](request.get('mode',g['MODE']))
        actual=g['SESSION'].get(url+'/'+old['id'],timeout=20).json()['data']
        assert actual==old
        save(label+'-restart-reopen.json',{'caseId':old['id'],'equalAfterProcessRestart':True,'modules':actual['report']['modules'],'repository':'REAL_DM8'})
        print('REAL_DM8_RESTART_REOPEN_PASS',flush=True)
    elif action=='recovery':
        # Let an earlier real operation finish before changing model modes. Never resume its
        # unfinished files with Mock extraction merely because a browser assertion was cancelled.
        deadline=time.monotonic()+700
        observed={}
        while time.monotonic()<deadline:
            response=g['SESSION'].get(url,timeout=30);response.raise_for_status();cases=response.json()['data']
            active=[c for c in cases if c['status'] in ['RUNNING','QUEUED','UPLOADING']]
            for c in active:observed[c['id']]=c
            if not active:break
            time.sleep(2)
        else:raise RuntimeError('real operations are still active; refusing model mode change')
        for id in observed:save('initial-browser-attempt-result.json',g['SESSION'].get(url+'/'+id,timeout=30).json())
        g['stop']('ai');g['start_ai']()
        gate=g['BACKUP']/'never-open-review-gate'
        assert not gate.exists()
        g['start_backend']('simulated',review_gate=gate)
        s=g['SESSION'];csrf=s.get('http://127.0.0.1:19131/api/v1/auth/csrf',timeout=5).json()['data']['token']
        handles=[]
        try:
            files=[]
            for path in sorted(Path(g['CONFIG']['fixtures']).iterdir()):
                h=path.open('rb');handles.append(h);files.append(('files',(path.name,h)))
            r=s.post(url,files=files,headers={'X-CSRF-TOKEN':csrf,'Idempotency-Key':str(uuid.uuid4())},timeout=90);r.raise_for_status();case=r.json()['data'];id=case['id']
        finally:
            for h in handles:h.close()
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            c=g['SESSION'].get(url+'/'+id,timeout=20).json()['data']
            if c.get('report') and c['report'].get('reviews',{}).get('AGENT_PRECHECK',{}).get('status')=='RUNNING':break
            time.sleep(1)
        else:raise RuntimeError('review claim not observed')
        save('restart-interrupted-before.json',c)
        p=g['CHILDREN'].pop('backend');p.kill();p.wait(timeout=15)
        g['start_backend']('simulated',clock_offset=3600)
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            c=g['SESSION'].get(url+'/'+id,timeout=20).json()['data']
            if c['status'] not in ['RUNNING','QUEUED','UPLOADING']:break
            time.sleep(1)
        assert c['report']['modules']['AGENT_PRECHECK']=='UNKNOWN'
        assert c['report']['modules']['TENDER_COMPLIANCE']=='COMPLETED'
        assert c['report']['modules']['QUOTE']=='COMPLETED'
        assert c['status']=='PARTIAL' and c['report']['reviews']['AGENT_PRECHECK']['errorCode']=='EXECUTOR_LOST_MODEL_OUTCOME_UNKNOWN'
        save('restart-interrupted-after.json',c)
        print('REAL_DM8_INTERRUPTED_MODULE_RECOVERY_PASS case='+id,flush=True)
    elif action=='actual-conflict':
        # A synthetic adversarial fixture, explicitly different from the user's twelve-file baseline.
        # Calls the real gateway and checks semantic behavior; no original files or DB records are mutated.
        body={'caseId':str(uuid.uuid4()),'module':'TENDER_COMPLIANCE','inputDigest':'d'*64,'version':'case-cross-file-v1',
            'documents':[],'evidence':[],'facts':[],'checks':[],'ruleSignals':[]}
        texts=[('TENDER','招标文件：到货初验后付款30%，终验后付款65%，质保期满5%。本条款继续有效。'),
               ('CLARIFICATION','澄清答复：到货初验后付款50%，终验后付款40%，质保期满10%。原招标文件继续有效，本答复没有优先适用约定。')]
        for n,(role,text) in enumerate(texts):
            doc=str(uuid.uuid4());eid='E-'+str(n+1)*24
            body['documents'].append({'documentId':doc,'fileName':role+'.pdf','role':role,'sourceChars':len(text),'suppliedChars':len(text)})
            body['evidence'].append({'id':eid,'documentId':doc,'fileName':role+'.pdf','sha256':str(n+1)*64,'locator':'p1','excerpt':text})
        r=requests.post('http://127.0.0.1:19121/ai/v1/cases/review',json=body,timeout=170)
        save('actual-model-conflict-fixture.json',{'request':body,'status':r.status_code,'response':r.json()})
        r.raise_for_status();result=r.json()
        assert any(f['code'] in ['PAYMENT_CONFLICT','CLARIFICATION_CONFLICT','BUSINESS_CONFLICT'] and set(f['evidenceIds'])=={e['id'] for e in body['evidence']} for f in result['findings'])
        print('ACTUAL_MODEL_ADVERSARIAL_CROSS_FILE_CONFLICT_PASS',flush=True)
    elif action=='actual-http-conflict':
        assert g['MODE']=='actual'
        from xml.etree import ElementTree as ET
        fixtures=g['BACKUP']/'cross-file-conflict';fixtures.mkdir(exist_ok=True)
        template=next(p for p in Path(g['CONFIG']['fixtures']).iterdir() if p.name.startswith('05_'))
        rows=[('采购招标文件','货物到货初验后支付合同价30%；终验后65%；质保期满5%。本付款条款继续有效。'),
              ('采购澄清答复','同一采购事项：到货初验后支付合同价50%；终验后40%；质保期满10%。原招标文件的付款条款继续有效，未规定本答复优先适用，两套比例并行执行。')]
        files=[];handles=[]
        ns='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
        for title,text in rows:
            document=ET.Element('{'+ns+'}document');body=ET.SubElement(document,'{'+ns+'}body')
            for paragraph in [title,text]:
                p=ET.SubElement(body,'{'+ns+'}p');r=ET.SubElement(p,'{'+ns+'}r');ET.SubElement(r,'{'+ns+'}t').text=paragraph
            output=fixtures/(title+'.docx')
            with zipfile.ZipFile(template) as source,zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as target:
                for entry in source.infolist():target.writestr(entry,ET.tostring(document,encoding='utf-8',xml_declaration=True) if entry.filename=='word/document.xml' else source.read(entry.filename))
            h=output.open('rb');handles.append(h);files.append(('files',(output.name,h)))
        try:
            s=g['SESSION'];csrf=s.get('http://127.0.0.1:19131/api/v1/auth/csrf',timeout=10).json()['data']['token']
            r=s.post(url,files=files,headers={'X-CSRF-TOKEN':csrf,'Idempotency-Key':str(uuid.uuid4())},timeout=60);r.raise_for_status();id=r.json()['data']['id'];save('actual-http-conflict-upload.json',r.json())
        finally:
            for h in handles:h.close()
        deadline=time.monotonic()+400
        while time.monotonic()<deadline:
            r=g['SESSION'].get(url+'/'+id,timeout=20);r.raise_for_status();c=r.json()['data']
            if c['status'] not in ['RUNNING','QUEUED','UPLOADING']:break
            time.sleep(2)
        save('actual-http-conflict-result.json',{'code':200,'data':c,'fixture':'Two deliberately contradictory synthetic DOCX files; not the twelve-file baseline'})
        review=c['report']['reviews']['TENDER_COMPLIANCE']
        findings=[f for f in review['findings'] if f['code'] in ['PAYMENT_CONFLICT','CLARIFICATION_CONFLICT','BUSINESS_CONFLICT'] and len({e['documentId'] for e in f['evidence']})>=2]
        assert findings and review['model']!='MOCK_CASE_REVIEW'
        for finding in findings:
            for source in finding['evidence']:
                actual=g['SESSION'].get(url+'/'+id+'/files/'+source['documentId']+'/evidence',timeout=15).json()['data']
                assert source['sha256']==actual['sha256'] and any(s['locator']==source['locator'] and source['excerpt'] in s['text'] for s in actual['segments'])
        assert c['status']!='COMPLETED' and c['report']['calculation'] is None
        print('ACTUAL_MODEL_REAL_DM8_HTTP_CROSS_FILE_FINDING_PASS case='+id,flush=True)
    else:raise ValueError('unknown phase3 scenario')
