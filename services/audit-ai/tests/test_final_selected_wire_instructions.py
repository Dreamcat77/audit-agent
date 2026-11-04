import json
from dataclasses import replace

from audit_ai.model.gateway import ContractTextOperation, contract_hierarchy_wire
from audit_ai.model.semantic_hierarchy import final_source, group_digest
from test_final_frozen_evidence import V2, build
from test_semantic_hierarchy import candidate


def test_v2_selected_wire_has_one_unambiguous_authority_and_no_legacy_row_index_claim():
    bundle=build()
    source=final_source(bundle, [group_digest(candidate(bundle,g),g,bundle) for g in bundle['groups']])
    operation=ContractTextOperation(project_id='synthetic',chunk_index=3,chunk_count=3,
        purpose='SYNTHESIZE',source_text=source,generation_policy='contract-semantic-work-v3',
        quality_protocol='contract-semantic-quality-v1',hierarchy_protocol=V2,hierarchy_role='FINAL',
        hierarchy_logical_key='b'*64,grouping_fingerprint=bundle['groupingFingerprint'])
    system=contract_hierarchy_wire(operation)['system_prompt']
    assert 'FINAL_RELEVANT_EVIDENCE_V2' in system
    assert 'FINAL evidence行号为全局索引' not in system
    assert system.count('AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=') == 1
    assert 'evidenceRegistry' in system and 'search miss' in system
    assert 'UNSPECIFIED' in system and '6144' not in system  # wire, not a prompt budget override


def test_native_final_wire_names_ineligible_partial_alias_and_schema_cannot_offer_it():
    from test_final_frozen_evidence_execution import run_private_process
    _,provider,_,_,_,_,last=run_private_process(partial_inherited=True,context=True)
    assert last['result'] is None  # malformed output remains rejected, never relaxed
    operation=next(op for op in provider.calls if op.hierarchy_role=='FINAL')
    source=json.loads(operation.source_text)
    eligibility=source['inheritanceEligibility']
    assert eligibility['protocol']=='contract-final-inheritance-eligibility-v1'
    assert 'G1:H1' not in eligibility['directTargetRefs']
    system=contract_hierarchy_wire(operation)['system_prompt']
    schema=json.loads(system.split('AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=',1)[1].splitlines()[0])
    alternatives=schema['properties']['resolutions']['items']['prefixItems'][3]['items']['anyOf']
    assert not any('G1:H1' in option.get('enum',[]) for option in alternatives)
    assert any(option.get('pattern')=='^N(0|[1-9][0-9]*)$' for option in alternatives)
    # Every original remains an observation requiring a disposition, even when
    # it cannot be directly inherited as an unchanged public target.
    assert sum(len(group['claims']) for group in source['groups'])>0


def test_native_final_eligible_aliases_are_explicit_but_local_observations_are_not_publishable():
    from test_final_frozen_evidence_execution import run_private_process
    _,provider,_,_,_,_,last=run_private_process(context=True)
    assert last['result'] is not None
    operation=next(op for op in provider.calls if op.hierarchy_role=='FINAL')
    source=json.loads(operation.source_text)
    refs=source['inheritanceEligibility']['directTargetRefs']
    assert 'G1:H1' not in refs and 'G1:H2' in refs


def test_forged_inheritance_allowlist_is_refused_by_actual_request_builder():
    import pytest
    from test_final_frozen_evidence_execution import run_private_process
    _,provider,_,_,_,_,_=run_private_process(partial_inherited=True,context=True)
    operation=next(op for op in provider.calls if op.hierarchy_role=='FINAL')
    source=json.loads(operation.source_text)
    source['inheritanceEligibility']['directTargetRefs'].append('G1:H1')
    with pytest.raises(ValueError,match='inheritance eligibility'):
        contract_hierarchy_wire(replace(operation,source_text=json.dumps(source,ensure_ascii=False)))


def test_primary_and_repair_actual_gateway_close_new_hint_and_basis_reference_domains():
    from test_final_frozen_evidence_execution import run_private_process
    from test_semantic_regeneration_context_wire import capture
    from audit_ai.model.contract_draft import ProviderValidationIssue
    from audit_ai.model.semantic_quality import build_regeneration_context
    _,provider,_,_,_,packets,_=run_private_process(partial_inherited=True,context=True)
    operation=next(op for op in provider.calls if op.hierarchy_role=='FINAL')
    source=json.loads(operation.source_text)
    assert [row[0] for row in source['evidence']]==['S:S1']
    assert source['fullyVisibleEvidenceIds']==[]  # one actual subrange, not the complete segment
    issue=ProviderValidationIssue('inheritedHints',None,'SEMANTIC_INHERITED_EVIDENCE_NOT_VISIBLE',
        'SOURCE_G1_H1_NEW_VISIBLE_TARGET_REQUIRED')
    context=build_regeneration_context(packets[-1]['completion']['payload'],(issue,),'a'*64,hierarchy=True)
    repair=replace(operation,attempt=2,validation_feedback=(issue,),regeneration_context_json=context)
    for op in (operation,repair):
        body=json.loads(capture(op).body)
        schema=json.loads(body['messages'][0]['content'].split('AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=',1)[1].splitlines()[0])
        hint=schema['$defs']['QualityHint']['properties']
        for field in ('problemEvidenceIds','comparisonEvidenceIds'):
            assert hint[field]['items']=={'$ref':'#/$defs/FinalVisibleEvidenceId'}
            assert schema['$defs']['FinalVisibleEvidenceId']=={'type':'string','enum':['S:S1']}
        basis=schema['properties']['resolutions']['items']['prefixItems'][4]['anyOf'][1]
        assert basis['items']=={'type':'integer','enum':[0]}
        assert body['max_tokens']==6144 and body['reasoning_effort']=='none'
