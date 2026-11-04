"""Update only the independent QA26 units; no schema/data migration or original service switch."""
import argparse
import hashlib
import json
import os
import pwd
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import time
import urllib.request

BACKUP=Path('/home/admin/audit-agent-backups/qa26-stage2-20261027')
CONFIG=Path('/etc/audit-qa26')
UNITS=['audit-qa26-ai','audit-qa26-backend','audit-qa26-web']
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1048576),b''):h.update(block)
    return h.hexdigest()
def original():
    manifest=json.loads((BACKUP/'original-manifest-private.json').read_text())
    assert str(Path('/opt/audit-agent/current').resolve())==manifest['release']
    for name,item in manifest['files'].items():assert sha(Path('/')/name)==item['sha256'],'ORIGINAL_FILE_CHANGED'
    return manifest
def env():
    values=dict(os.environ)
    for line in (CONFIG/'backend.env').read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):continue
        key,value=line.split('=',1);values[key]=' '.join(shlex.split(value,comments=False))
    assert values['AUDIT_DB_USERNAME']=='QA26_FULL2' and values['AUDIT_DB_URL']=='jdbc:dm://127.0.0.1:15287'
    return values
def run(args,label,environment=None):
    p=subprocess.run(args,capture_output=True,env=environment,timeout=180)
    (BACKUP/(label+'-private.log')).write_bytes(p.stdout+p.stderr)
    assert p.returncode==0,label+'_FAILED_INSPECT_PRIVATE_LOG'
    return p.stdout.decode('utf-8','replace')
def healthy(url,timeout=150):
    until=time.monotonic()+timeout
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while True:
        try:
            with opener.open(url,timeout=5) as r:
                data=r.read();assert r.status==200
                return data
        except Exception:
            if time.monotonic()>=until:raise RuntimeError('QA26_HEALTH_TIMEOUT') from None
            time.sleep(2)
def runtime_directory():
    # systemd removes RuntimeDirectory when the web service stops. nginx -t
    # opens the pid/temp paths before systemd has recreated it on next start.
    account=pwd.getpwnam('admin')
    root=Path('/run/audit-qa26')
    for path in [root]+[root/name for name in ['client_body','proxy','fastcgi','uwsgi','scgi']]:
        assert not path.is_symlink(),'QA26_RUNTIME_SYMLINK'
        path.mkdir(mode=0o700,exist_ok=True)
        assert path.is_dir(),'QA26_RUNTIME_NOT_DIRECTORY'
        os.chown(path,account.pw_uid,account.pw_gid);path.chmod(0o700)
