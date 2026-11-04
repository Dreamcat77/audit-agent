"""Exhaustive FINAL rejection families on synthetic, replayable native inputs.

Case IDs are coordinates in the interface audit, NOT claims about the lost
historical response. Positive controls run the same native entry before each
mutation. No HTTP, DB, fixture truth or provider inference is used here.
"""
from copy import deepcopy
import json

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.semantic_hierarchy_provider import decode_provider
from audit_ai.model.semantic_quality import QualityFailure, parse_scoped_draft, reconcile_observations
from test_final_frozen_evidence import P2


def public_hint(*, attention='REVIEW', evidence='e0'):
    return {'category':'DELIVERY_TERMS', 'attention':attention, 'title':'合成交付安排',
        'description':'本项仅核对合成交付安排。', 'problemEvidenceIds':[] if attention=='INFO' else [evidence],
        'comparisonEvidenceIds':[evidence] if attention=='INFO' else [], 'unavailableMaterialCategory':None,
        'claimKind':'CLAUSE_PRESENT' if attention=='INFO' else 'AMBIGUOUS_CLAUSE',
        'obligationScope':{'topic':'DELIVERY', 'responsibleParty':'PARTY_B', 'trigger':'交付'}}


def provider_case():
    source = {'G1:H1':public_hint(), 'G1:H2':public_hint()}
    payload = {'protocol':P2, 'summary':'合成接口正例。', 'newHints':[],
        'resolutions':[[0,0,'RETAINED',['G1:H1'],None,None], [0,1,'RETAINED',['G1:H2'],None,None]]}
    return {'payload':payload, 'role':'FINAL', 'source_hints':source, 'evidence_ids':['e0','e1'],
            'protocol':P2, 'fully_visible_evidence_ids':['e0','e1']}


def native_decode(case):
    return decode_provider(**case)


def put(case, path, value):
    cursor=case
    for part in path[:-1]: cursor=cursor[part]
    cursor[path[-1]]=value


