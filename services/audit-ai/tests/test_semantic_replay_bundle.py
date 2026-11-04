"""Approved synthetic capture retains failures, not thought or HTTP envelopes."""
import copy
import importlib.util
import json
import os
from pathlib import Path
from uuid import uuid4
import pytest

from audit_ai.errors import AuditAiError

pytestmark = pytest.mark.skipif(os.name != 'nt', reason='synthetic replay requires Windows ACL enforcement')


def replay_module():
    name='audit_ai.model.semantic_replay_bundle'
    assert importlib.util.find_spec(name) is not None,'restricted replay not implemented'
    return __import__(name,fromlist=['SyntheticReplayStore'])


def authority():
    return {'syntheticOnly':True,'projectId':'v205-synthetic-project','runId':str(uuid4()),
        'frozenInputFingerprint':'a'*64,'files':[{'fileId':str(uuid4()),'parseResultId':str(uuid4()),'sha256':'b'*64}],
        'allowedStages':['FINAL_DIAGNOSTIC','GROUP','FINAL'],'policyFingerprint':'c'*64}


def make(tmp_path):
    m=replay_module();a=authority()
    # Root is inside the actual ignored worktree, never the pytest system temp.
    workspace=next(p for p in Path(__file__).resolve().parents if (p/'.git').exists())
    root=workspace/'.runtime/contract-repair-test/synthetic-replay-tests'/uuid4().hex
    try:
        store=m.SyntheticReplayStore(root,a,workspace=workspace)
    except AuditAiError as error:
        if str(error) in {'SYNTHETIC_REPLAY_ACL_UNPROVEN', 'SYNTHETIC_REPLAY_WINDOWS_ACL_REQUIRED'}:
            pytest.skip('test host cannot prove the required private ACL')
        raise
    return m,store,a


def test_capture_before_validation_and_replay_same_native_coordinate(tmp_path):
    m,store,a=make(tmp_path)
    from audit_ai.model.semantic_quality import parse_scoped_draft,QualityFailure
    payload={'summary':'合成测试','hints':[{'category':'WRONG'}],'resolutions':[]}
    bundle=store.capture(payload,authority=a,stage='FINAL_DIAGNOSTIC',operation_fingerprint='d'*64,
        input_references=[{'path':'frozen-only','sha256':'e'*64}],source_hashes={'native':'f'*64})
    assert bundle['state']=='REPLAYABLE'
    assert (Path(bundle['directory'])/'candidate.json').exists()
    def native(value):
        try:parse_scoped_draft(value,synthesis=True)
        except QualityFailure as failure:return {'valid':False,'fieldPath':failure.field_path,'code':failure.code}
        raise AssertionError('invalid candidate unexpectedly accepted')
    observed=native(payload);store.validate(bundle,observed)
    assert store.replay(bundle,authority=a,source_hashes={'native':'f'*64},
        input_references=[{'path':'frozen-only','sha256':'e'*64}],native_validator=native)==observed


@pytest.mark.parametrize('bad',[{'reasoning':'secret'},{'hints':[],'token':'secret'},
    {'summary':'<think>private thinking</think>'},{'summary':'x'*1048576},
    {'summary':'合成业务候选','messages':[{'role':'system','content':'不应留存的指令'}]},
    {'summary':'合成业务候选','choices':[{'message':{'content':'完整响应封装'}}]},
    {'protocol':'contract-semantic-business-issues-v1','summary':'合成测试','decisions':[
        {'issueId':'I123','findings':[{'category':'WRONG','messages':[]}],'reason':'合成原因'}],
        'additionalFindings':[]}])
def test_unsafe_or_over_bound_is_not_modified_into_replayable_candidate(tmp_path,bad):
    _,store,a=make(tmp_path)
    bundle=store.capture(bad,authority=a,stage='FINAL',operation_fingerprint='d'*64,
        input_references=[{'path':'frozen-only','sha256':'e'*64}],source_hashes={'native':'f'*64})
    assert bundle['state']=='NOT_REPLAYABLE'
    assert not (Path(bundle['directory'])/'candidate.json').exists()


def test_replay_fails_closed_missing_inputs_tampering_or_other_run(tmp_path):
    _,store,a=make(tmp_path);payload={'summary':'合成测试','hints':[]}
    args=dict(authority=a,stage='FINAL',operation_fingerprint='d'*64,
        input_references=[{'path':'frozen-only','sha256':'e'*64}],source_hashes={'native':'f'*64})
    bundle=store.capture(payload,**args);store.validate(bundle,{'valid':True,'resultSha256':'0'*64})
    for change in ('input','source','run','candidate'):
        aa=copy.deepcopy(a);refs=copy.deepcopy(args['input_references']);hashes=dict(args['source_hashes'])
        if change=='input':refs=[]
        if change=='source':hashes['native']='0'*64
        if change=='run':aa['runId']=str(uuid4())
        if change=='candidate':(Path(bundle['directory'])/'candidate.json').write_text('{}',encoding='utf-8')
        with pytest.raises(AuditAiError):
            store.replay(bundle,authority=aa,source_hashes=hashes,input_references=refs,
                native_validator=lambda value:pytest.fail('unproven context reached validator'))


