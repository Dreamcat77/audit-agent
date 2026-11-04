"""Inspect only the existing independent DM8 instance. SSH credentials are stdin-only."""
import json, shlex, sys
from pathlib import Path
import paramiko

root=Path(__file__).resolve().parents[2]
config=json.loads(sys.stdin.readline())
ssh=paramiko.SSHClient();ssh.load_system_host_keys();ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
try:
    ssh.connect(config['host'],username=config['username'],password=config.pop('password'),timeout=15)
    remote=(root/'tools/automatic-case-phase2/remote-job-location.txt').read_text().strip()
    if not remote.startswith('/home/admin/audit-agent-backups/auto-case-phase2-'):raise RuntimeError('isolated root unproven')
    with ssh.open_sftp() as sftp:
        sftp.put(str(root/'tools/automatic-case-phase3/remote_reopen.py'),remote+'/contract-reopen.py')
        for filename in ['IsolatedSql.class','dmjdbc.jar']:
            sftp.put(str(root/'.test-runtime/contract-schema'/filename),remote+'/contract-'+filename)
    script="""import json,os,subprocess,sys
from pathlib import Path
root=Path(ROOT)
result=subprocess.run(['python3',str(root/'contract-reopen.py'),str(root)],capture_output=True,text=True,timeout=20)
if result.returncode:raise SystemExit('isolated reopen failed')
print(result.stdout.strip())
tools=root/'contract-schema';tools.mkdir(exist_ok=True)
(root/'contract-IsolatedSql.class').replace(tools/'IsolatedSql.class')
(root/'contract-dmjdbc.jar').replace(tools/'dmjdbc.jar')
state=json.loads((root/'isolated-instance.json').read_text())
assert state['port']==15287
contract=tools/'contract-isolated.json'
if contract.exists():
 progress=json.loads(contract.read_text())
 print(json.dumps({'contractBaseAcknowledged':progress['ack'],'contractSchema':progress['application_user']}),flush=True)
env=dict(os.environ,AUDIT_DB_URL='jdbc:dm://127.0.0.1:15287',AUDIT_DB_USERNAME=state['application_user'],AUDIT_DB_PASSWORD=state['application_password'])
queries="SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME\\nSELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL\\n"
query=subprocess.run(['java','-cp',str(tools)+':'+str(tools/'dmjdbc.jar'),'IsolatedSql'],input=queries,capture_output=True,text=True,env=env,timeout=45)
print(query.stdout.strip())
if query.returncode:raise SystemExit('isolated catalog query failed')
""".replace('ROOT',repr(remote))
    _,stdout,stderr=ssh.exec_command('python3 -c '+shlex.quote(script),timeout=30)
    output=stdout.read().decode();status=stdout.channel.recv_exit_status()
    if status:raise RuntimeError('isolated probe failed')
    print(output.strip())
finally:
    ssh.close()
