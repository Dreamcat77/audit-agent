"""Read only explicitly supplied synthetic 26 results, never customer diagnostics."""
import json
import sys
import requests

auth = json.loads(sys.stdin.readline())
client = requests.Session()
base = 'http://127.0.0.1/api/v1'

def get(path):
    result = client.get(base + path, timeout=90)
    result.raise_for_status()
    return result.json()['data']

def csrf():
    token = get('/auth/csrf')
    client.headers[token['headerName']] = token['token']

csrf()
response = client.post(base + '/auth/login', json=auth, timeout=30)
assert response.status_code == 200
csrf()
kind, identifier = sys.argv[1:3]
if kind == 'procurement':
    result = get('/procurement/projects/' + identifier + '/review-report')
    assert result['project']['projectName'].startswith('仿真'), 'SYNTHETIC_ONLY'
    print(json.dumps({
        'module': kind, 'reportId': result['id'],
        'findings': result['tender']['findings'],
        'collusion': result.get('collusion'),
    }, ensure_ascii=False), flush=True)
elif kind == 'contract':
    project = get('/projects/' + identifier)
    assert project['project']['projectNo'].startswith('SYN-PROJECT-'), 'SYNTHETIC_ONLY'
    contract = project['associatedContracts'][0]
    result = get('/projects/' + identifier + '/contracts/' + contract['contractId'] + '/review-runs/latest')
    print(json.dumps({
        'module': kind, 'runId': result['runId'], 'status': result['status'],
        'businessProjection': result.get('businessProjection'),
        'findings': [{k: f.get(k) for k in ('category','riskLevel','source','summary','recommendation')}
                     for step in result.get('outputs') or [] for f in step.get('findings') or []],
    }, ensure_ascii=False), flush=True)
else:
    raise AssertionError('UNSUPPORTED_SYNTHETIC_SCOPE')
