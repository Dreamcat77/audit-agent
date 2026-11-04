"""Exercise shipped v11 schema helpers against the disposable DM8 instance only.

Never opens the original application's DB configuration. Credentials stay root-only.
Leave the isolated test schema in place for rediscovery; no DROP/DELETE repair.
"""
from pathlib import Path
import hashlib
import json
import os
import secrets
import subprocess

ROOT = Path('/home/admin/audit-agent-backups/auto-case-phase2-20261027-020913')
OUT = Path('/home/admin/audit-agent-backups/qa26-stage2-20261027/customer-v11-schema')
PACKAGE = Path('/home/admin/customer-v11-probe')
PYTHON = '/opt/python/venvs/audit-ai/bin/python'
JAVA = '/opt/java/current/bin/java'
os.umask(0o077)
OUT.mkdir(mode=0o700, exist_ok=True)
instance = json.loads((ROOT / 'isolated-instance.json').read_text())
assert instance['port'] == 15287 and instance['ini'] == str(ROOT / 'instance/AUTOCASE2/dm.ini')
URL = 'jdbc:dm://127.0.0.1:15287'
sqlcp = str(ROOT / 'contract-schema') + ':' + str(ROOT / 'contract-schema/dmjdbc.jar')

def environment(user, password):
    return dict(os.environ, AUDIT_DB_URL=URL, AUDIT_DB_USERNAME=user, AUDIT_DB_PASSWORD=password)

def sql(user, password, statement):
    p = subprocess.run([JAVA, '-cp', sqlcp, 'IsolatedSql'], input=statement + '\n',
                       env=environment(user, password), text=True, capture_output=True, timeout=60)
    assert p.returncode == 0, 'ISOLATED_SQL_FAILED_REVIEW_PRIVATE_LOG'
    return p.stdout.strip()

private = OUT / 'account-private.json'
if not private.exists():
    assert sql('SYSDBA', instance['sysdba_password'], "SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME='QA_UPDATE_V11'") == '0', 'UNOWNED_TEST_SCHEMA'
    state = {'user': 'QA_UPDATE_V11', 'password': 'Uv11_' + secrets.token_hex(20), 'pending': True}
    private.write_text(json.dumps(state)); private.chmod(0o600)
    sql('SYSDBA', instance['sysdba_password'], 'CREATE USER QA_UPDATE_V11 IDENTIFIED BY "' + state['password'] + '"')
    sql('SYSDBA', instance['sysdba_password'], 'GRANT RESOURCE,PUBLIC TO QA_UPDATE_V11')
    state['pending'] = False
    private.write_text(json.dumps(state))
state = json.loads(private.read_text())
assert not state['pending'], 'UNKNOWN_USER_CREATION_DO_NOT_REPLAY'
env = environment(state['user'], state['password'])

manifest = json.loads((PACKAGE / 'schema.json').read_text())
new = {'baseline': [], 'checks': [], 'groups': [g for g in manifest['groups'] if g['tables'][0]['name'] in {'CONTRACT_ANALYSIS_CASE', 'AUDIT_CASE'}]}
assert len(new['groups']) == 2
test_manifest = OUT / 'new-case-schema.json'
test_manifest.write_text(json.dumps(new))
runtime = OUT / 'runtime'
runtime.mkdir(exist_ok=True)
if not (runtime / 'BOOT-INF/lib').exists():
    subprocess.run(['/opt/java/current/bin/jar', 'xf', str(PACKAGE / 'payload/backend/audit-backend-0.1.0-SNAPSHOT.jar')], cwd=runtime, check=True, capture_output=True)
cp = str(PACKAGE / 'tools') + ':' + str(runtime / 'BOOT-INF/classes') + ':' + str(runtime / 'BOOT-INF/lib/*')

def helper(mode, label, manifest_path=test_manifest, connection_env=env, expected=0):
    p = subprocess.run([JAVA, '-cp', cp, 'CustomerContractSchema', mode, str(manifest_path), str(OUT / label)],
                       env=connection_env, capture_output=True, text=True, timeout=180)
    (OUT / (label + '.log')).write_text(p.stdout + p.stderr)
    assert p.returncode == expected, label + '_UNEXPECTED_EXIT_REVIEW_PRIVATE_LOG'
    return p.stdout + p.stderr

result = {'realDm8': True, 'independentInstance': True, 'originalBusinessDatabaseModified': False, 'failures': []}
tables = int(sql(state['user'], state['password'], 'SELECT COUNT(*) FROM USER_TABLES'))
if tables == 0:
    output = helper('check', 'first-check')
    assert 'plannedStatements=11' in output
    assert sql(state['user'], state['password'], 'SELECT COUNT(*) FROM USER_TABLES') == '0'
    helper('apply', 'first-apply')
    result['firstCreate'] = 'PASS'
else:
    assert tables == 5 and (OUT / 'first-apply/journal.txt').is_file(), 'PARTIAL_OR_UNOWNED_SCHEMA_DO_NOT_REPLAY'
    result['firstCreate'] = 'RECOVERED_FROM_JOURNAL'
