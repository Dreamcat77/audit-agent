"""Synthetic actual gateway wire assertions; MockTransport forbids real HTTP.

RED tests describe a small derived field contract and precise repair feedback.
Controls prove the existing authoritative limits must NOT be weakened or fixed
by mutating a provider response. No actual failed provider literal is included.
"""
import copy
from dataclasses import replace
import json
from typing import get_args

import httpx
import pytest
from pydantic import ValidationError

from audit_ai.config import Settings
from audit_ai.model.contract_draft import MaterialCategory, ProviderHintDraft, ProviderValidationIssue
from audit_ai.model.gateway import ContractTextOperation, OpenAiCompatibleModelGateway, contract_material_scope_schema
from audit_ai.model.semantic_quality import build_regeneration_context, quality_schema, parse_scoped_draft, check_claims, QualityFailure

PROTOCOL = 'contract-hierarchy-hint-field-contract-v1'
MATERIALS = tuple(get_args(MaterialCategory))


def hint():
    return {'category': 'CONTRACT_STRUCTURE', 'attention': 'INFO', 'title': '核查已展示条款',
        'description': '本意见仅对应其明确引用的条款。', 'problemEvidenceIds': [],
        'comparisonEvidenceIds': ['e0'], 'unavailableMaterialCategory': None,
        'claimKind': 'CLAUSE_PRESENT',
        'obligationScope': {'topic': 'STRUCTURE', 'responsibleParty': 'UNSPECIFIED', 'trigger': None}}


def candidate(role):
    raw = {'protocol': 'contract-hierarchy-provider-references-v1', 'summary': '核查摘要', 'newHints': [hint()]}
    if role == 'GROUP': raw['lineage'] = [['A1:H1', 'KEEP', 'N0', [0], '核查当前条款。']]
    else: raw['resolutions'] = [[0, 0, 'RETAINED', ['N0'], [0], '核查当前条款。']]
    return raw


def invalid_material_missing(role):
    raw = candidate(role)
    raw['newHints'][0].update(category='SCOPE_AND_SUBCONTRACTING', attention='INSUFFICIENT',
        claimKind='MATERIAL_MISSING', obligationScope={'topic':'SUBCONTRACTING','responsibleParty':'PARTY_B','trigger':None},
        comparisonEvidenceIds=[f'e{i}' for i in range(51)], unavailableMaterialCategory='SYNTHETIC_INVALID_LITERAL')
    # This is a new global issue, not a replacement for an existing leaf.
    if role == 'FINAL': raw['resolutions'] = [[0, 0, 'RETAINED', ['G1:H1'], None, None]]
    return raw


def operation(role, available=MATERIALS):
    source = {'hierarchyProtocol': 'contract-bounded-hierarchy-v1', 'role': role,
        'groupingFingerprint': 'a'*64,
        # The checked-scope bag is intentionally larger than one hint's limit.
        'checkedScopeWitness': [f'e{i}' for i in range(55)],
        'evidence': [[i, f'e{i}', 'file', i, i+1, True] for i in range(55)]}
    if role == 'GROUP': source['analyses'] = [{'leaves': [['A1:H1']]}]
    else: source['groups'] = [{'claims': [[0]]}]
    return ContractTextOperation(project_id='synthetic-only', chunk_index=1, chunk_count=1,
        source_text=json.dumps(source, ensure_ascii=False, separators=(',', ':')), purpose='SYNTHESIZE',
        generation_policy='contract-semantic-work-v3', quality_protocol='contract-semantic-quality-v1',
        hierarchy_protocol='contract-bounded-hierarchy-v1', hierarchy_role=role,
        hierarchy_logical_key='b'*64, grouping_fingerprint='a'*64,
        globally_available_material_categories=available, current_operation_material_categories=available)


