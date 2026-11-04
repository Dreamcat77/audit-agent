"""Assemble a flat, versioned offline update; never read deployment secrets."""
from pathlib import Path
import argparse, ast, hashlib, json, re, shutil, subprocess, tarfile, zipfile

ROOT=Path(__file__).resolve().parents[1]

def split_sql(source, delimiter=';'):
    source=re.sub(r'(?m)^\s*--[^\n]*', '', source)
    parts=[]; start=0; depth=0; quote=False; i=0
    while i<len(source):
        ch=source[i]
        if ch=="'":
            if quote and i+1<len(source) and source[i+1]=="'": i+=2; continue
            quote=not quote
        if not quote:
            if ch=='(': depth+=1
            if ch==')': depth-=1
            if ch==delimiter and (delimiter==';' or depth==0):
                parts.append(source[start:i].strip()); start=i+1
        i+=1
    assert not quote and depth==0, 'unbalanced SQL'
    if source[start:].strip(): parts.append(source[start:].strip())
    return parts

def schema_manifest():
    tables={}; statements={}
    def column(clause, prior=None):
        m=re.fullmatch(r'"?([A-Z][A-Z0-9_]*)"?\s+(VARCHAR\((\d+)\)|DECIMAL\((\d+),(\d+)\)|INTEGER|INT|SMALLINT|BIGINT|TIMESTAMP|DATE|CLOB)(.*)',clause,re.S)
        assert m, 'unsupported column '+clause
        name,kind,width,precision,scale,tail=m.groups(); result=dict(prior or {})
        result.update(name=name,type='VARCHAR' if width else 'DECIMAL' if precision else 'INT' if kind=='INTEGER' else kind)
        if width: result['length']=int(width)
        if precision: result.update(precision=int(precision),scale=int(scale))
        if 'NOT NULL' in tail: result['nullable']=False
        elif re.search(r'\bNULL\b',tail): result['nullable']=True
        else: result.setdefault('nullable',True)
        default=re.search(r"\bDEFAULT\s+('(?:[^']|'')*'|CURRENT_TIMESTAMP|\d+)",tail)
        if default: result['default']=default.group(1)
        else: result.setdefault('default','')
        return result
    def key(clause):
        m=re.fullmatch(r'CONSTRAINT ([A-Z0-9_]+) (.*)',clause,re.S); assert m,clause
        name,body=m.groups()
        if body.startswith('CHECK '): return dict(name=name,kind='C',expression=body[body.index('(')+1:body.rindex(')')])
        body=re.sub(r'^NOT\s+CLUSTER\s+','',body)
        m=re.fullmatch(r'(PRIMARY KEY|UNIQUE|FOREIGN KEY)\s*\(([^)]+)\)(.*)',body,re.S);assert m,body
        kind,cols,tail=m.groups(); result=dict(name=name,kind={'PRIMARY KEY':'P','UNIQUE':'U','FOREIGN KEY':'R'}[kind],columns=[v.strip().strip('"') for v in cols.split(',')])
        if kind=='FOREIGN KEY':
            parent=re.fullmatch(r'\s*REFERENCES ([A-Z0-9_]+)\s*\(([^)]+)\)\s*',tail,re.S);assert parent,tail
            result.update(parent=parent[1],parentColumns=[v.strip().strip('"') for v in parent[2].split(',')])
        return result
    files=['V003__f02_contract_review.sql','V004__contract_review_session.sql','V005__contract_review_session_soft_delete.sql','V009__review_finding_acknowledgement.sql','V010__project_intake_core.sql','V011__project_contract_association.sql','V017__project_file_parse_provenance.sql']
    files+=['CONTRACT__'+n+'.sql' for n in ('project_file_parse_result','project_file_role_assessment','contract_candidate_authority','project_file_identity_assessment','contract_candidate_support_authority','review_session_project_file_binding','contract_candidate_confirmation','review_run','review_run_results','sanitized_debug_bundle','review_ack_report','contract_final_closeout','contract_final_closeout_fact_extraction','contract_final_closeout_typed_clause','contract_fact_v2_audit','contract_model_call_owner_unique_fix','project_file_processing_job','contract_semantic_checkpoints')]
    files+=['CONTRACT__independent_analysis_case.sql','PROCUREMENT__automatic_case_v1.sql']
    for filename in files:
        path=ROOT/'db/dm8'/filename
        for raw in split_sql(path.read_text(encoding='utf-8')):
            sql=re.sub(r'\s+',' ',raw).strip()
            create=re.fullmatch(r'CREATE TABLE ([A-Z0-9_]+)\s*\((.*)\)',sql,re.S)
            if create:
                name,body=create.groups()
                if not name.startswith(('CONTRACT','PROJECT','REVIEW_SESSION_PROJECT','SANITIZED_DEBUG','AUDIT_CASE','AUDIT_FACT','AUDIT_QUOTE_LINE')):continue
                spec=dict(name=name,columns=[],constraints=[],indexes=[]);tables[name]=spec;statements[name]=[sql]
                for part in split_sql(body,','):
                    if part.startswith('CONSTRAINT '):spec['constraints'].append(key(part))
                    else:
                        spec['columns'].append(column(part))
                        if 'PRIMARY KEY' in part:
                            spec['constraints'].append(dict(name='PRIMARY_KEY',kind='P',columns=[part.split()[0]],matchByColumns=True))
                continue
            index=re.fullmatch(r'CREATE (UNIQUE )?INDEX ([A-Z0-9_]+) ON ([A-Z0-9_]+)\s*\((.*)\)',sql,re.S)
            if index:
                unique,name,owner,cols=index.groups()
                if owner in tables:
                    tables[owner]['indexes'].append(dict(name=name,uniqueness='UNIQUE' if unique else 'NONUNIQUE',columns=[p.split()[0].strip('"')+(':'+p.split()[1] if len(p.split())>1 else ':ASC') for p in cols.split(',')]))
                    statements[owner].append(sql)
                continue
            alter=re.fullmatch(r'ALTER TABLE ([A-Z0-9_]+) (.*)',sql,re.S)
            if alter and alter[1] in tables:
                spec=tables[alter[1]];body=alter[2]
                if body.startswith('ADD CONSTRAINT '):spec['constraints'].append(key(body[4:]))
                elif body.startswith('DROP CONSTRAINT '):
                    name=body.split()[2];spec['constraints']=[k for k in spec['constraints'] if k['name']!=name]
                elif body.startswith('ADD '):spec['columns'].append(column(body[4:]))
                elif body.startswith('MODIFY '):
                    name=body.split()[1].strip('"');old=next(c for c in spec['columns'] if c['name']==name);old.update(column(body[7:],old))
                else:raise AssertionError(sql)
            elif sql.startswith(('COMMENT ','INSERT ','UPDATE ','ALTER TABLE ')):pass
            elif sql:raise AssertionError('unsupported '+sql[:120])
    checks=[]
    for name in ('PROJECT_FILE','PROJECT_FILE_PARSE_RESULT'):
        spec=tables[name];constraint=next(k for k in spec['constraints'] if k['name']==('CK_PROJECT_FILE_PARSE_METHOD' if name=='PROJECT_FILE' else 'CK_PROJECT_FILE_PARSE_RESULT_METHOD'))
        old=constraint['expression'];new=old.replace("'OCR'","'OCR','DOC_TEXT'");constraint['oldExpression']=old;constraint['expression']=new
        checks.append(dict(table=name,name=constraint['name'],old=old,new=new))
    spec=tables['CONTRACT_MODEL_CALL_AUDIT'];constraint=next(k for k in spec['constraints'] if k['name']=='CK_CONTRACT_MODEL_CALL_FACT_SCOPE')
    old=constraint['expression'];new=old.replace("'FAILED'","'FAILED','UNKNOWN'");constraint['oldExpression']=old;constraint['expression']=new
    checks.append(dict(table=spec['name'],name=constraint['name'],old=old,new=new))
    # Upload-time automatic authority is an explicit, controlled schema migration.
    # Keep the old catalog expression acceptable during preflight, while planning
    # the three exact constraint replacements. Unrelated constraint drift remains
    # fail-closed in CustomerContractSchema.
    authority_sql=(ROOT/'db/dm8/CONTRACT__contract_system_auto_authority.sql').read_text(encoding='utf-8')
    for raw in split_sql(authority_sql):
        match=re.fullmatch(r'ALTER TABLE ([A-Z0-9_]+) ADD CONSTRAINT ([A-Z0-9_]+) CHECK \((.*)\)',
                           re.sub(r'\s+',' ',raw).strip(), re.S)
        if not match: continue
        table_name, constraint_name, new_expression=match.groups()
        target=next((table for table in tables.values() if table['name']==table_name), None)
        if target is None: raise AssertionError(f'authority migration target missing: {table_name}')
        constraint=next((key for key in target['constraints'] if key['name']==constraint_name), None)
        if constraint is None: raise AssertionError(f'authority migration constraint missing: {constraint_name}')
        old_expression=constraint['expression']
        constraint['oldExpression']=old_expression
        constraint['expression']=new_expression.strip()
        checks.append(dict(table=table_name,name=constraint_name,old=old_expression,new=new_expression.strip()))
    groups=[]
    for names in [('PROJECT_FILE_PROCESSING_JOB','PROJECT_FILE_PROCESSING_ATTEMPT'),('CONTRACT_SEMANTIC_PLAN','CONTRACT_SEMANTIC_UNIT'),('CONTRACT_ANALYSIS_CASE',),('AUDIT_CASE','AUDIT_CASE_FILE','AUDIT_FACT','AUDIT_QUOTE_LINE')]:
        group=dict(tables=[tables.pop(n) for n in names],statements=[s for n in names for s in statements[n]])
        if names[0] in ('CONTRACT_ANALYSIS_CASE','AUDIT_CASE'):
            for table in group['tables']:table['boundedCaseGrammar']=True
        groups.append(group)
    return dict(baseline=list(tables.values()),groups=groups,checks=checks)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('output',type=Path)
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--version',default='v10')
    parser.add_argument('--frontend-dist',type=Path,required=True,
                        help='freshly built and browser-verified static frontend')
    parser.add_argument('--backend-jar',type=Path,required=True,
                        help='explicit JDK17 artifact from the verified build; never silently use backend/target')
    parser.add_argument('--evidence',type=Path)
    parser.add_argument('--target-arch',choices=('x86_64','aarch64'),default='aarch64',
                        help='deployment architecture enforced by update.sh')
    args=parser.parse_args()
    if not re.fullmatch(r'v[0-9]+[a-z]?',args.version):parser.error('short version required')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    def copy(source,target):
        dest=output/target;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,dest)
    scripts=ROOT/'scripts/offline/customer-v10'
    for source in scripts.iterdir():
        if source.is_file() and source.name not in ('backup-check.py','CUSTOMER-RECOVERY.md'):
            copy(source,source.name if source.suffix!='.java' else 'tools/'+source.name)
    for name in ('BudgetUploadedBatchPlatformCheck','BudgetMaterialRiskPlatformCheck','MaterialTransferPlatformCheck','MaterialExtractionPlatformCheck','ProcurementReportSnapshotPlatformCheck','MaterialDeadlineMigration'):
        copy(ROOT/'tools'/f'{name}.java',f'tools/{name}.java')
    for name in ('ContractAnalysisSchema','ContractAnalysisSchemaTest'):
        copy(ROOT/'tools/contract-automation'/f'{name}.java',f'tools/{name}.java')
    for source in (ROOT/'db/dm8').glob('*.sql'):
        if source.name.startswith(('CONTRACT__','PROCUREMENT__automatic_case','V016__','V048__','V049__','V050__','V051__','V052__','V053__','V054__','V055__','V056__','V057__')):copy(source,'db/'+source.name)
    copy(args.backend_jar.resolve(),'payload/backend/audit-backend-0.1.0-SNAPSHOT.jar')
    shutil.copytree(args.frontend_dist.resolve(),output/'payload/frontend/dist')
    for source in (ROOT/'services/audit-ai/src').rglob('*.py'):
        ast.parse(source.read_text(encoding='utf-8'));copy(source,'payload/services/audit-ai/src/'+source.relative_to(ROOT/'services/audit-ai/src').as_posix())
    for name in ('pyproject.toml','requirements-runtime.txt'):
        copy(ROOT/'services/audit-ai'/name,'payload/services/audit-ai/'+name)
    model=ROOT/'.runtime/rotation-model/PP-LCNet_x1_0_doc_ori_infer'
    for name in ('inference.json','inference.pdiparams','inference.yml'):copy(model/name,'payload/services/audit-ai/models/PP-LCNet_x1_0_doc_ori/'+name)
    for name in ('scripts/collect-ai-diagnostics.sh','tools/ai-log-extract.py'):copy(ROOT/name,name)
    if args.evidence:copy(args.evidence.resolve(),'TEST-EVIDENCE.md')
    copy(scripts/'README.txt','README.txt')
    readme=output/'README.txt'
    readme.write_text(readme.read_text(encoding='utf-8').replace('AuditAgent v10','AuditAgent '+args.version).replace('Audit_Update_v10','Update_'+args.version),encoding='utf-8',newline='\n')
    (output/'schema.json').write_text(json.dumps(schema_manifest(),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    diff=subprocess.check_output(['git','diff','HEAD'],cwd=ROOT)
    dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT).strip())
    payload_roots=['backend/src','services/audit-ai/src','frontend/src','db/dm8','scripts/offline/customer-v10','tools/build-customer-v10.py']
    payload_dirty=bool(subprocess.check_output(['git','status','--porcelain','--',*payload_roots],cwd=ROOT).strip())
    source_files=[]
    for folder in ('backend/src','services/audit-ai/src','frontend/src','db/dm8'):
        source_files.extend(p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    (output/'source.sha256').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(ROOT).as_posix()+'\n' for p in sorted(source_files)),encoding='utf-8',newline='\n')
    target_label = 'X86_64' if args.target_arch == 'x86_64' else 'AARCH64'
    (output/'VERSION.txt').write_text(f'PACKAGE_VERSION={args.version}\nAPP_COMMIT={commit}\nWORKING_TREE={"HAS_UNCOMMITTED_FILES" if dirty else "CLEAN"}\nPAYLOAD_SOURCE_STATE={"DIRTY_CANDIDATE" if payload_dirty else "CLEAN"}\nTRACKED_DIFF_SHA256={hashlib.sha256(diff).hexdigest()}\nTARGET=KYLIN_V10_SP3_2403_{target_label}_EXISTING_INSTALL\nCUSTOMER_E2E=NOT_VERIFIED\nCONTRACT_FORMAL_REVIEW=NOT_VERIFIED\n',encoding='utf-8')
    # Compile all shipped helpers to Java 17. The target machine needs only the existing JRE/JDK.
    runtime=output/'build-runtime';runtime.mkdir()
    with zipfile.ZipFile(output/'payload/backend/audit-backend-0.1.0-SNAPSHOT.jar') as jar:
        for entry in jar.namelist():
            if entry.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')):jar.extract(entry,runtime)
    jdk=Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin')
    classpath=str(runtime/'BOOT-INF/classes')+';'+str(runtime/'BOOT-INF/lib/*')
    sources=[str(p) for p in (output/'tools').glob('*.java')]
    subprocess.run([str(jdk/'javac.exe'),'--release','17','-encoding','UTF-8','-cp',classpath,'-d',str(output/'tools'),*sources],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',str(output/'tools')+';'+classpath,'CustomerContractSchemaTest',str(output/'schema.json')],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',str(output/'tools')+';'+classpath,'ContractAnalysisSchemaTest'],check=True)
    # Only generated build outputs in this new package directory are removed.
    assert runtime.parent==output and runtime.name=='build-runtime';shutil.rmtree(runtime)
    for p in output.rglob('*'):
        if p.is_file() and p.suffix in ('.sh','.java','.py','.sql','.md','.txt','.json'):
            data=p.read_text(encoding='utf-8');p.write_text(data,encoding='utf-8',newline='\n')
    if args.prepare_only:return
    seal(output)

