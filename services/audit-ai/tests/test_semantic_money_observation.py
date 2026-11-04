"""R2 contract-only observation: real validators and synthetic provider wires."""
import copy
from dataclasses import replace

import pytest

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.model.router import UnprocessableContractResult
from audit_ai.model.semantic_work import ContractSemanticWorkService, _JournaledApplication, checkpoint_hash
from test_money_repair_guidance import setup
from test_semantic_work_observations import fixture, normalize


def observed(stage='ANALYZE', repeat=True):
    service, request, wires, payloads, counts = setup(stage, repeat)
    settings = replace(service.settings, contract_semantic_money_mode='OBSERVE',
                       contract_semantic_observe_project_ids=(request.project_id,))
    request = request.model_copy(update={'semantic_money_mode': 'OBSERVE'})
    # Keep the actual evidence planner; shorten only this public unit fixture.
    request = request.model_copy(update={'materials': [request.materials[0].model_copy(
        update={'text': request.materials[0].text[:500]})]})
    return ContractSemanticWorkService(settings, service.gateway), request, wires, payloads, counts


def test_default_is_strict_and_unknown_mode_or_conflicting_legacy_flag_is_rejected(monkeypatch):
    monkeypatch.delenv('AUDIT_CONTRACT_SEMANTIC_MONEY_MODE', raising=False)
    monkeypatch.delenv('AUDIT_CONTRACT_SEMANTIC_OBSERVE_PROJECT_IDS', raising=False)
    assert getattr(Settings.from_env(), 'contract_semantic_money_mode', None) == 'STRICT'
    with pytest.raises(ValueError):
        Settings(contract_semantic_money_mode='OFF')
    with pytest.raises(ValueError):
        Settings(contract_semantic_money_mode='OBSERVE')
    with pytest.raises(ValueError):
        Settings(contract_semantic_money_mode='OBSERVE', contract_semantic_observe_project_ids=('p',),
                 demo_relax_breach_monetary=True)


@pytest.mark.parametrize('stage', ['ANALYZE', 'SYNTHESIZE'])
def test_actual_plan_all_stages_keep_money_text_and_complete_without_money_only_repair(stage):
    service, request, wires, payloads, counts = observed(stage)
    plan = service.plan(request)
    assert plan['policy']['monetaryMode'] == 'OBSERVE'
    assert plan['policy']['monetaryObservationProtocol'] == 'contract-money-observation-v1'
    prefix = []
    operation = plan['next']['operationFingerprint']
    for _ in range(plan['callLimit']):
        response = service.advance(request, plan['planFingerprint'], prefix, operation,
                                   remaining_ms=800000, execute=True)
        packet = response['checkpoint']
        assert packet['status'] == 'VERIFIED', packet.get('validationIssues')
        assert packet['monetaryObservation']['mode'] == 'OBSERVE'
        assert packet['completion']['payload'] == payloads[-1]
        if packet['purpose'] == stage:
            assert packet['monetaryObservation']['warnings']
            assert any(h['description'] == '合同金额与中标金额一致，后续交由F02确定性金额规则核对。'
                       for h in packet['validatedResult']['hints'])
        prefix.append(packet)
        if response['result']:
            break
        assert response['next'] is not None
        operation = response['next']['operationFingerprint']
    else:
        pytest.fail('complete real validation path did not terminate')
    assert len(wires) == len(plan['units'])
    assert all(p['purpose'] != 'REPAIR' for p in prefix)
    assert response['result']['monetaryObservation']['mode'] == 'OBSERVE'
    assert any(p['monetaryObservation']['warnings'] for p in prefix)
    assert plan['timeLimitMs'] == 900000 and plan['unitTimeLimitMs'] == 300000
    assert plan['maxOutputTokensPerCall'] == 6144 and plan['repairLimit'] == 2


