"""Bounded QA-only continuation. Waits for terminal suites before any switch."""
from pathlib import Path
import json
import os
import subprocess
import time

ROOT = Path('/home/admin/audit-agent-backups/qa26-stage2-20261027')
PYTHON = '/opt/python/venvs/audit-ai/bin/python'
SHA = '39ddc4c61e82f220d9ffdfb55f22bae31ebe747f0c533dfda234d268a8e4f917'
os.umask(0o077)

def state(stage, status, **extra):
    result = dict(stage=stage, status=status, **extra)
    path = ROOT / 'advance-r3-state.json'
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(result, indent=2))
    temp.replace(path)
    print(json.dumps(result), flush=True)

def run(stage, arguments):
    state(stage, 'RUNNING')
    with (ROOT / (stage + '.log')).open('wb') as output:
        result = subprocess.run([PYTHON] + arguments, stdout=output, stderr=subprocess.STDOUT, timeout=10800)
    state(stage, 'PASS' if result.returncode == 0 else 'FAILED', exitCode=result.returncode)
    if result.returncode:
        raise SystemExit(result.returncode)

deadline = time.monotonic() + 7200
state('wait-r2-formats', 'RUNNING')
while True:
    finished = True
    for module in ('budget', 'procurement'):
        path = ROOT / 'results' / ('formats-' + module + '-r2.json')
        rows = json.loads(path.read_text()) if path.exists() else []
        if any(r.get('failures') for r in rows):
            state('wait-r2-formats', 'FAILED', reasonCode='FORMAT_ASSERTION_FAILED', module=module)
            raise SystemExit(1)
        log = (ROOT / ('formats-' + module + '-r2.log')).read_text()
        if len(rows) != 24 or any('failures' not in r for r in rows) or '"formatSuite": "' + module + '"' not in log:
            finished = False
    if finished:
        break
    if time.monotonic() >= deadline:
        state('wait-r2-formats', 'FAILED', reasonCode='BOUNDED_WAIT_EXCEEDED')
        raise SystemExit(1)
    time.sleep(15)
state('wait-r2-formats', 'PASS', samplesPerModule=24)

deployment = json.loads((ROOT / 'qa26-deployment.json').read_text())
if deployment.get('version') == 'qa26-20261027-r3':
    assert deployment['packageSha256'] == SHA, 'RUNNING_R3_PACKAGE_DRIFT'
else:
    run('switch-r3', ['/home/admin/qa26-remote-update.py', '/home/admin/qa26-r3.tar.gz', SHA, 'r3'])
run('reopen-r3', ['/home/admin/qa26-reopen.py', '--revision', 'r3'])
run('feature-r3', ['/home/admin/qa26-feature-suite.py', '--revision', 'r3'])
run('procurement-r3', ['/home/admin/qa26-acceptance.py', 'procurement', 'normal', 'conflict', '--revision', 'r3'])
run('contract-r3', ['/home/admin/qa26-acceptance.py', 'contract', 'normal-service', '--revision', 'r3'])
state('r3-http-model-dm8-regression', 'PASS')
