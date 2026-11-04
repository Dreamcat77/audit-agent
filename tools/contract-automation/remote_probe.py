"""Read-only environment discovery. Credentials arrive over stdin and are never persisted or printed."""
import json
from pathlib import Path
import shlex
import sys
import paramiko

root=Path(__file__).resolve().parents[2]
config=json.loads(sys.stdin.readline())
ssh=paramiko.SSHClient()
ssh.load_system_host_keys()
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
try:
    ssh.connect(config["host"],username=config["username"],password=config.pop("password"),timeout=12,auth_timeout=12,banner_timeout=12)
    remote=(root/"tools/automatic-case-phase2/remote-job-location.txt").read_text().strip()
    if not remote.startswith("/home/admin/audit-agent-backups/auto-case-phase2-"):raise RuntimeError("unexpected isolated root")
    code="""import json,os,subprocess
from pathlib import Path
root=Path(ROOT)
state=json.loads((root/'isolated-instance.json').read_text())
assert state['port']==15287 and str(root/'instance/AUTOCASE2/dm.ini')==state['ini']
def run(args):
 p=subprocess.run(args,capture_output=True,text=True,timeout=15)
 return {'exit':p.returncode,'output':p.stdout.strip()}
print(json.dumps({'machine':run(['uname','-m']),'release':run(['readlink','-f','/opt/audit-agent/current']),
'backend':run(['systemctl','is-active','audit-backend.service']),'ai':run(['systemctl','is-active','audit-ai.service']),
'listeners':run(['ss','-ltn']),
'isolatedInstance':'AUTOCASE2','isolatedIniExists':Path(state['ini']).exists(),'originalServicesModified':False}))
""".replace("ROOT",repr(remote))
    stdin,stdout,stderr=ssh.exec_command("python3 -c "+shlex.quote(code),timeout=30)
    output=stdout.read().decode();status=stdout.channel.recv_exit_status()
    if status:raise RuntimeError("remote read-only probe failed")
    print(output.strip(),flush=True)
finally:
    ssh.close()
