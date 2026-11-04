"""Pure synthetic reference-wire inverse; no transport or business fixtures."""
import copy
import importlib.util
import json
from pathlib import Path

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.semantic_hierarchy import validate_group_lineage
from audit_ai.model.semantic_quality import parse_scoped_draft, QualityFailure

PROTOCOL = 'contract-hierarchy-provider-references-v1'


def module():
    from audit_ai.model import semantic_hierarchy_provider as result
    return result


def hint(i=0):
    return {'category': 'DELIVERY_TERMS', 'attention': 'REVIEW', 'title': '原始交付条款' + str(i),
            'description': '乙方应于2026年10月30日前交付，迟延比例2%，保留原始日期及责任主体。',
            'problemEvidenceIds': ['e0'], 'comparisonEvidenceIds': ['e1', 'e0'],
            'unavailableMaterialCategory': None, 'claimKind': 'AMBIGUOUS_CLAUSE',
            'obligationScope': {'topic': 'DELIVERY', 'responsibleParty': 'UNSPECIFIED', 'trigger': None}}


def group_wire(source):
    return {'protocol': PROTOCOL, 'summary': '原始摘要😀', 'newHints': [],
            'lineage': [[alias, 'KEEP', alias, None, None] for alias in source]}


def final_wire(source):
    return {'protocol': PROTOCOL, 'summary': '原始最终摘要', 'newHints': [], 'resolutions': [
        [int(alias.split(':')[0][1:]) - 1, int(alias.split(':')[1][1:]) - 1,
         'RETAINED', [alias], None, None] for alias in source]}


def decode(wire, source, role='GROUP'):
    return module().decode_provider(wire, role=role, source_hints=source, evidence_ids=['e0', 'e1'])


@pytest.mark.parametrize('count', [1, 53, 58])
def test_all_keep_copies_every_literal_and_identity_without_regenerating_metadata(count):
    source = {f'A4:H{i + 1}': hint(i) for i in range(count)}
    before = copy.deepcopy(source); wire = group_wire(source)
    result = decode(wire, source)
    assert result['hints'] == list(before.values())
    assert result['scopeStatus'] == 'CHECKED_WITH_FINDINGS' and result['summary'] == wire['summary']
    assert [r[:4] for r in result['lineage']] == [[a, 'KEEP', i, ['e0', 'e1']] for i, a in enumerate(source)]
    assert all(type(r[4]) is str and r[4] for r in result['lineage'])
    group = {'leafIds': list(source)}
    bundle = {'leaves': {a: {'hint': h} for a, h in source.items()}, 'evidenceIds': ['e0', 'e1'],
              'evidenceCatalog': [{'evidenceId': 'e0', 'fileId': 'f'}, {'evidenceId': 'e1', 'fileId': 'f'}]}
    assert validate_group_lineage(result, group, bundle) == result
    assert source == before and wire == group_wire(source)
    result['hints'][0]['obligationScope']['trigger'] = 'mutated result'
    assert source == before
    if count > 1:
        assert len(json.dumps(wire, ensure_ascii=False).encode()) < len(json.dumps(result, ensure_ascii=False).encode())


def test_incompatible_merge_is_not_repaired_and_original_validator_rejects():
    source = {'A1:H1': hint(), 'A2:H1': hint(1)}
    target = hint(2); target['obligationScope']['responsibleParty'] = 'PARTY_B'
    wire = {'protocol': PROTOCOL, 'summary': '合并', 'newHints': [target], 'lineage': [
        [a, 'MERGE', 'N0', [0, 1], '明确合并理由'] for a in source]}
    result = decode(wire, source)
    assert result['hints'] == [target]
    bundle = {'leaves': {a: {'hint': h} for a, h in source.items()}, 'evidenceIds': ['e0', 'e1'],
              'evidenceCatalog': [{'evidenceId': 'e0', 'fileId': 'f'}, {'evidenceId': 'e1', 'fileId': 'f'}]}
    with pytest.raises(AuditAiError): validate_group_lineage(result, {'leafIds': list(source)}, bundle)


@pytest.mark.parametrize('defect', ['protocol', 'extra', 'missing', 'row_width', 'leaf', 'duplicate', 'foreign', 'bool_basis', 'duplicate_basis', 'range', 'negative', 'unused_new', 'target', 'keep_new', 'merge_source', 'merge_null', 'reason', 'new_container', 'new_item', 'alias_domain', 'evidence_duplicate'])
def test_group_malformed_and_ambiguous_references_reject_safely(defect):
    source = {'A1:H1': hint(), 'A1:H2': hint(1)}; wire = group_wire(source); evidence = ['e0', 'e1']
    if defect == 'protocol': wire['protocol'] = 'PRIVATE_CANARY'
    if defect == 'extra': wire['PRIVATE_CANARY'] = 'PRIVATE_CANARY'
    if defect == 'missing': wire['lineage'].pop()
    if defect == 'row_width': wire['lineage'][0].append('PRIVATE_CANARY')
    if defect == 'leaf': wire['lineage'][0][0] = 'A1:H3'
    if defect == 'duplicate': wire['lineage'][1] = wire['lineage'][0]
    if defect == 'foreign': wire['lineage'][0][2] = 'A2:H1'
    if defect == 'bool_basis': wire['lineage'][0][3] = [True]
    if defect == 'duplicate_basis': wire['lineage'][0][3] = [0, 0]
    if defect == 'range': wire['lineage'][0][3] = [2]
    if defect == 'negative': wire['lineage'][0][3] = [-1]
    if defect == 'unused_new': wire['newHints'] = [hint()]
    if defect == 'target': wire['lineage'][0][2] = 'N99'
    if defect == 'keep_new': wire['newHints'] = [hint()]; wire['lineage'][0][2] = 'N0'
    if defect == 'merge_source': wire['lineage'][0][1] = 'MERGE'
    if defect == 'merge_null':
        wire['newHints'] = [hint()]; wire['lineage'][0][1:3] = ['MERGE', 'N0']
    if defect == 'reason': wire['lineage'][0][4] = 3
    if defect == 'new_container': wire['newHints'] = {}
    if defect == 'new_item': wire['newHints'] = [None]
    if defect == 'alias_domain': source = {'G1:H1': hint()}; wire = group_wire(source)
    if defect == 'evidence_duplicate': evidence = ['e0', 'e0']
    with pytest.raises(AuditAiError) as rejected:
        module().decode_provider(wire, role='GROUP', source_hints=source, evidence_ids=evidence)
    assert 'PRIVATE_CANARY' not in str(rejected.value) + rejected.value.public_message + str(rejected.value.data)