def capture(op, response=None):
    response = candidate(op.hierarchy_role) if response is None else response
    observed = []
    def transport(request):
        observed.append(json.loads(request.content))
        return httpx.Response(200, json={'model': 'synthetic-model', 'choices': [
            {'finish_reason': 'stop', 'message': {'content': json.dumps(response, ensure_ascii=False)}}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30}})
    gateway = OpenAiCompatibleModelGateway(Settings(model_base_url='https://provider.invalid/v1',
        model_api_key='synthetic-only', model_name='synthetic-model', semantic_structured_transport='PROMPT_ONLY'),
        transport=httpx.MockTransport(transport))
    completion = gateway.analyze_contract(op)
    assert len(observed) == 1 and completion.payload == response and completion.finish_reason == 'STOP'
    body = observed[0]
    assert body['max_tokens'] == 6144 and body['response_format'] == {'type': 'json_object'}
    assert body['messages'][1]['content'].split('source:\n', 1)[1] == op.source_text
    return body, completion


def marker(body, name):
    system = body['messages'][0]['content']
    assert name + '=' in system, 'actual hierarchy request lacks explicit ' + name
    return json.loads(system.split(name + '=', 1)[1].splitlines()[0])


def allowed_null_values(schema):
    values = schema.get('enum', [])
    for branch in schema.get('anyOf', []): values += branch.get('enum', [])
    nullable = schema.get('type') == 'null' or any(v.get('type') == 'null' for v in schema.get('anyOf', []))
    return values, nullable


@pytest.mark.parametrize('role', ['GROUP', 'FINAL'])
def test_existing_real_request_schema_keeps_runtime_fifty_and_nullable_material_authority(role):
    op = operation(role); body, _ = capture(op)
    schema = marker(body, 'AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA')['$defs']['QualityHint']['properties']
    expected = contract_material_scope_schema(quality_schema(synthesis=role == 'FINAL'), MATERIALS)['$defs']['QualityHint']['properties']
    for field in ('problemEvidenceIds', 'comparisonEvidenceIds'):
        assert schema[field]['maxItems'] == expected[field]['maxItems'] == 50
    assert allowed_null_values(schema['unavailableMaterialCategory']) == ([], True)


@pytest.mark.parametrize('role', ['GROUP', 'FINAL'])
@pytest.mark.parametrize('available', [MATERIALS, tuple(v for v in MATERIALS if v != 'SUPPLEMENT')])
def test_actual_primary_wire_contains_derived_compact_field_rules_not_only_large_schema(role, available):
    op = operation(role, available); body, _ = capture(op)
    contract = marker(body, 'HIERARCHY_HINT_FIELD_CONTRACT')
    schema = marker(body, 'AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA')['$defs']['QualityHint']['properties']
    values, nullable = allowed_null_values(schema['unavailableMaterialCategory'])
    assert contract['protocol'] == PROTOCOL
    assert contract['evidenceMaxItems'] == {field: schema[field]['maxItems']
        for field in ('problemEvidenceIds', 'comparisonEvidenceIds')}
    assert contract['unavailableMaterialCategory'] == {'allowedValues': values, 'nullAllowed': nullable}
    assert contract['evidenceSelection'] == 'CLAIM_SUPPORT_NOT_CHECKED_SCOPE_WITNESS'
    assert contract['noTruncationOrRiskDeletion'] is True
    assert contract['invalidMaterialLiteralIsNotNull'] is True
    assert contract['materialMissingRequiresGloballyAbsentCategory'] is True


@pytest.mark.parametrize('role', ['GROUP', 'FINAL'])
def test_actual_repair_wire_has_specific_feedback_for_both_real_field_failure_classes(role):
    raw = invalid_material_missing(role)
    issues = (
        ProviderValidationIssue('newHints[0].comparisonEvidenceIds', 0, 'too_long', 'ARRAY', category='CONTRACT_STRUCTURE'),
        ProviderValidationIssue('newHints[0].unavailableMaterialCategory', 0, 'literal_error', 'UNRECOGNIZED_STRING',
            allowed_values=MATERIALS, null_allowed=True, category='CONTRACT_STRUCTURE'))
    context = build_regeneration_context(raw, issues, 'c'*64, hierarchy=True)
    op = replace(operation(role), attempt=2, max_attempts=2, validation_feedback=issues, regeneration_context_json=context)
    body, _ = capture(op)
    guidance = marker(body, 'QUALITY_REPAIR_GUIDANCE')
    # These are actual instructions delivered to the model, not function mocks.
    assert any('comparisonEvidenceIds' in text and '50' in text for text in guidance), 'no precise maxItems repair feedback'
    assert any('unavailableMaterialCategory' in text and 'null' in text and 'MATERIAL_MISSING' in text
        for text in guidance), 'no precise nullable enum/global-material repair feedback'
    user = body['messages'][1]['content']
    embedded = user.split('regenerationContext=', 1)[1].splitlines()[0]
    assert json.loads(embedded)['candidate'] == raw
    assert 'SYNTHETIC_INVALID_LITERAL' not in body['messages'][0]['content']


