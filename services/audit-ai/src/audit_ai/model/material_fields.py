"""Bounded, source-grounded field extraction for procurement uploads."""
from __future__ import annotations

import json
import re
import threading
import time
import logging
import unicodedata
from typing import Literal
from audit_ai.diagnostics import diagnosed
from audit_ai.logging import diagnostic_context, log_event

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.concurrency import run_in_threadpool

from audit_ai.errors import AuditAiError
from audit_ai.model.gateway import ModelGatewayPort, ModelGatewayFailure, ModelNotConfigured
from audit_ai.config import Settings
from audit_ai.client_privacy import CURRENT
from audit_ai.model.material_chunks import bounded_chunks, render, split_chunk
from audit_ai.model.material_checkpoints import MaterialCheckpoints, digest
from audit_ai.model.automatic_procurement_prompt import AUTO_CASE_FIELDS_VERSION
from audit_ai.model.procurement_context import contextual_renderer

_STRICT = ConfigDict(extra="forbid", populate_by_name=True)
_PROCUREMENT = frozenset({"projectName", "projectNo", "departmentName", "procurementType", "budgetAmount",
    "subjectCode", "subjectName", "procurementMethod", "requirementDescription", "applicantName"})
_BUDGET = frozenset({"subjectCode", "subjectName", "declaredAmount", "approvalAmount", "projectBudgetAmount", "declaredTotalAmount", "approvedTotalAmount", "ceilingAmount", "supplierName", "projectCode", "projectName", "budgetYear", "departmentName",
                     "parentProjectName", "applicationNo", "applicationDate", "actualApprovalStep", "requiredApprovalStep", "policyClause", "approvalThreshold", "thresholdCondition", "spendingPurpose", "approvedPurpose", "requiredPreviousStep", "approvalDate"})
_PROCUREMENT_REVIEW = _PROCUREMENT | frozenset({
    'purchaserName', 'agencyName', 'supplierName', 'quoteAmount', 'ceilingAmount', 'productDescription'
})
_PROCUREMENT_AUTO = frozenset({"documentType", "projectName", "projectNo", "purchaserName", "agencyName", "packageName",
    "budgetAmount", "ceilingAmount", "supplierName", "quoteAmount", "awardAmount",
    "productDescription", "itemName", "quantity", "unitPrice", "lineAmount"})

def text_parts(text, limit=1800):
    """Keep normal office rows/paragraphs together without dropping any character."""
    start=0
    while start<len(text):
        end=min(len(text),start+limit)
        if end<len(text):
            newline=text.rfind('\n',start,end)
            if newline>=start:end=newline+1
        yield text[start:end]
        start=end

class Segment(BaseModel):
    model_config = _STRICT
    locator: str = Field(min_length=1, max_length=300)
    text: str = Field(min_length=1, max_length=6000, repr=False)

class DocumentContext(BaseModel):
    model_config = _STRICT
    header: str = Field(default='', max_length=800, repr=False)
    section: str = Field(default='', max_length=160, repr=False)

class ExtractionRequest(BaseModel):
    model_config = _STRICT
    business_type: Literal["PROCUREMENT", "BUDGET", "PROCUREMENT_REVIEW", "PROCUREMENT_AUTO"] = Field(alias="businessType")
    material_id: str = Field(alias="materialId", min_length=1, max_length=36)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    segments: list[Segment] = Field(min_length=1, max_length=10000)
    context: DocumentContext | None = None
    policy_id: str | None = Field(default=None, alias='policyId', pattern=r'^[a-f0-9]{64}$')
    call_limit: int = Field(default=512, alias='callLimit', ge=1, le=512)
    time_limit_millis: int = Field(default=900000, alias='timeLimitMillis', ge=1, le=900000)

class Fact(BaseModel):
    model_config = _STRICT
    field: str = Field(min_length=1, max_length=40)
    value: str = Field(min_length=1, max_length=2000, repr=False)
    locator: str = Field(min_length=1, max_length=300)
    excerpt: str = Field(min_length=1, max_length=2400, repr=False)
    subject_scope: Literal['CURRENT_PROJECT', 'REFERENCE', 'GENERAL', 'UNKNOWN'] | None = Field(default=None, alias='subjectScope')
    amount_scope: Literal['TOTAL', 'ANNUAL', 'PACKAGE', 'UNIT', 'UNKNOWN'] | None = Field(default=None, alias='amountScope')

