"""Explicit scope proof, not a null wildcard or a replacement business result."""
from copy import deepcopy
import pytest

from audit_ai.model.evidence import EvidenceCatalog,EvidenceSegment
from audit_ai.model.semantic_final_evidence import catalog_rows
from audit_ai.model.semantic_hierarchy import build_hierarchy_inputs
from audit_ai.model.semantic_quality import QualityFailure,reconcile_observations,parse_scoped_draft
from test_final_frozen_evidence import fixture,V2


def case(kind='identity'):
    analyses,_,_=fixture(); old=analyses[0]['hints'][0]
    if kind=='identity':
        category,topic='PROJECT_IDENTITY','PROJECT_IDENTITY'
        title,description='当前未见项目标识','当前片段未见项目名称及项目编号。'
        text='项目名称：合成服务项目\n项目编号：SYNTH-1\n'
        scope={'topic':topic,'responsibleParty':'BOTH','trigger':None}
    elif kind=='delay':
        category,topic='BREACH_LIABILITY','DELAY_LIABILITY'
        title,description='当前片段未见延期责任','当前片段未见乙方交付延期责任。'
        text='乙方未按约定期限完成交付的，承担违约责任；因甲方未提供场地造成的延误除外。\n'
        scope={'topic':topic,'responsibleParty':'PARTY_B','trigger':'交付延期'}
    else:
        category,topic='TERM_TERMINATION','TERMINATION'
        title,description='当前片段未见合同解除','当前片段未见合同解除条件。'
        text='乙方严重违约时，甲方有权解除合同，但须先书面催告。\n'
        scope={'topic':topic,'responsibleParty':'PARTY_A','trigger':'乙方严重违约'}
    old.update(category=category,title=title,description=description,
        obligationScope={'topic':topic,'responsibleParty':'UNSPECIFIED','trigger':None})
    if kind=='delay':old['obligationScope']=deepcopy(scope)
    source='当前片段说明。\n'
    segments=[EvidenceSegment('C:S1','frozen-contract','CONTRACT_BODY',0,len(source),source,True),
        EvidenceSegment('C:S2','frozen-contract','CONTRACT_BODY',len(source),len(source)+len(text),text,True)]
    catalog=EvidenceCatalog(segments,source_lengths={'frozen-contract':segments[-1].source_end})
    bundle=build_hierarchy_inputs(analyses,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    positive={**deepcopy(old),'attention':'INFO','claimKind':'CLAUSE_PRESENT',
        'title':'原文已记载对应事项','description':'对应原文已提供，保留其主体、条件和例外。',
        'obligationScope':scope,'comparisonEvidenceIds':['C:S2']}
    positive.pop('workScopeWitness')
    row={'observation':[0,0],'disposition':'SCOPED_COUNTEREVIDENCE','finalHintIndexes':[0],
         'basisEvidenceIds':['C:S2'],'reason':'依据同一材料明确原文核对原局部观察，原观察和未知范围仍保留。'}
    return analyses,catalog,bundle,positive,row


@pytest.mark.parametrize('kind',['identity','delay','termination'])
def test_supported_refinement_records_original_unknowns_and_actual_visible_basis(kind):
    a,c,b,h,row=case(kind); proofs=[]
    payload={'summary':'对应原文核对。','hints':[h],'resolutions':[row]}
    parse_scoped_draft(payload,synthesis=True,allow_process_resolution=True)
    rows=reconcile_observations(a,payload,catalog=c,allowed_evidence_ids=set(b['visibleEvidenceIds']),
        allow_process_resolution=True,process_scope_hashes={a[0]['workScope']['scopeHash']},
        final_evidence_proof=b['finalEvidenceProof'],scope_refinement_proofs=proofs)
    assert rows[0]['disposition']=='SCOPED_COUNTEREVIDENCE'
    assert proofs[0]['originalScope']==a[0]['hints'][0]['obligationScope']
    assert proofs[0]['targetScopes']==[h['obligationScope']]
    assert proofs[0]['basisVisibleRanges']=={'C:S2':b['finalEvidenceProof']['visibleRanges']['C:S2']}
    assert proofs[0]['facets'] and proofs[0]['automaticUnknownClosure'] is False


@pytest.mark.parametrize('mutation',['real_risk','qualification','unknown_target','wrong_topic',
    'wrong_party','missing_project_number','fake_witness','unseen','different_file','no_target','old_protocol'])
def test_scope_refinement_does_not_broaden_unknown_or_erase_real_gaps(mutation):
    a,c,b,h,row=case(); hashes={a[0]['workScope']['scopeHash']}
    old=a[0]['hints'][0]
    if mutation=='real_risk':old['problemEvidenceIds']=['C:S1']
    if mutation=='qualification':
        old.update(category='SUPPLIER_IDENTITY',description='未见供应商信用代码及资质证书。')
        old['obligationScope']['topic']='SUPPLIER_IDENTITY';h['category']='SUPPLIER_IDENTITY';h['obligationScope']['topic']='SUPPLIER_IDENTITY'
    if mutation=='unknown_target':h['obligationScope']['responsibleParty']='UNSPECIFIED'
    if mutation=='wrong_topic':h['obligationScope']['topic']='DELIVERY'
    if mutation=='wrong_party':old['obligationScope']['responsibleParty']='PARTY_B';h['obligationScope']['responsibleParty']='PARTY_A'
    if mutation=='missing_project_number':h['comparisonEvidenceIds']=['C:S1'];row['basisEvidenceIds']=['C:S1']
    if mutation=='fake_witness':old['workScopeWitness']['scopeHash']='f'*64
    if mutation=='unseen':b['finalEvidenceProof']['visibleRanges']['C:S2']=[]
    if mutation=='different_file':old['comparisonEvidenceIds']=['OTHER'];old['workScopeWitness']['witnessEvidenceIds']=['OTHER']
    if mutation=='no_target':row['finalHintIndexes']=[]
    with pytest.raises(QualityFailure):
        reconcile_observations(a,{'hints':[h],'resolutions':[row]},catalog=c,
            allowed_evidence_ids=set(b['visibleEvidenceIds']),allow_process_resolution=mutation!='old_protocol',
            process_scope_hashes=hashes,final_evidence_proof=b['finalEvidenceProof'])


@pytest.mark.parametrize('kind,text',[
    ('identity','项目名称：\n项目编号：SYNTH-1\n'),
    ('delay','乙方报告甲方交付延期，甲方应承担违约责任。\n'),
    ('termination','乙方严重违约时应补救。其他情形下甲方可以解除合同，具体条件另行协商。\n'),
],ids=['empty_static_field','different_liable_actor','disconnected_termination_condition'])
def test_empty_fields_other_actor_and_disconnected_conditions_do_not_refine(kind,text):
    a,_,_,h,row=case(kind);start=len('当前片段说明。\n')
    catalog=EvidenceCatalog([
        EvidenceSegment('C:S1','frozen-contract','CONTRACT_BODY',0,start,'当前片段说明。\n',True),
        EvidenceSegment('C:S2','frozen-contract','CONTRACT_BODY',start,start+len(text),text,True)],
        source_lengths={'frozen-contract':start+len(text)})
    bundle=build_hierarchy_inputs(a,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    with pytest.raises(QualityFailure,match='SCOPE_REFINEMENT_UNPROVEN'):
        reconcile_observations(a,{'hints':[h],'resolutions':[row]},catalog=catalog,
            allowed_evidence_ids=set(bundle['visibleEvidenceIds']),allow_process_resolution=True,
            process_scope_hashes={a[0]['workScope']['scopeHash']},final_evidence_proof=bundle['finalEvidenceProof'])


@pytest.mark.parametrize('placeholder',['未填写','待填写','待确认','空白','—','N/A'])
def test_placeholder_is_not_a_static_identity_fact(placeholder):
    a,_,_,h,row=case();text=f'项目名称：{placeholder}\n项目编号：SYNTH-1\n'
    start=len('当前片段说明。\n')
    catalog=EvidenceCatalog([EvidenceSegment('C:S1','frozen-contract','CONTRACT_BODY',0,start,'当前片段说明。\n',True),
        EvidenceSegment('C:S2','frozen-contract','CONTRACT_BODY',start,start+len(text),text,True)],
        source_lengths={'frozen-contract':start+len(text)})
    bundle=build_hierarchy_inputs(a,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    with pytest.raises(QualityFailure,match='SCOPE_REFINEMENT_UNPROVEN'):
        reconcile_observations(a,{'hints':[h],'resolutions':[row]},catalog=catalog,
            allowed_evidence_ids=set(bundle['visibleEvidenceIds']),allow_process_resolution=True,
            process_scope_hashes={a[0]['workScope']['scopeHash']},final_evidence_proof=bundle['finalEvidenceProof'])


@pytest.mark.parametrize('action,valid',[
    ('甲方可按约定程序发出解除通知',True),
    ('乙方可按约定程序发出解除通知',False),
    ('甲方不得按约定程序发出解除通知',False),
    ('甲方可通知乙方发出解除通知',False),
],ids=['conditional_notice','different_actor','negated_right','other_notice_actor'])
def test_procedural_termination_notice_keeps_condition_actor_and_continuing_liability(action,valid):
    a,_,_,h,row=case('termination')
    condition='累计延误达到约定阈值且书面通知后仍未改正'
    text=f'{condition}的，{action}；乙方的违约责任不因此免除。\n'
    h['obligationScope']['trigger']=condition
    start=len('当前片段说明。\n')
    catalog=EvidenceCatalog([
        EvidenceSegment('C:S1','frozen-contract','CONTRACT_BODY',0,start,'当前片段说明。\n',True),
        EvidenceSegment('C:S2','frozen-contract','CONTRACT_BODY',start,start+len(text),text,True)],
        source_lengths={'frozen-contract':start+len(text)})
    bundle=build_hierarchy_inputs(a,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    from audit_ai.model.semantic_final_evidence import visible_catalog
    assert text in visible_catalog(bundle['finalEvidenceProof'],catalog).by_id['C:S2'].exact_text
    args=dict(catalog=catalog,allowed_evidence_ids=set(bundle['visibleEvidenceIds']),
        allow_process_resolution=True,process_scope_hashes={a[0]['workScope']['scopeHash']},
        final_evidence_proof=bundle['finalEvidenceProof'])
    if valid:
        assert reconcile_observations(a,{'hints':[h],'resolutions':[row]},**args)[0]['disposition']=='SCOPED_COUNTEREVIDENCE'
    else:
        with pytest.raises(QualityFailure,match='SCOPE_REFINEMENT_UNPROVEN'):
            reconcile_observations(a,{'hints':[h],'resolutions':[row]},**args)


@pytest.mark.parametrize('notice',['经书面通知仍','经书面通知，仍','书面通知后，仍'])
def test_procedural_notice_condition_does_not_require_the_literal_character_after(notice):
    a,_,_,h,row=case('termination');condition='累计延误达到约定阈值且'+notice+'未改正'
    text=f'{condition}的，甲方可按约定程序发出解除通知；乙方责任不因此免除。\n'
    h['obligationScope']['trigger']=condition;start=len('当前片段说明。\n')
    catalog=EvidenceCatalog([
        EvidenceSegment('C:S1','frozen-contract','CONTRACT_BODY',0,start,'当前片段说明。\n',True),
        EvidenceSegment('C:S2','frozen-contract','CONTRACT_BODY',start,start+len(text),text,True)],
        source_lengths={'frozen-contract':start+len(text)})
    bundle=build_hierarchy_inputs(a,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    assert reconcile_observations(a,{'hints':[h],'resolutions':[row]},catalog=catalog,
        allowed_evidence_ids=set(bundle['visibleEvidenceIds']),allow_process_resolution=True,
        process_scope_hashes={a[0]['workScope']['scopeHash']},final_evidence_proof=bundle['finalEvidenceProof'])


def test_continuous_delay_and_written_demand_are_a_real_same_clause_condition():
    a,_,_,h,row=case('termination');condition='连续延期达到约定阈值且经书面催告仍未改正'
    text=f'{condition}的，甲方可按约定程序发出解除通知；乙方责任不因此免除。\n'
    h['obligationScope']['trigger']=condition;start=len('当前片段说明。\n')
    catalog=EvidenceCatalog([
        EvidenceSegment('C:S1','frozen-contract','CONTRACT_BODY',0,start,'当前片段说明。\n',True),
        EvidenceSegment('C:S2','frozen-contract','CONTRACT_BODY',start,start+len(text),text,True)],
        source_lengths={'frozen-contract':start+len(text)})
    bundle=build_hierarchy_inputs(a,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    assert reconcile_observations(a,{'hints':[h],'resolutions':[row]},catalog=catalog,
        allowed_evidence_ids=set(bundle['visibleEvidenceIds']),allow_process_resolution=True,
        process_scope_hashes={a[0]['workScope']['scopeHash']},final_evidence_proof=bundle['finalEvidenceProof'])


@pytest.mark.parametrize('defect_clause,mention,valid',[
    ('功能缺陷发生时，乙方应按约定修复并承担责任。',True,True),
    ('',True,False),
    ('功能缺陷发生时，甲方应按约定修复并承担责任。',True,False),
    ('功能缺陷发生时，乙方应按约定修复并承担责任。',False,False),
],ids=['both_facets','defect_unseen','wrong_defect_party','defect_silently_dropped'])
def test_mixed_delay_and_defect_observation_requires_both_source_and_target_facets(defect_clause,mention,valid):
    a,_,_,h,row=case('delay')
    a[0]['hints'][0]['description']='当前片段未见乙方交付延期或功能缺陷的责任安排。'
    h['description']='原文分别约定乙方交付延期和功能缺陷责任。' if mention else '原文已约定乙方交付延期责任。'
    start=len('当前片段说明。\n');text='乙方交付延期应承担违约责任。'+defect_clause+'\n'
    catalog=EvidenceCatalog([
        EvidenceSegment('C:S1','frozen-contract','CONTRACT_BODY',0,start,'当前片段说明。\n',True),
        EvidenceSegment('C:S2','frozen-contract','CONTRACT_BODY',start,start+len(text),text,True)],
        source_lengths={'frozen-contract':start+len(text)})
    bundle=build_hierarchy_inputs(a,catalog_rows(catalog),material_states=[],protocol=V2,frozen_catalog=catalog)
    args=dict(catalog=catalog,allowed_evidence_ids=set(bundle['visibleEvidenceIds']),allow_process_resolution=True,
        process_scope_hashes={a[0]['workScope']['scopeHash']},final_evidence_proof=bundle['finalEvidenceProof'])
    if valid:assert reconcile_observations(a,{'hints':[h],'resolutions':[row]},**args)
    else:
        with pytest.raises(QualityFailure,match='SCOPE_REFINEMENT_UNPROVEN'):
            reconcile_observations(a,{'hints':[h],'resolutions':[row]},**args)
