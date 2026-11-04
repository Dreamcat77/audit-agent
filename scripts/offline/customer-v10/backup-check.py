"""Validate a local operator's recovery attestation; never exports backup contents.

This verifies hashes and the attestation's scope, not the restore operation itself.
The operator must complete a separate restore exercise before creating the record.
"""
from pathlib import Path
import hashlib
import json
import os
import stat
import sys

def require(ok, code):
    if not ok:
        raise ValueError(code)

def private_file(path):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and not path.is_symlink(), 'BACKUP_RECORD_NOT_REGULAR')
    require(info.st_uid == 0 and stat.S_IMODE(info.st_mode) & 0o077 == 0, 'BACKUP_RECORD_NOT_PRIVATE_ROOT_FILE')

def validate(record_path, previous):
    record = Path(record_path)
    require(record.is_absolute(), 'BACKUP_RECORD_ABSOLUTE_PATH_REQUIRED')
    private_file(record)
    require(record.stat().st_size <= 65536, 'BACKUP_RECORD_TOO_LARGE')
    data = json.loads(record.read_text(encoding='utf-8'))
    require(data.get('format') == 'audit-agent-recovery-v1', 'BACKUP_RECORD_FORMAT')
    require(data.get('previousRelease') == str(Path(previous).resolve()), 'BACKUP_RECORD_RELEASE_MISMATCH')
    require(data.get('databaseRestoreTested') is True and data.get('filesRestoreTested') is True, 'RESTORE_ATTESTATION_REQUIRED')
    artifacts = data.get('artifacts')
    require(isinstance(artifacts, list) and len(artifacts) == 2, 'BACKUP_ARTIFACTS_REQUIRED')
    require({x.get('kind') for x in artifacts} == {'database', 'files'}, 'BACKUP_ARTIFACT_KINDS')
    seen = set()
    for artifact in artifacts:
        path = Path(artifact['path'])
        require(path.is_absolute() and path.resolve() != record.resolve(), 'BACKUP_ARTIFACT_PATH')
        private_file(path)
        require(path.stat().st_size > 0 and path.resolve() not in seen, 'BACKUP_ARTIFACT_EMPTY_OR_DUPLICATE')
        seen.add(path.resolve())
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        require(digest == artifact.get('sha256'), 'BACKUP_ARTIFACT_HASH_MISMATCH')

if __name__ == '__main__':
    try:
        require(os.geteuid() == 0 and len(sys.argv) == 3, 'BACKUP_RECORD_ARGUMENTS')
        validate(sys.argv[1], sys.argv[2])
        print('BACKUP_RECORD_VALIDATED=PASS restore_claim=OPERATOR_ATTESTED')
    except Exception as exc:
        reason = str(exc) if isinstance(exc, ValueError) and str(exc).isupper() and str(exc).replace('_', '').isalnum() else 'BACKUP_RECORD_UNREADABLE'
        print('UPDATE_BLOCKED stage=BACKUP reasonCode=' + reason, file=sys.stderr)
        raise SystemExit(1)
