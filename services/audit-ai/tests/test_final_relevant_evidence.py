"""Synthetic, not V2 truth: selected visibility is not global coverage."""
from copy import deepcopy

import pytest

from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.semantic_final_evidence import catalog_rows, unpack_view, verify_proof
from audit_ai.model.semantic_hierarchy import build_hierarchy_inputs
from test_final_frozen_evidence import fixture, V2


def selected():
    analyses, _, old = fixture()
    original = list(old.segments)
    # Irrelevant text in the same legal file must stay in the complete catalog,
    # not be confused with text actually supplied to the model.
    tail_text='\n2. 字体字号仅供排版'
    tail = EvidenceSegment('C:S3', 'frozen-contract', 'CONTRACT_BODY',
        original[-1].source_end, original[-1].source_end + len(tail_text), tail_text, True)
    foreign = EvidenceSegment('B:S4', 'response', 'BID_RESPONSE', 0, 15, '乙方交付延期承诺不替代合同约定', True)
    catalog = EvidenceCatalog([*original, tail, foreign], source_lengths={
        'frozen-contract': tail.source_end, 'response': foreign.source_end})
    bundle = build_hierarchy_inputs(analyses, catalog_rows(catalog), material_states=[],
        protocol=V2, frozen_catalog=catalog)
    return analyses, catalog, bundle


def test_relevant_tail_outside_old_refs_selected_but_unrelated_and_other_file_not_read():
    _, catalog, bundle = selected()
    assert bundle['evidenceIds'] == ['C:S1', 'C:S2']
    view = unpack_view(bundle['finalShell'])
    assert [r[0] for r in view['evidence']] == bundle['evidenceIds']
    assert bundle['finalEvidenceProof']['segmentCount'] == 4
    assert bundle['finalEvidenceProof']['visibleSegmentCount'] == 2
    assert bundle['finalEvidenceProof']['complete'] is True
    assert view['coverageMeaning'] == 'FROZEN_CATALOG_NOT_MODEL_READ_COVERAGE'
    assert view['selection'][0][0] == 'A1:H1'
    assert view['selectionSets'][view['selection'][0][2]] == [1]
    assert view['searchMissProvesAbsence'] is False
    assert verify_proof(bundle['finalEvidenceProof'], catalog)


@pytest.mark.parametrize('field,value', [
    ('evidenceOrder', ['C:S1', 'C:S2', 'C:S3']),
    ('visibleSegmentCount', 4), ('citableEvidenceIds', ['B:S4']),
])
def test_tampering_visible_proof_does_not_gain_unread_citations(field, value):
    _, catalog, bundle = selected()
    proof = deepcopy(bundle['finalEvidenceProof']); proof[field] = value
    assert not verify_proof(proof, catalog)


def test_every_real_concern_keeps_anchors_without_top_n_filter():
    analyses, catalog, _ = selected()
    hint = deepcopy(analyses[0]['hints'][0])
    hint.update(claimKind='AMBIGUOUS_CLAUSE', attention='REVIEW',
        problemEvidenceIds=['C:S3'], comparisonEvidenceIds=[])
    analyses[0]['hints'].append(hint)
    bundle = build_hierarchy_inputs(analyses, catalog_rows(catalog), material_states=[],
        protocol=V2, frozen_catalog=catalog)
    assert 'C:S3' in bundle['evidenceIds']
    shell = bundle['finalShell']
    assert shell['selectionSets'][shell['selection'][1][1]] == [bundle['evidenceIds'].index('C:S3')]


def test_clause_continuation_keeps_negation_exception_and_tail_exact():
    analyses, _, _ = fixture()
    texts = ['当前说明。\n', '1. 乙方交付延期承担责任，', '但因甲方未提供场地的除外。\n', '2. 字体排版。\n']
    cursor, segments = 0, []
    for i, text in enumerate(texts):
        segments.append(EvidenceSegment(f'C:S{i+1}', 'frozen-contract', 'CONTRACT_BODY',
            cursor, cursor + len(text), text, True)); cursor += len(text)
    catalog = EvidenceCatalog(segments, source_lengths={'frozen-contract': cursor})
    bundle = build_hierarchy_inputs(analyses, catalog_rows(catalog), material_states=[],
        protocol=V2, frozen_catalog=catalog)
    assert bundle['evidenceIds'] == ['C:S1', 'C:S2', 'C:S3']
    view = unpack_view(bundle['finalShell'])
    assert ''.join(row[-1] for row in view['evidence'] if row[0] in {'C:S2','C:S3'}) == ''.join(texts[1:3])


