"""Per-predicate native proof negatives, with valid positive controls.

These public synthetic fixtures characterize the interface. They are not the
lost completion or formal model results. Direct helper faults are explicitly
separated from full typed/native publication tests.
"""
from copy import deepcopy
from dataclasses import replace
import sys

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.semantic_final_evidence import catalog_rows, verify_proof
from audit_ai.model.semantic_hierarchy import build_hierarchy_inputs
from audit_ai.model.semantic_quality import ProcessResolution, QualityFailure, _validate_process_resolution
from audit_ai.model.semantic_scope_refinement import validate as validate_refinement
from test_final_frozen_evidence import fixture, process_payload, V2
from test_final_scope_refinement import case as refinement_case


def process_case():
    analyses,_,catalog=fixture()
    bundle=build_hierarchy_inputs(analyses,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    row=ProcessResolution.model_validate({'observation':[0,0], 'disposition':'PROCESS_RESOLVED',
        'finalHintIndexes':[], 'basisEvidenceIds':['C:S2'], 'reason':'同一原文明确延期责任，保留原局部观察轨迹。'})
    return {'analyses':analyses, 'original':analyses[0]['hints'][0], 'catalog':catalog,
        'row':row, 'finals':process_payload()['newHints'], 'allowed':{'C:S1','C:S2'},
        'scope_hashes':{analyses[0]['workScope']['scopeHash']}, 'proof':bundle['finalEvidenceProof']}


def scoped_case(kind='identity'):
    analyses,catalog,bundle,hint,row=refinement_case(kind)
    return {'analyses':analyses, 'original':analyses[0]['hints'][0], 'catalog':catalog,
        'row':ProcessResolution.model_validate(row), 'finals':[hint], 'allowed':set(bundle['visibleEvidenceIds']),
        'scope_hashes':{analyses[0]['workScope']['scopeHash']}, 'proof':bundle['finalEvidenceProof']}


def invoke(state, scoped=False):
    args=[state[k] for k in ('original','row','finals','catalog','allowed','scope_hashes','proof')]
    return validate_refinement(*args,0) if scoped else _validate_process_resolution(*args,0)


def rebuild(state, *, text=None, citable=True, other_file=False, extra_unseen=False):
    catalog=state['catalog']; segments=list(catalog.segments)
    old=segments[1]; text=old.exact_text if text is None else text
    start=0 if other_file else old.source_start
    segments[1]=replace(old, file_id='other-legal-frozen-file' if other_file else old.file_id,
        source_start=start,source_end=start+len(text),exact_text=text,citable=citable)
    if extra_unseen:
        tail='\n9. 背景说明\n仅作为背景的颜色说明。\n'; end=segments[1].source_end
        segments.append(EvidenceSegment('C:S3',old.file_id,'CONTRACT_BODY',end,end+len(tail),tail,True))
    lengths={}
    for segment in segments:lengths[segment.file_id]=max(lengths.get(segment.file_id,0),segment.source_end)
    state['catalog']=EvidenceCatalog(segments,source_lengths=lengths)
    bundle=build_hierarchy_inputs(state['analyses'],catalog_rows(state['catalog']),material_states=[],
        protocol=V2,frozen_catalog=state['catalog'])
    state['proof']=bundle['finalEvidenceProof']
    assert verify_proof(state['proof'],state['catalog']), 'later predicate must not be masked by invalid source proof'


def make_uncitable_basis_actually_visible(state):
    first=state['catalog'].segments[0];start=first.source_end
    pieces=['乙方交付延期','应承担违约责任；乙方交付延期应承担违约责任，项目名称：合成项目；项目编号：SYNTH-1。']
    segments=[first]
    for eid,text,citable in zip(['C:S2','C:S3'],pieces,[False,True]):
        segments.append(EvidenceSegment(eid,first.file_id,'CONTRACT_BODY',start,start+len(text),text,citable))
        start+=len(text)
    state['catalog']=EvidenceCatalog(segments,source_lengths={first.file_id:start})
    state['proof']=build_hierarchy_inputs(state['analyses'],catalog_rows(state['catalog']),material_states=[],
        protocol=V2,frozen_catalog=state['catalog'])['finalEvidenceProof']
    assert verify_proof(state['proof'],state['catalog'])
    assert 'C:S2' in state['proof']['evidenceOrder'] and not state['catalog'].by_id['C:S2'].citable


def add_visible_other_file(state):
    text='项目名称：另份合成材料\n项目编号：SYNTH-2\n'
    other=EvidenceSegment('C:S3','other-legal-frozen-file','CONTRACT_BODY',0,len(text),text,True)
    state['catalog']=EvidenceCatalog([*state['catalog'].segments,other],
        source_lengths={**state['catalog'].source_lengths,other.file_id:len(text)})
    from audit_ai.model.semantic_work import _work_scope
    extra=deepcopy(state['analyses'][0]);extra['logicalKey']='another-frozen-analysis';extra['checkpointHash']='b'*64
    work=_work_scope(state['catalog'],{'C:S3'},'CHECKED_WITH_FINDINGS')
    extra['hints'][0]['comparisonEvidenceIds']=['C:S3']
    extra['hints'][0]['workScopeWitness'].update(scopeHash=work['scopeHash'],witnessEvidenceIds=['C:S3'])
    state['analyses'].append(extra)
    state['proof']=build_hierarchy_inputs(state['analyses'],catalog_rows(state['catalog']),material_states=[],
        protocol=V2,frozen_catalog=state['catalog'])['finalEvidenceProof']
    assert verify_proof(state['proof'],state['catalog']) and 'C:S3' in state['proof']['evidenceOrder']
    state['allowed'].add('C:S3')


def failure_at_native_helper(state, *, scoped):
    sites=[]
    def trace(frame,event,arg):
        if previous is not None: previous(frame,event,arg)
        if event=='call' and frame.f_code.co_name=='reject' and frame.f_back is not None:
            sites.append((frame.f_back.f_code.co_name,frame.f_back.f_lineno))
        return trace
    previous=sys.gettrace()
    try:
        sys.settrace(trace)
        with pytest.raises(QualityFailure) as rejected:invoke(state,scoped)
    finally:sys.settrace(previous)
    assert rejected.value.code==('SCOPE_REFINEMENT_UNPROVEN' if scoped else 'PROCESS_RESOLUTION_UNPROVEN')
    assert rejected.value.field_path=='resolutions' and rejected.value.index==0
    assert sites and sites[-1][0]==('validate' if scoped else '_validate_process_resolution')
    return sites[-1]


PROCESS_PREDICATES=[
    'P01_targets','P02_original_kind','P03_original_attention','P04_real_problem','P05_material_gap',
    'P06_unknown_party','P07_null_trigger','P08_blank_trigger','P09_witness_protocol','P10_witness_type',
    'P11_witness_index','P12_witness_hash','P13_empty_comparison','P14_witness_bag','P15_absent_proof',
    'P16_tampered_proof','P17_empty_basis','P18_duplicate_basis','P19_not_allowed','P20_unknown_old_id',
    'P21_unknown_basis_id','P22_not_distinct_id','P23_uncitable','P24_other_file','P25_not_visible',
    'P26_wrong_actor','P27_wrong_trigger','P28_both_not_in_same_segment','P29_no_independent_info',
    'P30_positive_kind','P31_positive_attention','P32_positive_category','P33_positive_scope',
    'P34_positive_problem','P35_positive_basis_coverage',
]


@pytest.mark.parametrize('predicate',PROCESS_PREDICATES)
def test_process_resolution_each_predicate_preserves_valid_positive_and_rejects_one_fault(predicate):
    state=process_case();invoke(state)
    old=state['original']; row=state['row']; positive=state['finals'][0];w=old['workScopeWitness']
    if predicate=='P01_targets':row.final_hint_indexes=[0]
    if predicate=='P02_original_kind':old['claimKind']='AMBIGUOUS_CLAUSE'
    if predicate=='P03_original_attention':old['attention']='REVIEW'
    if predicate=='P04_real_problem':old['problemEvidenceIds']=['C:S1']
    if predicate=='P05_material_gap':old['unavailableMaterialCategory']='SUPPLEMENT'
    if predicate=='P06_unknown_party':old['obligationScope']['responsibleParty']='UNSPECIFIED'
    if predicate=='P07_null_trigger':old['obligationScope']['trigger']=None
    if predicate=='P08_blank_trigger':old['obligationScope']['trigger']=' \t\n'
    if predicate=='P09_witness_protocol':w['protocol']='invalid'
    if predicate=='P10_witness_type':w['type']='invalid'
    if predicate=='P11_witness_index':w['hintIndex']=1
    if predicate=='P12_witness_hash':w['scopeHash']='f'*64
    if predicate=='P13_empty_comparison':old['comparisonEvidenceIds']=[];w['witnessEvidenceIds']=[]
    if predicate=='P14_witness_bag':w['witnessEvidenceIds']=['C:S2']
    if predicate=='P15_absent_proof':state['proof']=None
    if predicate=='P16_tampered_proof':state['proof']['viewSha256']='f'*64
    if predicate=='P17_empty_basis':row.basis_evidence_ids=[]
    if predicate=='P18_duplicate_basis':row.basis_evidence_ids=['C:S2','C:S2']
    if predicate=='P19_not_allowed':state['allowed']={'C:S1'}
    if predicate=='P20_unknown_old_id':old['comparisonEvidenceIds']=['unknown'];w['witnessEvidenceIds']=['unknown']
    if predicate=='P21_unknown_basis_id':row.basis_evidence_ids=['unknown'];state['allowed'].add('unknown')
    if predicate=='P22_not_distinct_id':row.basis_evidence_ids=['C:S1']
    if predicate=='P23_uncitable':make_uncitable_basis_actually_visible(state)
    if predicate=='P24_other_file':rebuild(state,other_file=True)
    if predicate=='P25_not_visible':
        rebuild(state,extra_unseen=True);assert 'C:S3' not in state['proof']['evidenceOrder']
        row.basis_evidence_ids=['C:S3'];state['allowed'].add('C:S3')
    if predicate=='P26_wrong_actor':rebuild(state,text='甲方交付延期应承担责任。')
    if predicate=='P27_wrong_trigger':rebuild(state,text='乙方的付款延期责任由其他条款约定。')
    if predicate=='P28_both_not_in_same_segment':
        old['obligationScope']['responsibleParty']='BOTH';positive['obligationScope']['responsibleParty']='BOTH'
        rebuild(state,text='乙方交付延期应承担责任。')
    if predicate=='P29_no_independent_info':state['finals']=[]
    if predicate=='P30_positive_kind':positive['claimKind']='AMBIGUOUS_CLAUSE'
    if predicate=='P31_positive_attention':positive['attention']='REVIEW'
    if predicate=='P32_positive_category':positive['category']='TERM_TERMINATION'
    if predicate=='P33_positive_scope':positive['obligationScope']['trigger']='验收'
    if predicate=='P34_positive_problem':positive['problemEvidenceIds']=['C:S2']
    if predicate=='P35_positive_basis_coverage':positive['comparisonEvidenceIds']=['C:S1']
    failure_at_native_helper(state,scoped=False)


SCOPED_PREDICATES=[
    'S01_original_kind','S02_original_attention','S03_real_problem','S04_material_gap',
    'S05_witness_protocol','S06_witness_type','S07_witness_index','S08_witness_hash',
    'S09_empty_old_refs','S10_witness_bag','S11_invalid_proof','S12_empty_basis','S13_duplicate_basis',
    'S14_unallowed_basis','S15_unknown_old_refs','S16_empty_targets','S17_duplicate_targets','S18_target_outside',
    'S19_basis_not_visible','S20_basis_uncitable','S21_basis_other_file','S22_target_kind',
    'S23_target_attention','S24_target_problem','S25_target_category','S26_basis_not_target',
    'S27_target_unallowed','S28_target_unseen','S29_target_other_file','S30_known_party_change',
    'S31_known_trigger_change','S32_identity_target_topic','S33_identity_target_party','S34_identity_target_trigger',
    'S35_identity_name_missing','S36_identity_number_missing','S37_identity_party_a_missing',
    'S38_identity_party_b_missing','S39_identity_placeholder','S40_identity_next_label_value',
    'S41_unsupported_topic','S42_delay_category','S43_delay_no_delivery_question','S44_delay_no_responsibility',
    'S45_delay_defect_not_in_target','S46_delay_defect_unknown_actor','S47_delay_defect_wrong_actor',
    'S48_delay_defect_negated','S49_termination_no_facet','S50_termination_missing_facet',
    'S51_termination_target_coverage','S52_target_topic','S53_target_unknown_party','S54_target_blank_trigger',
    'S55_target_own_basis_no_actor','S56_delay_different_liable_actor','S57_termination_no_direct_actor',
    'S58_termination_no_same_clause_condition','S59_trigger_not_visible',
]


@pytest.mark.parametrize('predicate',SCOPED_PREDICATES)
def test_scoped_counterevidence_every_predicate_has_native_positive_and_one_negative(predicate):
    kind=('delay' if predicate.startswith(tuple(f'S{i:02d}_' for i in (30,31,42,43,44,45,46,47,48,52,53,54,55,56,59)))
          else 'termination' if predicate.startswith(tuple(f'S{i:02d}_' for i in (49,50,51,57,58))) else 'identity')
    state=scoped_case(kind);proof=invoke(state,True)
    assert proof['fieldEvidenceAtoms'] and proof['automaticUnknownClosure'] is False
    old=state['original'];row=state['row'];target=state['finals'][0];w=old['workScopeWitness']
    if predicate=='S01_original_kind':old['claimKind']='AMBIGUOUS_CLAUSE'
    if predicate=='S02_original_attention':old['attention']='INFO'
    if predicate=='S03_real_problem':old['problemEvidenceIds']=['C:S1']
    if predicate=='S04_material_gap':old['unavailableMaterialCategory']='SUPPLEMENT'
    if predicate=='S05_witness_protocol':w['protocol']='invalid'
    if predicate=='S06_witness_type':w['type']='invalid'
    if predicate=='S07_witness_index':w['hintIndex']=1
    if predicate=='S08_witness_hash':w['scopeHash']='f'*64
    if predicate=='S09_empty_old_refs':old['comparisonEvidenceIds']=[];w['witnessEvidenceIds']=[]
    if predicate=='S10_witness_bag':w['witnessEvidenceIds']=['C:S2']
    if predicate=='S11_invalid_proof':state['proof']=None
    if predicate=='S12_empty_basis':row.basis_evidence_ids=[]
    if predicate=='S13_duplicate_basis':row.basis_evidence_ids=['C:S2','C:S2']
    if predicate=='S14_unallowed_basis':state['allowed'].discard('C:S2')
    if predicate=='S15_unknown_old_refs':old['comparisonEvidenceIds']=['unknown'];w['witnessEvidenceIds']=['unknown']
    if predicate=='S16_empty_targets':row.final_hint_indexes=[]
    if predicate=='S17_duplicate_targets':row.final_hint_indexes=[0,0]
    if predicate=='S18_target_outside':row.final_hint_indexes=[1]
    if predicate=='S19_basis_not_visible':
        rebuild(state,extra_unseen=True);assert 'C:S3' not in state['proof']['evidenceOrder']
        row.basis_evidence_ids=['C:S3'];state['allowed'].add('C:S3')
    if predicate=='S20_basis_uncitable':make_uncitable_basis_actually_visible(state)
    if predicate=='S21_basis_other_file':rebuild(state,other_file=True)
    if predicate=='S22_target_kind':target['claimKind']='AMBIGUOUS_CLAUSE'
    if predicate=='S23_target_attention':target['attention']='REVIEW'
    if predicate=='S24_target_problem':target['problemEvidenceIds']=['C:S2']
    if predicate=='S25_target_category':target['category']='SUPPLIER_IDENTITY'
    if predicate=='S26_basis_not_target':target['comparisonEvidenceIds']=['C:S1']
    if predicate=='S27_target_unallowed':target['comparisonEvidenceIds'].append('C:S1');state['allowed'].discard('C:S1')
    if predicate=='S28_target_unseen':
        rebuild(state,extra_unseen=True);assert 'C:S3' not in state['proof']['evidenceOrder']
        target['comparisonEvidenceIds'].append('C:S3');state['allowed'].add('C:S3')
    if predicate=='S29_target_other_file':
        add_visible_other_file(state);target['comparisonEvidenceIds'].append('C:S3')
    if predicate=='S30_known_party_change':target['obligationScope']['responsibleParty']='PARTY_A'
    if predicate=='S31_known_trigger_change':target['obligationScope']['trigger']='延期交付'
    if predicate=='S32_identity_target_topic':target['obligationScope']['topic']='DELIVERY'
    if predicate=='S33_identity_target_party':target['obligationScope']['responsibleParty']='PARTY_A'
    if predicate=='S34_identity_target_trigger':target['obligationScope']['trigger']='项目编号'
    if predicate=='S35_identity_name_missing':rebuild(state,text='项目编号：SYNTH-1\n登记说明。\n')
    if predicate=='S36_identity_number_missing':rebuild(state,text='项目名称：合成服务项目\n登记说明。\n')
    if predicate in {'S37_identity_party_a_missing','S38_identity_party_b_missing'}:
        old['description']='当前片段未见项目名称及项目编号、双方主体名称。'
        text='项目名称：合成服务项目\n项目编号：SYNTH-1\n'+('乙方：合成乙\n' if 'a_missing' in predicate else '甲方：合成甲\n')
        rebuild(state,text=text)
    if predicate=='S39_identity_placeholder':rebuild(state,text='项目名称：待确认\n项目编号：SYNTH-1\n')
    if predicate=='S40_identity_next_label_value':rebuild(state,text='项目名称：甲方：合成甲\n项目编号：SYNTH-1\n')
    if predicate=='S41_unsupported_topic':old['obligationScope']['topic']='OTHER'
    if predicate=='S42_delay_category':old['category']='SUPPLIER_IDENTITY';target['category']='SUPPLIER_IDENTITY'
    if predicate=='S43_delay_no_delivery_question':old.update(title='当前片段未见责任安排',description='当前片段未见一般责任安排。')
    if predicate=='S44_delay_no_responsibility':rebuild(state,text='乙方交付延期时应与甲方协商。')
    if predicate in {'S45_delay_defect_not_in_target','S46_delay_defect_unknown_actor','S47_delay_defect_wrong_actor','S48_delay_defect_negated'}:
        old['description']='当前片段未见乙方交付延期或功能缺陷责任安排。'
        target['description']='原文记载交付延期和功能缺陷责任。'
        if predicate=='S45_delay_defect_not_in_target':target['description']='原文记载交付延期责任。'
        if predicate=='S46_delay_defect_unknown_actor':old['obligationScope']['responsibleParty']='UNSPECIFIED'
        actor='甲方' if predicate=='S47_delay_defect_wrong_actor' else '乙方'
        defect=f'功能缺陷发生时，{actor}'+('无需承担' if predicate=='S48_delay_defect_negated' else '应承担')+'责任。'
        rebuild(state,text='乙方交付延期应承担违约责任。'+defect)
    if predicate=='S49_termination_no_facet':old.update(title='当前片段未见合同事项',description='当前片段未见合同事项。')
    if predicate=='S50_termination_missing_facet':old['description']='当前片段未见解除及生效约定。'
    if predicate=='S51_termination_target_coverage':target['obligationScope']['topic']='EFFECTIVENESS'
    if predicate=='S52_target_topic':target['obligationScope']['topic']='WARRANTY'
    if predicate=='S53_target_unknown_party':old['obligationScope']['responsibleParty']='UNSPECIFIED';target['obligationScope']['responsibleParty']='UNSPECIFIED'
    if predicate=='S54_target_blank_trigger':old['obligationScope']['trigger']=None;target['obligationScope']['trigger']=' \t'
    if predicate=='S55_target_own_basis_no_actor':
        old['obligationScope']['responsibleParty']='UNSPECIFIED';target['obligationScope']['responsibleParty']='PARTY_A'
        rebuild(state,text='乙方交付延期应承担违约责任。')
    if predicate=='S56_delay_different_liable_actor':
        old['obligationScope']['responsibleParty']='UNSPECIFIED';rebuild(state,text='乙方报告甲方交付延期，甲方应承担违约责任。')
    if predicate=='S57_termination_no_direct_actor':rebuild(state,text='乙方严重违约时，甲方可通知乙方解除合同。')
    if predicate=='S58_termination_no_same_clause_condition':rebuild(state,text='乙方严重违约应补救。甲方解除合同的条件另行协商。')
    if predicate=='S59_trigger_not_visible':old['obligationScope']['trigger']=None;target['obligationScope']['trigger']='完全另一个触发条件'
    failure_at_native_helper(state,scoped=True)


def test_scope_refinement_effectiveness_has_its_own_supported_positive_not_termination_substitution():
    state=scoped_case('termination');old=state['original'];target=state['finals'][0]
    old.update(title='当前片段未见生效约定',description='当前片段未见合同生效约定。')
    target['obligationScope']={'topic':'EFFECTIVENESS','responsibleParty':'BOTH','trigger':'签字并盖章'}
    rebuild(state,text='甲方与乙方签字并盖章后，本合同生效。')
    proof=invoke(state,True)
    assert proof['facets']==['EFFECTIVENESS'] and proof['fieldEvidenceAtoms']
    target['obligationScope']['trigger']='内部审批完成'
    failure_at_native_helper(state,scoped=True)


@pytest.mark.parametrize('field,value',[('responsibleParty','PARTY_A'),('trigger','另一个条件')])
def test_redundant_known_scope_defense_rejects_only_synthetic_in_process_mutation_after_first_guard(field,value):
    # Normal inputs already fail the earlier immutable-scope check (S30/S31).
    # Fault injection proves the later repeated defensive check; it is not an
    # independently model-reachable business condition or historical evidence.
    state=scoped_case('delay');invoke(state,True);previous=sys.gettrace();mutated=[]
    def fault(frame,event,arg):
        if previous is not None:previous(frame,event,arg)
        if event=='return' and frame.f_code.co_name=='locate' and not mutated:
            state['finals'][0]['obligationScope'][field]=value;mutated.append(True)
        return fault
    try:
        sys.settrace(fault);failure_at_native_helper(state,scoped=True)
    finally:sys.settrace(previous)
    assert mutated


def test_source_atom_defense_rejects_a_faulty_visible_renderer_not_a_missing_customer_material(monkeypatch):
    from audit_ai.model import semantic_final_evidence
    state=scoped_case();invoke(state,True)
    rebuild(state,text='项目编号：SYNTH-1\n')
    real_visible=semantic_final_evidence.visible_catalog(state['proof'],state['catalog'])
    fabricated='项目名称：仅用于组件故障注入的合成字段\n项目编号：SYNTH-1\n'
    fake=EvidenceCatalog([replace(s,exact_text=fabricated) if s.evidence_id=='C:S2' else s
                         for s in real_visible.segments])
    monkeypatch.setattr(semantic_final_evidence,'visible_catalog',lambda proof,catalog:fake)
    failure_at_native_helper(state,scoped=True)
    # The original immutable text and proof never acquire the injected label.
    assert '项目名称' not in state['catalog'].by_id['C:S2'].exact_text
    assert verify_proof(state['proof'],state['catalog'])


COMPOSITION_PREDICATES=['C01_group_count','C02_rows_type','C03_coordinate_type','C04_coordinate_width',
    'C05_coordinate_bool','C06_duplicate_coordinate','C07_unknown_coordinate','C08_missing_coordinate',
    'C09_group_lineage','C10_private_old_protocol','C11_process_nonempty','C12_targets_empty',
    'C13_targets_duplicate','C14_targets_bool','C15_targets_outside','C16_basis_not_target',
    'C17_counter_catalog_unknown','C18_counter_other_file','C19_missing_leaf_count']


def composition_case():
    from test_semantic_hierarchy import bundle,candidate
    b=bundle(1,hints_per_analysis=2);groups=[candidate(b,g) for g in b['groups']]
    payload={'hints':deepcopy(groups[0]['hints']), 'resolutions':[
        {'observation':[0,i],'disposition':'RETAINED','finalHintIndexes':[i],
         'basisEvidenceIds':list(h['problemEvidenceIds']), 'reason':'完整保留原观察。'} for i,h in enumerate(groups[0]['hints'])]}
    return b,groups,payload


@pytest.mark.parametrize('predicate',COMPOSITION_PREDICATES)
def test_composition_rejection_sites_have_native_positive_and_negative(predicate):
    from audit_ai.model.semantic_hierarchy import compose_final_resolutions
    b,groups,p=composition_case();assert len(compose_final_resolutions(b,groups,p)[0])==2
    row=p['resolutions'][0]
    if predicate=='C01_group_count':groups=[]
    if predicate=='C02_rows_type':p['resolutions']={}
    if predicate=='C03_coordinate_type':row['observation']='invalid'
    if predicate=='C04_coordinate_width':row['observation']=[0]
    if predicate=='C05_coordinate_bool':row['observation']=[0,True]
    if predicate=='C06_duplicate_coordinate':p['resolutions'][1]['observation']=[0,0]
    if predicate=='C07_unknown_coordinate':row['observation']=[2,0]
    if predicate=='C08_missing_coordinate':p['resolutions'].pop()
    if predicate=='C09_group_lineage':groups[0]['lineage'].pop()
    if predicate=='C10_private_old_protocol':row.update(disposition='PROCESS_RESOLVED',finalHintIndexes=[])
    if predicate=='C11_process_nonempty':b['protocol']=V2;row['disposition']='PROCESS_RESOLVED'
    if predicate.startswith(('C12_','C13_','C14_','C15_')):row['finalHintIndexes']={
        'C12_targets_empty':[], 'C13_targets_duplicate':[0,0], 'C14_targets_bool':[True], 'C15_targets_outside':[2]}[predicate]
    if predicate=='C16_basis_not_target':p['hints'][0]['problemEvidenceIds']=[]
    if predicate=='C17_counter_catalog_unknown':row['disposition']='COUNTEREVIDENCE';p['hints'][0]['comparisonEvidenceIds']=['unknown']
    if predicate=='C18_counter_other_file':
        row['disposition']='COUNTEREVIDENCE';p['hints'][0]['comparisonEvidenceIds']=['other']
        b['evidenceCatalog'].append({'evidenceId':'other','fileId':'other-file'})
    if predicate=='C19_missing_leaf_count':b['leaves']['extra-closed-context-leaf']={}
    with pytest.raises(AuditAiError) as rejected:compose_final_resolutions(b,groups,p)
    assert rejected.value.code=='SEMANTIC_HIERARCHY_LINEAGE_INVALID'
