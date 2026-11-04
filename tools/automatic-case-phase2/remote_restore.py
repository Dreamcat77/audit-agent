"""Restore only to a new named user in the dedicated isolated instance; fail on every conflict."""
import hashlib,json,os,subprocess,sys
from pathlib import Path
os.umask(0o077)
job=Path(sys.argv[1]).resolve()
assert str(job).startswith(str(Path.home()/"audit-agent-backups"/"auto-case-phase2-"))
state=json.loads((job/"isolated-instance.json").read_text())
status=json.loads((job/"consistent-backup-status.json").read_text())
assert status["native_exit"]==0
dump=job/"consistent-source-default.dmp"
assert hashlib.sha256(dump.read_bytes()).hexdigest()==status["sha256"]
source=(job/"source-schema.txt").read_text().strip()
env=dict(os.environ)
args=["/opt/dmdbms/bin/dimp","USERID=SYSDBA@127.0.0.1:"+str(state["port"]),
      "DIRECTORY="+str(job),"FILE="+dump.name,"LOG="+str(job/"independent-restore.log"),
      "SCHEMAS="+source,"REMAP_SCHEMA="+source+":"+state["restore_user"],
      "GRANTS=N","PARALLEL=1","TABLE_PARALLEL=1","IGNORE=N","DUMMY=N","CTRL_INFO=34"]
p=subprocess.run(args,input=(state["sysdba_password"]+"\n").encode(),env=env,
    stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
(job/"independent-restore.console.log").write_bytes(p.stdout.replace(state["sysdba_password"].encode(),b"<redacted>"))
print("DIMP_EXIT="+str(p.returncode),flush=True)
status["restore_native_exit"]=p.returncode
if p.returncode==0:
    env.update(AUDIT_DB_URL="jdbc:dm://127.0.0.1:"+str(state["port"]),
               AUDIT_DB_USERNAME=state["restore_user"],AUDIT_DB_PASSWORD=state["restore_password"],
               AUDIT_MANIFEST_SCHEMA=state["restore_user"])
    verify=subprocess.run(["/opt/java/current/bin/java","--class-path",
        "/home/admin/audit-agent-backups/auto-case-before-20261027/dm-driver.jar",
        str(job/"Dm8BackupManifest.java"),"read",str(job/"restore-manifest.tsv")],env=env,
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=100)
    print(verify.stdout.decode("utf-8","replace"),flush=True)
    status["restore_manifest_exit"]=verify.returncode
    if verify.returncode==0:
        before=(job/"source-manifest.tsv").read_bytes();after=(job/"restore-manifest.tsv").read_bytes()
        status["manifest_equal"]=before==after
        status["tables_verified"]=len(before.splitlines())
        status["restore"]="VERIFIED" if before==after else "MISMATCH"
        status["source_manifest_sha256"]=hashlib.sha256(before).hexdigest()
        status["restore_manifest_sha256"]=hashlib.sha256(after).hexdigest()
    else:print(verify.stderr.decode("utf-8","replace")[-600:])
else:status["restore"]="FAILED"
(job/"consistent-backup-status.json").write_text(json.dumps(status,indent=2))
print(json.dumps(status),flush=True)