# One row per independently rejected structural condition, including trusted
# context guard families that are deliberately not model response instructions.
PROVIDER_CASES = [
    ('D01_envelope_type', ('payload',), [], 'response', 'FINAL_ENVELOPE_FIELDS'),
    ('D02_envelope_extra', ('payload','PRIVATE_SENTINEL'), True, 'response', 'FINAL_ENVELOPE_FIELDS'),
    ('D03_protocol', ('payload','protocol'), 'PRIVATE_SENTINEL', 'protocol', 'FINAL_PROTOCOL_LITERAL'),
    ('D04_summary_type', ('payload','summary'), 7, 'summary', 'FINAL_SUMMARY_TYPE'),
    ('D05_new_list_type', ('payload','newHints'), {}, 'newHints', 'FINAL_NEW_HINT_SHAPE'),
    ('D06_new_item_type', ('payload','newHints'), [None], 'newHints', 'FINAL_NEW_HINT_SHAPE'),
    ('D07_new_count', ('payload','newHints'), [{}]*101, 'newHints', 'FINAL_NEW_HINT_SHAPE'),
    ('D08_rows_type', ('payload','resolutions'), {}, 'resolutions', 'FINAL_RESOLUTION_LIST_BOUND'),
    ('D09_rows_count', ('payload','resolutions'), [[]]*6401, 'resolutions', 'FINAL_RESOLUTION_LIST_BOUND'),
    ('D10_row_type', ('payload','resolutions',0), {}, 'resolutions[0]', 'FINAL_RESOLUTION_ROW_SHAPE'),
    ('D11_row_width', ('payload','resolutions',0), [0,0], 'resolutions[0]', 'FINAL_RESOLUTION_ROW_SHAPE'),
    ('D12_group_bool', ('payload','resolutions',0,0), True, 'resolutions[0].observation', 'FINAL_OBSERVATION_COORDINATE'),
    ('D13_group_negative', ('payload','resolutions',0,0), -1, 'resolutions[0].observation', 'FINAL_OBSERVATION_COORDINATE'),
    ('D14_hint_bool', ('payload','resolutions',0,1), True, 'resolutions[0].observation', 'FINAL_OBSERVATION_COORDINATE'),
    ('D15_hint_negative', ('payload','resolutions',0,1), -1, 'resolutions[0].observation', 'FINAL_OBSERVATION_COORDINATE'),
    ('D16_disposition_type', ('payload','resolutions',0,2), [], 'resolutions[0].disposition', 'FINAL_DISPOSITION_LITERAL'),
    ('D17_disposition_literal', ('payload','resolutions',0,2), 'PRIVATE_SENTINEL', 'resolutions[0].disposition', 'FINAL_DISPOSITION_LITERAL'),
    ('D18_targets_type', ('payload','resolutions',0,3), None, 'resolutions[0].targetRefs', 'FINAL_TARGET_REFERENCE_TYPE'),
    ('D19_targets_empty', ('payload','resolutions',0,3), [], 'resolutions[0].targetRefs', 'FINAL_TARGET_CARDINALITY'),
    ('D20_process_nonempty', ('payload','resolutions',0,2), 'PROCESS_RESOLVED', 'resolutions[0].targetRefs', 'FINAL_TARGET_CARDINALITY'),
    ('D21_target_item_type', ('payload','resolutions',0,3), [3], 'resolutions[0].targetRefs', 'FINAL_TARGET_REFERENCE_TYPE'),
    ('D22_target_duplicate', ('payload','resolutions',0,3), ['G1:H1','G1:H1'], 'resolutions[0].targetRefs', 'FINAL_DUPLICATE_TARGET_REFERENCE'),
    ('D23_observation_unknown_group', ('payload','resolutions',0,0), 4, 'resolutions[0].observation', 'FINAL_OBSERVATION_UNKNOWN'),
    ('D24_observation_unknown_hint', ('payload','resolutions',0,1), 4, 'resolutions[0].observation', 'FINAL_OBSERVATION_UNKNOWN'),
    ('D25_observation_duplicate', ('payload','resolutions',1,1), 0, 'resolutions[1].observation', 'FINAL_DUPLICATE_OBSERVATION'),
    ('D26_basis_type', ('payload','resolutions',0,4), 'PRIVATE_SENTINEL', 'resolutions[0].basisIndexes', 'FINAL_BASIS_REQUIRED'),
    ('D27_basis_empty', ('payload','resolutions',0,4), [], 'resolutions[0].basisIndexes', 'FINAL_BASIS_REQUIRED'),
    ('D28_basis_bool', ('payload','resolutions',0,4), [True], 'resolutions[0].basisIndexes', 'FINAL_BASIS_INDEX_DOMAIN'),
    ('D29_basis_negative', ('payload','resolutions',0,4), [-1], 'resolutions[0].basisIndexes', 'FINAL_BASIS_INDEX_DOMAIN'),
    ('D30_basis_outside', ('payload','resolutions',0,4), [2], 'resolutions[0].basisIndexes', 'FINAL_BASIS_INDEX_DOMAIN'),
    ('D31_basis_duplicate', ('payload','resolutions',0,4), [0,0], 'resolutions[0].basisIndexes', 'FINAL_DUPLICATE_BASIS_INDEX'),
    ('D32_reason_type', ('payload','resolutions',0,5), 5, 'resolutions[0].reason', 'FINAL_REASON_REQUIRED_OR_TYPE'),
    ('D33_reason_empty', ('payload','resolutions',0,5), '', 'resolutions[0].reason', 'FINAL_REASON_REQUIRED_OR_TYPE'),
    ('D34_observation_coverage', ('payload','resolutions'), [], 'resolutions', 'FINAL_OBSERVATION_COVERAGE'),
    ('D35_source_type', ('source_hints',), [], None, None),
    ('D36_source_hint_type', ('source_hints','G1:H1'), [], None, None),
    ('D37_evidence_type', ('evidence_ids',), {}, None, None),
    ('D38_evidence_id_type', ('evidence_ids',0), 1, None, None),
    ('D39_evidence_empty_id', ('evidence_ids',0), '', None, None),
    ('D40_evidence_duplicate', ('evidence_ids',1), 'e0', None, None),
    ('D41_full_type', ('fully_visible_evidence_ids',), {}, None, None),
    ('D42_full_id_type', ('fully_visible_evidence_ids',0), 4, None, None),
    ('D43_full_duplicate', ('fully_visible_evidence_ids',1), 'e0', None, None),
    ('D44_full_outside_registry', ('fully_visible_evidence_ids',1), 'foreign', None, None),
    ('D45_unsupported_protocol', ('protocol',), 'PRIVATE_SENTINEL', None, None),
    ('D46_unsupported_role', ('role',), 'PRIVATE_SENTINEL', None, None),
]


@pytest.mark.parametrize('case_id,path,value,coordinate,classification', PROVIDER_CASES,
                         ids=[r[0] for r in PROVIDER_CASES])
def test_provider_branch_has_native_positive_and_precise_safe_negative(case_id,path,value,coordinate,classification):
    case=provider_case(); positive=native_decode(case)
    parse_scoped_draft(positive,synthesis=True,allow_process_resolution=True)
    assert len(positive['hints'])==2 and len(positive['resolutions'])==2
    put(case,path,deepcopy(value))
    with pytest.raises(AuditAiError) as rejected: native_decode(case)
    assert rejected.value.code=='SEMANTIC_HIERARCHY_PROVIDER_INVALID'
    if coordinate is not None:
        assert rejected.value.field_path==coordinate and rejected.value.actual_class==classification
    assert 'PRIVATE_SENTINEL' not in str(rejected.value)+str(rejected.value.data)


