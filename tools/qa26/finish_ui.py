"""QA-only frontend update after the r3 business regression has actually passed."""
from pathlib import Path
import hashlib
import json
import os
import pwd
import subprocess
import tarfile
import time

ROOT = Path('/home/admin/audit-agent-backups/qa26-stage2-20261027')
PYTHON = '/opt/python/venvs/audit-ai/bin/python'
SHA = 'feb0ab61812ea14c4fcd1071e65cd121e8fad90aa8a92c4165d61e97229497ce'
os.umask(0o077)
deadline = time.monotonic() + 10800
while True:
    result = json.loads((ROOT / 'advance-r3-state.json').read_text())
    if result['status'] == 'FAILED':
        raise SystemExit('PRIOR_R3_STAGE_FAILED')
    if result == {'stage': 'r3-http-model-dm8-regression', 'status': 'PASS'}:
        break
    if time.monotonic() >= deadline:
        raise SystemExit('R3_WAIT_DEADLINE')
    time.sleep(15)

with tarfile.open('/home/admin/qa26-r3.tar.gz') as old, tarfile.open('/home/admin/qa26-r4.tar.gz') as new:
    x = {f['path']: f['sha256'] for f in json.load(old.extractfile('manifest.json'))['files']}
    y = {f['path']: f['sha256'] for f in json.load(new.extractfile('manifest.json'))['files']}
    changed = {p for p in x.keys() | y.keys() if x.get(p) != y.get(p)}
    assert changed and all(p.startswith('frontend/') for p in changed), 'NOT_FRONTEND_ONLY'
with (ROOT / 'switch-r4.log').open('wb') as output:
    result = subprocess.run([PYTHON, '/home/admin/qa26-remote-update.py', '/home/admin/qa26-r4.tar.gz', SHA, 'r4'],
                            stdout=output, stderr=subprocess.STDOUT, timeout=1200)
assert result.returncode == 0, 'R4_SWITCH_FAILED'
with (ROOT / 'reopen-r4.log').open('wb') as output:
    result = subprocess.run([PYTHON, '/home/admin/qa26-reopen.py', '--revision', 'r4'],
                            stdout=output, stderr=subprocess.STDOUT, timeout=300)
assert result.returncode == 0, 'R4_FROZEN_REPORT_REOPEN_FAILED'
account = pwd.getpwnam('admin')
public = Path('/home/admin/qa26-public-evidence')
assert not public.is_symlink()
public.mkdir(mode=0o700, exist_ok=True)
os.chown(public, account.pw_uid, account.pw_gid)
for name in ('procurement-normal-r3', 'contract-normal-service-r3'):
    data = (ROOT / 'results' / (name + '-verification.json')).read_bytes()
    assert not json.loads(data)['failures']
    path = public / (name + '-verification.json')
    assert not path.is_symlink()
    path.write_bytes(data)
    os.chown(path, account.pw_uid, account.pw_gid)
    path.chmod(0o600)
state = {'stage': 'r4-frontend-only-switch', 'status': 'PASS', 'sha256': SHA,
         'browser109': 'NOT_EXECUTED', 'backendUnchanged': True, 'pythonUnchanged': True}
(ROOT / 'finish-ui-state.json').write_text(json.dumps(state, indent=2))
print(json.dumps(state), flush=True)
