"""Export only synthetic verification metadata, never raw reports or credentials."""
from pathlib import Path
import hashlib
import io
import json
import os
import pwd
import tarfile

ROOT = Path('/home/admin/audit-agent-backups/qa26-stage2-20261027')
TARGET = Path('/home/admin/qa26-public-evidence.tar.gz')
DENIED = {'password', 'token', 'cookie', 'csrf', 'jdbc', 'prompt', 'responsebody',
          'excerpt', 'text', 'segments', 'content', 'authorization'}

def validate(value):
    if isinstance(value, dict):
        assert not any(str(k).lower() in DENIED for k in value), 'PRIVATE_FIELD_IN_VERIFICATION'
        for v in value.values():
            validate(v)
    elif isinstance(value, list):
        for v in value:
            validate(v)

files = sorted((ROOT / 'results').glob('*-verification.json'))
files += [p for p in sorted((ROOT / 'results').glob('formats-*.json'))]
files += sorted(ROOT.glob('qa26-deployment-r[2345].json'))
assert files and all(p.is_file() and not p.is_symlink() for p in files)
manifest = []
os.umask(0o077)
with tarfile.open(TARGET, 'w:gz') as archive:
    for p in files:
        value = json.loads(p.read_text())
        if p.parent == ROOT:
            allowed = {'release', 'version', 'packageSha256', 'schema', 'instance', 'webPort',
                       'backendPort', 'aiPort', 'originalFilesUnchanged', 'originalDatabaseDdlOrDml',
                       'configurationUnchanged', 'ddl', 'historyDm8Probe', 'quiescence', 'webPublished'}
            value = {key: item for key, item in value.items() if key in allowed}
        validate(value)
        data = json.dumps(value, ensure_ascii=False, indent=2).encode('utf8')
        member = tarfile.TarInfo(p.name)
        member.size = len(data)
        member.mode = 0o600
        archive.addfile(member, io.BytesIO(data))
        manifest.append({'name': p.name, 'sha256': hashlib.sha256(data).hexdigest()})
    data = json.dumps(manifest, indent=2).encode('utf8')
    member = tarfile.TarInfo('manifest.json')
    member.size = len(data)
    member.mode = 0o600
    archive.addfile(member, io.BytesIO(data))
account = pwd.getpwnam('admin')
os.chown(TARGET, account.pw_uid, account.pw_gid)
os.chmod(TARGET, 0o600)
print(json.dumps({'verificationFiles': len(files), 'archiveSha256': hashlib.sha256(TARGET.read_bytes()).hexdigest(),
                  'rawReportsExported': False, 'credentialsExported': False}))