def test_final_reference_order_inherited_basis_and_extra_new_hints_are_not_dropped():
    source = {'G1:H1': hint(), 'G2:H1': hint(1)}; before = copy.deepcopy(source)
    wire = final_wire(source); wire['resolutions'].reverse(); wire['newHints'] = [hint(8), {'invalid': 'preserve'}]
    result = decode(wire, source, 'FINAL')
    assert result['hints'] == [source['G2:H1'], source['G1:H1'], *wire['newHints']]
    assert result['resolutions'][0]['observation'] == [1, 0]
    assert [r['finalHintIndexes'] for r in result['resolutions']] == [[0], [1]]
    assert result['resolutions'][0]['basisEvidenceIds'] == ['e0', 'e1']
    assert source == before
    with pytest.raises(QualityFailure): parse_scoped_draft(result, synthesis=True)


@pytest.mark.parametrize('defect', ['duplicate_observation', 'missing', 'bool_coordinate', 'foreign_observation', 'duplicate_targets', 'wrong_target', 'null_replacement', 'bool_basis', 'row_width', 'alias_domain'])
def test_final_invalid_reference_wire_never_becomes_success(defect):
    source = {'G1:H1': hint(), 'G1:H2': hint(1)}; wire = final_wire(source)
    if defect == 'duplicate_observation': wire['resolutions'][1] = wire['resolutions'][0]
    if defect == 'missing': wire['resolutions'].pop()
    if defect == 'bool_coordinate': wire['resolutions'][0][0] = True
    if defect == 'foreign_observation': wire['resolutions'][0][0] = 4
    if defect == 'duplicate_targets': wire['resolutions'][0][3] *= 2
    if defect == 'wrong_target': wire['resolutions'][0][3] = ['PRIVATE_CANARY']
    if defect == 'null_replacement': wire['resolutions'][0][2] = 'SCOPED_REPLACEMENT'
    if defect == 'bool_basis': wire['resolutions'][0][4] = [False]
    if defect == 'row_width': wire['resolutions'][0].append(None)
    if defect == 'alias_domain': source = {'A1:H1': hint()}; wire = {'protocol': PROTOCOL, 'summary': 's', 'newHints': [], 'resolutions': []}
    with pytest.raises(AuditAiError): decode(wire, source, 'FINAL')


def test_explicit_final_new_targets_are_materialized_in_first_consumption_order():
    source = {'G1:H1': hint()}; wire = final_wire(source)
    wire['newHints'] = [hint(1), hint(2)]
    wire['resolutions'][0][2:] = ['SCOPED_REPLACEMENT', ['N1', 'N0'], [1, 0], '显式说明']
    result = decode(wire, source, 'FINAL')
    assert result['hints'] == [hint(2), hint(1)]
    assert result['resolutions'][0] == {'observation': [0, 0], 'disposition': 'SCOPED_REPLACEMENT',
        'finalHintIndexes': [0, 1], 'basisEvidenceIds': ['e1', 'e0'], 'reason': '显式说明'}


@pytest.mark.parametrize('action', ['KEEP', 'UNRESOLVED'])
def test_group_explicit_compact_new_target_keeps_existing_gate_permission(action):
    source = {'A1:H1': hint()}; target = hint(); target['description'] = '保留原交付义务，具体日期比例及主体按完整证据核查。'
    wire = group_wire(source); wire['newHints'] = [target]
    wire['lineage'] = [['A1:H1', action, 'N0', [0, 1], '明确保留同一义务范围。']]
    result = decode(wire, source)
    bundle = {'leaves': {'A1:H1': {'hint': source['A1:H1']}}, 'evidenceIds': ['e0', 'e1'],
              'evidenceCatalog': [{'evidenceId': 'e0', 'fileId': 'f'}, {'evidenceId': 'e1', 'fileId': 'f'}]}
    assert validate_group_lineage(result, {'leafIds': ['A1:H1']}, bundle) == result


def test_same_literal_claims_do_not_collapse_distinct_source_aliases():
    source = {'A1:H1': hint(), 'A1:H2': hint()}
    result = decode(group_wire(source), source)
    assert result['hints'] == [hint(), hint()]
    assert [row[2] for row in result['lineage']] == [0, 1]


def test_valid_final_inverse_passes_original_scoped_parser_and_new_hint_bound_is_explicit():
    source = {'G1:H1': hint()}; wire = final_wire(source)
    result = decode(wire, source, 'FINAL')
    normalized, metadata = parse_scoped_draft(result, synthesis=True)
    assert normalized['summary'] == wire['summary'] and len(normalized['hints']) == 1
    assert metadata['resolutions'] == result['resolutions']
    wire['newHints'] = [hint()] * 101
    with pytest.raises(AuditAiError): decode(wire, source, 'FINAL')