@pytest.mark.parametrize('kind', ['unknown_alias','leading_zero','negative_new','new_outside','new_large_int',
    'null_basis','null_reason','nonself_null','missing_envelope','bad_alias','source_count','inherited_basis_type',
    'inherited_basis_item','inherited_basis_empty','inherited_basis_outside','partial_inherit','no_citations',
    'non_json_key','non_json_value','nonfinite','unicode','cyclic'])
def test_remaining_reference_and_json_context_guards_have_paired_positive(kind):
    case=provider_case(); assert len(native_decode(case)['hints'])==2
    row=case['payload']['resolutions'][0]; coordinate=None; classification=None
    if kind in {'unknown_alias','leading_zero','negative_new','new_outside','new_large_int','null_basis','null_reason'}:
        row[2:]=['SCOPED_REPLACEMENT',['N0'],[0],'明确的合成处置。']
        case['payload']['newHints']=[public_hint()]
        assert native_decode(case)['hints'][0]==public_hint()
        ref={'unknown_alias':'G2:H9','leading_zero':'N00','negative_new':'N-1',
             'new_outside':'N1','new_large_int':'N'+'9'*4500}.get(kind)
        if ref is not None:
            row[3]=[ref];coordinate='resolutions[0].targetRefs'
            classification='FINAL_NEW_HINT_INDEX_DOMAIN' if kind in {'new_outside','new_large_int'} else 'FINAL_TARGET_REFERENCE_UNKNOWN'
        elif kind=='null_basis':row[4]=None;coordinate='resolutions[0].basisIndexes';classification='FINAL_BASIS_REQUIRED'
        else:row[5]=None;coordinate='resolutions[0].reason';classification='FINAL_REASON_REQUIRED_OR_TYPE'
    if kind=='nonself_null':row[3]=['G1:H2'];coordinate='resolutions[0].basisIndexes';classification='FINAL_BASIS_REQUIRED'
    if kind=='missing_envelope':del case['payload']['summary'];coordinate='response';classification='FINAL_ENVELOPE_FIELDS'
    if kind=='bad_alias':case['source_hints']['A1:H1']=case['source_hints'].pop('G1:H1')
    if kind=='source_count':case['source_hints']={f'G1:H{i+1}':public_hint() for i in range(6401)}
    if kind=='inherited_basis_type':case['source_hints']['G1:H1']['problemEvidenceIds']='e0'
    if kind=='inherited_basis_item':case['source_hints']['G1:H1']['problemEvidenceIds']=[2]
    if kind=='inherited_basis_empty':case['source_hints']['G1:H1']['problemEvidenceIds']=[]
    if kind=='inherited_basis_outside':case['source_hints']['G1:H1']['problemEvidenceIds']=['foreign']
    if kind in {'partial_inherit','no_citations'}:
        row[4]=[0]
        if kind=='partial_inherit':case['fully_visible_evidence_ids']=[]
        else:case['source_hints']['G1:H1']['problemEvidenceIds']=[]
    if kind=='non_json_key':case['payload'][1]='PRIVATE_SENTINEL'
    if kind=='non_json_value':case['payload']['summary']={'PRIVATE_SENTINEL'}
    if kind=='nonfinite':case['payload']['summary']=float('nan')
    if kind=='unicode':case['payload']['summary']='\ud800'
    if kind=='cyclic':case['payload']['summary']=[];case['payload']['summary'].append(case['payload']['summary'])
    with pytest.raises(AuditAiError) as rejected:native_decode(case)
    if kind in {'partial_inherit','no_citations'}:
        assert rejected.value.code=='SEMANTIC_INHERITED_EVIDENCE_NOT_VISIBLE'
        assert rejected.value.field_path=='inheritedHints'
    else:assert rejected.value.code=='SEMANTIC_HIERARCHY_PROVIDER_INVALID'
    if coordinate:assert rejected.value.field_path==coordinate and rejected.value.actual_class==classification
    assert 'PRIVATE_SENTINEL' not in str(rejected.value)+str(rejected.value.data)


def reconciliation_case():
    original=public_hint(); row={'observation':[0,0], 'disposition':'RETAINED', 'finalHintIndexes':[0],
        'basisEvidenceIds':['e0'], 'reason':'保持同一主体和交付事项。'}
    return [{'hints':[original]}], {'hints':[deepcopy(original)],'resolutions':[row]}


