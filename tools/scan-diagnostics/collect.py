#!/usr/bin/env python3
"""Customer-side read-only diagnosis. Private inputs never enter the export directory."""
import argparse
from collections import Counter
from datetime import datetime,timedelta,timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parent
import sys
sys.path.insert(0,str(ROOT))
from report import processing_limits, build_findings, machine_stats
UUID=re.compile(r'(?:material-)?[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}\Z')
COUNTS=set('factCount callCount fileCount itemCount rowCount byteLimit textLimit responseBytes connectTimeoutMs readTimeoutMs nativeTextChars ocrTextChars emptyOcrPages empty_page_count count unitIndex unitCount priorCalls priorUsedMillis elapsedMillis part_index source_chars supplied_chars candidate_count verified_count excluded_count durationMs duration_ms inputBytes inputChars outputChars pageCount page_count page_index page_number segmentCount candidateCount evidenceRejectedCount httpStatus status_code http_status input_bytes output_bytes provider_content_chars prompt_tokens completion_tokens total_tokens item_index dbCode attemptNo sourceChars suppliedChars rotation rotation_degrees deskew_millidegrees orientation_candidates orientation_quality_milli classifier_confidence_milli ocr_page_count input_chars selected_rotation angle chars rejectedCitations rejected_count elapsed_ms native_chars ocr_chars'.split())
KEYS=set('operationId documentId materialId fileId projectId jobId parseResultId assessmentId caseId requestId traceId file_id project_id case_id job_id parse_result_id trace_id document_id material_id request_id call_id resourceId'.split())
TOKENS=set('aiStatus businessType parseEngine classification errorClass error_class rootCauseClass exception_type event stage outcome reasonCode safeReasonCode reason_code status parseMethod operation kind provider_finish_reason provider_error_kind json_error_kind reason'.split())
EVENT=re.compile(r'\b(?:PIPELINE_STAGE_(?:START|END)|DOCUMENT_[A-Z0-9_]+|FIELD_[A-Z0-9_]+|DB_[A-Z0-9_]+|TENDER_[A-Z0-9_]+|F10_[A-Z0-9_]+|PROJECT_FILE_[A-Z0-9_]+|CONTRACT_[A-Z0-9_]+|ROLE_[A-Z0-9_]+|FACT_[A-Z0-9_]+|OCR_[A-Z0-9_]+|MODEL_[A-Z0-9_]+|PROVIDER_[A-Z0-9_]+|MATERIAL_[A-Z0-9_]+|AUTO_CASE_[A-Z0-9_]+|PROCUREMENT_[A-Z0-9_]+|PDF_[A-Z0-9_]+)\b')
PAIR=re.compile(r'\b([A-Za-z_][A-Za-z0-9_]*)=([^\s,"}]+)')
SCHEMA_FIELDS=set('type value citations document text topic summary code level basis title explanation suggestion role profile complete facts clauses findings field locator rows rowKey excerpt'.split())
SCHEMA_ERRORS=set('missing string_too_long string_too_short literal_error extra_forbidden value_error model_type string_type list_type bool_type uuid_parsing int_parsing too_long too_short dict_type enum none_required'.split())

def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for b in iter(lambda:stream.read(1024*1024),b''):h.update(b)
    return h.hexdigest()

def run(args,timeout=30,**kwargs):
    try:return subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=timeout,**kwargs)
    except (OSError,subprocess.TimeoutExpired):return None

def load_env(pid=None,explicit=None,fallback=True):
    env={}
    if pid and not explicit:
        try:
            for item in Path('/proc/'+pid+'/environ').read_bytes().split(b'\0'):
                key,sep,value=item.partition(b'=')
                if sep:env[key.decode('utf-8','replace')]=value.decode('utf-8','replace')
        except OSError:pass
    for file in [Path(explicit)] if explicit else ([Path('/etc/audit-agent/backend.env')] if fallback else []):
        try:
            for line in file.read_text().splitlines():
                line=line.strip().removeprefix('export ')
                if not line or line.startswith('#'):continue
                key,sep,value=line.partition('=')
                if sep and re.fullmatch('[A-Z][A-Z0-9_]*',key):
                    parts=shlex.split(value,comments=False);env.setdefault(key,parts[0] if len(parts)==1 else value)
        except (OSError,ValueError):pass
    return env