def seal(output):
    files=sorted(p for p in output.rglob('*') if p.is_file() and p.name!='manifest.sha256')
    for p in files:
        assert not p.is_symlink(); assert p.name not in ('backend.env','audit-ai.env','.env')
        if p.suffix=='.class':assert int.from_bytes(p.read_bytes()[6:8],'big')==61
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    (output/'manifest.sha256').write_text(''.join(sha(p)+'  '+p.relative_to(output).as_posix()+'\n' for p in files),encoding='utf-8',newline='\n')
    archive=output.with_suffix('.tar.gz')
    assert not archive.exists(), 'preserve existing archive'
    with tarfile.open(archive,'w:gz') as tar:
        for p in sorted(output.rglob('*')):
            info=tar.gettarinfo(str(p),p.relative_to(output).as_posix());info.uid=info.gid=0;info.uname=info.gname='root';info.mode=0o755 if p.is_dir() or p.suffix=='.sh' else 0o644
            if p.is_dir():tar.addfile(info)
            else:
                with p.open('rb') as stream:tar.addfile(info,stream)
    archive.with_name(archive.name+'.sha256').write_text(sha(archive)+'  '+archive.name+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(dict(archive=str(archive),bytes=archive.stat().st_size,sha256=sha(archive),files=len(files)+1)))

if __name__=='__main__':main()
