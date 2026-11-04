import json, sys, requests

auth = json.loads(sys.stdin.readline())
root = sys.argv[1]
base = 'http://127.0.0.1/api/v1'
s = requests.Session()
def csrf():
    r = s.get(base + '/auth/csrf', timeout=20); r.raise_for_status()
    token = r.json()['data']; s.headers[token['headerName']] = token['token']
csrf()
r = s.post(base + '/auth/login', json={'username': auth['username'], 'password': auth['password']}, timeout=30)
assert r.status_code == 200, r.status_code
csrf()
data = json.load(open(root, encoding='utf-8'))
rows = []
for row in data:
    if row.get('module') != 'procurement' or not row.get('id'): continue
    r = s.get(base + '/procurement/materials/' + row['id'], timeout=60)
    body = r.json().get('data') if r.status_code == 200 else None
    rows.append({'file': row['file'], 'id': row['id'], 'http': r.status_code,
                 'status': body.get('status') if body else None,
                 'errorCode': body.get('errorCode') if body else None})
print(json.dumps({'rows': rows}, ensure_ascii=False))