RECONCILIATION_CASES=[
    ('R01_local_published','UNRESOLVED_LOCAL_OBSERVATION','hints'),
    ('R02_global_unknown_scope','OBLIGATION_SCOPE','hints'),
    ('R03_global_contradiction','FINAL_CLAIM_CONTRADICTION','hints'),
    ('R04_resolution_schema','RESOLUTION_SCHEMA','resolutions'),
    ('R05_duplicate_coordinate','OBSERVATION_COVERAGE','resolutions'),
    ('R06_missing_coordinate','OBSERVATION_COVERAGE','resolutions'),
    ('R07_unknown_coordinate','OBSERVATION_COVERAGE','resolutions'),
    ('R08_empty_target','RESOLUTION_TARGET','resolutions'),
    ('R09_duplicate_target','RESOLUTION_TARGET','resolutions'),
    ('R10_negative_target','RESOLUTION_TARGET','resolutions'),
    ('R11_outside_target','RESOLUTION_TARGET','resolutions'),
    ('R12_category','OBLIGATION_SCOPE','resolutions'),
    ('R13_retained_scope','OBLIGATION_SCOPE','resolutions'),
    ('R14_needs_scope','OBLIGATION_SCOPE','resolutions'),
    ('R15_replacement_topic','OBLIGATION_SCOPE','resolutions'),
    ('R16_replacement_party','OBLIGATION_SCOPE','resolutions'),
    ('R17_replacement_trigger','OBLIGATION_SCOPE','resolutions'),
    ('R18_replacement_missing_scope','OBLIGATION_SCOPE','resolutions'),
    ('R19_basis_not_allowed','RESOLUTION_EVIDENCE','resolutions'),
    ('R20_basis_not_target','RESOLUTION_EVIDENCE','resolutions'),
    ('R21_lost_real_anchor','OBSERVATION_ANCHOR_LOST','resolutions'),
    ('R22_real_risk_to_info','RISK_DOWNGRADE','resolutions'),
    ('R23_counter_no_different_basis','RISK_DOWNGRADE','resolutions'),
    ('R24_counter_unknown_party','OBLIGATION_SCOPE','resolutions'),
    ('R25_counter_unknown_trigger','OBLIGATION_SCOPE','resolutions'),
    ('R26_counter_wrong_kind','OBLIGATION_SCOPE','resolutions'),
    ('R27_counter_wrong_scope','OBLIGATION_SCOPE','resolutions'),
    ('R28_counter_not_positive','COUNTEREVIDENCE_STATE','resolutions'),
    ('R29_needs_all_info','RISK_DOWNGRADE','resolutions'),
]


@pytest.mark.parametrize('case_id,code,path',RECONCILIATION_CASES,ids=[r[0] for r in RECONCILIATION_CASES])
def test_every_ordinary_reconciliation_branch_is_exercised_with_native_positive(case_id,code,path):
    partials,payload=reconciliation_case();allowed={'e0','e1'}
    assert reconcile_observations(partials,payload,allowed_evidence_ids=allowed,allow_process_resolution=True)[0]['observation']==[0,0]
    old=partials[0]['hints'][0];target=payload['hints'][0];row=payload['resolutions'][0]
    if case_id=='R01_local_published':target['claimKind']='LOCAL_NOT_SEEN'
    if case_id in {'R02_global_unknown_scope','R03_global_contradiction'}:
        target['claimKind']='GLOBAL_CLAUSE_NOT_FOUND'
        if case_id=='R02_global_unknown_scope':target['obligationScope']['responsibleParty']='UNSPECIFIED'
        else:
            present=deepcopy(target);present['claimKind']='CLAUSE_PRESENT';payload['hints'].append(present)
    if case_id=='R04_resolution_schema':row['reason']=None
    if case_id=='R05_duplicate_coordinate':payload['resolutions'].append(deepcopy(row))
    if case_id=='R06_missing_coordinate':payload['resolutions']=[]
    if case_id=='R07_unknown_coordinate':row['observation']=[2,0]
    if case_id.startswith(('R08_','R09_','R10_','R11_')):
        row['finalHintIndexes']={'R08_empty_target':[], 'R09_duplicate_target':[0,0],
            'R10_negative_target':[-1],'R11_outside_target':[1]}[case_id]
    if case_id=='R12_category':target['category']='BREACH_LIABILITY'
    if case_id in {'R13_retained_scope','R14_needs_scope'}:
        target['obligationScope']['responsibleParty']='PARTY_A'
        if case_id=='R14_needs_scope':row['disposition']='NEEDS_VERIFICATION'
    if case_id.startswith(('R15_','R16_','R17_','R18_')):
        row['disposition']='SCOPED_REPLACEMENT'
        field,value={'R15_replacement_topic':('topic','WARRANTY'),
            'R16_replacement_party':('responsibleParty','PARTY_A'),
            'R17_replacement_trigger':('trigger','验收'), 'R18_replacement_missing_scope':('trigger','交付')}[case_id]
        if case_id=='R18_replacement_missing_scope':old.pop('obligationScope')
        else:target['obligationScope'][field]=value
    if case_id=='R19_basis_not_allowed':allowed={'e1'}
    if case_id=='R20_basis_not_target':row['basisEvidenceIds']=['e1']
    if case_id=='R21_lost_real_anchor':target['problemEvidenceIds']=['e1'];row['basisEvidenceIds']=['e1']
    if case_id=='R22_real_risk_to_info':target.update(attention='INFO',claimKind='CLAUSE_PRESENT')
    if case_id.startswith(('R23_','R24_','R25_','R26_','R27_','R28_')):
        old.update(attention='INSUFFICIENT',claimKind='LOCAL_NOT_SEEN',title='当前片段未见交付约定',
            description='当前片段未见交付约定，须结合其他真实条款核对。')
        row['disposition']='COUNTEREVIDENCE'
        target.update(attention='INFO',claimKind='CLAUSE_PRESENT',comparisonEvidenceIds=['e1'])
        row['basisEvidenceIds']=['e1']
        # Keep the old real anchor so only the intended downgrade condition varies.
        target['problemEvidenceIds']=['e0']
        if case_id=='R23_counter_no_different_basis':row['basisEvidenceIds']=['e0']
        if case_id=='R24_counter_unknown_party':old['obligationScope']['responsibleParty']='UNSPECIFIED';target['obligationScope']['responsibleParty']='UNSPECIFIED'
        if case_id=='R25_counter_unknown_trigger':old['obligationScope']['trigger']=None;target['obligationScope']['trigger']=None
        if case_id=='R26_counter_wrong_kind':target['claimKind']='AMBIGUOUS_CLAUSE'
        if case_id=='R27_counter_wrong_scope':target['obligationScope']['trigger']='验收'
        if case_id=='R28_counter_not_positive':target['attention']='REVIEW'
    if case_id=='R29_needs_all_info':old['attention']='INFO';target['attention']='INFO';row['disposition']='NEEDS_VERIFICATION'
    with pytest.raises(QualityFailure) as rejected:reconcile_observations(partials,payload,allowed_evidence_ids=allowed,allow_process_resolution=True)
    assert rejected.value.code==code and rejected.value.field_path==path


