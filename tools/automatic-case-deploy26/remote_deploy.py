"""Install only separate pilot services. Never replace the original release or DB.

All credentials are loaded server-side; public output contains status and hashes.
"""
from pathlib import Path
import argparse
import hashlib
import ipaddress
import json
import os
import pwd
import secrets
import shlex
import socket
import subprocess
import tarfile
import time
import uuid
import zipfile

PYTHON = '/opt/python/venvs/audit-ai/bin/python'
JAVA = '/opt/java/current/bin/java'
RELEASE = Path('/opt/audit-agent/pilots/autocase-stage3-20261027')
DATA = Path('/var/lib/audit-autocase-pilot')
LOG = Path('/var/log/audit-autocase-pilot')
RUN = Path('/run/audit-autocase-pilot')
CONFIG = Path('/etc/audit-autocase-pilot')
UNITS = ['audit-autocase-pilot-dm', 'audit-autocase-pilot-ai', 'audit-autocase-pilot-backend', 'audit-autocase-pilot-web']


def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()


def execute(args, label, env=None, expected=0):
    p = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (LOG / (label + '.log')).write_bytes(p.stdout)
    if p.returncode != expected:
        raise RuntimeError(label + '_FAILED_EXIT_' + str(p.returncode))
    print(label + '=PASS', flush=True)
    return p.stdout.decode('utf-8', 'replace')


def load_env(paths):
    values = {}
    for path in paths:
        for line in Path(path).read_text().splitlines():
            line = line.strip()
            if not line or line.startswith('#'): continue
            if line.startswith('export '): line = line[7:]
            key, value = line.split('=', 1)
            values[key] = ' '.join(shlex.split(value, comments=False))
    return values


def save_env(path, values):
    def quote(v):
        v = str(v); assert '\n' not in v and '\r' not in v
        return '"' + v.replace('\\', '\\\\').replace('"', '\\"') + '"'
    with path.open('x') as f:
        f.write('\n'.join(k + '=' + quote(v) for k, v in values.items()) + '\n')
    path.chmod(0o600)


def write_unit(name, text):
    path = Path('/etc/systemd/system') / (name + '.service')
    with path.open('x') as f: f.write(text)
    path.chmod(0o644)


def unit(executable, workdir, after='', environment='', extra=''):
    return ('[Unit]\nDescription=Independent procurement pilot\nAfter=network.target ' + after + '\n'
            '[Service]\nType=simple\nUser=admin\nGroup=admin\nUMask=0077\nNoNewPrivileges=true\n'
            'WorkingDirectory=' + str(workdir) + '\n' + environment +
            'ExecStart=' + executable + '\nRestart=on-failure\nRestartSec=5\nTimeoutStopSec=45\n' + extra +
            '[Install]\nWantedBy=multi-user.target\n')


