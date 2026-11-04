"""Read-only final QA and original-environment guards. No schema or service edits."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import urllib.request

spec = importlib.util.spec_from_file_location('update', '/home/admin/qa26-remote-update.py')
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)
update.original()
root = update.BACKUP
deployment = json.loads((root / 'qa26-deployment.json').read_text())
assert deployment['version'] == 'qa26-20261027-r5', 'FINAL_RELEASE_NOT_R5'
release = Path(deployment['release'])
assert update.sha(release / 'backend/audit-backend.jar') == 'c5ebe922d4bc241e7c2505668042fe4f4ced8992a8d3d97de727032b0f1ede69'
classpath = str(release / 'qa-tools') + ':' + str(release / 'qa-tools/lib/*')
quiescence = subprocess.run(['/opt/java/current/bin/java', '-cp', classpath, 'Qa26Quiescence'],
                            env=update.env(), capture_output=True, timeout=180)
assert quiescence.returncode == 0, 'FINAL_TASKS_STILL_ACTIVE'
units = ['audit-backend', 'audit-ai', 'nginx'] + update.UNITS
for unit in units:
    assert subprocess.run(['systemctl', 'is-active', '--quiet', unit]).returncode == 0, 'SERVICE_NOT_ACTIVE_' + unit
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
statuses = {}
for url in ('http://127.0.0.1:18082/api/v1/health', 'http://127.0.0.1:19153/health', 'http://127.0.0.1/api/v1/health'):
    with opener.open(url, timeout=15) as response:
        assert response.status == 200
        statuses[url.split('127.0.0.1')[-1]] = response.status
with opener.open('http://127.0.0.1:18082/', timeout=15) as response:
    assert hashlib.sha256(response.read()).hexdigest() == update.sha(release / 'frontend/index.html')
result = {'release': str(release), 'version': deployment['version'], 'archiveSha256': deployment['packageSha256'],
          'backendSha256': update.sha(release / 'backend/audit-backend.jar'), 'http': statuses,
          'activeTaskCounts': quiescence.stdout.decode().splitlines(), 'servicesActive': units,
          'originalFilesAndConfigurationUnchanged': True, 'originalDatabaseDdlOrDml': False,
          'realDm8': True, 'readOnly': True, 'failures': []}
path = root / 'results/final-qa26-readiness-verification.json'
path.write_text(json.dumps(result, indent=2))
print(json.dumps(result), flush=True)
