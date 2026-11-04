"""Finite real QA regression queue; raw output stays private on the server."""
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path('/home/admin/audit-agent-backups/unified-material-20261028-r1')
PYTHON = '/opt/python/venvs/audit-ai/bin/python'
JOBS = [
    ('history', ['/home/admin/unified-reopen.py', '--revision', 'r6']),
    ('budget', ['/home/admin/unified-acceptance.py', 'budget', '--revision', 'r6']),
    ('procurement', ['/home/admin/unified-acceptance.py', 'procurement', '--revision', 'r6']),
    ('contract', ['/home/admin/unified-acceptance.py', 'contract', '--revision', 'r6']),
    ('feature', ['/home/admin/unified-feature.py', '--revision', 'r6']),
    ('formats-budget', ['/home/admin/unified-formats.py', 'budget', '--revision', 'r6']),
    ('formats-procurement', ['/home/admin/unified-formats.py', 'procurement', '--revision', 'r6']),
]

def main():
    os.umask(0o077)
    status = ROOT / 'r6-regression-status.json'
    assert not status.exists(), 'QUEUE_ALREADY_STARTED_INSPECT_STATUS'
    rows = []
    def save():
        temporary = status.with_suffix('.tmp')
        temporary.write_text(json.dumps(rows, indent=2))
        temporary.replace(status)
    for label, command in JOBS:
        row = {'job': label, 'state': 'RUNNING', 'startedAt': time.time()}
        rows.append(row); save()
        try:
            with (ROOT / ('r6-' + label + '-private.log')).open('xb') as output:
                child = subprocess.Popen([PYTHON] + command, stdout=output, stderr=subprocess.STDOUT)
                row['pid'] = child.pid; save()
                code = child.wait(timeout=14400)
            row.update(state='PASSED' if code == 0 else 'FAILED', exitCode=code)
        except Exception as error:
            row.update(state='FAILED', reason=type(error).__name__)
            save()
            raise
        row['finishedAt'] = time.time(); save()
        print(json.dumps({'job': label, 'state': row['state']}), flush=True)
    print('FINITE_REGRESSION_QUEUE_COMPLETED', flush=True)

if __name__ == '__main__':
    main()