def pid_for(unit):
    p=run(['systemctl','show',unit,'-p','MainPID','--value'])
    value=p.stdout.decode().strip() if p and p.returncode==0 else ''
    return value if value.isdigit() and value!='0' else None

def safe_event(entry,vocabulary):
    message=entry.get('MESSAGE','')
    if not isinstance(message,str):return None
    fields={}
    if message.startswith('{'):
        try:
            parsed=json.loads(message)
            if isinstance(parsed,dict):fields=parsed;message=fields.get('message','')
        except ValueError:pass
    if not isinstance(message,str):return None
    match=EVENT.search(message);values=dict(fields);values.update(PAIR.findall(message))
    if values.get('operation') == 'HTTP_REQUEST' or str(values.get('event','')).startswith(('HTTP_','AI_HTTP_')):
        return None
    event=match.group(0) if match else values.get('event')
    if event not in vocabulary or not EVENT.fullmatch(event or ''):
        if values.get('operation') not in {'CONTRACT_CASE_ANALYSIS','MATERIAL_FIELD_EXTRACTION','PROCUREMENT_MATERIAL_EXTRACTION','PROCUREMENT_AUTO'}:return None
        event='PROVIDER_DIAGNOSTIC'
    result={'event':event}
    for key,value in values.items():
        s=str(value)
        if key in COUNTS and re.fullmatch(r'-?\d{1,12}',s):result[key]=int(s)
        elif key in KEYS and UUID.fullmatch(s):result[key]=s
        elif key in TOKENS and s in vocabulary:result[key]=s
    for key,allowed in [('validation_error_fields',SCHEMA_FIELDS),('validation_error_types',SCHEMA_ERRORS)]:
        m=re.search(r'\b'+key+r'=([a-z_]+(?:,[a-z_]+)*)',message)
        if m:
            values=sorted(set(m.group(1).split(','))&allowed)
            if values:result[key]=values
    for key in ('category','field'):
        value=fields.get(key) or dict(PAIR.findall(message)).get(key)
        if value in {'facts','clauses','findings','budgetAmount','ceilingAmount','quoteAmount','purchaserName','supplierName','awardAmount'}:result[key]=value
    stamp=str(entry.get('__REALTIME_TIMESTAMP',''))
    if stamp.isdigit():
        try:result['timestampUtc']=datetime.fromtimestamp(int(stamp)/1e6,timezone.utc).isoformat()
        except (ValueError,OverflowError,OSError):pass
    return result