def local_replacement_case():
    from test_final_frozen_evidence import fixture, V2
    from audit_ai.model.semantic_hierarchy import build_hierarchy_inputs
    from test_semantic_hierarchy import candidate
    analyses,rows,catalog=fixture()
    bundle=build_hierarchy_inputs(analyses,rows,material_states=[],protocol=V2,frozen_catalog=catalog)
    groups=[candidate(bundle,g) for g in bundle['groups']]
    target=deepcopy(analyses[0]['hints'][0]);target.pop('workScopeWitness')
    target.update(attention='REVIEW',claimKind='AMBIGUOUS_CLAUSE',title='交付责任适用条件待核对',
        description='现有交付责任条款的适用条件应进一步核对。',problemEvidenceIds=['C:S2'],comparisonEvidenceIds=[])
    row={'observation':[0,0],'disposition':'SCOPED_REPLACEMENT','finalHintIndexes':[0],
        'basisEvidenceIds':['C:S2'],'reason':'以同一义务真实条款承接观察，局部见证保留在私有轨迹。'}
    return analyses,catalog,bundle,groups,{'hints':[target],'resolutions':[row]}


def test_pure_local_witness_stays_private_when_real_same_scope_concern_replaces_observation():
    from audit_ai.model.semantic_hierarchy import compose_final_resolutions
    analyses,catalog,bundle,groups,payload=local_replacement_case()
    before=deepcopy((analyses,groups,payload))
    # The same accepted GROUP-level contract must not acquire a contradictory
    # requirement to publicly cite a process-only witness at composition.
    assert reconcile_observations(groups,payload,allowed_evidence_ids={'C:S1','C:S2'},catalog=catalog)
    composed,chain=compose_final_resolutions(bundle,groups,payload)
    assert composed[0]['basisEvidenceIds']==['C:S2']
    assert chain[0]['groupBasisEvidenceIds']==['C:S1']
    assert chain[0]['privateScopeWitnessEvidenceIds']==['C:S1']
    assert reconcile_observations(analyses,{**payload,'resolutions':composed},
        allowed_evidence_ids={'C:S1','C:S2'},catalog=catalog)
    assert (analyses,groups,payload)==before


