"""Dedicated contract acceptance schema inside AUTOCASE2; never connects to the original database."""
import hashlib,json,os,secrets,subprocess,sys
from pathlib import Path

root=Path(sys.argv[1]).resolve()
assert str(root).startswith(str(Path.home()/'audit-agent-backups'/'auto-case-phase2-'))
instance=json.loads((root/'isolated-instance.json').read_text())
assert instance['port']==15287 and instance['ini']==str(root/'instance/AUTOCASE2/dm.ini')
tools=root/'contract-schema';plan=json.loads((tools/'base-plan.json').read_text())
statefile=tools/'contract-isolated.json';app='CONTRACT_AUTO_APP'
def sql(user,password,statements):
    env=dict(os.environ,AUDIT_DB_URL='jdbc:dm://127.0.0.1:15287',AUDIT_DB_USERNAME=user,AUDIT_DB_PASSWORD=password)
    result=subprocess.run(['java','-cp',str(tools)+':'+str(tools/'dmjdbc.jar'),'IsolatedSql'],input='\n'.join(' '.join(s.splitlines()) for s in statements)+'\n',capture_output=True,text=True,env=env,timeout=120)
    if result.returncode:raise RuntimeError('isolated SQL failed; '+result.stdout.strip())
    return result.stdout
if not statefile.exists():
    found=sql('SYSDBA',instance['sysdba_password'],["SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME='CONTRACT_AUTO_APP'"]).strip()
    assert found=='0','unowned isolated contract schema exists'
    state={'application_user':app,'application_password':'Ca9_'+secrets.token_hex(16),'port':15287,'ack':0,'planDigest':plan['digest']}
    statefile.write_text(json.dumps(state));os.chmod(statefile,0o600)
state=json.loads(statefile.read_text());assert state['port']==15287 and state['application_user']==app and state['planDigest']==plan['digest']
found=sql('SYSDBA',instance['sysdba_password'],["SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME='CONTRACT_AUTO_APP'"]).strip()
if found=='0':
    assert state['ack']==0,'schema disappeared after acknowledged DDL'
    state['application_password']='Ca9_'+secrets.token_hex(16)
    statefile.write_text(json.dumps(state));os.chmod(statefile,0o600)
    sql('SYSDBA',instance['sysdba_password'],['CREATE USER '+app+' IDENTIFIED BY "'+state['application_password']+'"','GRANT RESOURCE, PUBLIC TO '+app])
assert state['ack'] in [0,len(plan['statements'])],'partially applied isolated bootstrap: inspect acknowledgements, never replay'
if state['ack']==0:
    owned=sql(app,state['application_password'],['SELECT COUNT(*) FROM USER_TABLES']).strip();assert owned=='0','partial base schema must be inspected'
    for index,statement in enumerate(plan['statements']):
        assert statement.startswith(('CREATE TABLE ','CREATE INDEX ','CREATE UNIQUE INDEX ','ALTER TABLE ','COMMENT ON '))
        sql(app,state['application_password'],[statement]);state['ack']=index+1;statefile.write_text(json.dumps(state));os.chmod(statefile,0o600)
        print('ISOLATED_BASE_DDL_ACK='+str(index+1),flush=True)
env=dict(os.environ,AUDIT_DB_URL='jdbc:dm://127.0.0.1:15287',AUDIT_DB_USERNAME=app,AUDIT_DB_PASSWORD=state['application_password'])
for mode in ['check','apply','check','apply']:
    result=subprocess.run(['java','-cp',str(tools)+':'+str(tools/'dmjdbc.jar'),'ContractAnalysisSchema',mode,str(tools/'analysis.sql')],env=env,capture_output=True,text=True,timeout=60)
    print(result.stdout.strip(),flush=True)
    if result.returncode:raise RuntimeError(result.stderr.strip())
print(json.dumps({'isolatedDatabase':'AUTOCASE2','dedicatedSchema':app,'baseStatements':state['ack'],'originalDatabaseModified':False,'newCaseTableVerified':True}))
