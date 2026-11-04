"""Actual request contract, not a reconstruction of the lost FINAL response.

The small evaluator below is test-only and intentionally handles only the
resolution-row JSON Schema keywords emitted by the production constructor.
Unknown keywords fail the test rather than silently broadening its oracle.
Native decoding/typed validation remains the independent acceptance authority.
"""
from copy import deepcopy
from dataclasses import replace
import json
import re

import pytest

from audit_ai.model.contract_draft import ProviderValidationIssue
from audit_ai.model.gateway import ContractTextOperation
from audit_ai.model.semantic_hierarchy import final_source, group_digest
from audit_ai.model.semantic_hierarchy_provider import decode_provider
from audit_ai.model.semantic_quality import build_regeneration_context, parse_scoped_draft
from test_final_frozen_evidence import build, P2, V2
from test_semantic_hierarchy import candidate
from test_semantic_regeneration_context_wire import capture


def row_schema_accepts(node, value):
    known = {'type', 'const', 'enum', 'pattern', 'anyOf', 'allOf', 'if', 'then', 'else',
             'not', 'minItems', 'maxItems', 'uniqueItems', 'items', 'prefixItems',
             'minLength', 'maxLength', 'minimum', 'maximum', 'contains'}
    assert not set(node) - known, 'test oracle must explicitly handle every row keyword'
    def equal_json(left,right):
        if type(left) in (int,float) and type(right) in (int,float):return left==right
        return type(left) is type(right) and left==right
    types = {'null': lambda v: v is None, 'array': lambda v: type(v) is list,
             'integer': lambda v: type(v) is int or type(v) is float and v.is_integer(),
             'string': lambda v: type(v) is str}
    if 'type' in node and not types[node['type']](value): return False
    if 'const' in node and not equal_json(value,node['const']): return False
    if 'enum' in node and not any(equal_json(v,value) for v in node['enum']): return False
    if 'anyOf' in node and not any(row_schema_accepts(n, value) for n in node['anyOf']): return False
    if 'allOf' in node and not all(row_schema_accepts(n, value) for n in node['allOf']): return False
    if 'not' in node and row_schema_accepts(node['not'], value): return False
    if 'if' in node:
        branch = 'then' if row_schema_accepts(node['if'], value) else 'else'
        if branch in node and not row_schema_accepts(node[branch], value): return False
    if type(value) is list:
        if len(value) < node.get('minItems', 0) or len(value) > node.get('maxItems', float('inf')): return False
        if node.get('uniqueItems') and any(equal_json(v,prior) for i,v in enumerate(value) for prior in value[:i]): return False
        prefix = node.get('prefixItems', [])
        if not all(row_schema_accepts(n, v) for n, v in zip(prefix, value)): return False
        if 'items' in node and not all(row_schema_accepts(node['items'], v) for v in value[len(prefix):]): return False
        if 'contains' in node and not any(row_schema_accepts(node['contains'], v) for v in value): return False
    if type(value) is str:
        if len(value) < node.get('minLength', 0) or len(value) > node.get('maxLength', float('inf')): return False
        if 'pattern' in node and re.search(node['pattern'], value) is None: return False
    if type(value) in (int,float) and (value < node.get('minimum', -float('inf')) or value > node.get('maximum', float('inf'))): return False
    return True


def operation():
    bundle = build()
    source = final_source(bundle, [group_digest(candidate(bundle, g), g, bundle) for g in bundle['groups']])
    return ContractTextOperation(project_id='synthetic-interface-audit', chunk_index=3, chunk_count=3,
        source_text=source, purpose='SYNTHESIZE', generation_policy='contract-semantic-work-v3',
        quality_protocol='contract-semantic-quality-v1', hierarchy_protocol=V2, hierarchy_role='FINAL',
        hierarchy_logical_key='b'*64, grouping_fingerprint=bundle['groupingFingerprint'])


def actual_schema(op):
    wire = json.loads(capture(op).body)  # production gateway + in-memory HTTP transport
    schema = json.loads(wire['messages'][0]['content'].split('AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=', 1)[1].splitlines()[0])
    assert wire['max_tokens'] == 6144 and wire['reasoning_effort'] == 'none'
    assert wire['response_format'] == {'type': 'json_object'} and not wire.get('stream', False)
    return schema, wire


@pytest.fixture(params=['PRIMARY', 'REPAIR'])
def actual_row(request):
    op = operation()
    if request.param == 'REPAIR':
        rejected = {'protocol': P2, 'summary': '合成接口负例。', 'newHints': [], 'resolutions': []}
        issue = ProviderValidationIssue('resolutions', None, 'OBSERVATION_COVERAGE', 'INVALID_SCOPED_CLAIM')
        context = build_regeneration_context(rejected, (issue,), 'a'*64, hierarchy=True)
        op = replace(op, attempt=2, max_attempts=2, validation_feedback=(issue,), regeneration_context_json=context)
    schema, _ = actual_schema(op)
    return schema['properties']['resolutions']['items']


@pytest.mark.parametrize('disposition', ['SCOPED_REPLACEMENT', 'COUNTEREVIDENCE', 'SCOPED_COUNTEREVIDENCE'])
@pytest.mark.parametrize('missing', ['basis', 'reason'])
def test_actual_request_cannot_offer_nulls_that_explicit_disposition_decoder_always_rejects(actual_row, disposition, missing):
    valid = [0, 0, disposition, ['N0'], [0], '原文支持的明确处置。']
    assert row_schema_accepts(actual_row, valid)
    invalid = deepcopy(valid); invalid[4 if missing == 'basis' else 5] = None
    assert not row_schema_accepts(actual_row, invalid), 'constructor offered a decoder-forbidden null'