@pytest.mark.parametrize('kind',['real_problem','not_local','fake_witness','unmatched_witness'])
def test_composition_never_discards_real_or_unproven_group_basis_as_process_witness(kind):
    from audit_ai.model.semantic_hierarchy import compose_final_resolutions
    analyses,catalog,bundle,groups,payload=local_replacement_case()
    # Both original and grouped immutable claims receive the same deliberate
    # mutation, so group identity checks do not mask the basis-retention guard.
    for hint in (bundle['leaves']['A1:H1']['hint'],groups[0]['hints'][0]):
        if kind=='real_problem':hint['problemEvidenceIds']=['C:S1']
        if kind=='not_local':hint.update(claimKind='AMBIGUOUS_CLAUSE',attention='REVIEW')
        if kind=='fake_witness':hint['workScopeWitness']['scopeHash']='invalid'
        if kind=='unmatched_witness':hint['workScopeWitness']['witnessEvidenceIds']=['C:S2']
    with pytest.raises(AuditAiError):compose_final_resolutions(bundle,groups,payload)


def test_nullable_inheritance_of_51_distinct_citations_is_a_confirmed_unfixed_derived_capacity_gap():
    # Explicit characterization, NOT a passing business fix. Both hint arrays
    # are individually valid, while server-derived union exceeds Resolution50.
    case=provider_case();refs=[f'e{i}' for i in range(51)]
    hint=case['source_hints']['G1:H1'];hint['problemEvidenceIds']=refs[:50];hint['comparisonEvidenceIds']=refs[50:]
    case['evidence_ids']=refs;case['fully_visible_evidence_ids']=refs
    parse_scoped_draft({'summary':'合法合成原意见。','hints':[deepcopy(hint)],
        'scopeStatus':'CHECKED_WITH_FINDINGS'},synthesis=False)
    expanded=native_decode(case)
    assert expanded['resolutions'][0]['basisEvidenceIds']==refs
    with pytest.raises(QualityFailure) as rejected:parse_scoped_draft(expanded,synthesis=True,allow_process_resolution=True)
    assert rejected.value.field_path=='resolutions[0].basisEvidenceIds'


@pytest.mark.parametrize('field,value,coordinate',[
    ('observation',[0],'resolutions[0].observation'),
    ('observation',[0,True],'resolutions[0].observation[1]'),
    ('finalHintIndexes',[True],'resolutions[0].finalHintIndexes[0]'),
    ('disposition','invalid','resolutions[0].disposition'),
    ('finalHintIndexes',[],'resolutions'),
    ('basisEvidenceIds',[],'resolutions[0].basisEvidenceIds'),
    ('basisEvidenceIds',['e0']*51,'resolutions[0].basisEvidenceIds'),
    ('reason','','resolutions[0].reason'),
    ('reason','原'*301,'resolutions[0].reason'),
])
def test_expanded_typed_resolution_fields_have_concrete_positive_and_negative(field,value,coordinate):
    case=provider_case();expanded=native_decode(case)
    parse_scoped_draft(expanded,synthesis=True,allow_process_resolution=True)
    expanded['resolutions'][0][field]=value
    with pytest.raises(QualityFailure) as rejected:
        parse_scoped_draft(expanded,synthesis=True,allow_process_resolution=True)
    assert rejected.value.field_path==coordinate


@pytest.mark.parametrize('kind,code,path',[
    ('global_not_visible','GLOBAL_ABSENCE_NOT_VISIBLE','hints[0].claimKind'),
    ('global_in_analysis','LOCAL_CLAIM_SCOPE','hints[0].claimKind'),
    ('material_already_present','GLOBAL_MATERIAL_PRESENT','hints[0].unavailableMaterialCategory'),
    ('non_chinese','CHINESE_BUSINESS_TEXT','hints[0].description'),
    ('count_contradiction','CLAIM_COUNT_CONTRADICTION','hints[0].description'),
    ('evidence_outside','EVIDENCE_SCOPE','hints[0].problemEvidenceIds'),
    ('whole_from_table','EVIDENCE_SUPPORT_SCOPE','hints[0]'),
    ('unscoped_analysis_absence','LOCAL_CLAIM_SCOPE','hints[0].description'),
])
def test_all_final_claim_guard_sites_have_paired_positive_and_precise_negative(kind,code,path):
    from audit_ai.model.evidence import EvidenceCatalog,EvidenceSegment
    from audit_ai.model.semantic_quality import check_claims
    text='乙方交付安排的真实合成原文。'
    catalog=EvidenceCatalog([EvidenceSegment('e0','body','CONTRACT_BODY',0,len(text),text,True)],
        source_lengths={'body':len(text)})
    hint=public_hint();options=dict(catalog=catalog,available_categories={'CONTRACT_BODY'},allowed_evidence_ids={'e0'},synthesis=True)
    check_claims({'hints':[hint]},**options)
    if kind in {'global_not_visible','global_in_analysis'}:hint['claimKind']='GLOBAL_CLAUSE_NOT_FOUND'
    if kind=='global_not_visible':options['required_global_source_files']={'not-completely-visible-file'}
    if kind=='global_in_analysis':options['synthesis']=False
    if kind=='material_already_present':hint['unavailableMaterialCategory']='CONTRACT_BODY'
    if kind=='non_chinese':hint.update(title='Source concern',description='Check only this source.')
    if kind=='count_contradiction':hint['description']='MX-003至MX-012共12项。'
    if kind=='evidence_outside':options['allowed_evidence_ids']=set()
    if kind=='whole_from_table':
        table='明细\nMX-001\t数量2\t单价已移除'
        options['catalog']=EvidenceCatalog([EvidenceSegment('e0','body','CONTRACT_BODY',0,len(table),table,True)])
        hint.update(category='CONTRACT_STRUCTURE',attention='INFO',claimKind='CLAUSE_PRESENT',
            title='合同结构完整',description='合同全文结构完整。',problemEvidenceIds=[],comparisonEvidenceIds=['e0'])
    if kind=='unscoped_analysis_absence':
        options['synthesis']=False;hint.update(attention='INSUFFICIENT',title='交付条款缺失',description='交付条款缺失，未作约定。')
    with pytest.raises(QualityFailure) as rejected:check_claims({'hints':[hint]},**options)
    assert rejected.value.code==code and rejected.value.field_path==path


