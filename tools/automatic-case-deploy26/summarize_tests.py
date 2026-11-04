from pathlib import Path
import json
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[2]
doc=root/'docs/automatic-procurement-deploy26-20261027/evidence'
baseline=json.loads((root/'docs/automatic-procurement-phase3-20261027/evidence/final-verification-summary.json').read_text(encoding='utf-8'))
totals=dict(tests=0,failures=0,errors=0,skipped=0); failures=[]
for p in (root/'backend/target-deploy26-verify/surefire-reports').glob('TEST-*.xml'):
    tree=ET.parse(p).getroot()
    for k in totals: totals[k]+=int(tree.get(k,0))
    for case in tree.findall('testcase'):
        for kind in ['failure','error']:
            element=case.find(kind)
            if element is not None:
                name=case.get('classname')+'#'+case.get('name')
                category=next((f['category'] for f in baseline['failures'] if f['test']==name),'NEW')
                failures.append(dict(test=name,kind=kind,category=category,message=element.get('message','')))
expected={f['test'] for f in baseline['failures']};actual={f['test'] for f in failures}
result=dict(command='mvnw.cmd clean verify -Daudit.build.directory=target-deploy26-verify',exitCode=1,totals=totals,
            passed=totals['tests']-sum(totals[k] for k in ['failures','errors','skipped']),
            baselineFailureSetExactlyMatches=actual==expected,newFailures=sorted(actual-expected),resolvedFailures=sorted(expected-actual),failures=failures)
(doc/'full-test-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k!='failures'},ensure_ascii=False))
