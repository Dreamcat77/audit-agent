"""Prepare a fresh contract acceptance schema. Private connection details arrive on stdin."""
import hashlib,importlib.util,json,shlex,sys
from pathlib import Path
import paramiko

root=Path(__file__).resolve().parents[2]
module_spec=importlib.util.spec_from_file_location('customer_builder',root/'tools/build-customer-v10.py')
builder=importlib.util.module_from_spec(module_spec);module_spec.loader.exec_module(builder)
files=['V001__audit_task_baseline.sql','V002__local_account_auth_observability.sql','V003__f02_contract_review.sql','V004__contract_review_session.sql','V005__contract_review_session_soft_delete.sql','V009__review_finding_acknowledgement.sql','V010__project_intake_core.sql','V011__project_contract_association.sql','V017__project_file_parse_provenance.sql']
files+=['CONTRACT__'+name+'.sql' for name in ('project_file_parse_result','project_file_role_assessment','contract_candidate_authority','project_file_identity_assessment','contract_candidate_support_authority','review_session_project_file_binding','contract_candidate_confirmation','contract_system_auto_authority','review_run','review_run_results','sanitized_debug_bundle','review_ack_report','contract_final_closeout','contract_final_closeout_fact_extraction','contract_final_closeout_typed_clause','contract_fact_v2_audit','contract_model_call_owner_unique_fix','project_file_processing_job','contract_semantic_checkpoints','project_file_word97_method','contract_fact_batches')]
statements=[];inventory=[]
for name in files:
    raw=(root/'db/dm8'/name).read_bytes();inventory.append({'file':name,'sha256':hashlib.sha256(raw).hexdigest()})
    for statement in builder.split_sql(raw.decode('utf-8')):
        if statement.startswith(('INSERT ','UPDATE ','DELETE ','MERGE ')):continue
        if not statement.startswith(('CREATE TABLE ','CREATE INDEX ','CREATE UNIQUE INDEX ','ALTER TABLE ','COMMENT ON ')):raise RuntimeError('unapproved isolated DDL in '+name)
        statements.append(statement)
digest=hashlib.sha256(json.dumps(statements).encode()).hexdigest()
plan={'digest':digest,'inventory':inventory,'statements':statements}
local=root/'.test-runtime/contract-schema';(local/'base-plan.json').write_text(json.dumps(plan,ensure_ascii=False),encoding='utf-8')
config=json.loads(sys.stdin.readline());ssh=paramiko.SSHClient();ssh.load_system_host_keys();ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
try:
    ssh.connect(config['host'],username=config['username'],password=config.pop('password'),timeout=15)
    remote=(root/'tools/automatic-case-phase2/remote-job-location.txt').read_text().strip();assert remote.startswith('/home/admin/audit-agent-backups/auto-case-phase2-')
    with ssh.open_sftp() as sftp:
        for path in local.glob('ContractAnalysisSchema*.class'):sftp.put(str(path),remote+'/contract-schema/'+path.name)
        sftp.put(str(local/'base-plan.json'),remote+'/contract-schema/base-plan.json')
        sftp.put(str(root/'db/dm8/CONTRACT__independent_analysis_case.sql'),remote+'/contract-schema/analysis.sql')
        script='remote_batch_bootstrap.py' if '--batch' in sys.argv else 'remote_bootstrap.py'
        destination='batch-bootstrap.py' if '--batch' in sys.argv else 'bootstrap.py'
        sftp.put(str(root/'tools/contract-automation'/script),remote+'/contract-schema/'+destination)
    _,stdout,stderr=ssh.exec_command('python3 -u '+shlex.quote(remote+'/contract-schema/'+destination)+' '+shlex.quote(remote),timeout=300)
    for line in stdout:
        line=line.strip()
        if line.startswith('ISOLATED_BASE_DDL_ACK='):
            number=int(line.split('=')[1])
            if number%25==0 or number==len(statements):print(line,flush=True)
        else:print(line,flush=True)
    status=stdout.channel.recv_exit_status()
    if status:raise RuntimeError('isolated bootstrap failed: '+stderr.read().decode()[-1800:])
finally: ssh.close()