class Row(BaseModel):
    model_config = _STRICT
    row_key: str = Field(alias="rowKey", min_length=1, max_length=200)
    facts: list[Fact] = Field(max_length=100)

class Payload(BaseModel):
    model_config = _STRICT
    rows: list[Row] = Field(max_length=100)


def restore_description_sentence_breaks(fact: Fact, text: str | None) -> Fact:
    """Restore only omitted line breaks after punctuation in verbatim descriptions.

    Never join split digits/words, skip source sentences, guess a locator or
    repair identity/amount fields. Ambiguous source matches stay unverified.
    Returned evidence always contains the actual, unchanged source bytes.
    """
    if (text is None or fact.field not in {'requirementDescription', 'productDescription'}
            or fact.value != fact.excerpt or fact.excerpt in text):
        return fact
    removed = set()
    for match in re.finditer(r'(?<=[。；！？;!?])(?:\r?\n[ \t]*)+', text):
        removed.update(range(match.start(), match.end()))
    positions = [i for i in range(len(text)) if i not in removed]
    collapsed = ''.join(text[i] for i in positions)
    first = collapsed.find(fact.excerpt)
    if first < 0 or collapsed.find(fact.excerpt, first + 1) >= 0:
        return fact
    actual = text[positions[first]:positions[first + len(fact.excerpt)-1]+1]
    if len(actual) > 2000:
        return fact
    return fact.model_copy(update={'value': actual, 'excerpt': actual})

