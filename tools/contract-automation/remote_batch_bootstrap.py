"""Fresh isolated schema bootstrap in one JDBC session, with per-statement receipts.

The earlier one-process-per-DDL experiment is preserved; it is not repaired or replayed.
No connection to the original application instance is permitted.
"""
import json, os, secrets, subprocess, sys
from pathlib import Path

root=Path(sys.argv[1]).resolve()
assert str(root).startswith(str(Path.home()/'audit-agent-backups'/'auto-case-phase2-'))
instance=json.loads((root/'isolated-instance.json').read_text())
assert instance['port']==15287 and instance['ini']==str(root/'instance/AUTOCASE2/dm.ini')
tools=root/'contract-schema';plan=json.loads((tools/'base-plan.json').read_text())
statefile=tools/'contract-isolated-batch.json';app='CONTRACT_CASE_QA'
def sql(user,password,statements):
    env=dict(os.environ,AUDIT_DB_URL='jdbc:dm://127.0.0.1:15287',AUDIT_DB_USERNAME=user,AUDIT_DB_PASSWORD=password)
    result=subprocess.run(['java','-cp',str(tools)+':'+str(tools/'dmjdbc.jar'),'IsolatedSql'],input='\n'.join(' '.join(s.splitlines()) for s in statements)+'\n',capture_output=True,text=True,env=env,timeout=120)
    if result.returncode:raise RuntimeError('isolated SQL failed; '+result.stdout.strip())
    return result.stdout
def save(state):
    temporary=statefile.with_suffix('.tmp');temporary.write_text(json.dumps(state));os.chmod(temporary,0o600);temporary.replace(statefile)
if not statefile.exists():
    assert sql('SYSDBA',instance['sysdba_password'],["SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME='CONTRACT_CASE_QA'"]).strip()=='0','unowned schema exists'
    state={'application_user':app,'application_password':'Cq9_'+secrets.token_hex(16),'port':15287,'ack':0,'planDigest':plan['digest']}
    save(state)
    sql('SYSDBA',instance['sysdba_password'],['CREATE USER '+app+' IDENTIFIED BY "'+state['application_password']+'"','GRANT RESOURCE, PUBLIC TO '+app])
state=json.loads(statefile.read_text());assert state['port']==15287 and state['application_user']==app and state['planDigest']==plan['digest']
assert state['ack'] in [0,len(plan['statements'])],'partial bootstrap: inspect receipts; never replay acknowledged DDL'
if state['ack']==0:
    assert sql(app,state['application_password'],['SELECT COUNT(*) FROM USER_TABLES']).strip()=='0','partial schema must be inspected'
    statements=plan['statements']
    assert all(s.startswith(('CREATE TABLE ','CREATE INDEX ','CREATE UNIQUE INDEX ','ALTER TABLE ','COMMENT ON ')) for s in statements)
    env=dict(os.environ,AUDIT_DB_URL='jdbc:dm://127.0.0.1:15287',AUDIT_DB_USERNAME=app,AUDIT_DB_PASSWORD=state['application_password'])
    process=subprocess.Popen(['java','-cp',str(tools)+':'+str(tools/'dmjdbc.jar'),'IsolatedSql'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
    # Output is tiny compared with OS pipe buffers, but feed from a thread to
    # avoid stdin/stdout deadlock on larger migration inventories.
    import threading
    def feed():
        try:process.stdin.write('\n'.join(' '.join(s.splitlines()) for s in statements)+'\n');process.stdin.close()
        except BrokenPipeError:pass
    threading.Thread(target=feed,daemon=True).start()
    for line in process.stdout:
        if not line.startswith('SQL_OK '):raise RuntimeError('batch SQL failed; inspect protected receipt index='+str(state['ack']))
        state['ack']+=1;save(state)
        if state['ack']%50==0 or state['ack']==len(statements):print('ISOLATED_BASE_DDL_ACK='+str(state['ack']),flush=True)
    if process.wait(timeout=60)!=0 or state['ack']!=len(statements):raise RuntimeError('batch incomplete; do not replay')
env=dict(os.environ,AUDIT_DB_URL='jdbc:dm://127.0.0.1:15287',AUDIT_DB_USERNAME=app,AUDIT_DB_PASSWORD=state['application_password'])
for mode in ['check','apply','check','apply']:
    result=subprocess.run(['java','-cp',str(tools)+':'+str(tools/'dmjdbc.jar'),'ContractAnalysisSchema',mode,str(tools/'analysis.sql')],env=env,capture_output=True,text=True,timeout=60)
    print(result.stdout.strip(),flush=True)
    if result.returncode:raise RuntimeError(result.stderr.strip())
print(json.dumps({'isolatedDatabase':'AUTOCASE2','dedicatedSchema':app,'baseStatements':state['ack'],'originalDatabaseModified':False,'newCaseTableVerified':True}))
