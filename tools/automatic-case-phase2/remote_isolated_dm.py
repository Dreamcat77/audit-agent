"""Run on the user-confirmed test host; creates only a new isolated DM instance.

No installation, existing service changes, existing-account grants or destructive cleanup.
Credentials live in a mode-0600 file inside the mode-0700 experiment directory.
"""
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys

job=Path(sys.argv[1]).resolve()
assert str(job).startswith(str(Path.home()/"audit-agent-backups"/"auto-case-phase2-"))
os.umask(0o077)
state=job/"isolated-instance.json"
if state.exists():
    raise SystemExit("Instance already initialized; inspect and reopen, do not overwrite")
port=15287
with socket.socket() as probe:
    probe.bind(("127.0.0.1",port))
password="Aa9_"+secrets.token_hex(18)
auditor="Aa9_"+secrets.token_hex(18)
args=["/opt/dmdbms/bin/dminit","PATH="+str(job/"instance"),"DB_NAME=AUTOCASE2",
      "INSTANCE_NAME=AUTOCASE2","PORT_NUM="+str(port),"CHARSET=1","PAGE_SIZE=32",
      "LOG_SIZE=256","BUFFER=256","AUTO_ADJ_PARA=0","AUTO_OVERWRITE=0",
      "SYSDBA_PWD="+password,"SYSAUDITOR_PWD="+auditor,"ELOG_PATH="+str(job)]
p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
log=p.stdout.replace(password.encode(),b"<redacted>").replace(auditor.encode(),b"<redacted>")
(job/"isolated-init.log").write_bytes(log)
print("INIT_EXIT="+str(p.returncode))
if p.returncode:raise SystemExit("Initialization failed; keep evidence and do not recreate")
ini=job/"instance/AUTOCASE2/dm.ini"
text=ini.read_text()
# Change only this new instance; never touch the existing database configuration.
for key,value in {"LISTEN_IP":"127.0.0.1","MAX_OS_MEMORY":"20","MEMORY_POOL":"128",
                  "MEMORY_TARGET":"1024","BUFFER":"256","SORT_BUF_SIZE":"8",
                  "HJ_BUF_GLOBAL_SIZE":"128","HJ_BUF_SIZE":"8"}.items():
    text,n=re.subn(r"(?m)^(\s*"+key+r"\s*=\s*)[^#\n]*",lambda m:m.group(1)+value+" ",text)
    if not n and key=="LISTEN_IP":text+="\nLISTEN_IP = 127.0.0.1\n"
ini.write_text(text)
with (job/"isolated-server.log").open("ab") as output:
    server=subprocess.Popen(["/opt/dmdbms/bin/dmserver",str(ini)],stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
state.write_text(json.dumps({"port":port,"pid":server.pid,"sysdba_password":password,"ini":str(ini),
    "application_user":"AUTOCASE_APP","application_password":"Aa9_"+secrets.token_hex(18),
    "restore_user":"AUTOCASE_RESTORE","restore_password":"Aa9_"+secrets.token_hex(18)},indent=2))
print("ISOLATED_INSTANCE_CREATED port="+str(port)+" pid="+str(server.pid))
