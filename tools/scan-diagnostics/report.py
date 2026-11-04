"""Only fixed technical projections. Never copy strings from business payloads."""
import json
import math
import os
from pathlib import Path
import re
import shutil
import uuid
import zipfile

STATUSES={'STAGING','STORED','PARSING','PARSED','CALLING','EXTRACTED','FAILED','UNKNOWN','UPLOADING','QUEUED','RUNNING','PARTIAL','COMPLETED','DATA_INSUFFICIENT','READY','COMPLETE','OTHER_OR_UNAVAILABLE'}
REASONS={'PAGE_LIMIT_EXCEEDED','TEXT_LIMIT_EXCEEDED','FILE_LIMIT_EXCEEDED','DOCUMENT_TEXT_EMPTY','DOCUMENT_PARSE_FAILED','DOCUMENT_SIGNATURE_INVALID','DOCUMENT_INTERRUPTED','SOURCE_READ_FAILED','OCR_CAPACITY_TIMEOUT','OCR_UNAVAILABLE','OCR_RUNTIME_UNAVAILABLE','OCR_INVALID_RESULT','OCR_EXECUTION_FAILED','OCR_PAGE_COVERAGE_INVALID','OCR_BUDGET_EXHAUSTED','OCR_OUTCOME_UNPROVEN','MATERIAL_CALL_TIMEOUT','MATERIAL_TIME_LIMIT','MATERIAL_QUEUE_TIMEOUT','MATERIAL_AI_TIME_LIMIT','MATERIAL_AI_INPUT_LIMIT','MODEL_INPUT_TOO_LARGE','INVALID_SOURCE','INVALID_PROVIDER_JSON','INVALID_PROVIDER_RESPONSE','READ_TIMEOUT','CONNECT_TIMEOUT','CONNECT_ERROR','MODEL_NOT_CONFIGURED','MODEL_GATEWAY_ERROR','EXTRACTION_OUTCOME_UNPROVEN','EXECUTOR_LOST_EXTERNAL_OUTCOME_UNKNOWN','UNACKNOWLEDGED_CALL','OUTPUT_TOKEN_LIMIT','EVIDENCE_UNVERIFIED','FIELD_ROLE_MISMATCH','DOCUMENT_TYPE_UNSUPPORTED'}

def integer(value,default=None):
    return value if isinstance(value,int) and not isinstance(value,bool) and -1<=value<=10**12 else default

def identifier(value):
    try:return str(uuid.UUID(value))
    except (TypeError,ValueError,AttributeError):return None

def processing_limits(jar,env,pid=None):
    result={'status':'OK','effectiveLimitsVerified':False,'note':'CLASS_DEFAULT_AND_OBSERVED_OVERRIDES_NOT_FULL_SPRING_RESOLUTION','classDefaults':{},'environmentOverrides':{},'commandLineOverrides':{}}
    names={'max-pages':'maxPages','max-chars':'maxChars','max-bytes':'maxBytes'}
    try:
        with zipfile.ZipFile(jar) as z:
            data=z.read('BOOT-INF/classes/com/fuquan/audit/procurement/autocase/AuditCaseMaterialLimits.class')
        for key,name in names.items():
            match=re.search(rb'\$\{audit\.procurement\.material\.'+key.encode()+rb':([0-9]{1,12})\}',data)
            if match:result['classDefaults'][name]=int(match.group(1))
    except (OSError,KeyError,zipfile.BadZipFile):result['status']='CLASS_DEFAULTS_UNAVAILABLE'
    for key,value in env.items():
        if key not in {'AUDIT_PROCUREMENT_MATERIAL_MAX_PAGES','AUDIT_PROCUREMENT_MATERIAL_MAXPAGES','AUDIT_PROCUREMENT_MATERIAL_MAX_CHARS','AUDIT_PROCUREMENT_MATERIAL_MAXCHARS','AUDIT_PROCUREMENT_MATERIAL_MAX_BYTES','AUDIT_PROCUREMENT_MATERIAL_MAXBYTES','AUDIT_OCR_READ_TIMEOUT_MS','AUDIT_AI_READ_TIMEOUT_MS'}:continue
        result['environmentOverrides'][key]=int(value) if re.fullmatch('[0-9]{1,12}',value) else 'INVALID_OR_EXPRESSION'
    if pid:
        try:
            for arg in Path('/proc/'+pid+'/cmdline').read_bytes().split(b'\0'):
                match=re.fullmatch(rb'(?:-D|--)(audit\.procurement\.material\.(?:max-pages|max-chars|max-bytes))=([0-9]{1,12})',arg)
                if match:result['commandLineOverrides'][match.group(1).decode()]=int(match.group(2))
        except OSError:pass
    result['externalConfigurationConfigured']=any(env.get(k) for k in ('SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION','SPRING_APPLICATION_JSON','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'))
    return result

