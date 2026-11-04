"""Read-only synthetic QA status; never prints credentials or source content."""
import importlib.util
import json

spec = importlib.util.spec_from_file_location('qa', '/home/admin/qa26-acceptance.py')
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)
for module in ('budget', 'procurement'):
    path = qa.RESULTS / ('formats-' + module + '-r2.json')
    rows = json.loads(path.read_text()) if path.exists() else []
    print(json.dumps({'module': module, 'observed': len(rows),
                      'terminal': sum('failures' in r for r in rows),
                      'failures': [r for r in rows if r.get('failures')]}, ensure_ascii=False))
    for row in rows:
        if 'failures' not in row and module == 'budget':
            job = qa.CLIENT.call('GET', '/api/v1/budget/material-uploads/' + row['id'])
            print(json.dumps({'file': row['file'], 'state': job['status']}, ensure_ascii=False))
for name in ('format-procurement-r2-office', 'format-procurement-r2-scans'):
    path = qa.RESULTS / (name + '-identity.json')
    if not path.exists():
        continue
    operation = json.loads(path.read_text())['key']
    case = qa.CLIENT.call('GET', '/api/v1/procurement/cases/operations/' + operation)
    print(json.dumps({'group': name, 'state': case['status'], 'documents': [
        {'name': d['name'], 'status': d['status']} for d in case['documents']]}, ensure_ascii=False))