@pytest.mark.parametrize('role', ['GROUP', 'FINAL'])
def test_invalid_provider_values_remain_rejected_never_truncated_or_coerced(role):
    raw = invalid_material_missing(role)
    before = copy.deepcopy(raw); _, completion = capture(operation(role), raw)
    with pytest.raises(ValidationError) as error: ProviderHintDraft.model_validate(completion.payload['newHints'][0])
    errors = {(item['loc'], item['type']) for item in error.value.errors(include_input=False)}
    assert (('comparisonEvidenceIds',), 'too_long') in errors
    assert (('unavailableMaterialCategory',), 'literal_error') in errors
    assert completion.payload == before == raw


def test_invalid_material_missing_cannot_be_made_valid_by_coercing_category_to_null():
    value = invalid_material_missing('FINAL')['newHints'][0]
    # Deliberate adversarial mutation, NOT a suggested server-side repair.
    value['comparisonEvidenceIds'] = ['e0']; value['unavailableMaterialCategory'] = None
    with pytest.raises(QualityFailure) as failure:
        parse_scoped_draft({'summary':'核查材料','hints':[value],'resolutions':[]}, synthesis=True)
    assert failure.value.code == 'MATERIAL_ABSENCE_TYPE'


def test_even_a_real_material_enum_is_rejected_when_frozen_directory_says_present():
    value = invalid_material_missing('FINAL')['newHints'][0]
    value['comparisonEvidenceIds'] = ['e0']; value['unavailableMaterialCategory'] = 'SUPPLEMENT'
    with pytest.raises(QualityFailure) as failure:
        check_claims({'hints':[value]}, catalog=None, available_categories=set(MATERIALS),
                     allowed_evidence_ids={'e0'}, synthesis=True)
    assert failure.value.code == 'GLOBAL_MATERIAL_PRESENT'


@pytest.mark.parametrize('field', ['problemEvidenceIds', 'comparisonEvidenceIds'])
@pytest.mark.parametrize('count', [50, 51])
def test_real_typed_boundary_keeps_fifty_references_and_rejects_fifty_one_without_mutation(field, count):
    value = hint()
    value.update(attention='REVIEW', problemEvidenceIds=['e0'])
    value[field] = [f'e{i}' for i in range(count)]
    payload = {'summary': '仅核对合成条款。', 'scopeStatus': 'CHECKED_WITH_FINDINGS', 'hints': [value]}
    before = copy.deepcopy(payload)
    if count == 50:
        result, _ = parse_scoped_draft(payload, synthesis=False)
        assert result['hints'][0][field] == [f'e{i}' for i in range(50)]
    else:
        with pytest.raises(QualityFailure) as failure:
            parse_scoped_draft(payload, synthesis=False)
        assert any(issue.field_path == f'hints[0].{field}' and issue.error_kind == 'too_long'
            for issue in failure.value.safe_issues)
    assert payload == before


@pytest.mark.parametrize('coordinate, expected', [
    ('newHints[99].problemEvidenceIds', True),
    ('newHints[100].problemEvidenceIds', False),
    ('newHints[-1].problemEvidenceIds', False),
    ('newHints[0].OTHER', False),
])
def test_actual_repair_wire_bounds_its_precise_server_authored_coordinates(coordinate, expected):
    issue = ProviderValidationIssue(coordinate, None, 'too_long', 'ARRAY')
    op = replace(operation('FINAL'), attempt=2, max_attempts=2, validation_feedback=(issue,))
    body, _ = capture(op)
    guidance = marker(body, 'QUALITY_REPAIR_GUIDANCE')
    assert any(coordinate in text and '50' in text for text in guidance) is expected
    if not expected:
        assert guidance == []