@pytest.mark.parametrize('category', ['CONTRACT_STRUCTURE', 'DELIVERY_TERMS', 'BREACH_LIABILITY'])
@pytest.mark.parametrize('attempt', [1, 2])
def test_local_work_and_repair_observe_money_but_preserve_witnesses_and_nonmoney_guards(category, attempt):
    app, catalog, hint = fixture(attempt=attempt)
    app = _JournaledApplication(Settings(model_name='unit-model', contract_semantic_money_mode='OBSERVE',
        contract_semantic_observe_project_ids=('synthetic-project',)), app.replay, 'contract-semantic-quality-v1')
    hint.update(category=category, description='本块金额提示100元需综合核实，当前片段未见完整约定。')
    original = copy.deepcopy(hint)
    result = normalize(app, catalog, hint)
    assert result[0].hints[0].description == original['description']
    assert hint == original and result[1] == 0 and not result[2]
    warnings = app.replay.monetary_observation['warnings']
    assert warnings[0]['errorKind'] == 'MONETARY_VALUE'
    assert warnings[0]['category'] == category
    assert warnings[0]['fieldPath'] == 'hints[0].description'
    for invalid in ({'comparisonEvidenceIds': ['FOREIGN']}, {'attention': 'INFO'},
                    {'description': '合同合法有效，应支付100元。'}):
        broken = {**hint, **invalid}
        with pytest.raises(UnprocessableContractResult):
            normalize(app, catalog, broken)


def test_mode_and_project_conflicts_fail_before_provider_and_strict_plan_cannot_reuse_observe_checkpoint():
    service, request, wires, _, _ = observed()
    plan = service.plan(request)
    first = service.advance(request, plan['planFingerprint'], [], plan['next']['operationFingerprint'],
                            remaining_ms=800000, execute=True)
    before = len(wires)
    foreign = request.model_copy(update={'project_id': 'foreign'})
    with pytest.raises(AuditAiError):
        service.plan(foreign)
    strict_service = ContractSemanticWorkService(replace(service.settings, contract_semantic_money_mode='STRICT',
        contract_semantic_observe_project_ids=()), service.gateway)
    with pytest.raises(AuditAiError):
        strict_service.plan(request)
    strict_request = request.model_copy(update={'semantic_money_mode': 'STRICT'})
    strict_plan = strict_service.plan(strict_request)
    assert strict_plan['policyFingerprint'] != plan['policyFingerprint']
    with pytest.raises(AuditAiError):
        strict_service.advance(strict_request, strict_plan['planFingerprint'], [first['checkpoint']], None,
                               remaining_ms=700000, execute=False)
    assert len(wires) == before


def test_observation_replay_rederives_warning_metadata_and_rejects_even_rehashed_tampering():
    service, request, wires, _, _ = observed()
    plan = service.plan(request)
    first = service.advance(request, plan['planFingerprint'], [], plan['next']['operationFingerprint'],
                            remaining_ms=800000, execute=True)
    packet = first['checkpoint']
    restarted = ContractSemanticWorkService(service.settings, service.gateway)
    resumed = restarted.advance(request, plan['planFingerprint'], [packet], None,
                                remaining_ms=700000, execute=False)
    assert resumed['next'] == first['next'] and len(wires) == 1
    tampered = copy.deepcopy(packet)
    tampered['monetaryObservation']['warnings'] = []
    tampered['checkpointHash'] = checkpoint_hash({k: v for k, v in tampered.items() if k != 'checkpointHash'})
    with pytest.raises(AuditAiError):
        restarted.advance(request, plan['planFingerprint'], [tampered], None,
                          remaining_ms=700000, execute=False)
    assert len(wires) == 1