def test_real_problem_anchor_stays_whole_even_when_only_first_line_matches_topic():
    analyses, _, _ = fixture()
    texts=['乙方交付延期承担责任。\n其他条件及尾部例外均必须保留。\n', '仅供排版。\n']
    segments=[]; cursor=0
    for i,text in enumerate(texts):
        segments.append(EvidenceSegment(f'C:S{i+1}', 'frozen-contract','CONTRACT_BODY',cursor,cursor+len(text),text,True));cursor+=len(text)
    catalog=EvidenceCatalog(segments,source_lengths={'frozen-contract':cursor})
    hint=analyses[0]['hints'][0]
    hint.update(claimKind='AMBIGUOUS_CLAUSE',attention='REVIEW',problemEvidenceIds=['C:S1'],comparisonEvidenceIds=[])
    bundle=build_hierarchy_inputs(analyses,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    rows=unpack_view(bundle['finalShell'])['evidence']
    assert ''.join(row[-1] for row in rows if row[0]=='C:S1') == texts[0]


def test_invisible_remainder_of_selected_id_cannot_support_a_new_fact():
    from audit_ai.model.semantic_final_evidence import visible_catalog
    analyses,_,_=fixture()
    text='1. 乙方交付延期承担责任。\n2. 其他事项生效日期为2099年。\n'
    catalog=EvidenceCatalog([EvidenceSegment('C:S1','frozen-contract','CONTRACT_BODY',0,len(text),text,True)],
        source_lengths={'frozen-contract':len(text)})
    bundle=build_hierarchy_inputs(analyses,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    view=visible_catalog(bundle['finalEvidenceProof'],catalog)
    assert '交付延期' in view.by_id['C:S1'].exact_text
    assert '2099' not in view.by_id['C:S1'].exact_text
    assert not view.source_lengths  # partial visibility cannot prove global absence


@pytest.mark.parametrize('separator',['\n','\n\n'],ids=['next_line_exception','next_paragraph_exception'])
def test_selected_clause_keeps_exception_in_later_line_until_real_next_clause(separator):
    from audit_ai.model.semantic_final_evidence import visible_catalog
    analyses,_,_=fixture()
    text='1. 乙方交付延期承担责任。'+separator+'但前述责任不适用于甲方原因造成的延误。\n2. 排版事项。\n'
    catalog=EvidenceCatalog([EvidenceSegment('C:S1','frozen-contract','CONTRACT_BODY',0,len(text),text,True)],
        source_lengths={'frozen-contract':len(text)})
    bundle=build_hierarchy_inputs(analyses,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    visible=visible_catalog(bundle['finalEvidenceProof'],catalog)
    assert '但前述责任不适用于甲方原因造成的延误。' in visible.by_id['C:S1'].exact_text
    assert '排版事项' not in visible.by_id['C:S1'].exact_text


def test_partial_visible_source_does_not_authorize_global_clause_absence():
    from audit_ai.model.semantic_quality import check_claims,QualityFailure
    from audit_ai.model.semantic_final_evidence import visible_catalog
    analyses,catalog,bundle=selected()
    hint=deepcopy(analyses[0]['hints'][0]);hint.pop('workScopeWitness')
    hint.update(claimKind='GLOBAL_CLAUSE_NOT_FOUND',problemEvidenceIds=['C:S1'])
    with pytest.raises(QualityFailure,match='GLOBAL_ABSENCE_NOT_VISIBLE'):
        check_claims({'hints':[hint]},catalog=visible_catalog(bundle['finalEvidenceProof'],catalog),
            available_categories={'CONTRACT_BODY','BID_RESPONSE'},allowed_evidence_ids=set(bundle['visibleEvidenceIds']),
            synthesis=True,required_global_source_files=set(catalog.source_lengths))
