"""F10: bounded review of original risk anchors against document-wide qualifications.

The model may qualify its own hints, never delete a deterministic rule finding.
Every decision covers one immutable candidate and cites server-selected original text.
"""
import json
import re
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field
from audit_ai.errors import AuditAiError

_QUALIFICATION = re.compile(r'但|除外|例外|不适用|同等|等效|不限|不限制|不限定|允许|均可|更正|变更|补充|以.+为准')
_TOPICS = (
    r'品牌|型号|同等|等效|技术参数', r'本地|本市|本省|外地|外省|注册地|地域',
    r'资质|资格|授权|注册资本|业绩', r'评分|分值|加分', r'交付|交货|工期|期限',
    r'付款|支付|预付|结算', r'质保|保修', r'保证金|担保',
)


class ReconciliationEvidence(BaseModel):
    model_config = ConfigDict(extra='forbid', populate_by_name=True)
    source_locator: str = Field(alias='sourceLocator', min_length=1, max_length=500)
    excerpt: str = Field(min_length=1, max_length=1000)


class ReconciliationDecision(BaseModel):
    model_config = ConfigDict(extra='forbid', populate_by_name=True)
    candidate_id: str = Field(alias='candidateId')
    decision: Literal['RETAIN', 'CONTEXT_DEPENDENT', 'REBUTTED']
    reason: str = Field(min_length=1, max_length=600)
    evidence: list[ReconciliationEvidence] = Field(min_length=1, max_length=12)


class ReconciliationResult(BaseModel):
    model_config = ConfigDict(extra='forbid', populate_by_name=True)
    decisions: list[ReconciliationDecision] = Field(min_length=1, max_length=1)


def reconciliation_plans(request, findings, settings):
    candidates = {}
    for finding in findings:
        value = finding.model_dump(by_alias=True)
        key = (finding.source_locator, finding.matched_text, finding.risk_category)
        candidates[key] = value
    for rule in request.rule_findings:
        key = (rule.source_locator, rule.matched_text, rule.risk_category)
        candidates.setdefault(key, dict(sourceLocator=rule.source_locator, matchedText=rule.matched_text,
            pageNumber=None, riskCategory=rule.risk_category, riskLevel=rule.risk_level,
            rationale='规则命中，需结合适用范围核对。', remediationSuggestion=rule.remediation_suggestion,
            confidence=1.0))
    plans=[]
    for value in candidates.values():
        anchor = value['matchedText']
        topics=[re.compile(p) for p in _TOPICS if re.search(p, value['riskCategory']+' '+anchor)]
        # An unrelated genuine qualification is not evidence for this finding.
        # Only run this bounded review for the topics we can actually select.
        if not topics:continue
        contexts=[];seen=set()
        for segment in request.segments:
            for sentence in re.finditer(r'[^。；;\r\n]+[。；;]?',segment.text):
                text=sentence.group()
                if not _QUALIFICATION.search(text):continue
                if topics and not any(p.search(text) for p in topics):continue
                # The anchor alone is not independent counterevidence.
                if text.strip() in anchor or anchor in text and len(text.strip())<=len(anchor)+1:continue
                identity=(segment.source_locator,text)
                if identity in seen:continue
                seen.add(identity)
                contexts.append(dict(sourceLocator=segment.source_locator,text=text))
        if not contexts:continue
        candidate_id=f'candidate:{len(plans)}'
        source=dict(reviewFocus='COUNTEREVIDENCE_REVIEW',candidateId=candidate_id,
                    candidate=value,qualifyingSources=contexts)
        rendered=json.dumps(source,ensure_ascii=False,separators=(',',':'))
        if (len(plans)>=32 or len(rendered)>min(settings.model_chunk_chars,settings.material_chunk_chars)
                or len(rendered.encode())>settings.material_chunk_bytes):
            raise AuditAiError(413,'MODEL_RECONCILIATION_LIMIT','counterevidence review cannot fit complete context')
        plans.append((candidate_id,value,contexts,rendered))
    return plans


def apply_decision(plan, payload):
    candidate_id, candidate, contexts, _ = plan
    result=ReconciliationResult.model_validate(payload)
    decision=result.decisions[0]
    if decision.candidate_id!=candidate_id:
        raise ValueError('reconciliation candidate identity mismatch')
    # Citing genuine text from another candidate/document is still invalid.
    for evidence in decision.evidence:
        if not any(evidence.source_locator==c['sourceLocator'] and evidence.excerpt in c['text'] for c in contexts):
            raise ValueError('reconciliation evidence not in supplied qualifications')
    if decision.decision=='REBUTTED' and not any(_QUALIFICATION.search(e.excerpt) for e in decision.evidence):
        raise ValueError('rebuttal lacks an original qualification')
    citations='；'.join(dict.fromkeys(e.excerpt for e in decision.evidence))
    updated=dict(candidate)
    # Retain the original issue/evidence and explicitly show the qualification.
    # INFO qualifies AI-only hints; Java retains deterministic rule severity.
    if decision.decision=='REBUTTED':updated['riskLevel']='INFO'
    updated['rationale']=(f'原文另有说明：“{citations}”。{decision.reason} '
                          '请核对是否适用于同一事项，不能仅凭局部条款认定违规。')
    if len(updated['rationale'])>2000:raise ValueError('reconciliation reason exceeds output bound')
    return updated
