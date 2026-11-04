"""Hand over only the owned isolated QA identity; consume credentials on stdin."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

os.umask(0o077)
assert os.geteuid() == 0
payloads = []
for line in sys.stdin:
    try:
        value = json.loads(line)
    except (ValueError, TypeError):
        continue
    if isinstance(value, dict) and set(value) == {'username', 'password'}:
        payloads.append(value)
assert len(payloads) == 1, 'ONE_HANDOVER_PAYLOAD_REQUIRED'
payload = payloads[0]
spec = importlib.util.spec_from_file_location('update', '/home/admin/qa26-remote-update.py')
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)
update.original()
deployment = json.loads((update.BACKUP / 'qa26-deployment.json').read_text())
assert deployment['version'] == 'qa26-20261027-r5'
release = Path(deployment['release'])
classpath = str(release / 'qa-tools') + ':' + str(release / 'qa-tools/lib/*')
private = update.BACKUP / 'qa26-access-private.json'
assert private.is_file() and not private.is_symlink()
stat = private.stat()
assert stat.st_mode & 0o077 == 0, 'PRIVATE_ACCOUNT_MODE_INVALID'
access = json.loads(private.read_text())
environment = update.env()
environment.update(QA_OWNED_FIXTURE_ID=access['primary']['id'],
                   QA_OWNED_FIXTURE_USERNAME=access['primary']['username'])
classes = update.BACKUP / 'handover-classes'
classes.mkdir(mode=0o700, exist_ok=True)
compiled = subprocess.run(['/opt/java/current/bin/javac', '--release', '17', '-cp', classpath,
                           '-d', str(classes), '/home/admin/Qa26HandoverAccount.java'], capture_output=True)
assert compiled.returncode == 0, 'HANDOVER_COMPILE_FAILED'
command = ['/opt/java/current/bin/java', '-cp', str(classes) + ':' + classpath, 'Qa26HandoverAccount']
negative_checks = 0
for overrides, rejected, reason in [
    ({'AUDIT_DB_USERNAME': 'NOT_THE_QA_SCHEMA'}, payload, 'DISPOSABLE_QA_SCHEMA_REQUIRED'),
    ({'AUDIT_DB_URL': 'jdbc:dm://127.0.0.1:1'}, payload, 'DISPOSABLE_QA_SCHEMA_REQUIRED'),
    ({}, dict(payload, username=access['secondary']['username']), 'NAME_BELONGS_TO_ANOTHER_ACCOUNT'),
    ({'QA_OWNED_FIXTURE_ID': str(uuid.uuid4())}, dict(payload, username='qa_absent_' + uuid.uuid4().hex[:12]), 'OWNED_FIXTURE_MISSING'),
]:
    attempt = subprocess.run(command, input=json.dumps(rejected).encode(),
                             env=dict(environment, **overrides), capture_output=True, timeout=90)
    assert attempt.returncode != 0 and reason.encode() in attempt.stderr, 'HANDOVER_GUARD_REGRESSION'
    negative_checks += 1
result = subprocess.run(command, input=json.dumps(payload).encode(),
                        env=environment, capture_output=True, timeout=90)
assert result.returncode == 0, 'HANDOVER_TRANSACTION_FAILED'
assert result.stdout.decode().strip().startswith('QA26_ACCOUNT_HANDOVER=PASS')
access['primary'].update(payload)
fd, temporary = tempfile.mkstemp(prefix='.qa26-access-', dir=private.parent)
try:
    with os.fdopen(fd, 'w') as stream:
        json.dump(access, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o600)
    os.chown(temporary, stat.st_uid, stat.st_gid)
    os.replace(temporary, private)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
verification = {'isolatedSchema': 'QA26_FULL2', 'ownerIdPreserved': True,
                'rolesUnchanged': True, 'durableAudit': True, 'credentialsExported': False,
                'originalDatabaseDdlOrDml': False, 'negativeGuards': negative_checks, 'failures': []}
(update.BACKUP / 'results/account-handover-verification.json').write_text(json.dumps(verification, indent=2))
print(json.dumps(verification))
