"""Capture the current server baseline; no DDL, application switch or business data export."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('snapshot')
    args=parser.parse_args()
    assert os.geteuid()==0
    os.umask(0o077)
    target=Path(args.snapshot)
    assert target.parent==Path('/home/admin/audit-agent-backups') and target.name.startswith('unified-material-')
    assert not target.exists(), 'CHECKPOINT_ALREADY_EXISTS'
    current=Path('/opt/audit-agent/current').resolve()
    assert current.parent==Path('/opt/audit-agent/releases')
    old=Path('/home/admin/audit-agent-backups/qa26-stage2-20261027')
    deployment=json.loads((old/'qa26-deployment.json').read_text())
    qa=Path(deployment['release'])
    assert qa.parent==Path('/opt/audit-agent/pilots') and qa.name.startswith('qa26-20261027-')
    candidates=[Path('/etc/audit-agent/backend.env'),Path('/home/admin/audit_ai_runtime/audit-ai.env'),
                Path('/etc/nginx/nginx.conf')]
    for directory in (current/'backend',current/'frontend'):
        candidates.extend(p for p in directory.rglob('*') if p.is_file())
    files={str(p).lstrip('/'):{'sha256':sha(p)} for p in candidates if p.is_file()}
    target.mkdir(mode=0o700)
    (target/'original-manifest-private.json').write_text(json.dumps({'release':str(current),'files':files}))
    shutil.copy2(old/'qa26-deployment.json',target/'qa26-deployment.json')
    with tarfile.open(target/'qa-release-and-configuration-private.tar.gz','w:gz') as archive:
        for p in sorted(qa.rglob('*')):
            if p.is_file() and 'qa-tools' not in p.parts:
                archive.add(p,arcname='release/'+p.relative_to(qa).as_posix(),recursive=False)
        for directory in (Path('/etc/audit-qa26'),):
            for p in directory.rglob('*'):
                if p.is_file():archive.add(p,arcname='configuration/'+p.name,recursive=False)
        for unit in ('audit-qa26-ai','audit-qa26-backend','audit-qa26-web'):
            p=Path('/etc/systemd/system')/(unit+'.service')
            archive.add(p,arcname='units/'+p.name,recursive=False)
    result={'checkpoint':target.name,'qaVersion':deployment['version'],'originalFileCount':len(files),
            'releaseBackupSha256':sha(target/'qa-release-and-configuration-private.tar.gz'),
            'businessDataExported':False,'ddl':False,'configurationValuesExported':False}
    (target/'checkpoint-public.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result),flush=True)

if __name__=='__main__':main()