def machine_stats(path):
    result={}
    try:
        free=shutil.disk_usage(path);result['filesystemFreeBytes']=free.free;result['filesystemTotalBytes']=free.total
        result['cpuCount']=os.cpu_count()
        if hasattr(os,'getloadavg'):result['loadAverages']=[round(v,2) for v in os.getloadavg() if math.isfinite(v)]
        memory={}
        for line in Path('/proc/meminfo').read_text().splitlines():
            match=re.fullmatch(r'(MemTotal|MemAvailable|SwapTotal|SwapFree):\s+(\d+) kB',line)
            if match:memory[match.group(1)+'KiB']=int(match.group(2))
        result['memory']=memory
    except OSError:result['status']='PARTLY_UNAVAILABLE'
    return result

def build_findings(database,pdfs,events_path,runtime):
    inventories={identifier(p.get('fileId')):p for p in pdfs if identifier(p.get('fileId'))}
    cases=database.get('cases',{}).get('AUDIT_CASE',{})
    by_id={};event_count=0
    try:
        with Path(events_path).open(encoding='utf-8') as stream:
            for line in stream:
                try:event=json.loads(line)
                except ValueError:continue
                event_count+=1
                identifiers={identifier(event.get(k)) for k in ('resourceId','fileId','documentId','document_id','materialId','material_id','operationId')}-{None}
                for value in identifiers:
                    slot=by_id.setdefault(value,{'events':0,'reasons':set(),'maxDurationMs':0})
                    slot['events']+=1
                    for key in ('reason','reasonCode','reason_code','outcome','status'):
                        if event.get(key) in REASONS:slot['reasons'].add(event[key])
                    for key in ('durationMs','duration_ms','elapsedMillis'):
                        slot['maxDurationMs']=max(slot['maxDurationMs'],integer(event.get(key),0))
    except OSError:pass
    output=[]
    for case in cases.get('rows',[]):
        cid=identifier(case.get('caseId'))
        for doc in case.get('documents',[]):
            fid=identifier(doc.get('fileId'))
            if not fid:continue
            pdf=inventories.get(fid,{});characters=integer(doc.get('text',{}).get('chars'));observations=[]
            ids={fid};ledgers=[]
            for attempt in doc.get('extractionLedgers',[]):
                lid=identifier(attempt.get('ledgerId'))
                if lid:ids.add(lid)
                for row in attempt.get('rows',[]):
                    if not isinstance(row,dict) or 'UNIT_COUNT' not in row:continue
                    state=row.get('STATUS') if row.get('STATUS') in STATUSES else 'OTHER_OR_UNAVAILABLE'
                    unit=integer(row.get('UNIT_COUNT'));next_unit=integer(row.get('NEXT_UNIT'))
                    ledgers.append({'attempt':integer(attempt.get('attempt')),'status':state,'unitCount':unit,'completedUnits':next_unit,'callCount':integer(row.get('CALL_COUNT')),'usedMillis':integer(row.get('USED_MILLIS')),'reason':row.get('REASON_CODE') if row.get('REASON_CODE') in REASONS else None})
                    if unit is not None and next_unit is not None and next_unit<unit:observations.append('MODEL_EXTRACTION_UNITS_INCOMPLETE')
            reasons={doc['errorCode']} if doc.get('errorCode') in REASONS else set()
            matched=0;duration=0
            for value in ids:
                slot=by_id.get(value,{})
                reasons.update(slot.get('reasons',set()));matched+=slot.get('events',0);duration=max(duration,slot.get('maxDurationMs',0))
            state=doc.get('status') if doc.get('status') in STATUSES else 'OTHER_OR_UNAVAILABLE'
            if 'PAGE_LIMIT_EXCEEDED' in reasons:observations.append('READ_BLOCKED_BY_PAGE_LIMIT')
            if 'TEXT_LIMIT_EXCEEDED' in reasons:observations.append('READ_BLOCKED_BY_TEXT_LIMIT')
            if any(r.startswith('OCR_') for r in reasons):observations.append('OCR_FAILURE_OR_INCOMPLETE_COVERAGE_RECORDED')
            if characters==0 and state in {'FAILED','UNKNOWN'}:observations.append('NO_SAVED_TEXT_READ_FAILURE_OR_UNCERTAIN')
            if characters==0 and state in {'PARSING','STORED','QUEUED','STAGING'}:observations.append('READING_NOT_FINISHED')
            facts=integer(doc.get('persistedFactCount'))
            if characters is not None and characters>0 and facts==0:observations.append('TEXT_EXISTS_WITHOUT_PUBLISHED_FACTS')
            budget=doc.get('text',{}).get('signals',{}).get('BUDGET',{})
            if integer(budget.get('count'),0)>0 and not doc.get('factTypes',{}).get('budgetAmount'):observations.append('BUDGET_WORDING_EXISTS_WITHOUT_PUBLISHED_BUDGET')
            samples=pdf.get('sampledPages',[]);page_count=integer(pdf.get('pageCount'))
            native_checked=[p for p in samples if integer(p.get('nativeChars')) is not None]
            sparse=sum(integer(p.get('nonWhitespaceChars'),integer(p.get('nativeChars'),0))<20 for p in native_checked)
            if len(native_checked)==len(samples) and samples and sparse==len(samples):observations.append('NATIVE_TEXT_SPARSE_SCAN_OR_RASTER_PDF_LIKELY')
            if len(native_checked)<len(samples):observations.append('NATIVE_PAGE_INSPECTION_INCOMPLETE')
            default=runtime.get('procurementLimits',{}).get('classDefaults',{}).get('maxPages')
            if page_count and isinstance(default,int) and page_count>default:observations.append('PDF_EXCEEDS_RUNNING_CLASS_DEFAULT_PAGE_LIMIT_CHECK_OVERRIDES')
            coverage=doc.get('pageCoverage',{});saved={p['page'] for p in coverage.get('pages',[]) if integer(p.get('page')) is not None and p.get('chars',0)>0}
            row={'caseId':cid,'fileId':fid,'fileOrder':integer(doc.get('fileOrder')),'status':state,'savedChars':characters,'publishedFactCount':facts,'modelCallsRecorded':integer(doc.get('calls')),'pdfPageCount':page_count,'nativePagesInspected':len(native_checked),'nativePagesWithSparseText':sparse,'nativePageInspectionFailures':len(samples)-len(native_checked),'savedTextPageCount':len(saved),'savedTextLastPage':max(saved) if saved else None,'pageCountComparisonAvailable':page_count is not None,'missingSavedTextPages':sorted(set(range(1,min(page_count,10000)+1))-saved) if page_count else None,'matchedEventCount':matched,'maxMatchedStageDurationMs':duration,'recordedReasons':sorted(reasons),'modelLedgers':ledgers,'observations':sorted(set(observations)),'diagnosis':'EVIDENCE_COLLECTED_REVIEW_REQUIRED' if observations else 'NO_DECISIVE_EVIDENCE'}
            output.append(row)
    return {'format':'scan-findings-v1','files':output,'sanitizedEventsRead':event_count,'databaseAccessible':database.get('connection')=='OK','noRootCauseInferredFromEmptyResultAlone':True,'pdfLimits':{'maxFiles':8,'maxPagesPerFile':512},'originalContentExported':False}
