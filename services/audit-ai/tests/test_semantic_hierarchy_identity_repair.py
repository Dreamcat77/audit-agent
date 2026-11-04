"""Actual GROUP failure shape, synthetic literals only; no relaxed identity gate."""
import copy
import json

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.semantic_hierarchy import validate_group_lineage
from test_semantic_hierarchy import bundle, candidate
from test_semantic_hierarchy_execution import Provider, setup, drive


def test_eight_identity_drifts_are_rejected_with_safe_bounded_row_coordinates():
    b = bundle(1, hints_per_analysis=10)
    g = b['groups'][0]
    value = candidate(b, g)
    changes = [
        ('category', 'SERVICE_WARRANTY'), ('topic', 'WARRANTY'),
        ('responsibleParty', 'BOTH'), ('trigger', None),
        ('category', 'TERM_TERMINATION'), ('topic', 'SERVICE_RESPONSE'),
        ('responsibleParty', 'UNSPECIFIED'), ('trigger', '另一合成条件'),
    ]
    for hint, (field, replacement) in zip(value['hints'], changes):
        target = hint if field == 'category' else hint['obligationScope']
        target[field] = replacement
    original = copy.deepcopy(value)
    with pytest.raises(AuditAiError) as failed:
        validate_group_lineage(value, g, b)
    assert failed.value.code == 'SEMANTIC_HIERARCHY_LINEAGE_INVALID'
    assert value == original
    issues = failed.value.data['lineageIdentityIssues']
    assert len(issues) == 8
    assert [i['fieldPath'] for i in issues] == [f'lineage[{i}]' for i in range(8)]
    assert all(set(i) == {'fieldPath', 'errorKind'} for i in issues)
    assert all(i['errorKind'] == 'HIERARCHY_LEAF_IDENTITY_MISMATCH' for i in issues)
    assert '另一合成条件' not in json.dumps(issues, ensure_ascii=False)


def test_compatible_rows_still_pass_and_incompatible_merge_is_not_repaired_in_place():
    b = bundle(1, hints_per_analysis=2)
    g = b['groups'][0]
    value = candidate(b, g)
    assert validate_group_lineage(value, g, b) == value
    value['hints'][0]['obligationScope']['trigger'] = None
    before = copy.deepcopy(value)
    with pytest.raises(AuditAiError) as failed:
        validate_group_lineage(value, g, b)
    assert failed.value.data['lineageIdentityIssues'][0]['fieldPath'] == 'lineage[0]'
    assert value == before


def test_real_execution_passes_all_identity_coordinates_to_one_shared_repair():
    from audit_ai.model.semantic_work import ContractSemanticWorkService

    class DriftThenCorrect(Provider):
        def analyze_contract(self, operation):
            completion = super().analyze_contract(operation)
            if operation.hierarchy_role == 'GROUP' and operation.attempt == 1 and operation.chunk_index == first_group:
                for hint in completion.payload['hints']:
                    hint['category'] = 'SERVICE_WARRANTY'
                    hint['obligationScope']['responsibleParty'] = 'BOTH'
            return completion

    _, _, settings, request = setup()
    provider = DriftThenCorrect()
    service = ContractSemanticWorkService(settings, provider)
    plan = service.plan(request)
    first_group = next(u['ordinal'] for u in plan['units'] if u['role'] == 'GROUP')
    _, packets, last = drive(service, provider, request)
    repairs = [op for op in provider.calls if op.hierarchy_role == 'GROUP' and op.attempt == 2]
    assert len(repairs) == 1
    assert len(repairs[0].validation_feedback) == 1
    assert repairs[0].validation_feedback[0].field_path == 'lineage'
    assert repairs[0].validation_feedback[0].actual_class.startswith('ROWS_0_1')
    assert all(i.error_kind == 'HIERARCHY_LEAF_IDENTITY_MISMATCH' for i in repairs[0].validation_feedback)
    assert last['result'] is not None
    assert len(provider.calls) <= plan['callLimit'] <= 22
    assert sum(p['purpose'] == 'REPAIR' for p in packets) == 1


def test_explicit_hierarchy_context_uses_complete_codec_and_legacy_default_is_unchanged():
    from audit_ai.model.semantic_quality import build_regeneration_context, decode_regeneration_context
    from audit_ai.model.contract_draft import ProviderValidationIssue
    from test_hierarchy_regeneration_packing import dense, canonical
    value = dense()
    before = canonical(value)
    issue = ProviderValidationIssue('lineage', None, 'HIERARCHY_LEAF_IDENTITY_MISMATCH',
        'ROWS_2_4_6_8_9_10_11_14', schema_version='contract-semantic-quality-v1')
    with pytest.raises(AuditAiError) as rejected:
        build_regeneration_context(value, (issue,), 'b' * 64)
    assert rejected.value.code == 'SEMANTIC_REGENERATION_CONTEXT_BOUND'
    wire = build_regeneration_context(value, (issue,), 'b' * 64, hierarchy=True)
    assert len(wire.encode()) <= 8192
    decoded = decode_regeneration_context(wire)
    assert decoded['candidateEncoding'] == 'contract-regeneration-hierarchy-columns-v1'
    assert canonical(decoded['candidate']) == before == canonical(value)
    assert decoded['issues'] == [issue.as_dict()]


def test_actual_group_prompt_explains_identity_literals_and_readable_repair_pools():
    from dataclasses import replace
    from audit_ai.model.gateway import contract_hierarchy_wire
    from audit_ai.model.contract_draft import ProviderValidationIssue
    from audit_ai.model.semantic_quality import build_regeneration_context
    service, provider, _, request = setup()
    drive(service, provider, request, stop_after_group=True)
    operation = next(o for o in provider.calls if o.hierarchy_role == 'GROUP')
    prompt = contract_hierarchy_wire(operation)
    assert 'GROUP_IDENTITY_REPAIR_V1' in prompt['system_prompt']
    assert 'category、topic、responsibleParty、trigger' in prompt['system_prompt']
    assert 'UNSPECIFIED和null' in prompt['system_prompt']
    issue = ProviderValidationIssue('lineage', None, 'HIERARCHY_LEAF_IDENTITY_MISMATCH', 'ROWS_2_4')
    context = build_regeneration_context({'hints': [], 'lineage': []}, (issue,), 'a' * 64, hierarchy=True)
    repair = contract_hierarchy_wire(replace(operation, attempt=2, validation_feedback=(issue,),
        regeneration_context_json=context))
    assert 'candidateEncoding FULL就是完整旧candidate' in repair['system_prompt']
    assert 'candidate.pools' not in repair['system_prompt']
    assert 'ROWS_' in repair['system_prompt']
