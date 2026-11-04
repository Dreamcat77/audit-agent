"""Lossless repeated disposition metadata, never shortened claim text."""
import copy
import pytest
from audit_ai.errors import AuditAiError
from audit_ai.model import semantic_hierarchy as h
from test_semantic_hierarchy import bundle, candidate

def inverse(digest):
    value=copy.deepcopy(digest)
    if 'lineageReasonEncoding' not in value:
        return value
    assert value.pop('lineageReasonEncoding')=='contract-lineage-reason-pool-v1'
    pool=value.pop('lineageReasonPool')
    for row in value['lineage']:
        row[-1]=pool[row[-1]]
    return value

def test_repeated_reasons_fit_without_changing_claims_or_evidence():
    b=bundle(3,hints_per_analysis=10);g=b['groups'][0];value=candidate(b,g)
    for row in value['lineage']: row[-1]='保留同一原始观察、完整日期及全部证据。'
    before=copy.deepcopy(value)
    wide={**g,'digestMaxChars':12000}
    digest=h.group_digest(value,wide,b)
    raw=inverse(digest)
    assert digest['lineageReasonEncoding']=='contract-lineage-reason-pool-v1'
    assert raw['claims']==digest['claims'] and value==before
    assert len(h.canonical(digest))<len(h.canonical(raw))
    bound=len(h.canonical(digest))
    assert h.group_digest(value,{**g,'digestMaxChars':bound},b)==digest
    with pytest.raises(AuditAiError):h.group_digest(value,{**g,'digestMaxChars':bound-1},b)
    assert [row[-1] for row in raw['lineage']]==[row[-1] for row in value['lineage']]

def test_unique_short_reasons_do_not_gain_encoding_overhead():
    b=bundle(1,hints_per_analysis=2);g=b['groups'][0];value=candidate(b,g)
    for i,row in enumerate(value['lineage']): row[-1]=str(i)
    digest=h.group_digest(value,g,b)
    assert 'lineageReasonEncoding' not in digest
    assert h.expand_group_digest(digest)==digest

@pytest.mark.parametrize('defect',['boolean','negative','outOfBounds','unused','duplicate','marker','nullMarker','missingPool'])
def test_pool_reference_tampering_is_rejected_before_final_source(defect):
    b=bundle(3,hints_per_analysis=10);g=b['groups'][0];value=candidate(b,g)
    for row in value['lineage']:row[-1]='保留同一原始观察及全部证据。'
    digest=h.group_digest(value,{**g,'digestMaxChars':12000},b)
    assert 'lineageReasonPool' in digest
    if defect in {'boolean','negative','outOfBounds'}:
        digest['lineage'][0][-1]={'boolean':True,'negative':-1,'outOfBounds':999}[defect]
    elif defect=='unused':digest['lineageReasonPool'].append('unused')
    elif defect=='duplicate':digest['lineageReasonPool'].append(digest['lineageReasonPool'][0])
    elif defect=='nullMarker':digest['lineageReasonEncoding']=None
    elif defect=='missingPool':digest.pop('lineageReasonPool')
    else:digest['lineageReasonEncoding']='unknown'
    with pytest.raises(AuditAiError):h.expand_group_digest(digest)


@pytest.mark.parametrize('defect',['float','extra','firstSeen'])
def test_final_source_itself_checks_pool_before_accepting_a_digest(defect):
    b=bundle(1,hints_per_analysis=12);g=b['groups'][0];value=candidate(b,g)
    for i,row in enumerate(value['lineage']):row[-1]=('保留原始问题与证据甲。' if i%2 else '保留原始问题与证据乙。')
    digest=h.group_digest(value,g,b)
    assert 'lineageReasonPool' in digest
    if defect=='float':digest['lineage'][0][-1]=0.0
    elif defect=='extra':digest['extra']='untrusted'
    else:
        digest['lineageReasonPool'].reverse()
        for row in digest['lineage']:row[-1]=1-row[-1]
    with pytest.raises(AuditAiError):h.final_source(b,[digest])


def test_capacity_failure_audit_keeps_precise_kind_without_publishing(monkeypatch):
    from test_semantic_hierarchy_execution import setup, drive
    def reject(*_args):raise AuditAiError(413,'SEMANTIC_HIERARCHY_DIGEST_BOUND','capacity exceeded')
    monkeypatch.setattr(h,'group_digest',reject)
    service,provider,_,request=setup()
    _,packets,last=drive(service,provider,request)
    failed=[p for p in packets if p['role']=='GROUP']
    assert [p['status'] for p in failed]==['REPAIR_REQUIRED','FAILED']
    assert all(p['call']['safeReason']=='SEMANTIC_HIERARCHY_DIGEST_BOUND' for p in failed)
    assert last['result'] is None and last['next'] is None
