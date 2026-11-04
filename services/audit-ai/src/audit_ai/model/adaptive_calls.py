"""Bounded recovery for semantic calls over intact business units."""
import time
from audit_ai.errors import AuditAiError
from audit_ai.logging import log_event
from audit_ai.model.gateway import ModelGatewayFailure
import logging


def adaptive_calls(chunks, invoke, *, budget_seconds=900, clock=time.monotonic, splitter=None, pass_remaining_budget=False, atomic_group=None):
    """Split only proven limits; never split one JSON row into invalid fragments."""
    budget_seconds=900 if budget_seconds is None else budget_seconds
    started=clock();work=[(list(chunk),0) for chunk in chunks];calls=0
    while work:
        units,depth=work.pop(0);calls+=1
        remaining=budget_seconds-(clock()-started)
        if calls>512 or remaining<=0:
            raise AuditAiError(504,'MODEL_TOTAL_BUDGET_EXHAUSTED','bounded semantic operation exhausted')
        reason=None
        try:
            completion=invoke(units,calls,calls+len(work),remaining) if pass_remaining_budget else invoke(units,calls,calls+len(work))
            if completion.finish_reason=='LENGTH':reason='OUTPUT_TRUNCATED'
        except (ModelGatewayFailure,AuditAiError) as exc:
            failure=exc if isinstance(exc,ModelGatewayFailure) else exc.__cause__
            if isinstance(failure,ModelGatewayFailure) and failure.input_limited:reason='INPUT_LIMIT'
            elif (isinstance(failure,ModelGatewayFailure) and failure.reason in {'INVALID_PROVIDER_JSON','INVALID_PROVIDER_RESPONSE'}
                  and failure.finish_reason=='LENGTH'):reason='OUTPUT_TRUNCATED'
            else:raise
        if reason:
            if atomic_group and atomic_group(units):
                parts=None
            elif len(units)>1:
                middle=len(units)//2;parts=[units[:middle],units[middle:]]
            else:parts=splitter(units) if splitter else None
            log_event(logging.getLogger('audit_ai.model.adaptive'),'SEMANTIC_CHUNK_SPLIT',stage='CHUNKING',
                      outcome='RETRY_SMALLER' if parts and depth<5 else 'LIMIT_REACHED',
                      reason_code=reason,chunk_index=calls,chunk_count=calls+len(work),attempt=depth+1)
            if not parts or depth>=5:
                raise AuditAiError(413,'MODEL_INPUT_TOO_LARGE','one semantic unit cannot fit provider limits')
            work[:0]=[(part,depth+1) for part in parts]
            continue
        if clock()-started>=budget_seconds:
            raise AuditAiError(504,'MODEL_TOTAL_BUDGET_EXHAUSTED','bounded semantic operation exhausted')
        yield units,completion