def original_unchanged(backup):
    baseline = json.loads((backup / 'original-manifest-private.json').read_text())
    assert Path('/opt/audit-agent/current').resolve() == Path(baseline['release'])
    for rel, item in baseline['files'].items():
        assert sha(Path('/') / rel) == item['sha256'], 'original deployment file changed: ' + rel
    return baseline


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('action', choices=['prepare', 'publish', 'status', 'restart', 'enable', 'rollback-check', 'frontend-update'])
    ap.add_argument('--package'); ap.add_argument('--sha256'); ap.add_argument('--backup', required=True)
    ap.add_argument('--isolated-job', required=True); ap.add_argument('--allow-cidr', required=True)
    ap.add_argument('--allow-client')
    args = ap.parse_args(); backup = Path(args.backup); job = Path(args.isolated_job)
    os.umask(0o077)
    assert Path('/opt/audit-agent/current').resolve().parent == Path('/opt/audit-agent/releases')
    assert job.parent == Path('/home/admin/audit-agent-backups') and job.name.startswith('auto-case-phase2-')
    state = json.loads((job / 'isolated-instance.json').read_text())
    assert state['port'] == 15287 and state['application_user'] == 'AUTOCASE_APP'
    assert Path(state['ini']) == job / 'instance/AUTOCASE2/dm.ini'
    network = str(ipaddress.ip_network(args.allow_cidr))
    assert ipaddress.ip_network(network).is_private and ipaddress.ip_network(network).prefixlen >= 24
    client_allow = ''
    if args.allow_client:
        client = ipaddress.ip_network(args.allow_client)
        assert client.version == 4 and client.is_private and client.prefixlen == 32
        client_allow = 'allow ' + str(client) + ';'
    original_unchanged(backup)
    owner = pwd.getpwnam('admin')
    if args.action == 'prepare':
        assert not RELEASE.exists(), 'do not overwrite an existing pilot release'
        package = Path(args.package); assert sha(package) == args.sha256
        for port in [19121, 19131, 18081, 15287]:
            with socket.socket() as sock: sock.bind(('127.0.0.1', port))
        RELEASE.mkdir(parents=True)
        # This root-run installer uses umask 077. Permit only the service group
        # to traverse the newly created pilot parent and release directories.
        for directory in [RELEASE.parent, RELEASE]:
            os.chown(directory, 0, owner.pw_gid)
            directory.chmod(0o750)
        with tarfile.open(package) as tar:
            for member in tar:
                path = (RELEASE / member.name).resolve()
                assert path.is_relative_to(RELEASE) and not member.issym() and not member.islnk()
                if member.isdir(): path.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as src, path.open('xb') as dst:
                        while chunk := src.read(1024 * 1024): dst.write(chunk)
                else: raise RuntimeError('unsupported package entry')
        manifest = json.loads((RELEASE / 'manifest.json').read_text())
        for item in manifest['files']:
            p = RELEASE / item['path']; assert sha(p) == item['sha256'] and p.stat().st_size == item['bytes']
        for path in [DATA, LOG, RUN, CONFIG]:
            path.mkdir(mode=0o700); os.chown(path, owner.pw_uid, owner.pw_gid)
        for name in ['storage', 'ai-checkpoints', 'ai-temp']:
            p = DATA / name; p.mkdir(); os.chown(p, owner.pw_uid, owner.pw_gid)
        runtime = RELEASE / 'tool-runtime'; runtime.mkdir()
        with zipfile.ZipFile(RELEASE / 'backend/audit-backend.jar') as z:
            for name in z.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    dst = runtime / 'lib' / Path(name).name
                elif name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                    dst = runtime / 'classes' / name[len('BOOT-INF/classes/'):]
                else: continue
                dst.parent.mkdir(parents=True, exist_ok=True); dst.write_bytes(z.read(name))
        cp = str(RELEASE / 'tools/classes') + ':' + str(runtime / 'classes') + ':' + str(runtime / 'lib/*')
        (CONFIG / 'classpath.txt').write_text(cp)
        # Snapshot original metadata using read-only JDBC before any pilot DDL.
        active = Path('/opt/audit-agent/current').resolve()
        source_env = dict(os.environ, **load_env(['/etc/audit-agent/backend.env', active / 'deploy/update-v10/backend.env']))
        execute([JAVA, '-cp', cp, 'MetadataSnapshot', str(backup / 'original-schema-before.tsv')], 'source-metadata-before', source_env)
        dm_env = dict(os.environ, AUDIT_DB_URL='jdbc:dm://127.0.0.1:15287', AUDIT_DB_USERNAME=state['application_user'],
                      AUDIT_DB_PASSWORD=state['application_password'], AUDIT_CASE_TEST_DB_CONFIRM='ISOLATED_AUTOCASE2')
        write_unit(UNITS[0], unit('/opt/dmdbms/bin/dmserver ' + state['ini'], job))
        execute(['systemctl', 'daemon-reload'], 'register-independent-dm')
        execute(['systemctl', 'start', UNITS[0]], 'start-independent-dm')
        deadline = time.monotonic() + 50
        while time.monotonic() < deadline:
            try:
                with socket.create_connection(('127.0.0.1', 15287), timeout=1): break
            except OSError: time.sleep(1)
        execute([JAVA, '-cp', cp, 'AutoCaseSchemaCheck', str(RELEASE / 'tools/PROCUREMENT__automatic_case_v1.sql')], 'four-table-existing-catalog', dm_env)
        schema = str(RELEASE / 'tools/pilot-support-schema.json')
        execute([JAVA, '-cp', cp, 'CustomerContractSchema', 'check', schema, str(DATA / 'support-migration')], 'support-preflight', dm_env)
        execute([JAVA, '-cp', cp, 'CustomerContractSchema', 'apply', schema, str(DATA / 'support-migration')], 'support-apply', dm_env)
        execute([JAVA, '-cp', cp, 'CustomerContractSchema', 'verify', schema, str(DATA / 'support-migration')], 'support-repeat-verify', dm_env)
        drift = json.loads(Path(schema).read_text()); drift['groups'][0]['tables'][0]['columns'][0]['length'] += 1
        drift_path = DATA / 'negative-expected-width.json'; drift_path.write_text(json.dumps(drift))
        execute([JAVA, '-cp', cp, 'CustomerContractSchema', 'verify', str(drift_path), str(DATA / 'unused-negative-journal')], 'real-catalog-drift-rejected', dm_env, expected=1)
        access = {}
        for prefix, username in [('PRIMARY', 'pilot_auditor'), ('SECONDARY', 'pilot_access_check')]:
            access[prefix.lower()] = {'id': str(uuid.uuid4()), 'username': username, 'password': 'Ap9_' + secrets.token_urlsafe(28)}
            for key, value in access[prefix.lower()].items(): dm_env['AUDIT_PILOT_' + prefix + '_' + key.upper()] = value
        access_path = backup / 'pilot-access-private.json'
        with access_path.open('x') as f: f.write(json.dumps(access, indent=2))
        access_path.chmod(0o600); os.chown(access_path, owner.pw_uid, owner.pw_gid)
        execute([JAVA, '-cp', cp, 'PilotAdmin', 'bootstrap'], 'real-user-and-f10-bootstrap', dm_env)
        backend = dict(load_env(['/etc/audit-agent/backend.env', active / 'deploy/update-v10/backend.env']))
        backend.update(AUDIT_DB_URL=dm_env['AUDIT_DB_URL'], AUDIT_DB_USERNAME=dm_env['AUDIT_DB_USERNAME'], AUDIT_DB_PASSWORD=dm_env['AUDIT_DB_PASSWORD'],
                       AUDIT_AI_BASE_URL='http://127.0.0.1:19121', AUDIT_OCR_BASE_URL='http://127.0.0.1:19121',
                       AUDIT_SESSION_REDIS_NAMESPACE='audit-autocase-pilot:session:v1', AUDIT_CACHE_KEY_PREFIX='audit-autocase-pilot:cache:',
                       AUDIT_SESSION_COOKIE_SECURE='false', AUDIT_CORS_ALLOWED_ORIGINS='', AUDIT_FILE_STORAGE_ROOT=str(DATA / 'storage'),
                       AUDIT_PROCUREMENT_MATERIAL_WORKER_ENABLED='false', AUDIT_BUDGET_MATERIAL_WORKER_ENABLED='false',
                       AUDIT_CONTRACT_REVIEW_WORKER_ENABLED='false',
                       AUDIT_CONTRACT_FILE_PROCESSING_WORKER_ENABLED='false', AUDIT_OBSERVABILITY_FLOW_DEBUG_ENABLED='false',
                       AUDIT_MULTIPART_MAX_FILE_SIZE='20MB', AUDIT_MULTIPART_MAX_REQUEST_SIZE='102MB', AUDIT_SESSION_TIMEOUT='30m')
        for key in list(backend):
            if key.startswith('AUDIT_LOCAL_ACCOUNT_'): del backend[key]
        save_env(CONFIG / 'backend.env', backend)
        ai = load_env(['/home/admin/audit_ai_runtime/audit-ai.env', active / 'deploy/update-v10/ai.env'])
        ai.update(AUDIT_CASE_MODEL_READ_TIMEOUT_SECONDS='150', AUDIT_CASE_OUTPUT_TOKENS='4096',
                  AUDIT_MATERIAL_CHECKPOINT_ROOT=str(DATA / 'ai-checkpoints'), AUDIT_AI_TEMP_ROOT=str(DATA / 'ai-temp'),
                  PYTHONUNBUFFERED='1', PYTHONDONTWRITEBYTECODE='1')
        save_env(CONFIG / 'ai.env', ai)
        write_unit(UNITS[1], unit(PYTHON + ' -m uvicorn audit_ai.main:app --app-dir ' + str(RELEASE / 'services/audit-ai/src') + ' --host 127.0.0.1 --port 19121 --workers 1',
                                 RELEASE / 'services/audit-ai', environment='EnvironmentFile=' + str(CONFIG / 'ai.env') + '\n'))
        write_unit(UNITS[2], unit(JAVA + ' -jar ' + str(RELEASE / 'backend/audit-backend.jar') + ' --spring.profiles.active=dev29 --server.address=127.0.0.1 --server.port=19131 --server.servlet.session.cookie.name=AUTOPROC_PILOT_SESSION --audit.procurement.auto-case.enabled=true',
                                 RELEASE / 'backend', after=UNITS[0]+'.service '+UNITS[1]+'.service', environment='EnvironmentFile=' + str(CONFIG / 'backend.env') + '\n'))
        nginx = '''pid __RUN__/nginx.pid;
error_log __LOG__/nginx-error.log warn;
events { worker_connections 256; }
http {
 include /etc/nginx/mime.types;
 default_type application/octet-stream;
 access_log __LOG__/nginx-access.log;
 client_body_temp_path __RUN__/client_body;
 proxy_temp_path __RUN__/proxy;
 fastcgi_temp_path __RUN__/fastcgi;
 uwsgi_temp_path __RUN__/uwsgi;
 scgi_temp_path __RUN__/scgi;
 limit_req_zone $binary_remote_addr zone=pilot_login:1m rate=10r/m;
 server {
  listen 18081;
  server_name _;
  allow 127.0.0.1;
  allow __NETWORK__;
  __CLIENT_ALLOW__
  deny all;
  root __ROOT__;
  index pilot.html;
  client_max_body_size 102m;
  add_header X-Frame-Options DENY always;
  add_header X-Content-Type-Options nosniff always;
  location = /api/v1/auth/login {
   limit_req zone=pilot_login burst=5 nodelay;
   proxy_pass http://127.0.0.1:19131;
   proxy_set_header Host $http_host;
   proxy_set_header X-Forwarded-Proto http;
   proxy_set_header X-Real-IP $remote_addr;
  }
  location ~ ^/api/v1/auth/(csrf|me|logout)$ {
   proxy_pass http://127.0.0.1:19131;
   proxy_set_header Host $http_host;
   proxy_set_header X-Forwarded-Proto http;
   proxy_set_header X-Real-IP $remote_addr;
  }
  location ~ ^/api/v1/procurement/cases(?:/|$) {
   proxy_pass http://127.0.0.1:19131;
   proxy_set_header Host $http_host;
   proxy_set_header X-Forwarded-Proto http;
   proxy_set_header X-Real-IP $remote_addr;
   proxy_read_timeout 190s;
   proxy_request_buffering on;
  }
  location /api/ { return 404; }
  location / { try_files $uri $uri/ /pilot.html; }
 }
}
'''.replace('__RUN__', str(RUN)).replace('__LOG__', str(LOG)).replace('__NETWORK__', network).replace('__CLIENT_ALLOW__', client_allow).replace('__ROOT__', str(RELEASE / 'frontend'))
        (CONFIG / 'nginx.conf').write_text(nginx); (CONFIG / 'nginx.conf').chmod(0o644)
        for name in ['client_body', 'proxy', 'fastcgi', 'uwsgi', 'scgi']:
            p = RUN / name; p.mkdir(); os.chown(p, owner.pw_uid, owner.pw_gid)
        write_unit(UNITS[3], unit('/usr/sbin/nginx -e ' + str(LOG / 'nginx-error.log') + ' -c ' + str(CONFIG / 'nginx.conf') + ' -g "daemon off;"', RELEASE,
                                 after=UNITS[2]+'.service', extra='RuntimeDirectory=audit-autocase-pilot\nRuntimeDirectoryMode=0700\n'))
        for p in RELEASE.rglob('*'):
            if not p.is_symlink(): p.chmod(0o755 if p.is_dir() else 0o644)
        execute(['/usr/sbin/nginx', '-t', '-c', str(CONFIG / 'nginx.conf')], 'independent-nginx-config')
        for name in ['nginx-access.log', 'nginx-error.log']:
            os.chown(LOG / name, owner.pw_uid, owner.pw_gid)
        execute([PYTHON, '-c', 'import sys;sys.path.insert(0,'+repr(str(RELEASE / 'services/audit-ai/src'))+');import audit_ai.model.case_review;print("CASE_AI_IMPORT_OK")'], 'installed-python-case-import')
        execute(['systemctl', 'daemon-reload'], 'register-independent-apps')
        execute(['systemctl', 'start', UNITS[1], UNITS[2]], 'start-independent-apps')
        summary = {'release': str(RELEASE), 'backup': str(backup), 'allowedCidr': network,
                   'profile': 'dev29', 'databaseInstance': 'AUTOCASE2', 'databaseSchema': 'AUTOCASE_APP',
                   'backendBind': '127.0.0.1:19131', 'aiBind': '127.0.0.1:19121', 'webPort': 18081,
                   'webPublished': False, 'productionHarnessUsed': False, 'originalDatabaseDdlOrDml': False,
                   'packageSha256': args.sha256, 'sourceSchemaSha256Before': sha(backup / 'original-schema-before.tsv')}
        (backup / 'pilot-deployment-summary.json').write_text(json.dumps(summary, indent=2))
        os.chown(backup / 'pilot-deployment-summary.json', owner.pw_uid, owner.pw_gid)
        print(json.dumps(summary), flush=True)
    elif args.action == 'frontend-update':
        package = Path(args.package); assert sha(package) == args.sha256
        target = RELEASE / 'frontend-http-v2'
        assert not target.exists(), 'preserve prior frontend overlays'
        with tarfile.open(package) as tar:
            manifest = json.load(tar.extractfile('manifest.json'))
            for item in manifest['files']:
                content = tar.extractfile(item['path']).read()
                assert len(content) == item['bytes'] and hashlib.sha256(content).hexdigest() == item['sha256']
                if not item['path'].startswith('frontend/'): continue
                destination = target / item['path'][len('frontend/'):]
                assert destination.resolve().is_relative_to(target)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(content)
        for path in [target] + list(target.rglob('*')):
            os.chown(path, 0, owner.pw_gid); path.chmod(0o750 if path.is_dir() else 0o640)
        conf = CONFIG / 'nginx.conf'; current = conf.read_text()
        assert 'root ' + str(RELEASE / 'frontend') + ';' in current
        (backup / 'pilot-nginx-before-http-overlay.conf').write_text(current)
        conf.write_text(current.replace('root ' + str(RELEASE / 'frontend') + ';', 'root ' + str(target) + ';'))
        execute(['/usr/sbin/nginx', '-t', '-e', str(LOG / 'nginx-error.log'), '-c', str(conf)], 'http-overlay-config')
        execute(['systemctl', 'restart', UNITS[3]], 'http-overlay-web-restart')
        (backup / 'pilot-frontend-overlay.json').write_text(json.dumps({'packageSha256': args.sha256, 'frontend': str(target)}, indent=2))
    elif args.action == 'publish':
        execute(['systemctl', 'start', UNITS[3]], 'publish-independent-lan-web')
    elif args.action == 'restart':
        execute(['systemctl', 'restart', UNITS[2]], 'restart-pilot-backend')
    elif args.action == 'enable':
        execute(['systemctl', 'enable'] + UNITS, 'enable-independent-pilot-services')
    elif args.action == 'rollback-check':
        execute(['systemctl', 'stop', UNITS[3], UNITS[2], UNITS[1]], 'rollback-stop-new-apps')
        original_unchanged(backup)
        execute(['systemctl', 'start', UNITS[1], UNITS[2]], 'rollback-reopen-new-apps')
    states = {}
    for name in UNITS + ['audit-backend', 'audit-ai', 'nginx', 'DmServiceAUDITDB']:
        p = subprocess.run(['systemctl', 'show', name, '--property=ActiveState,MainPID'], capture_output=True, text=True)
        states[name] = p.stdout.strip().replace('\n', ';')
    print(json.dumps({'services': states, 'originalReleaseUnchanged': True}), flush=True)


if __name__ == '__main__':
    main()
