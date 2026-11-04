"""Real service/compiler/normalizer, in-memory transport only."""
from audit_ai.model.semantic_work import ContractSemanticWorkService
from test_semantic_hierarchy_execution import setup, drive, Provider


def test_reference_wire_is_journalled_raw_but_full_validated_result_is_rederived():
    service, provider, _, request = setup()
    plan, packets, last = drive(service, provider, request)
    assert last['result'] is not None
    for packet in packets:
        if packet['role'] in {'GROUP', 'FINAL'}:
            raw = packet['completion']['payload']
            assert raw['protocol'] == 'contract-hierarchy-provider-references-v1'
            assert raw['newHints'] == []
            assert 'hints' not in raw
            assert packet['quality']['hintMetadata']
    assert plan['policy']['hierarchyProviderDraftProtocol'] == 'contract-hierarchy-provider-references-v1'
    _, _, settings, _ = setup()
    restarted_provider = Provider()
    restarted = ContractSemanticWorkService(settings, restarted_provider)
    reopened = restarted.advance(request, plan['planFingerprint'], packets, None, remaining_ms=600000, execute=False)
    assert reopened['result'] == last['result'] and restarted_provider.calls == []


def test_complete_reference_repair_does_not_repeat_immutable_feedback_or_group_instructions():
    import json
    from dataclasses import replace
    from audit_ai.model.gateway import contract_hierarchy_wire
    from audit_ai.model.contract_draft import ProviderValidationIssue
    from audit_ai.model.semantic_quality import build_regeneration_context
    service, provider, _, request = setup()
    drive(service, provider, request)
    operation = next(op for op in provider.calls if op.hierarchy_role == 'FINAL')
    issue = ProviderValidationIssue('summary', None, 'EVIDENCE_SUPPORT_SCOPE', 'STRING')
    candidate = {'protocol': 'contract-hierarchy-provider-references-v1', 'summary': '合成意见',
                 'newHints': [], 'resolutions': []}
    context = build_regeneration_context(candidate, (issue,), 'a' * 64, hierarchy=True)
    wire = contract_hierarchy_wire(replace(operation, attempt=2, validation_feedback=(issue,),
        regeneration_context_json=context))
    assert wire['user_content'].count(issue.as_dict()['errorId']) == 1
    assert issue.as_dict()['errorId'] not in wire['system_prompt']
    assert 'ROWS_' not in wire['system_prompt']
    assert 'regenerationContext.issues' in wire['system_prompt']
    assert 'QUALITY_REPAIR_GUIDANCE=' in wire['system_prompt']
    assert json.loads(context)['candidate'] == candidate
    schema = json.loads(wire['system_prompt'].split('AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=')[1].split('\n')[0])
    assert 'title' not in schema['$defs']['QualityHint']
    assert 'title' in schema['$defs']['QualityHint']['properties']
    assert schema['$defs']['QualityHint']['properties']['title']['maxLength'] == 200
