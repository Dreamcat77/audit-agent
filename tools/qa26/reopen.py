"""Read frozen synthetic reports after a version switch. Never starts model work."""
import importlib.util
import json
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--revision', choices=['r2', 'r3', 'r4', 'r5', 'r6'], default='r2')
args = parser.parse_args()

spec=importlib.util.spec_from_file_location('qa','/home/admin/qa26-acceptance.py')
qa=importlib.util.module_from_spec(spec);spec.loader.exec_module(qa)
rows=[]
for module in ('budget','procurement','contract'):
    for scenario in qa.GOLDEN[module]:
        label=module+'-'+scenario['id']
        raw=qa.RESULTS/(label+'-raw.json')
        if not raw.exists():
            rows.append({'label':label,'failures':['PRIOR_FROZEN_REPORT_MISSING']});continue
        before=json.loads(raw.read_text());failures=[]
        if module=='budget':
            old=[r['materialReference'] for r in before['reviews']]
            now=[qa.CLIENT.call('GET','/api/v1/budget/reviews/'+j['id'])['materialReference'] for j in before['jobs']]
        elif module=='contract':
            old=before['report']
            now=qa.CLIENT.call('GET','/api/v1/contract-analysis-cases/'+before['case']['id']+'/report')
        else:
            old=before
            now=qa.CLIENT.call('GET','/api/v1/procurement/cases/'+before['id'])
        if old!=now:failures.append('FROZEN_REPORT_CHANGED_AFTER_RESTART')
        rows.append({'label':label,'before':qa.fingerprint(old),'after':qa.fingerprint(now),'failures':failures})
qa.save(args.revision+'-frozen-history-reopen-verification',rows)
print(json.dumps({'reopened':len(rows),'failures':[r for r in rows if r['failures']],
                  'readOnly':True,'realDm8':True,'newLogin':True}),flush=True)
raise SystemExit(any(r['failures'] for r in rows))