@pytest.mark.parametrize('disposition', ['RETAINED', 'NEEDS_VERIFICATION'])
def test_new_target_requires_explicit_basis_and_reason_even_in_retention_dispositions(actual_row, disposition):
    valid = [0, 0, disposition, ['N0'], [0], '新意见有实际原文依据。']
    assert row_schema_accepts(actual_row, valid)
    for column in (4, 5):
        invalid = deepcopy(valid); invalid[column] = None
        assert not row_schema_accepts(actual_row, invalid), 'N target cannot inherit another object implicitly'


def test_resolution_array_limits_match_existing_typed_contract_at_actual_request_boundary(actual_row):
    targets = actual_row['prefixItems'][3]
    basis = actual_row['prefixItems'][4]['anyOf'][1]
    # Assert consumer-visible generated contract, not source text/constants.
    assert targets.get('maxItems') == 100
    assert basis.get('maxItems') == 50


def test_existing_process_empty_target_and_ordinary_nonempty_targets_remain_strict(actual_row):
    assert row_schema_accepts(actual_row, [0, 0, 'PROCESS_RESOLVED', [], [0], '明确证据解释纯过程观察。'])
    assert not row_schema_accepts(actual_row, [0, 0, 'PROCESS_RESOLVED', ['N0'], [0], '错误目标。'])
    assert not row_schema_accepts(actual_row, [0, 0, 'RETAINED', [], [0], '错误空目标。'])


def test_native_typed_resolution_limit_keeps_every_item_and_rejects_basis_overflow():
    evidence = [f'e{i}' for i in range(51)]
    hint = {'category': 'DELIVERY_TERMS', 'attention': 'REVIEW', 'title': '交付安排待核对',
        'description': '原文中的交付安排需要核对。', 'problemEvidenceIds': evidence[:50],
        'comparisonEvidenceIds': [], 'unavailableMaterialCategory': None, 'claimKind': 'AMBIGUOUS_CLAUSE',
        'obligationScope': {'topic': 'DELIVERY', 'responsibleParty': 'PARTY_B', 'trigger': '交付'}}
    source = {'G1:H1': deepcopy(hint)}
    wire = {'protocol': P2, 'summary': '合成边界正例。', 'newHints': [deepcopy(hint) for _ in range(100)],
        'resolutions': [[0, 0, 'SCOPED_REPLACEMENT', [f'N{i}' for i in range(100)], list(range(50)), '保留全部原始引用。']]}
    restored = decode_provider(wire, role='FINAL', source_hints=source, evidence_ids=evidence,
        protocol=P2, fully_visible_evidence_ids=evidence)
    _, metadata = parse_scoped_draft(restored, synthesis=True, allow_process_resolution=True)
    assert len(metadata['resolutions'][0]['finalHintIndexes']) == 100
    assert len(metadata['resolutions'][0]['basisEvidenceIds']) == 50
    wire['resolutions'][0][4].append(50)
    restored = decode_provider(wire, role='FINAL', source_hints=source, evidence_ids=evidence,
        protocol=P2, fully_visible_evidence_ids=evidence)
    from audit_ai.model.semantic_quality import QualityFailure
    with pytest.raises(QualityFailure) as rejected:
        parse_scoped_draft(restored, synthesis=True, allow_process_resolution=True)
    assert rejected.value.field_path.startswith('resolutions[0].basisEvidenceIds')


def test_typed_target_bound_rejects_101_without_truncating():
    from audit_ai.model.semantic_quality import ProcessResolution
    from pydantic import ValidationError
    row = {'observation':[0,0], 'disposition':'SCOPED_REPLACEMENT',
        'finalHintIndexes':list(range(100)), 'basisEvidenceIds':['e0'], 'reason':'完整明确的合成理由。'}
    assert len(ProcessResolution.model_validate(row).final_hint_indexes) == 100
    row['finalHintIndexes'].append(100)
    with pytest.raises(ValidationError) as rejected: ProcessResolution.model_validate(row)
    assert rejected.value.errors(include_input=False)[0]['loc'] == ('finalHintIndexes',)
    assert row['finalHintIndexes'] == list(range(101))


@pytest.mark.parametrize('column,path,classification',[(0,'resolutions[0].observation','FINAL_OBSERVATION_COORDINATE'),
    (1,'resolutions[0].observation','FINAL_OBSERVATION_COORDINATE'),
    (4,'resolutions[0].basisIndexes','FINAL_BASIS_INDEX_DOMAIN')])
def test_json_schema_integral_numbers_are_not_strict_native_program_ids(actual_row,column,path,classification):
    from audit_ai.errors import AuditAiError
    from test_final_resolution_rejections import provider_case,public_hint,native_decode
    case=provider_case();row=case['payload']['resolutions'][0]
    row[2:]=['SCOPED_REPLACEMENT',['N0'],[0],'完整合成依据。'];case['payload']['newHints']=[public_hint()]
    assert native_decode(case)['resolutions'][0]['observation']==[0,0]
    row[column]=[0.0] if column==4 else 0.0
    # JSON Schema integer is numeric, not a JSON lexical integer guarantee.
    # This intentional strict native-ID boundary must remain visible, not be
    # hidden by a test oracle that incorrectly equates the two contracts.
    assert row_schema_accepts(actual_row,row)
    with pytest.raises(AuditAiError) as rejected:native_decode(case)
    assert rejected.value.field_path==path and rejected.value.actual_class==classification
    row[column]=[0.5] if column==4 else 0.5
    assert not row_schema_accepts(actual_row,row)