def main():
    p=argparse.ArgumentParser();p.add_argument('archive');p.add_argument('sha256');p.add_argument('revision');args=p.parse_args()
    os.umask(0o077);assert os.geteuid()==0 and re.fullmatch(r'r[2-9][0-9]*',args.revision)
    original();configuration={p:sha(p) for p in [CONFIG/'backend.env',CONFIG/'ai.env']}
    previous=json.loads((BACKUP/'qa26-deployment.json').read_text());old=Path(previous['release'])
    assert str(old).startswith('/opt/audit-agent/pilots/qa26-20261027-')
    archive=Path(args.archive);assert sha(archive)==args.sha256
    new=Path('/opt/audit-agent/pilots/qa26-20261027-'+args.revision)
    assert old!=new,'QA26_ALREADY_RUNNING_REVISION'
    checkpoint=BACKUP/('qa26-'+args.revision+'-switch-private.json')
    retry=new.exists()
    if retry:
        assert checkpoint.is_file() and not new.is_symlink(),'QA26_UNPROVEN_RETRY'
        recovery=json.loads(checkpoint.read_text())
        assert recovery['old']==str(old) and recovery['new']==str(new),'QA26_RETRY_PATH_DRIFT'
    with tarfile.open(archive) as tar:
        manifest=json.load(tar.extractfile('manifest.json'));assert manifest['version']==new.name
        expected={x['path']:x for x in manifest['files']};assert len(expected)==len(manifest['files'])
        assert set(tar.getnames())==set(expected)|{'manifest.json'}
        if not retry:new.mkdir();new.chmod(0o755)
        for member in tar:
            target=(new/member.name).resolve();assert target.is_relative_to(new) and member.isfile() and not member.issym() and not member.islnk()
            if retry:
                assert not (new/member.name).is_symlink() and target.is_file(),'QA26_RETRY_FILE_MISSING'
                with tar.extractfile(member) as src:assert sha(target)==hashlib.file_digest(src,'sha256').hexdigest(),'QA26_RETRY_FILE_DRIFT'
            else:
                target.parent.mkdir(parents=True,exist_ok=True)
                with tar.extractfile(member) as src,target.open('xb') as dst:
                    while block:=src.read(1048576):dst.write(block)
            if member.name!='manifest.json':assert sha(target)==expected[member.name]['sha256']
        for path in new.rglob('*'):
            assert not path.is_symlink(),'QA26_RETRY_SYMLINK'
            if path.is_file():assert path.relative_to(new).as_posix() in expected or path.name=='manifest.json' or path.is_relative_to(new/'qa-tools'),'QA26_RETRY_UNLISTED_FILE'
            path.chmod(0o755 if path.is_dir() else 0o644)
    java='/opt/java/current/bin/java';javac='/opt/java/current/bin/javac'
    classes=new/'qa-tools';classes.mkdir(exist_ok=True)
    # The history probe uses new repository/mapper classes from the new executable JAR.
    import zipfile
    with zipfile.ZipFile(new/'backend/audit-backend.jar') as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                target=classes/name[len('BOOT-INF/classes/'):];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(jar.read(name))
            elif name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                target=classes/'lib'/Path(name).name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(jar.read(name))
    cp=str(classes)+':'+str(classes/'lib/*')
    run([javac,'--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),'/home/admin/Qa26Quiescence.java','/home/admin/Qa26HistoryPlatformProbe.java'],'compile-qa-guards')
    environment=env()
    guard=run([java,'-cp',cp,'Qa26Quiescence'],'quiescence-before-'+args.revision,environment)
    probe=run([java,'-cp',cp,'Qa26HistoryPlatformProbe'],'history-platform-'+args.revision,environment)
    files=[Path('/etc/systemd/system')/(unit+'.service') for unit in UNITS]+[CONFIG/'nginx.conf']
    saved={path:path.read_bytes() for path in files}
    recovery={'old':str(old),'new':str(new),'files':{str(p):v.hex() for p,v in saved.items()}}
    if retry:assert json.loads(checkpoint.read_text())==recovery,'QA26_RETRY_CONFIG_DRIFT'
    else:
        with checkpoint.open('x') as stream:json.dump(recovery,stream)
    switched=False
    try:
        run(['systemctl','stop',UNITS[2],UNITS[1],UNITS[0]],'stop-qa26-'+args.revision)
        # Recheck after workers stop; a task accepted in the guard/stop interval prevents switching.
        run([java,'-cp',cp,'Qa26Quiescence'],'quiescence-after-'+args.revision,environment)
        for path,content in saved.items():
            text=content.decode();assert str(old) in text
            path.write_text(text.replace(str(old),str(new)))
        runtime_directory()
        run(['/usr/sbin/nginx','-t','-e','/var/log/audit-qa26/nginx-error.log','-c',str(CONFIG/'nginx.conf')],'nginx-'+args.revision)
        run(['systemctl','daemon-reload'],'reload-qa26-'+args.revision)
        run(['systemctl','start',UNITS[0],UNITS[1]],'start-qa26-'+args.revision)
        healthy('http://127.0.0.1:19153/health');healthy('http://127.0.0.1:19151/api/v1/health')
        run(['systemctl','start',UNITS[2]],'start-web-'+args.revision)
        index=healthy('http://127.0.0.1:18082/')
        assert hashlib.sha256(index).hexdigest()==sha(new/'frontend/index.html')
        for asset in re.findall(r'(?:src|href)="(/assets/[^"?]+)"',index.decode()):
            data=healthy('http://127.0.0.1:18082'+asset)
            assert hashlib.sha256(data).hexdigest()==sha(new/'frontend'/asset.lstrip('/'))
        original();assert all(sha(p)==v for p,v in configuration.items())
        switched=True
        result=dict(previous,release=str(new),version=new.name,packageSha256=args.sha256,configurationUnchanged=True,
                    originalFilesUnchanged=True,ddl=False,historyDm8Probe='PASS',quiescence='PASS')
        (BACKUP/'qa26-deployment.json').write_text(json.dumps(result,indent=2))
        (BACKUP/('qa26-deployment-'+args.revision+'.json')).write_text(json.dumps(result,indent=2))
        print(guard.strip());print(probe.strip());print(json.dumps(result),flush=True)
    finally:
        if not switched:
            subprocess.run(['systemctl','stop']+UNITS,capture_output=True,timeout=180)
            for path,content in saved.items():path.write_bytes(content)
            run(['systemctl','daemon-reload'],'rollback-reload-'+args.revision)
            run(['systemctl','start']+UNITS,'rollback-start-'+args.revision)
            healthy('http://127.0.0.1:18082/api/v1/health')
            print('QA26_ROLLBACK=PASS ORIGINAL_SERVICES_UNCHANGED=true',flush=True)
if __name__=='__main__':main()
