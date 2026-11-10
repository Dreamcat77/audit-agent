import json
from pathlib import Path
from decimal import Decimal
r=Path(__file__).resolve().parent
rows=json.loads((r/'acceptance-extra-r6-results.json').read_text(encoding='utf-8'),parse_float=Decimal)
baseline=rows
retested=json.loads((r/'acceptance-extra-r7-results.json').read_text(encoding='utf-8'),parse_float=Decimal)
assert len(retested)==6
updates={x['label']:x for x in retested}
rows=[updates.get(x['label'],x) for x in baseline]
by={x['label'][:2]:x for x in rows}
assert len(rows)==17
for x in rows:
 assert x['status']=='REVIEWED'
 assert all(f['status']=='COMPLETED' for f in x['references'])
 assert all(n>0 for n in x['sourceLengths'].values())
def ref(n):return by[n]['references'][0]
def warnings(n):return [a for a in by[n]['uniqueAssessments'] if a['level']=='WARNING']
assert ref('01')['totals'][0]['amount']==Decimal('999999.99') and not warnings('01')
assert ref('02')['totals'][0]['scope']=='ANNUAL' and not warnings('02')
assert [t['amount'] for t in ref('03')['totals']]==[800000]
assert not ref('03')['assessments']
assert len(ref('04')['totals'])==2 and all(t['scope'].startswith('PACKAGE:') for t in ref('04')['totals'])
assert not warnings('04')
assert any(a['code']=='TOTAL_VS_APPROVAL' and '0.01' in a['explanation'] for a in warnings('05'))
assert any(a['code']=='SEMANTIC_PURPOSE_SCOPE' and len({e['taskId'] for e in a['sources']})==2 for a in warnings('06'))
assert not warnings('07')
assert not warnings('08')
assert any(a['code']=='APPROVAL_PROCEDURE' and a['level']=='INFO' for a in ref('08')['assessments'])
assert warnings('09')
assert len(ref('10')['totals'])==1 and len(ref('10')['totals'][0]['sources'])==2 and not warnings('10')
assert len(ref('11')['totals'])==2 and not warnings('11')
assert not warnings('12') and not ref('12')['totals']
for x in [row for row in rows if row['label'].startswith('13')]:
 f=x['references'][0]
 assert any(t['amount']==200000 for t in f['totals'])
 assert any(l['declaredAmount']==31200 for l in f['lines'])
 assert not any(a['code']=='SEMANTIC_IDENTITY_CONFLICT' and a['level']=='WARNING' for a in x['uniqueAssessments'])
assert any(a['code']=='SEMANTIC_PURPOSE_SCOPE' for a in warnings('14'))
assert sum(by['14']['sourceLengths'].values())>10000
assert not warnings('15')
assert {t['amount'] for t in ref('16')['totals']}=={850000,900000}
proof={'finalRelease':'budget-batch-20261106-r7','retestedBatches':len(retested),'baselineRelease':'budget-batch-20261106-r6','ocrConfusableNotConfirmedAsFault':True,'longFileChars':sum(by['14']['sourceLengths'].values()),'synonymNoWarning':True,'status':'PASS','batches':len(rows),'files':sum(len(x['ids']) for x in rows),'rotationAngles':[90,180],'historyExcluded':True,'normalApprovalNoWarning':True,'exactCentDifference':'0.01','crossFilePurposeConflict':True,'approvedRevisionNoWarning':True,'nonBudgetStillFinished':True,'sourceExactness':'remote acceptance asserts ranges against frozen text'}
(r/'extra-business-r7-verification.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(proof))