def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--day',default=datetime.now().strftime('%Y-%m-%d'));ap.add_argument('--limit',type=int,default=20)
    ap.add_argument('--model-probe',action='store_true');ap.add_argument('--env-file');ap.add_argument('--backend-unit',default='audit-backend.service');ap.add_argument('--ai-unit',default='audit-ai.service');args=ap.parse_args()
    day=datetime.strptime(args.day,'%Y-%m-%d');assert 1<=args.limit<=50
    if os.name!='posix' or os.geteuid()!=0:raise SystemExit('DIAG_STOP=RUN_WITH_SUDO')
    for unit in (args.backend_unit,args.ai_unit):
        if not re.fullmatch(r'[A-Za-z0-9_-]+\.service',unit):raise SystemExit('DIAG_STOP=INVALID_UNIT')
    os.umask(0o077);backend_pid=pid_for(args.backend_unit);ai_pid=pid_for(args.ai_unit)
    env=load_env(backend_pid,args.env_file);ai_env=load_env(ai_pid,fallback=False)
    if not all(env.get(k) for k in ('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD')):raise SystemExit('DIAG_STOP=DB_CONFIG_NOT_FOUND')
    if not env['AUDIT_DB_URL'].startswith('jdbc:dm:'):raise SystemExit('DIAG_STOP=DM8_REQUIRED')
    jar=None
    if backend_pid:
        try:
            argv=Path('/proc/'+backend_pid+'/cmdline').read_bytes().decode().split('\0');name=argv[argv.index('-jar')+1]
            jar=Path(name) if Path(name).is_absolute() else Path('/proc/'+backend_pid+'/cwd').resolve()/name
        except (OSError,ValueError,IndexError):pass
    if jar is None or not jar.is_file():raise SystemExit('DIAG_STOP=RUNNING_JAR_NOT_FOUND')
    java='/opt/java/current/bin/java';python='/opt/python/venvs/audit-ai/bin/python'
    secrets=[v for d in (env,ai_env) for k,v in d.items() if len(v)>=4 and k.upper().endswith(('PASSWORD','TOKEN','SECRET','API_KEY','ACCESS_KEY','AUTHORIZATION'))]
    vocabulary=set(json.loads((ROOT/'vocabulary.json').read_text()))
    output=Path.cwd()/('ScanDiag-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'.tar.gz')
    with tempfile.TemporaryDirectory(prefix='scan-diag-') as folder:
        temp=Path(folder);export=temp/'export';export.mkdir();private=temp/'private';private.mkdir();libs=temp/'lib';libs.mkdir()
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar') and re.search(r'(?:dmjdbc|jackson-(?:databind|core|annotations))',Path(name).name,re.I):
                    (libs/Path(name).name).write_bytes(z.read(name))
        child_env=dict(os.environ,**{k:v for k,v in env.items() if k.startswith('AUDIT_')})
        raw_inputs=private/'inputs.json';cp=str(ROOT)+os.pathsep+str(libs/'*')
        print('DIAG_STAGE=DATABASE_AND_TEXT_FEATURES',flush=True)
        p=run([java,'-Xmx512m','-cp',cp,'FieldDiag',args.day,str(args.limit),str(raw_inputs),str(args.model_probe).lower()],timeout=300,env=child_env)
        try:database=json.loads(p.stdout) if p else {'status':'DATABASE_INSPECTION_TIMEOUT'}
        except (ValueError,UnicodeError):database={'status':'DATABASE_INSPECTION_FAILED','exitCode':p.returncode}
        write(export/'database.json',database)
        inputs=json.loads(raw_inputs.read_text()) if raw_inputs.exists() else []
        pdf=[];storage=env.get('AUDIT_FILE_STORAGE_ROOT')
        if storage:
            base=Path(storage).resolve();seen=set()
            for doc in inputs:
                key=doc.get('storageKey','');fid=doc.get('fileId')
                if len(pdf)>=8:break
                if not re.fullmatch('[a-f0-9]{64}',key) or fid in seen:continue
                seen.add(fid);path=base/'objects'/key[:2]/(key+'.bin')
                try:
                    if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(base):
                        pdf.append({'fileId':fid,'status':'SOURCE_NOT_READABLE_IN_STORAGE'});continue
                    with path.open('rb') as stream:magic=stream.read(5)
                    if magic!=b'%PDF-':continue
                    if path.stat().st_size>500*1024*1024:pdf.append({'fileId':fid,'status':'PDF_SIZE_BOUND'});continue
                    print('DIAG_STAGE=PDF_NATIVE_SAMPLE fileId='+fid,flush=True)
                    p=run([python,'-I',str(ROOT/'probe.py'),'pdf',str(path)],timeout=60)
                    try:r=json.loads(p.stdout) if p else {'status':'PDF_INSPECTION_TIMEOUT'}
                    except (ValueError,UnicodeError):r={'status':'PDF_PROBE_FAILED'}
                    pdf.append({'fileId':fid,'caseId':doc.get('caseId'),'fileOrder':doc.get('fileOrder'),'storedBytesMatch':path.stat().st_size==doc.get('size'),**r})
                except OSError:pdf.append({'fileId':fid,'status':'FILE_UNREADABLE'})
        write(export/'pdf.json',{'files':pdf,'maxFiles':8,'maxPagesPerFile':512,'perFileTimeoutSeconds':60,'noOcrRerun':True,'storageAvailable':bool(storage)})
        model={'mode':'NOT_REQUESTED'}
        if args.model_probe and raw_inputs.exists():
            print('DIAG_STAGE=MODEL_PROBE_MAX_TWO_CALLS',flush=True)
            model_env=dict(ai_env);model_env['OMP_NUM_THREADS']='1';model_env['MKL_NUM_THREADS']='1'
            cwd=Path('/proc/'+ai_pid+'/cwd').resolve() if ai_pid else jar.parent.parent/'services/audit-ai'
            source=cwd/'src' if (cwd/'src/audit_ai').is_dir() else jar.parent.parent/'services/audit-ai/src'
            # -I ignores PYTHONPATH, so explicitly add only the active release source in this launcher.
            launcher="import sys,runpy;sys.path.insert(0,sys.argv.pop(1));script=sys.argv.pop(1);runpy.run_path(script,run_name='__main__')"
            p=run([python,'-I','-c',launcher,str(source),str(ROOT/'probe.py'),'model',str(raw_inputs)],timeout=390,env=model_env,cwd=cwd)
            try:model=json.loads(p.stdout) if p else {'status':'MODEL_PROBE_TOTAL_TIMEOUT'}
            except (ValueError,UnicodeError):model={'status':'MODEL_PROBE_FAILED'}
        write(export/'model.json',model)
        print('DIAG_STAGE=SANITIZED_JOURNAL',flush=True)
        totals=Counter();journal={}
        with (export/'events.jsonl').open('w',encoding='utf-8') as dest:
            for unit in (args.backend_unit,args.ai_unit):
                proc=subprocess.Popen(['journalctl','-u',unit,'--since',(day-timedelta(days=1)).strftime('%Y-%m-%d 00:00:00'),'--until',(day+timedelta(days=2)).strftime('%Y-%m-%d 00:00:00'),'-o','json','--no-pager','--reverse'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
                read=kept=0
                try:
                    for line in proc.stdout:
                        read+=1
                        if read>500000 or kept>=80000:break
                        if len(line)>2_000_000:continue
                        try:item=safe_event(json.loads(line),vocabulary)
                        except (ValueError,TypeError,AttributeError):continue
                        if item is None:continue
                        item['service']='BACKEND' if unit==args.backend_unit else 'AI'
                        dest.write(json.dumps(item,ensure_ascii=False)+'\n');kept+=1;totals[item['event']]+=1
                finally:
                    proc.stdout.close()
                    if proc.poll() is None:proc.terminate()
                    try:proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:proc.kill();proc.wait()
                journal[unit]={'read':read,'kept':kept,'limitReached':read>500000 or kept>=80000,'exitCode':proc.returncode,'status':'LIMIT_REACHED' if read>500000 or kept>=80000 else 'OK' if proc.returncode==0 else 'JOURNAL_UNAVAILABLE'}
        flags={k:env.get(k,'NOT_EXPLICIT') if env.get(k) in ('true','false') else 'NOT_EXPLICIT' for k in ('AUDIT_CONTRACT_ANALYSIS_ENABLED','AUDIT_CONTRACT_AUTO_INTAKE_ENABLED','AUDIT_CONTRACT_FILE_PROCESSING_WORKER_ENABLED','AUDIT_PROCUREMENT_AUTO_CASE_ENABLED')}
        index=jar.parent.parent/'frontend/dist/index.html'
        runtime={'runningJarSha256':sha(jar),'frontendIndexSha256':sha(index) if index.is_file() else None,'serverClock':datetime.now().astimezone().isoformat(),'collectedAtUtc':datetime.now(timezone.utc).isoformat(),'flags':flags,'backendRunning':bool(backend_pid),'aiRunning':bool(ai_pid),'diagnosticVersion':'scan-diag-v1'}
        effective={}
        for key,value in {**env,**ai_env}.items():
            if key not in {'AUDIT_OCR_ORIENTATION_ENABLED','AUDIT_OCR_PROCESS_ISOLATION','AUDIT_OCR_READ_TIMEOUT_MS','AUDIT_MATERIAL_MODEL_READ_TIMEOUT_SECONDS','AUDIT_MATERIAL_OUTPUT_TOKENS','AUDIT_MATERIAL_CHUNK_CHARS','AUDIT_MATERIAL_CHUNK_BYTES','AUDIT_MODEL_MAX_CHUNKS','AUDIT_MODEL_CHUNK_CHARS','AUDIT_CASE_OUTPUT_TOKENS','AUDIT_CASE_MODEL_READ_TIMEOUT_SECONDS','AUDIT_CONTRACT_MODEL_READ_TIMEOUT_SECONDS','AUDIT_MODEL_TOTAL_BUDGET_SECONDS'}:continue
            if value in ('true','false'):effective[key]=value=='true'
            elif re.fullmatch(r'\d{1,9}',value):effective[key]=int(value)
        runtime['explicitProcessingConfig']=effective
        runtime['modelConfigPresent']={key:bool(ai_env.get(key)) for key in ('AUDIT_MODEL_BASE_URL','AUDIT_MODEL_API_KEY','AUDIT_MODEL_NAME')}
        active_source=Path('/proc/'+ai_pid+'/cwd').resolve()/'src/audit_ai' if ai_pid else None
        runtime['aiSourceHashes']={name:sha(active_source/name) for name in ('model/gateway.py','model/automatic_procurement_prompt.py','model/contract_analysis.py','ocr/orientation.py') if active_source and (active_source/name).is_file()}
        runtime['procurementLimits']=processing_limits(jar,env,backend_pid)
        runtime['machine']=machine_stats(jar)
        if active_source:
            cp=run([python,'-I',str(ROOT/'probe.py'),'config',str(active_source.parent)],timeout=20,env=ai_env)
            try:runtime['activeAiSettings']=json.loads(cp.stdout) if cp else {'status':'CONFIG_INSPECTION_TIMEOUT'}
            except (ValueError,UnicodeError):runtime['activeAiSettings']={'status':'CONFIG_INSPECTION_FAILED'}
        write(export/'runtime.json',runtime)
        write(export/'findings.json',build_findings(database,pdf,export/'events.jsonl',runtime))
        write(export/'summary.json',{'format':'scan-diag-v1','requestedDay':args.day,'scope':'previous/current/next server-local calendar days, recent bounded cases','maxCasesPerModule':args.limit,'eventCounts':dict(totals),'journal':journal,'journalOrder':'NEWEST_FIRST_BUSINESS_EVENTS','databaseWrites':0,'servicesRestarted':False,'fileContentsExported':False,'fileNamesExported':False,'amountValuesExported':False,'modelProbeRequested':args.model_probe,'fixedKeywordsOnly':True,'pdfProbeLimitReached':len(pdf)>=8,'originalPdfsExported':False,'ocrRerun':False,'businessResultsModified':False})
        for file in export.iterdir():
            data=file.read_text(encoding='utf-8')
            if any(secret in data for secret in secrets):raise SystemExit('DIAG_STOP=SECRET_FILTER_FAILED')
        with tarfile.open(output,'w:gz') as tar:
            for file in sorted(export.iterdir()):
                if file.name not in {'database.json','pdf.json','model.json','events.jsonl','runtime.json','summary.json','findings.json'}:raise SystemExit('DIAG_STOP=EXPORT_ALLOWLIST')
                tar.add(file,arcname=file.name)
    print('DIAG=COLLECTED\nOUTPUT='+str(output)+'\nSHA256='+sha(output))
    print('请只发回OUTPUT对应压缩包；不含正文、PDF、截图、文件名、实际金额、密钥或模型原始回答。')

if __name__=='__main__':
    try:main()
    except (OSError,ValueError,AssertionError,subprocess.SubprocessError):raise SystemExit('DIAG_STOP=LOCAL_INSPECTION_ERROR')