def create_material_fields_router(gateway: ModelGatewayPort, settings: Settings | None = None) -> APIRouter:
    settings = settings or Settings()
    router = APIRouter(prefix="/ai/v1/materials")
    capacity = threading.BoundedSemaphore(1)
    checkpoints = MaterialCheckpoints(settings.material_checkpoint_root,settings.material_checkpoint_ttl_seconds)
    namespace = gateway.material_cache_namespace() if hasattr(gateway,'material_cache_namespace') else type(gateway).__qualname__
    policy_id=digest([namespace,'material-evidence-v12-budget-scope',AUTO_CASE_FIELDS_VERSION,settings.material_chunk_chars,settings.material_chunk_bytes,
                      settings.material_context_chars,settings.material_output_tokens])

    @router.get('/policy')
    def policy():
        # An opaque policy fingerprint, never the endpoint, credential or model configuration.
        return {'policyId':policy_id}

    @diagnosed("PROCUREMENT_MATERIAL_EXTRACTION")
    def execute(data: ExtractionRequest, request_id: str):
        try:
            if data.policy_id is not None and data.policy_id != policy_id:
                raise AuditAiError(409,'MATERIAL_AI_POLICY_DRIFT','extraction policy changed')
            budget_seconds=data.time_limit_millis/1000
            total = sum(len(s.text) for s in data.segments)
            if total > 192000:
                raise AuditAiError(413, "MATERIAL_AI_INPUT_LIMIT", "material text exceeds extraction limit")
            if len({s.locator for s in data.segments}) != len(data.segments):
                raise AuditAiError(422, "MATERIAL_AI_DUPLICATE_SOURCE", "duplicate source locator")
            allowed = {'PROCUREMENT': _PROCUREMENT, 'BUDGET': _BUDGET, 'PROCUREMENT_REVIEW': _PROCUREMENT_REVIEW,
                       'PROCUREMENT_AUTO': _PROCUREMENT_AUTO}[data.business_type]
            # A numbered, explicitly labelled requirement is already a verbatim
            # fact. Preserve it locally instead of asking the model to reproduce
            # thousands of words and exhaust its output window. Unstructured
            # sections and paragraphs containing identity labels still use AI.
            explicit = []
            semantic_segments = []
            for segment in data.segments:
                if data.business_type != "PROCUREMENT":
                    semantic_segments.append(segment)
                    continue
                # TXT/CSV readers can place many paragraphs in one source span.
                # Preserve every explicitly labelled line, retaining its original
                # locator. Keep remaining contiguous spans exact, so no model
                # citation can accidentally bridge removed source lines.
                pending = []
                for line in segment.text.splitlines(keepends=True):
                    match = re.fullmatch(r"第[0-9一二三四五六七八九十]+条\s*技术要求\s*[:：]\s*(.+)", line.strip())
                    has_identity = re.search(r"(?:项目名称|项目编号|申请部门|申请人|采购类型|采购方式|预算金额|科目编码|科目名称)\s*[:：]", line)
                    if match and not has_identity and len(match[1])<=2000 and len(line)<=2400:
                        if pending:
                            semantic_segments.append(Segment(locator=segment.locator,text=''.join(pending)))
                            pending=[]
                        explicit.append({'field':'requirementDescription','value':match[1],
                                         'locator':segment.locator,'excerpt':line})
                    else:
                        pending.append(line)
                if pending:
                    semantic_segments.append(Segment(locator=segment.locator,text=''.join(pending)))
            if len(explicit)>2000:
                raise AuditAiError(413, "MATERIAL_AI_RESULT_LIMIT", "too many extracted fields")
            renderer = (contextual_renderer(data.segments, data.context, settings)
                        if data.business_type == 'PROCUREMENT_AUTO' else render)
            try: chunks=bounded_chunks(semantic_segments,settings,text_parts,renderer)
            except ValueError as exc:
                raise AuditAiError(413,'MATERIAL_AI_INPUT_LIMIT','source metadata exceeds configured chunk bound') from exc
            identity = CURRENT.get()
            identity_scope = digest(sorted(identity.names)) if identity is not None else None
            operation=digest([namespace,policy_id,identity_scope,'material-adaptive-v1',data.model_dump(),
                              settings.material_chunk_chars,settings.material_chunk_bytes,
                              settings.material_context_chars,settings.material_output_tokens])
            logger = logging.getLogger("audit_ai.material_fields")
            log_event(logger, "MATERIAL_CHUNKS_READY", stage="CHUNKING", material_id=data.material_id,
                      chunk_count=len(chunks), input_chars=total, segment_count=len(data.segments),
                      explicit_fact_count=len(explicit), text_limit=192000, remaining_budget_ms=data.time_limit_millis)
            # One bounded sequential operation; no thread-per-page or unbounded executor queue.
            started = time.monotonic()
            rows: dict[str, list[dict]] = {'explicit-requirements':explicit} if explicit else {}
            model = 'EXPLICIT_LABELS' if not chunks else None
            work=[(chunk,0,str(i)) for i,chunk in enumerate(chunks)]
            calls=0
            empty_chunks=0
            chunks_with_facts=0
            seen_rows={}
            models=set()
            issues=[]
            checkpoint_reused=False
            while work:
                chunk,depth,path=work.pop(0)
                calls+=1
                index=calls
                if calls>data.call_limit:
                    raise AuditAiError(413,"MATERIAL_AI_CALL_LIMIT","material extraction call budget exceeded")
                if time.monotonic() - started > budget_seconds:
                    raise AuditAiError(504, "MATERIAL_AI_TIME_LIMIT", "material extraction time budget exceeded")
                views={s['locator']:s for s in [p.view(settings.material_context_chars) for p in chunk]}
                texts={locator:v.get('contextBefore','')+v['text']+v.get('contextAfter','') for locator,v in views.items()}
                source=renderer(chunk,settings.material_context_chars)
                checkpoint_key=digest([path,source])
                cached=checkpoints.get(operation,checkpoint_key)
                completion=None
                chunk_issues=[]
                truncated=False
                split_reason=None
                with diagnostic_context(material_id=data.material_id, chunk_index=index, chunk_count=calls+len(work)):
                    log_event(logger, "MATERIAL_CHUNK_START", stage="EXTRACTION", outcome="START",
                              segment_count=len(chunk), input_chars=sum(len(s.text) for s in chunk),
                              remaining_budget_ms=max(0, int((budget_seconds-(time.monotonic()-started))*1000)))
                    if isinstance(cached,dict) and cached.get('kind')=='complete':
                        # Revalidate disposable cached candidates against the current
                        # exact source. A corrupt/missing cache triggers recomputation.
                        try:
                            restored=Payload.model_validate(cached['payload'])
                            if not isinstance(cached['model'],str) or not cached['model']:raise ValueError()
                            for row in restored.rows:
                                for fact in row.facts:
                                    if (fact.field not in allowed or fact.locator not in texts
                                            or fact.excerpt not in texts[fact.locator] or fact.value not in fact.excerpt):raise ValueError()
                            restored_issues=cached.get('issues',[])
                            if (not isinstance(restored_issues,list) or len(restored_issues)>2000
                                or any(not isinstance(i,dict) or set(i)!={'field','locator','reasonCode'}
                                    or i['field'] not in allowed|{'unknown'} or i['locator'] not in texts
                                    or i['reasonCode']!='EVIDENCE_UNVERIFIED' for i in restored_issues)):
                                raise ValueError()
                            from audit_ai.model.gateway import ModelCompletion
                            completion=ModelCompletion(payload=cached['payload'],model=cached['model'],duration_ms=0)
                            checkpoint_reused=True
                            chunk_issues.extend(restored_issues)
                            log_event(logger,'MATERIAL_CHECKPOINT_REUSED',stage='EXTRACTION',outcome='REVALIDATED')
                        except (KeyError,TypeError,ValueError):pass
                    if isinstance(cached,dict) and cached=={'kind':'split'}:
                        split_reason='PREVIOUS_SPLIT'
                    elif completion is None:
                        try:
                            completion = gateway.extract_material_fields(data.business_type,source, request_id,
                                timeout_seconds=max(0.001, budget_seconds - (time.monotonic() - started)))
                            truncated=completion.finish_reason=='LENGTH'
                        except ModelGatewayFailure as failure:
                            if failure.input_limited:split_reason='INPUT_LIMIT'
                            elif failure.reason in {'INVALID_PROVIDER_JSON','INVALID_PROVIDER_RESPONSE'} and failure.finish_reason=='LENGTH':truncated=True
                            else:raise
                    if truncated or split_reason:
                        split=split_chunk(chunk) if depth<5 else None
                        log_event(logger,"MATERIAL_CHUNK_SPLIT",stage="CHUNKING",outcome="RETRY_SMALLER" if split else "TRUNCATED_LIMIT",
                                  reason_code=split_reason or 'OUTPUT_TRUNCATED',
                                  input_chars=sum(len(s.text) for s in chunk),segment_count=len(chunk),attempt=depth+1)
                        if not split:
                            code='MATERIAL_AI_CONTEXT_LIMIT' if split_reason else 'MATERIAL_AI_TRUNCATED'
                            raise AuditAiError(502,code,'material extraction cannot fit provider limits')
                        checkpoints.put(operation,checkpoint_key,{'kind':'split'})
                        work[:0]=[(part,depth+1,path+'.'+str(i)) for i,part in enumerate(split)]
                        continue
                if time.monotonic() - started > budget_seconds:
                    raise AuditAiError(504, "MATERIAL_AI_TIME_LIMIT", "material extraction time budget exceeded")
                model = completion.model
                models.add(model)
                if len(models)>1:
                    raise AuditAiError(502,'MATERIAL_AI_INVALID_RESULT','provider changed model within one extraction')
                # Some local providers represent "no fields in this paragraph"
                # as {}. Only this exact empty shape is normalized; it never
                # becomes a parsed file without grounded facts in other chunks.
                payload=completion.payload
                if payload=={}:
                    empty_chunks+=1
                    log_event(logger,"MATERIAL_CHUNK_EMPTY",stage="RESULT_VALIDATION",outcome="NO_FIELDS",
                              chunk_index=index,chunk_count=calls+len(work),
                              input_chars=sum(len(s.text) for s in chunk),segment_count=len(chunk),
                              provider_finish_reason=completion.finish_reason or "MISSING",
                              **({"prompt_tokens":completion.prompt_tokens} if completion.prompt_tokens is not None else {}),
                              **({"completion_tokens":completion.completion_tokens} if completion.completion_tokens is not None else {}))
                    payload={'rows':[]}
                try: parsed = Payload.model_validate(payload)
                except ValidationError as exc:
                    log_event(logger, "MATERIAL_CHUNK_REJECTED", stage="RESULT_VALIDATION", outcome="INVALID_SCHEMA",
                              chunk_index=index, chunk_count=calls+len(work), validation_error_count=exc.error_count())
                    for error in exc.errors(include_input=False,include_url=False)[:5]:
                        log_event(logger,"MATERIAL_SCHEMA_ERROR",stage="RESULT_VALIDATION",outcome="REJECTED",
                                  validation_error_type=error['type'],validation_error_loc='.'.join(map(str,error['loc'])))
                    raise AuditAiError(502, "MATERIAL_AI_INVALID_RESULT", "invalid material extraction shape") from exc
                checkpoint_rows=[]
                for restored_issue in chunk_issues:
                    if restored_issue not in issues:issues.append(restored_issue)
                for row_index,row in enumerate(parsed.rows):
                    # Budget row keys are model-local. Different chunks must never
                    # overwrite or merge distinct budget lines with the same key.
                    key = f"{path}:{row_index}:{row.row_key}"
                    accepted = []
                    anchors=[]
                    unverified_row_identity = False
                    for fact in row.facts:
                        text = texts.get(fact.locator)
                        fact = restore_description_sentence_breaks(fact, text)
                        if fact.field not in allowed or text is None or fact.excerpt not in text or fact.value not in fact.excerpt:
                            if data.business_type == 'BUDGET' and fact.field in {
                                    'projectName', 'projectCode', 'subjectName', 'subjectCode'}:
                                unverified_row_identity = True
                            # Uncertain fields are discarded individually. The file and
                            # all grounded facts remain usable; the client must never see
                            # a guessed value or a technical evidence warning.
                            issue={'field':fact.field if fact.field in allowed else 'unknown',
                                   'locator':fact.locator if fact.locator in texts else next(iter(texts)),
                                   'reasonCode':'EVIDENCE_UNVERIFIED'}
                            if issue not in issues:issues.append(issue)
                            if issue not in chunk_issues:chunk_issues.append(issue)
                            log_event(logger,'MATERIAL_FACT_EXCLUDED',stage='RESULT_VALIDATION',
                                      outcome='PARTIAL',reason_code='MATERIAL_AI_UNGROUNDED')
                            continue
                        value = fact.model_dump(by_alias=True, exclude_none=True)
                        if data.business_type not in {'PROCUREMENT_AUTO','BUDGET'}:
                            value.pop('subjectScope', None)
                            value.pop('amountScope', None)
                        view=views[fact.locator]
                        # An overlap is context, not a second budget row. Only keep
                        # evidence that intersects the newly processed core text.
                        core_start=len(view.get('contextBefore',''));core_end=core_start+len(view['text'])
                        positions=[m.start() for m in re.finditer(re.escape(fact.excerpt),texts[fact.locator])]
                        if not any(p<core_end and p+len(fact.excerpt)>core_start for p in positions):continue
                        piece=next(p for p in chunk if p.locator==fact.locator)
                        positions=[piece.start-core_start+p for p in positions if p<core_end and p+len(fact.excerpt)>core_start]
                        anchors.append([value,positions])
                        if value not in accepted: accepted.append(value)
                    if unverified_row_identity:
                        # An amount may be verbatim while its project/subject was invented.
                        # Keeping that orphan amount could attach it to a different valid row.
                        # Drop only this row; other independently grounded rows survive.
                        accepted = []
                        log_event(logger, 'MATERIAL_FACT_EXCLUDED', stage='RESULT_VALIDATION',
                                  outcome='PARTIAL', reason_code='BUDGET_ROW_IDENTITY_UNVERIFIED')
                    identity=digest(anchors)
                    if accepted and seen_rows.get(identity,path)==path:
                        rows[key]=accepted
                        seen_rows[identity]=path
                    if accepted:
                        checkpoint_rows.append({'rowKey':row.row_key,'facts':accepted})
                        chunks_with_facts+=len(accepted)
                    if len(issues)>2000 or len(accepted) > 2000 or len(rows) > (100 if data.business_type == "PROCUREMENT" else 10000):
                        raise AuditAiError(413, "MATERIAL_AI_RESULT_LIMIT", "too many extracted fields")
                checkpoints.put(operation,checkpoint_key,{'kind':'complete',
                    # Cache only the evidence-validated projection. Caching the
                    # provider payload would replay discarded or ungrounded facts
                    # and could make a later request fail during revalidation.
                    'payload':{'rows':checkpoint_rows},
                    'issues':chunk_issues,'model':completion.model})
                log_event(logger, "MATERIAL_CHUNK_VALIDATED", stage="RESULT_VALIDATION", outcome="SUCCESS",
                          chunk_index=index, chunk_count=calls+len(work), row_count=len(parsed.rows),
                          fact_count=sum(len(r.facts) for r in parsed.rows), duration_ms=completion.duration_ms)
            # Chunks without an identity may contain supplementary requirements.
            # Merge their model-local row keys only when grounded identity facts
            # prove a single project; never hide conflicting project identities.
            project_numbers = {unicodedata.normalize('NFKC', f['value']).strip()
                               for facts in rows.values() for f in facts if f['field']=='projectNo'}
            project_names = {unicodedata.normalize('NFKC', f['value']).strip()
                             for facts in rows.values() for f in facts if f['field']=='projectName'}
            # A rowKey is generated independently per model call. It is never
            # evidence of another project. Identity-free supplemental chunks
            # may be combined; scalar disagreements remain cited conflicts in
            # Java and are not silently picked as authoritative values.
            compatible_identity = len(project_numbers)<=1 and len(project_names)<=1
            if data.business_type == "PROCUREMENT" and len(rows)>1 and compatible_identity:
                merged = []
                for facts in rows.values():
                    for fact in facts:
                        if fact not in merged: merged.append(fact)
                if len(merged)>2000:
                    raise AuditAiError(413, "MATERIAL_AI_RESULT_LIMIT", "too many extracted fields")
                rows = {next(iter(project_numbers or project_names), 'material'):merged}
            final_fact_count=sum(len(f) for f in rows.values())
            log_event(logger, "MATERIAL_RESULT_READY", stage="MERGE",
                      outcome="PARTIAL_EMPTY_CHUNKS" if empty_chunks else "SUCCESS", material_id=data.material_id,
                      row_count=len(rows), fact_count=final_fact_count, chunk_count=calls,
                      empty_chunk_count=empty_chunks, chunks_with_facts=chunks_with_facts)
            # Keep a just-published complete projection for one idempotent
            # duplicate request. Once that projection is actually reused, or
            # after recovery from an interrupted run, remove all disposable
            # checkpoints so they cannot accumulate beyond their short TTL.
            if checkpoint_reused:
                checkpoints.discard(operation)
            return {"code": 200, "message": "success", "data": {"rows": [
                {"rowKey": key, "facts": facts} for key, facts in rows.items() if facts],
                "model": model, "callCount": calls,
                **({'issues':issues} if data.business_type in {'BUDGET','PROCUREMENT_AUTO'} else {}),
                "promptVersion": (AUTO_CASE_FIELDS_VERSION if data.business_type == 'PROCUREMENT_AUTO' else
                    'procurement-review-fields-v2' if data.business_type == 'PROCUREMENT_REVIEW' else
                    "business-material-fields-v8-budget-procedure" if data.business_type == "BUDGET" else "business-material-fields-v5")}}
        except ModelNotConfigured as exc:
            raise AuditAiError(503, "MATERIAL_AI_UNAVAILABLE", "material AI is not configured") from exc
        except ModelGatewayFailure as exc:
            if exc.reason == 'READ_TIMEOUT':
                if 'operation' in locals(): checkpoints.discard(operation)
                raise AuditAiError(504, "MATERIAL_AI_CALL_TIMEOUT", "material model response timed out; processing ended") from exc
            raise AuditAiError(502, "MATERIAL_AI_FAILED", "material AI extraction failed") from exc
        except AuditAiError as exc:
            if exc.code == 'MATERIAL_AI_TIME_LIMIT' and 'operation' in locals(): checkpoints.discard(operation)
            raise
        finally:
            capacity.release()

    @router.post("/extract-fields")
    async def extract(data: ExtractionRequest, request: Request):
        # Reject before entering Starlette's worker pool, so waiting requests cannot fill its queue.
        if not capacity.acquire(blocking=False):
            log_event(logging.getLogger("audit_ai.material_fields"), "MATERIAL_CAPACITY_REJECTED",
                      request_id=request.state.request_id, stage="CAPACITY", outcome="MATERIAL_AI_BUSY")
            raise AuditAiError(503, "MATERIAL_AI_BUSY", "material extraction capacity is busy")
        return await run_in_threadpool(execute, data, request.state.request_id)

    return router
