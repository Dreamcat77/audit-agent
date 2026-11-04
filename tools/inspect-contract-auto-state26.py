import json,sys,requests
from pathlib import Path
auth=json.loads(sys.stdin.readline()); root=Path(sys.argv[1]); c=requests.Session(); base='http://127.0.0.1/api/v1'
t=c.get(base+'/auth/csrf').json()['data']; c.headers[t['headerName']]=t['token']
r=c.post(base+'/auth/login',json={k:auth[k] for k in ('username','password')}); assert r.status_code==200
t=c.get(base+'/auth/csrf').json()['data']; c.headers[t['headerName']]=t['token']
records=json.loads((root/'result.json').read_text(encoding='utf-8'))['files']
for x in records:
 d=c.get(base+'/projects/'+x['projectId']).json()['data']
 candidates=d.get('candidates') or []
 cand=[]
 for candidate in candidates:
  detail=c.get(base+'/projects/'+x['projectId']+'/contract-candidates/'+candidate['id']).json().get('data') or {}
  value=detail.get('candidate') or candidate
  states=detail.get('fieldStates') or {}
  required = ['contractNo','contractName','contractType','partyA','partyB','contractAmount']
  cand.append({'id':candidate.get('id'),'promotionState':value.get('promotionState'),'versionNo':value.get('versionNo'),
              'requiredPresent':{k: value.get(k) is not None and str(value.get(k)).strip() != '' for k in required},
              'fieldStateKeys':sorted(states.keys()),'fieldStateValues':sorted(set(str(v) for v in states.values())),
              'missingFieldKeys':sorted(k for k,v in states.items() if str(v) == 'MISSING'),
              'promotedContractId':value.get('promotedContractId'),
              'promotedConfirmationId':value.get('promotedConfirmationId'),'authorityMode':value.get('authorityMode'),
              'supportAuthorityVersionNo':value.get('supportAuthorityVersionNo')})
 print(json.dumps({'projectId':x['projectId'],'associatedContractCount':len(d.get('associatedContracts') or []),
  'candidateCount':len(candidates),'candidates':cand,'extractionCount':len(d.get('extractionResults') or []),
  'fileStates':[{'id':f.get('id'),'parseStatus':f.get('parseStatus'),'roleDecisionStatus':f.get('roleDecisionStatus'),
                 'effectiveRole':f.get('effectiveRole'),'identityStatus':f.get('effectiveIdentityStatus'),
                 'progressionStatus':(f.get('progression') or {}).get('status')} for f in d.get('files') or []]},ensure_ascii=False),flush=True)