def test_reference_type_defense_also_rejects_in_process_mutation_after_row_type_validation():
    # This branch is unreachable for an immutable parsed response. The fault
    # deliberately mutates ONLY a public synthetic fixture between guards.
    # It is not a provider response reproduction or a relaxed parsing path.
    import sys
    case=provider_case();assert native_decode(case)['hints']
    previous=sys.gettrace();mutated=[]
    def fault(frame,event,arg):
        if previous is not None:previous(frame,event,arg)
        if event=='return' and frame.f_code.co_name=='basis' and not mutated:
            case['payload']['resolutions'][0][3][0]=False;mutated.append(True)
        return fault
    try:
        sys.settrace(fault)
        with pytest.raises(AuditAiError) as rejected:native_decode(case)
    finally:sys.settrace(previous)
    assert mutated and rejected.value.field_path=='resolutions[0].targetRefs'
    assert rejected.value.actual_class=='FINAL_TARGET_REFERENCE_TYPE'


def test_total_expanded_hint_bound_is_not_only_the_new_hint_array_bound():
    case=provider_case();case['payload']['newHints']=[public_hint() for _ in range(99)]
    expanded=native_decode(case)
    assert len(expanded['hints'])==101 and len(case['payload']['newHints'])==99
    with pytest.raises(QualityFailure) as rejected:parse_scoped_draft(expanded,synthesis=True,allow_process_resolution=True)
    assert rejected.value.field_path=='hints'
    assert len(expanded['hints'])==101, 'capacity failure must not become tail truncation'


def test_composed_51_basis_union_remains_an_explicit_open_derived_capacity_failure():
    from audit_ai.model.semantic_hierarchy import build_hierarchy_inputs,compose_final_resolutions
    from test_semantic_hierarchy import candidate
    refs=[f'e{i}' for i in range(51)];hint=public_hint();hint['problemEvidenceIds']=refs[:50]
    analyses=[{'logicalKey':'synthetic-derived-capacity','checkpointHash':'a'*64,
        'summary':'合成容量正例。','hints':[hint]}]
    rows=[{'evidenceId':eid,'fileId':'same-frozen-file','materialCategory':'CONTRACT_BODY',
        'sourceStart':i,'sourceEnd':i+1,'citable':True} for i,eid in enumerate(refs)]
    bundle=build_hierarchy_inputs(analyses,rows,material_states=[])
    groups=[candidate(bundle,g) for g in bundle['groups']]
    final=deepcopy(hint);final['comparisonEvidenceIds']=[refs[-1]]
    payload={'hints':[final],'resolutions':[{'observation':[0,0],'disposition':'RETAINED',
        'finalHintIndexes':[0],'basisEvidenceIds':[refs[-1]],'reason':'原锚点及额外依据全部保留。'}]}
    assert reconcile_observations(groups,payload,allowed_evidence_ids=set(refs))
    composed,chain=compose_final_resolutions(bundle,groups,payload)
    assert composed[0]['basisEvidenceIds']==refs and chain[0]['groupBasisEvidenceIds']==refs[:50]
    with pytest.raises(QualityFailure) as rejected:
        reconcile_observations(analyses,{**payload,'resolutions':composed},allowed_evidence_ids=set(refs))
    assert rejected.value.code=='RESOLUTION_SCHEMA'


