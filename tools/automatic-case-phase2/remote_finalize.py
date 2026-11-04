"""Stop only the instance created for this acceptance run; preserve its data and all original services."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

job=Path(sys.argv[1]).resolve()
if not str(job).startswith(str(Path.home()/"audit-agent-backups"/"auto-case-phase2-")):
    raise SystemExit("Unexpected experiment directory")
state=json.loads((job/"isolated-instance.json").read_text())
pid=state["pid"]
cmdline=Path("/proc")/str(pid)/"cmdline"
if cmdline.exists():
    args=cmdline.read_bytes().split(b"\0")
    if str(job/"instance/AUTOCASE2/dm.ini").encode() not in args or b"/opt/dmdbms/bin/dmserver" not in args:
        raise SystemExit("PID identity does not match this run; refusing to signal")
    os.kill(pid,signal.SIGTERM)
    deadline=time.monotonic()+45
    while time.monotonic()<deadline and cmdline.exists():
        if not cmdline.read_bytes():break
        time.sleep(.5)
stopped=not cmdline.exists() or not cmdline.read_bytes()
services=subprocess.run(["systemctl","list-units","--type=service","--state=running","--no-legend","--no-pager"],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
active=[]
for line in services.stdout.decode("utf-8",errors="replace").splitlines():
    if "audit" in line.lower():active.append(line.split()[0])
result={"isolatedInstanceStopped":stopped,"dataDirectoryPreserved":(job/"instance/AUTOCASE2/dm.ini").exists(),"originalAuditServicesRunning":active,"originalServicesSignalled":False}
(job/"finalization.json").write_text(json.dumps(result,indent=2))
print(json.dumps(result))
if not stopped:raise SystemExit(2)
