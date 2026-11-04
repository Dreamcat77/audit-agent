"""Read-only final evidence collection. No business DML, secrets or service edits."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import subprocess
import sys

RELEASE = Path('/opt/audit-agent/pilots/autocase-stage3-20261027')
BACKUP = Path('/home/admin/audit-agent-backups/deploy26-stage3-20261027-1718')
JOB = Path('/home/admin/audit-agent-backups/auto-case-phase2-20261027-020913')
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def command(args, env=None):
    r = subprocess.run(args, env=env, capture_output=True, text=True, timeout=100)
    if r.returncode: raise RuntimeError('CHECK_FAILED_' + Path(args[0]).name)
    return r.stdout.strip()

def main():
    label, case_id = sys.argv[1:]
    assert label.isalnum() or label.replace('-', '').isalnum()
    spec = importlib.util.spec_from_file_location('deployment', Path('/home/admin/autocase-pilot-stage3-20261027/remote_deploy.py'))
    deploy = importlib.util.module_from_spec(spec);spec.loader.exec_module(deploy)
    baseline = deploy.original_unchanged(BACKUP)
    folder = BACKUP / ('final-' + label); folder.mkdir(mode=0o700)
    cp = str(BACKUP / 'schema-fix-v6') + ':' + str(RELEASE / 'tools/classes') + ':' + str(RELEASE / 'tool-runtime/classes') + ':' + str(RELEASE / 'tool-runtime/lib/*')
    state = json.loads((JOB / 'isolated-instance.json').read_text())
    assert state['port'] == 15287 and state['application_user'] == 'AUTOCASE_APP'
    dm = dict(os.environ, AUDIT_DB_URL='jdbc:dm://127.0.0.1:15287', AUDIT_DB_USERNAME=state['application_user'], AUDIT_DB_PASSWORD=state['application_password'])
    probe = json.loads(command([deploy.JAVA, '-cp', cp, 'PilotReadOnlyCheck', case_id], dm))
    (folder / 'pilot-database.json').write_text(json.dumps(probe, ensure_ascii=False, indent=2))
    active = Path('/opt/audit-agent/current').resolve()
    source = dict(os.environ, **deploy.load_env(['/etc/audit-agent/backend.env', active / 'deploy/update-v10/backend.env']))
    command([deploy.JAVA, '-cp', cp, 'MetadataSnapshot', str(folder / 'original-schema-after.tsv')], source)
    before, after = sha(BACKUP / 'original-schema-before.tsv'), sha(folder / 'original-schema-after.tsv')
    assert before == after, 'original schema changed; investigate'
    services = {}
    for unit in deploy.UNITS + ['audit-backend', 'audit-ai', 'nginx', 'DmServiceAUDITDB']:
        services[unit] = command(['systemctl', 'show', unit, '--property=Id,ActiveState,MainPID,UnitFileState'])
    summary = {'label': label, 'originalReleaseUnchanged': True, 'originalFilesVerified': len(baseline['files']),
               'sourceSchemaUnchanged': True, 'sourceSchemaSha256': before, 'services': services,
               'nginxConfigSha256': sha(Path('/etc/audit-autocase-pilot/nginx.conf')), 'pilot': probe}
    (folder / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps(summary, ensure_ascii=False))

if __name__ == '__main__': main()
