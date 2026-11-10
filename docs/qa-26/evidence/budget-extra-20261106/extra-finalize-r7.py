from pathlib import Path
import hashlib,json,urllib.request
root=Path('/home/admin/budget-batch-20261106');current=Path('/opt/audit-agent/current').resolve()
assert current.name=='budget-batch-20261106-r7'
rows=json.loads((root/'acceptance-extra-r7-results.json').read_text());by={r['label']:r for r in rows}
assert json.loads((root/'extra-business-r7-verification.json').read_text())['status']=='PASS'
assert json.loads((root/'extra-browser-r7-verification.json').read_text())['status']=='PASS'
assert len(rows)==6 and all(r['status']=='REVIEWED' and all(f['status']=='COMPLETED' for f in r['references']) for r in rows)
by={r['label'][:2]:r for r in rows}
assert [t['amount'] for t in by['03']['references'][0]['totals']]==[800000]
assert not any(a['level']=='WARNING' for a in by['08']['uniqueAssessments'])
assert any(a['code']=='SEMANTIC_PURPOSE_SCOPE' for a in by['14']['uniqueAssessments'])
assert all(not any(a['code']=='SEMANTIC_IDENTITY_CONFLICT' and a['level']=='WARNING' for a in r['uniqueAssessments']) for r in rows if r['label'].startswith('13'))
sha=lambda data:hashlib.sha256(data).hexdigest()
manifest=json.loads((current/'SOURCE-PATCH-MANIFEST.json').read_text())
assert sha((current/'backend/audit-backend-0.1.0-SNAPSHOT.jar').read_bytes())==manifest['jarSha256']
for item in manifest['files']:
 assert sha((current/item['path']).read_bytes())==item['sha256']
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
health={}
for port,path in [(8001,'/health'),(8080,'/api/v1/health'),(80,'/')]:
 with opener.open('http://127.0.0.1:'+str(port)+path,timeout=10) as response:health[str(port)]=response.status
assert set(health.values())=={200}
proof=json.loads((root/'deployment.json').read_text());proof.update(budgetSemanticReview='PASS_REAL_MODEL_SYNTHETIC',verifiedHealth=health,sourceHashesVerified=True,finalModelBatchIds=[r['batchId'] for r in rows])
(root/'deployment.json').write_text(json.dumps(proof,indent=2));(current/'DEPLOYMENT-VERIFICATION.json').write_text(json.dumps(proof,indent=2))
print(json.dumps(proof))
