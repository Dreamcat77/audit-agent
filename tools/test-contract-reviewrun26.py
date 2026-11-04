"""Synthetic-only live five-step contract review acceptance for 26."""
import json
from pathlib import Path
import sys
import time
import uuid
import requests

root = Path(sys.argv[1])
auth = json.loads(sys.stdin.readline())
client = requests.Session()
base = 'http://127.0.0.1/api/v1'

def csrf():
    value = client.get(base + '/auth/csrf', timeout=20).json()['data']
    client.headers[value['headerName']] = value['token']

def login():
    client.cookies.clear(); csrf()
    response = client.post(base + '/auth/login', json={k: auth[k] for k in ('username', 'password')}, timeout=30)
    assert response.status_code == 200 and response.json()['code'] == 200
    csrf()

def get(path):
    response = client.get(base + path, timeout=120)
    assert response.status_code == 200, 'GET_' + str(response.status_code)
    body = response.json(); assert body['code'] == 200
    return body['data']

def post(path, payload, key):
    response = client.post(base + path, headers={'Idempotency-Key': key}, json=payload, timeout=180)
    assert response.status_code in (200, 202), 'POST_' + str(response.status_code)
    body = response.json(); assert body['code'] == 200
    return body['data']

login()
records = json.loads((root / 'result.json').read_text(encoding='utf-8'))['files']
assert records
for record in records:
    project_id = record['projectId']
    detail = get('/projects/' + project_id)
    contracts = detail.get('associatedContracts') or []
    assert len(contracts) == 1, 'AUTO_CONTRACT_COUNT'
    contract = contracts[0]
    files = [f for f in (detail.get('files') or []) if f.get('parseStatus') == 'PARSED']
    assert files, 'AUTO_CONTRACT_NO_PARSED_FILE'
    file_ids = [f['id'] for f in files]
    run = post('/projects/{}/contracts/{}/review-runs'.format(project_id, contract['contractId']),
               {'expectedContractVersion': contract['versionNo'], 'materialFileIds': file_ids},
               'synthetic-review-' + str(uuid.uuid4()))
    run_path = '/projects/{}/contracts/{}/review-runs/{}'.format(project_id, contract['contractId'], run['runId'])
    deadline = time.monotonic() + 1800
    while True:
        current = get(run_path)
        status = current['status']
        if status in ('COMPLETED', 'FAILED', 'UNKNOWN'):
            break
        assert time.monotonic() < deadline, 'REVIEW_RUN_TIMEOUT'
        time.sleep(5)
    assert status == 'COMPLETED', 'REVIEW_RUN_' + status
    steps = current.get('steps') or []
    assert len(steps) == 5, 'REVIEW_RUN_STEP_COUNT'
    assert all(step['status'] == 'COMPLETED' for step in steps), 'REVIEW_RUN_STEP_STATUS'
    reopened = get(run_path)
    assert reopened['runId'] == current['runId'] and reopened['status'] == 'COMPLETED'
    print(json.dumps({'projectId': project_id, 'contractId': contract['contractId'],
                      'runId': run['runId'], 'status': status, 'stepCount': len(steps),
                      'stepStatuses': [s['status'] for s in steps]}, ensure_ascii=False), flush=True)
print('CONTRACT_FORMAL_FIVE_STEP_REVIEW=PASS', flush=True)
