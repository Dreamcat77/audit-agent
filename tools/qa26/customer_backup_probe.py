"""Test the backup attestation guard on synthetic root-only files, no real backup."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import tempfile

spec = importlib.util.spec_from_file_location('guard', '/home/admin/customer-v11-probe/backup-check.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
assert os.geteuid() == 0
with tempfile.TemporaryDirectory(prefix='v11-backup-guard-', dir='/root') as directory:
    root = Path(directory)
    artifacts = []
    for kind in ('database', 'files'):
        path = root / (kind + '.synthetic')
        path.write_bytes(('synthetic guard fixture ' + kind).encode())
        path.chmod(0o600)
        artifacts.append({'kind': kind, 'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    baseline = {'format': 'audit-agent-recovery-v1', 'previousRelease': '/opt/audit-agent/releases/synthetic',
                'databaseRestoreTested': True, 'filesRestoreTested': True, 'artifacts': artifacts}
    record = root / 'record.json'
    def save(value):
        record.write_text(json.dumps(value)); record.chmod(0o600)
    save(baseline)
    guard.validate(str(record), baseline['previousRelease'])
    def reject(value, reason):
        save(value)
        try:
            guard.validate(str(record), baseline['previousRelease'])
            raise AssertionError('UNSAFE_BACKUP_RECORD_ACCEPTED')
        except ValueError as exc:
            assert str(exc) == reason
    reject(dict(baseline, previousRelease='/opt/audit-agent/releases/other'), 'BACKUP_RECORD_RELEASE_MISMATCH')
    reject(dict(baseline, databaseRestoreTested=False), 'RESTORE_ATTESTATION_REQUIRED')
    bad = json.loads(json.dumps(baseline)); bad['artifacts'][0]['sha256'] = '0' * 64
    reject(bad, 'BACKUP_ARTIFACT_HASH_MISMATCH')
    save(baseline); record.chmod(0o644)
    try:
        guard.validate(str(record), baseline['previousRelease']); raise AssertionError('PUBLIC_RECORD_ACCEPTED')
    except ValueError as exc:
        assert str(exc) == 'BACKUP_RECORD_NOT_PRIVATE_ROOT_FILE'
    save(baseline)
    link = root / 'link.json'; link.symlink_to(record)
    try:
        guard.validate(str(link), baseline['previousRelease']); raise AssertionError('SYMLINK_ACCEPTED')
    except ValueError as exc:
        assert str(exc) == 'BACKUP_RECORD_NOT_REGULAR'
result = {'positive': 1, 'negative': 5, 'realRootPermissions': True, 'syntheticOnly': True,
          'realBackupRestorePerformed': False, 'failures': []}
Path('/home/admin/audit-agent-backups/qa26-stage2-20261027/results/customer-v11-backup-guard-verification.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result), flush=True)
