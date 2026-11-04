"""Reopen only the existing phase-two independent instance; never signal original services."""
import json, os, subprocess, sys, time
from pathlib import Path
job=Path(sys.argv[1]).resolve()
assert str(job).startswith(str(Path.home()/"audit-agent-backups"/"auto-case-phase2-"))
state_file=job/"isolated-instance.json"
state=json.loads(state_file.read_text())
ini=job/"instance/AUTOCASE2/dm.ini"
assert ini.exists() and str(ini)==state["ini"] and state["port"]==15287
matching=[]
for proc in Path('/proc').iterdir():
    if not proc.name.isdigit():continue
    try:args=(proc/'cmdline').read_bytes().split(b'\0')
    except (OSError,PermissionError):continue
    if b'/opt/dmdbms/bin/dmserver' in args and str(ini).encode() in args:matching.append(int(proc.name))
if len(matching)>1:raise SystemExit('ambiguous isolated instance')
if matching:pid=matching[0];started=False
else:
    with (job/'phase3-isolated-server.log').open('ab') as log:
        process=subprocess.Popen(['/opt/dmdbms/bin/dmserver',str(ini)],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    pid=process.pid;started=True
state['pid']=pid;state_file.write_text(json.dumps(state,indent=2));os.chmod(state_file,0o600)
print(json.dumps({'independentInstance':'AUTOCASE2','pid':pid,'started':started,'originalServicesModified':False,'databaseMigrationExecuted':False}))
