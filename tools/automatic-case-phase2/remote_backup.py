"""Consistent native backup: verified SHARE locks + ordinary dexp, no flashback changes."""
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import subprocess
import sys
import time

os.umask(0o077)
job=Path(sys.argv[1]).resolve()
assert str(job).startswith(str(Path.home()/"audit-agent-backups"/"auto-case-phase2-"))
env=dict(os.environ)
for path in ["/etc/audit-agent/backend.env","/opt/audit-agent/current/deploy/update-v10/backend.env"]:
    for line in Path(path).read_text().splitlines():
        line=line.strip()
        if not line or line.startswith("#") or "=" not in line:continue
        if line.startswith("export "):line=line[7:]
        key,value=line.split("=",1);env[key]=" ".join(shlex.split(value,comments=False))
assert re.match(r"jdbc:dm://(?:127\.0\.0\.1|localhost):",env["AUDIT_DB_URL"])
assert env["AUDIT_DB_USERNAME"].upper() not in ["SYSDBA","SYSAUDITOR"]
env["AUDIT_EXTRA_TABLE"]="SYSDBA.PROCUREMENT_DEMAND"
driver="/home/admin/audit-agent-backups/auto-case-before-20261027/dm-driver.jar"
holder=subprocess.Popen(["/opt/java/current/bin/java","--class-path",driver,str(job/"Dm8BackupManifest.java"),"lock",str(job/"source-manifest.tsv")],
    env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
status={"source":"user-confirmed test26 existing application schema","writes_to_source":False,"native_export":"NOT_EXECUTED","restore":"NOT_VERIFIED"}
try:
    deadline=time.monotonic()+100
    while True:
        selector=selectors.DefaultSelector();selector.register(holder.stdout,selectors.EVENT_READ)
        if not selector.select(max(0,deadline-time.monotonic())):raise RuntimeError("backup lock/manifest deadline")
        line=holder.stdout.readline()
        print(line.strip(),flush=True)
        if line.startswith("MANIFEST_READY"):break
        if not line:raise RuntimeError("source lock failed; no export")
    match=re.match(r"jdbc:dm://([^/?;]+)",env["AUDIT_DB_URL"])
    host=match.group(1)
    user=env["AUDIT_DB_USERNAME"]
    names=[line.split("\t")[0] for line in (job/"source-manifest.tsv").read_text().splitlines()]
    tables=",".join(name if "." in name else user+"."+name for name in names)
    args=["/opt/dmdbms/bin/dexp","USERID="+user+"@"+host,"TABLES=("+tables+")","DIRECTORY="+str(job),
          "FILE=consistent-with-dependency.dmp","LOG="+str(job/"consistent-export-dependency.log"),
          "WITH_UR=N","ROWS=Y","CONSTRAINTS=Y","INDEXES=Y","GRANTS=Y","TRIGGERS=Y",
          "PARALLEL=1","TABLE_PARALLEL=1","DUMMY=N"]
    exported=subprocess.run(args,input=(env["AUDIT_DB_PASSWORD"]+"\n").encode(),env=env,
        stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90)
    console=exported.stdout.replace(env["AUDIT_DB_PASSWORD"].encode(),b"<redacted>")
    (job/"consistent-export-dependency.console.log").write_bytes(console)
    status["native_exit"]=exported.returncode
    status["native_export"]="COMPLETED_PENDING_RESTORE" if exported.returncode==0 else "FAILED"
    dump=job/"consistent-with-dependency.dmp"
    if dump.exists():
        status["bytes"]=dump.stat().st_size;status["sha256"]=hashlib.sha256(dump.read_bytes()).hexdigest()
    (job/"source-schema.txt").write_text(user)
finally:
    try:holder.communicate("release\n",timeout=10)
    except Exception:holder.kill();holder.communicate()
    status["locks_released"]=True
    (job/"consistent-backup-status.json").write_text(json.dumps(status,indent=2))
    print(json.dumps(status),flush=True)