def test_observe_typed_failure_keeps_one_required_repair_without_money_feedback_or_lost_warning():
    import json
    import httpx
    service, request, wires, _, counts = observed()
    transport = service.gateway.delegate._transport
    def provider(req):
        response = transport.handle_request(req)
        if counts['ANALYZE'] == 1:
            body = response.json()
            payload = json.loads(body['choices'][0]['message']['content'])
            local = copy.deepcopy(payload['hints'][1])
            local.update(title='本块需综合核对', description='仅当前块未见对应条款，需全局核对。',
                         claimKind='LOCAL_NOT_SEEN', attention='INFO')
            payload['hints'].append(local)
            body['choices'][0]['message']['content'] = json.dumps(payload, ensure_ascii=False)
            return httpx.Response(200, json=body)
        return response
    service.gateway.delegate._transport = httpx.MockTransport(provider)
    plan = service.plan(request)
    first = service.advance(request, plan['planFingerprint'], [], plan['next']['operationFingerprint'],
                            remaining_ms=800000, execute=True)
    packet = first['checkpoint']
    assert packet['status'] == 'REPAIR_REQUIRED'
    assert [i['errorKind'] for i in packet['validationIssues']] == ['WORK_OBSERVATION_TYPE']
    assert packet['monetaryObservation']['warnings'][0]['errorKind'] == 'MONETARY_CALCULATION'
    second = service.advance(request, plan['planFingerprint'], [packet], first['next']['operationFingerprint'],
                             remaining_ms=700000, execute=True)
    assert second['checkpoint']['status'] == 'VERIFIED'
    assert second['checkpoint']['monetaryObservation']['warnings']
    assert counts['ANALYZE'] == 2
    assert 'REGENERATE_WITH_STAGE_VALID_CLAIM_AND_EVIDENCE_TYPE' in wires[1]['messages'][0]['content']
    assert 'REGENERATE_WITHOUT_MONETARY_VERDICTS' not in wires[1]['messages'][0]['content']


def test_server_records_every_monetary_field_and_actual_rule_subtype_without_provider_metadata_authority():
    app, catalog, hint = fixture()
    app = _JournaledApplication(Settings(model_name='unit-model', contract_semantic_money_mode='OBSERVE',
        contract_semantic_observe_project_ids=('synthetic-project',)), app.replay, 'contract-semantic-quality-v1')
    hint.update(description='当前片段金额100元待核对。')
    other = {**hint, 'description': '本块合同金额与中标金额一致，需全局核对。'}
    payload = {'summary': '当前金额100元仅供核对。', 'scopeStatus': 'CHECKED_WITH_FINDINGS', 'hints': [hint, other]}
    before = copy.deepcopy(payload)
    app._normalize_contract_result(payload, {}, {'CONTRACT_BODY', 'TENDER_DOCUMENT'}, catalog,
                                   {'T1', 'X1'}, operation_key='A1')
    warnings = app.replay.monetary_observation['warnings']
    assert [w['fieldPath'] for w in warnings] == ['summary', 'hints[0].description', 'hints[1].description']
    assert 'ABSOLUTE_VALUE' in warnings[0]['ruleSubtypes']
    assert 'UNSAFE_CONCLUSION' in warnings[2]['ruleSubtypes']
    assert all(len(w['textSha256']) == 64 for w in warnings)
    assert payload == before


def test_money_diagnostic_cannot_mask_primary_invalid_utf8_or_lose_its_completed_call_audit():
    import json
    import httpx
    service, request, wires, _, _ = observed()
    transport = service.gateway.delegate._transport
    def provider(req):
        response = transport.handle_request(req)
        body = response.json()
        payload = json.loads(body['choices'][0]['message']['content'])
        payload['hints'][0]['description'] = '金额100元\ud800'
        body['choices'][0]['message']['content'] = json.dumps(payload, ensure_ascii=True)
        return httpx.Response(200, json=body)
    service.gateway.delegate._transport = httpx.MockTransport(provider)
    plan = service.plan(request)
    response = service.advance(request, plan['planFingerprint'], [], plan['next']['operationFingerprint'],
                               remaining_ms=800000, execute=True)
    packet = response['checkpoint']
    assert packet['status'] == 'FAILED'
    assert packet['checkpointFailureCode'] == 'SEMANTIC_CHECKPOINT_ENCODING'
    assert packet['call']['safeReason'] == 'TYPE_MISMATCH'
    assert packet['call']['finishReason'] == 'STOP' and packet['call']['totalTokens'] == 300
    assert response['result'] is None and response['next'] is None and len(wires) == 1


def test_untyped_category_cannot_crash_or_echo_arbitrary_values_in_money_metadata():
    app, _, _ = fixture()
    for category in ([], {}, ['private-source-canary'], 9, None, 'private-source-canary'):
        diagnostic = app._observe_money({'summary': '核对当前材料。', 'hints': [
            {'category': category, 'title': '待核实', 'description': '合同金额100元待核实。'}]})
        assert diagnostic['warnings'][0]['category'] is None
        assert 'private-source-canary' not in str(diagnostic)