assert helper('check', 'repeat-check').count('plannedStatements=0') == 1
assert helper('apply', 'repeat-apply').count('plannedStatements=0') == 1
assert not (OUT / 'repeat-apply').exists(), 'REPEATED_DDL_JOURNAL_CREATED'
helper('verify', 'verify-existing')
result['repeatNoDdl'] = 'PASS'
# Simulate code rollback after successful DDL: no changes to the database, then
# reopen the original successful migration journal with a new checker process.
helper('check', 'code-rollback-recheck')
result['ddlSurvivesCodeRollback'] = 'PASS'

# Drift tests alter expected metadata in a disposable manifest, not actual tables.
# The stricter condition and missing-column negatives must be rejected from real Catalog.
for label, change, reason in [
    ('changed-check', lambda t: t['constraints'][2].update(expression="ROW_VERSION>0"), 'CHECK_EXPRESSION_DRIFT'),
    ('wrong-column-type', lambda t: t['columns'][0].update(type='CLOB'), 'COLUMN_TYPE_DRIFT'),
    ('wrong-primary-key', lambda t: t['constraints'][0].update(columns=['OWNER_ID']), 'KEY_COLUMN_ORDER_DRIFT'),
]:
    negative = json.loads(json.dumps(new))
    change(negative['groups'][0]['tables'][0])
    path = OUT / (label + '.json'); path.write_text(json.dumps(negative))
    output = helper('check', label, manifest_path=path, expected=1)
    assert reason in output, label + '_WRONG_DIAGNOSTIC'
result['realCatalogDriftNegatives'] = 3
# A partial table inventory is unknown, not a license to replay the complete SQL.
negative = json.loads(json.dumps(new))
negative['groups'][1]['tables'][-1]['name'] = 'QA_V11_MISSING_TABLE'
path = OUT / 'partial-group.json'; path.write_text(json.dumps(negative))
assert 'PARTIAL_ADDITIVE_GROUP' in helper('check', 'partial-group', manifest_path=path, expected=1)
result['partialGroupRejected'] = True

# Actual Catalog drift in this disposable, empty schema. These deliberate test
# changes are never available in the updater and never touch a business schema.
pending = OUT / 'fault-injection-pending.json'
assert not pending.exists(), 'INTERRUPTED_FAULT_TEST_REQUIRES_INVESTIGATION'
for label, fault, repair, reason in [
    ('disabled-check', 'ALTER TABLE CONTRACT_ANALYSIS_CASE DISABLE CONSTRAINT CK_CONTRACT_ANALYSIS_VERSION',
     'ALTER TABLE CONTRACT_ANALYSIS_CASE ENABLE CONSTRAINT CK_CONTRACT_ANALYSIS_VERSION', 'CONSTRAINT_KIND_OR_STATUS_DRIFT'),
    ('actual-default', 'ALTER TABLE CONTRACT_ANALYSIS_CASE MODIFY ROW_VERSION DEFAULT 1',
     'ALTER TABLE CONTRACT_ANALYSIS_CASE MODIFY ROW_VERSION DEFAULT 0', 'COLUMN_DEFAULT_DRIFT'),
    ('actual-fk-order', 'ALTER TABLE AUDIT_FACT DROP CONSTRAINT FK_AUTO_FACT_FILE;\nALTER TABLE AUDIT_FACT ADD CONSTRAINT FK_AUTO_FACT_FILE FOREIGN KEY (FILE_ID,CASE_ID) REFERENCES AUDIT_CASE_FILE(CASE_ID,ID)',
     'ALTER TABLE AUDIT_FACT DROP CONSTRAINT FK_AUTO_FACT_FILE;\nALTER TABLE AUDIT_FACT ADD CONSTRAINT FK_AUTO_FACT_FILE FOREIGN KEY (CASE_ID,FILE_ID) REFERENCES AUDIT_CASE_FILE(CASE_ID,ID)', 'KEY_COLUMN_ORDER_DRIFT'),
]:
    pending.write_text(json.dumps({'test': label, 'scope': 'QA_UPDATE_V11_ON_AUTOCASE2_ONLY'}))
    sql(state['user'], state['password'], fault)
    try:
        assert reason in helper('check', label, expected=1), label + '_WRONG_DIAGNOSTIC'
    finally:
        sql(state['user'], state['password'], repair)
    pending.unlink()
    helper('verify', label + '-restored')
result['actualSchemaDriftNegatives'] = 3

qa = json.loads((ROOT / 'qa26-full2-private.json').read_text())
helper('check', 'existing-qa-full-manifest', manifest_path=PACKAGE / 'schema.json',
       connection_env=environment(qa['application_user'], qa['application_password']))
result['existingQaFullManifestReadOnly'] = 'PASS'
result['schemaSha256'] = hashlib.sha256((PACKAGE / 'schema.json').read_bytes()).hexdigest()
target = Path('/home/admin/audit-agent-backups/qa26-stage2-20261027/results/customer-v11-schema-verification.json')
target.write_text(json.dumps(result, indent=2))
print(json.dumps(result), flush=True)
