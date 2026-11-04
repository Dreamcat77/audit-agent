"""Back up the existing deployment without stopping or changing any service.

Run as root. Sensitive configuration remains inside a private server-side archive.
Restore validation writes regular files only into a new backup directory.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import pwd
import subprocess
import tarfile


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def run(*args):
    p = subprocess.run(args, capture_output=True, text=True, check=True)
    return p.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('backup')
    args = parser.parse_args()
    target = Path(args.backup)
    if not target.is_absolute() or target.exists():
        raise SystemExit('new absolute backup directory required')
    os.umask(0o077)
    target.mkdir(parents=False)
    release = Path('/opt/audit-agent/current').resolve(strict=True)
    if release.parent != Path('/opt/audit-agent/releases'):
        raise SystemExit('unexpected active release; stop')
    roots = [release, Path('/etc/audit-agent'), Path('/etc/nginx'),
             Path('/etc/systemd/system/audit-backend.service'),
             Path('/etc/systemd/system/audit-ai.service'),
             Path('/home/admin/audit_ai_runtime/audit-ai.env')]
    for name in ['audit-backend.service.d', 'audit-ai.service.d']:
        p = Path('/etc/systemd/system') / name
        if p.exists():
            roots.append(p)
    files = {}
    links = {}
    for source in roots:
        if not source.exists():
            raise SystemExit('required deployment source missing: ' + str(source))
        candidates = [source] + (list(source.rglob('*')) if source.is_dir() else [])
        for p in candidates:
            rel = p.as_posix().lstrip('/')
            if p.is_symlink():
                links[rel] = os.readlink(p)
            elif p.is_file():
                files[rel] = {'sha256': sha(p), 'bytes': p.stat().st_size,
                              'mode': oct(p.stat().st_mode & 0o777),
                              'uid': p.stat().st_uid, 'gid': p.stat().st_gid}
    states = {}
    for service in ['audit-backend.service', 'audit-ai.service', 'nginx.service', 'DmServiceAUDITDB.service']:
        states[service] = run('systemctl', 'show', service, '--property=ActiveState,MainPID,ExecStart,EnvironmentFiles,User,WorkingDirectory')
    (target / 'service-start-state.txt').write_text(json.dumps(states, indent=2))
    (target / 'python-freeze.txt').write_text(run('/opt/python/venvs/audit-ai/bin/python', '-m', 'pip', 'freeze'))
    archive = target / 'original-deployment.tar'
    with tarfile.open(archive, 'w') as tar:
        for p in roots:
            tar.add(p, arcname=p.as_posix().lstrip('/'), recursive=True)
    restored = target / 'restore-check'
    restored.mkdir()
    checked = set()
    with tarfile.open(archive) as tar:
        for member in tar:
            if not member.isfile():
                continue
            path = (restored / member.name).resolve()
            if not path.is_relative_to(restored):
                raise SystemExit('archive traversal rejected')
            path.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as src, path.open('wb') as out:
                while chunk := src.read(1024 * 1024):
                    out.write(chunk)
            if member.name in files:
                if sha(path) != files[member.name]['sha256']:
                    raise SystemExit('restore content mismatch: ' + member.name)
                checked.add(member.name)
    if checked != set(files):
        raise SystemExit('backup omitted original files')
    for rel, value in files.items():
        if sha(Path('/') / rel) != value['sha256']:
            raise SystemExit('source changed during backup; keep archive as unverified')
    private = {'release': str(release), 'files': files, 'symlinks': links,
               'currentLink': os.readlink('/opt/audit-agent/current'), 'services': states}
    (target / 'original-manifest-private.json').write_text(json.dumps(private, indent=2))
    public = {'backup': str(target), 'originalRelease': str(release),
              'archive': archive.name, 'archiveSha256': sha(archive),
              'files': len(files), 'bytes': sum(x['bytes'] for x in files.values()),
              'regularFilesRestoredAndHashVerified': len(checked),
              'symlinksRecorded': len(links), 'sourceUnchangedDuringBackup': True,
              'originalServicesStopped': False, 'databaseDataBackupIncluded': False}
    (target / 'backup-summary.json').write_text(json.dumps(public, indent=2))
    owner = pwd.getpwnam('admin')
    for p in [target] + list(target.rglob('*')):
        if not p.is_symlink():
            os.chown(p, owner.pw_uid, owner.pw_gid)
    print(json.dumps(public))


if __name__ == '__main__':
    main()