def test_replayer_denies_network_even_if_native_validator_tries(tmp_path):
    _,store,a=make(tmp_path);payload={'summary':'合成测试','hints':[]}
    bundle=store.capture(payload,authority=a,stage='FINAL',operation_fingerprint='d'*64,
        input_references=[{'path':'frozen-only','sha256':'e'*64}],source_hashes={'native':'f'*64})
    store.validate(bundle,{'valid':True})
    def native(value):
        import socket
        with pytest.raises(AuditAiError,match='NO_OUTBOUND_ALLOWED'):socket.create_connection(('127.0.0.1',1))
        return {'valid':True}
    assert store.replay(bundle,authority=a,source_hashes={'native':'f'*64},input_references=[{'path':'frozen-only','sha256':'e'*64}],native_validator=native)=={'valid':True}


def test_missing_frozen_references_never_claims_replayable(tmp_path):
    _,store,a=make(tmp_path)
    bundle=store.capture({'summary':'合成测试','hints':[]},authority=a,stage='FINAL',operation_fingerprint='d'*64,
        input_references=[],source_hashes={})
    assert bundle['state']=='NOT_REPLAYABLE'


def test_safe_usage_metadata_is_not_confused_with_prompt_content(tmp_path):
    _,store,a=make(tmp_path)
    bundle=store.capture({'summary':'合成候选','hints':[]},authority=a,stage='FINAL',operation_fingerprint='d'*64,
        input_references=[{'kind':'PINNED_FROZEN','sha256':'e'*64}],source_hashes={'native':'f'*64},
        original_metadata={'promptTokens':123,'completionTokens':'UNKNOWN','totalTokens':'UNKNOWN'})
    assert bundle['state']=='REPLAYABLE'


def test_generic_worker_refuses_unproven_run_parse_identity_before_send(tmp_path,monkeypatch):
    from dataclasses import replace
    import hashlib
    from test_semantic_hierarchy_execution import setup
    from test_final_frozen_evidence_execution import Provider
    from test_semantic_business_adapter import valid
    from audit_ai.model.router import ContractAnalysisRequest
    from audit_ai.model.semantic_work import ContractSemanticWorkService
    monkeypatch.setenv('AUDIT_CONTRACT_SEMANTIC_BUSINESS_ADAPTER','v1')
    _,_,settings,old=setup(repeat=8)
    data=old.model_dump(by_alias=True);data['semanticHierarchyProtocol']='contract-bounded-hierarchy-v2'
    for material in data['materials']:material['fileId']=str(uuid4())
    request=ContractAnalysisRequest.model_validate(data)
    class BadBusiness(Provider):
        replay_test_dispatch_count=0
        def analyze_contract(self,operation):
            self.replay_test_dispatch_count+=1
            completion=super().analyze_contract(operation)
            if operation.hierarchy_role in {'GROUP','FINAL'}:
                payload=valid(json.loads(operation.source_text));payload['decisions'][0]['findings'][0]['category']='SYNTHETIC_INVALID_CATEGORY'
                return replace(completion,payload=payload)
            return completion
    service=ContractSemanticWorkService(settings,BadBusiness('FINAL',invalid=False,money=False));plan=service.plan(request)
    m=replay_module();a=authority();a.update(projectId=request.project_id,policyFingerprint=plan['policyFingerprint'],
        frozenInputFingerprint=plan['inputFingerprint'],files=[{'fileId':v.file_id,'parseResultId':str(uuid4()),'sha256':hashlib.sha256(v.text.encode()).hexdigest()} for v in request.materials])
    workspace=next(p for p in Path(__file__).resolve().parents if (p/'.git').exists());root=workspace/'.runtime/contract-repair-test/synthetic-replay-tests'/uuid4().hex
    config={'workspace':str(workspace),'root':str(root),'authority':a,'inputReferences':[{'path':'explicit-frozen-synthetic-test','sha256':'e'*64}]}
    config_path=tmp_path/'capture-config.json';config_path.write_text(json.dumps(config),encoding='utf-8')
    monkeypatch.setenv('AUDIT_CONTRACT_SYNTHETIC_REPLAY_CONFIG',str(config_path))
    with pytest.raises(AuditAiError,match='SYNTHETIC_REPLAY_EXECUTION_IDENTITY_UNPROVEN'):
        service.advance(request,plan['planFingerprint'],[],plan['next']['operationFingerprint'],remaining_ms=800000,execute=True)
    assert service.gateway.delegate.replay_test_dispatch_count==0
    assert not root.exists()