@pytest.mark.parametrize('kind',['local_final','material_attention','material_identity'])
def test_final_claim_type_guards_have_real_positive_and_field_specific_negative(kind):
    payload={'summary':'合成材料要求。','hints':[public_hint(attention='INSUFFICIENT')], 'resolutions':[]}
    payload['hints'][0].update(claimKind='MATERIAL_MISSING',unavailableMaterialCategory='SUPPLEMENT')
    parse_scoped_draft(payload,synthesis=True,allow_process_resolution=True)
    if kind=='local_final':payload['hints'][0].update(claimKind='LOCAL_NOT_SEEN',unavailableMaterialCategory=None)
    if kind=='material_attention':payload['hints'][0]['attention']='REVIEW'
    if kind=='material_identity':payload['hints'][0]['unavailableMaterialCategory']=None
    with pytest.raises(QualityFailure) as rejected:parse_scoped_draft(payload,synthesis=True,allow_process_resolution=True)
    assert rejected.value.code==('UNRESOLVED_LOCAL_OBSERVATION' if kind=='local_final' else 'MATERIAL_ABSENCE_TYPE')
    assert rejected.value.field_path=={'local_final':'hints[0].claimKind','material_attention':'hints[0].attention',
        'material_identity':'hints[0].unavailableMaterialCategory'}[kind]


@pytest.mark.parametrize('kind,code,path',[
    ('extra','EXTRA_PROPERTY','hints[0].OTHER'), ('missing','MISSING_REQUIRED_FIELD','hints[0].title'),
    ('enum','ENUM_VALUE_INVALID','hints[0].attention'), ('type','TYPE_MISMATCH','hints[0].title')])
def test_shared_base_parser_safe_error_taxonomy_is_preserved_in_final(kind,code,path):
    expanded=native_decode(provider_case());parse_scoped_draft(expanded,synthesis=True,allow_process_resolution=True)
    if kind=='extra':expanded['hints'][0]['PRIVATE_SENTINEL']='PRIVATE_SENTINEL'
    if kind=='missing':expanded['hints'][0].pop('title')
    if kind=='enum':expanded['hints'][0]['attention']='PRIVATE_SENTINEL'
    if kind=='type':expanded['hints'][0]['title']=7
    with pytest.raises(QualityFailure) as rejected:parse_scoped_draft(expanded,synthesis=True,allow_process_resolution=True)
    assert rejected.value.code==code and rejected.value.field_path==path
    assert 'PRIVATE_SENTINEL' not in str(rejected.value)+str(rejected.value.safe_issues)


@pytest.mark.parametrize('kind',['unknown','uncitable','outside','review_no_problem','insufficient_no_problem',
    'info_problem','info_no_comparison','missing_invalid_category','missing_present','missing_no_comparison',
    'missing_problem','missing_wrong_attention'])
def test_public_evidence_rejection_flags_have_valid_role_specific_positive(kind):
    from audit_ai.model.contract_draft import ModelHintDraft,reconstruct_public_hint
    from audit_ai.model.evidence import EvidenceCatalog,EvidenceSegment
    text='合成原文明确要求提供补充材料。'
    catalog=EvidenceCatalog([EvidenceSegment('e0','same-frozen-file','CONTRACT_BODY',0,len(text),text,True)])
    missing=kind.startswith('missing_');info=kind.startswith('info_')
    values=dict(category='DELIVERY_TERMS',attention='INSUFFICIENT' if missing else 'INFO' if info else 'REVIEW',
        title='补充材料缺失' if missing else '合成条款核对',description='仅验证真实原文证据规则。',
        problem_evidence_ids=() if missing or info else ('e0',),comparison_evidence_ids=('e0',) if missing or info else (),
        unavailable_material_category='SUPPLEMENT' if missing else None)
    options=dict(catalog=catalog,available_material_categories={'CONTRACT_BODY'},allowed_evidence_ids={'e0'})
    assert reconstruct_public_hint(ModelHintDraft(**values),**options).evidence_valid
    if kind=='unknown':values['problem_evidence_ids']=('unknown',)
    if kind=='uncitable':options['catalog']=EvidenceCatalog([replace_segment(catalog.segments[0],citable=False)])
    if kind=='outside':options['allowed_evidence_ids']=set()
    if kind in {'review_no_problem','insufficient_no_problem'}:
        values['problem_evidence_ids']=();values['attention']='REVIEW' if kind=='review_no_problem' else 'INSUFFICIENT'
    if kind=='info_problem':values['problem_evidence_ids']=('e0',)
    if kind=='info_no_comparison':values['comparison_evidence_ids']=()
    if kind=='missing_invalid_category':values['unavailable_material_category']='INVALID'
    if kind=='missing_present':values['unavailable_material_category']='CONTRACT_BODY'
    if kind=='missing_no_comparison':values['comparison_evidence_ids']=()
    if kind=='missing_problem':values['problem_evidence_ids']=('e0',)
    if kind=='missing_wrong_attention':values['attention']='REVIEW'
    assert reconstruct_public_hint(ModelHintDraft(**values),**options).evidence_valid is False


def replace_segment(segment,**fields):
    from dataclasses import replace
    return replace(segment,**fields)
