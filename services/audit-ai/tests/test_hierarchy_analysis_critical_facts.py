"""Cited ANALYSIS hints must not freeze facts later rejected when inherited.

Synthetic source and native analysis payloads only. The real service, typed
parser, evidence checks and observation collector remain unmodified.
"""
import copy
import re

import pytest

from audit_ai.config import Settings
from audit_ai.model.gateway import ModelCompletion
from audit_ai.model.router import ContractAnalysisRequest
from audit_ai.model.semantic_work import ContractSemanticWorkService


SOURCE = ("乙方应于2026年10月20日交付材料，并在三十日内提交。"
          "乙方应按合同约定的百分之五承担责任。")


class AnalysisProvider:
    def __init__(self, description, *, no_hints=False, summary="核对本段责任约定。", citation_index=0):
        self.description, self.no_hints, self.summary = description, no_hints, summary
        self.citation_index = citation_index
        self.calls, self.sent = [], []

    def analyze_contract(self, operation):
        assert operation.hierarchy_role is None, "fixture never dispatches GROUP/FINAL"
        self.calls.append(operation)
        evidence_id = re.findall(r"\[evidenceId=([^;]+);", operation.source_text)[self.citation_index]
        payload = {"summary": self.summary,
            "scopeStatus": "CHECKED_NO_RELEVANT_FINDINGS" if self.no_hints else "CHECKED_WITH_FINDINGS",
            "hints": [] if self.no_hints else [{
                "category": "BREACH_LIABILITY", "attention": "INFO", "title": "本段责任约定核对",
                "description": self.description, "problemEvidenceIds": [],
                "comparisonEvidenceIds": [evidence_id], "unavailableMaterialCategory": None,
                "claimKind": "CLAUSE_PRESENT", "obligationScope": {
                    "topic": "DELAY_LIABILITY", "responsibleParty": "PARTY_B", "trigger": "交付"}}]}
        self.sent.append(copy.deepcopy(payload))
        return ModelCompletion(payload=payload, model="unit-model", duration_ms=10, finish_reason="STOP")


def first_analysis(description, *, hierarchy=True, source_text=SOURCE, extra_text=None, **kwargs):
    provider = AnalysisProvider(description, **kwargs)
    settings = Settings(model_name="unit-model", model_chunk_chars=650, model_max_chunks=100,
        contract_semantic_money_mode="OBSERVE", contract_semantic_observe_project_ids=("hierarchy-project",))
    data = {"projectId": "hierarchy-project",
        "semanticMoneyMode": "OBSERVE", "semanticQualityProtocol": "contract-semantic-quality-v1",
        "materials": [{"fileId": "supplement", "category": "SUPPLEMENT", "text": source_text}]}
    if hierarchy:
        data["semanticHierarchyProtocol"] = "contract-bounded-hierarchy-v1"
    if extra_text is not None:
        data["materials"].append({"fileId": "elsewhere", "category": "SUPPLEMENT", "text": extra_text})
    request = ContractAnalysisRequest.model_validate(data)
    service = ContractSemanticWorkService(settings, provider)
    plan = service.plan(request)
    provider.plan = plan
    result = service.advance(request, plan["planFingerprint"], [],
        plan["next"]["operationFingerprint"], remaining_ms=800000, execute=True)
    packet = result["checkpoint"]
    assert len(provider.calls) == 1
    if hierarchy:
        assert packet["role"] == "ANALYSIS"
    else:
        assert "role" not in packet  # The old packet identity is unchanged.
    assert packet["completion"]["payload"] == provider.sent[0]
    assert result["result"] is None  # An analysis is never the public final result.
    return provider, result, packet


@pytest.mark.parametrize("description", [
    "乙方应于2026年11月20日交付材料。",
    "乙方应在三十一日内提交。",
    "乙方应按合同约定的百分之六承担责任。",
    "甲方应交付材料。",
], ids=["unsupported-date", "unsupported-duration", "unsupported-rate", "inverted-actor"])
def test_cited_unsupported_critical_fact_cannot_be_verified_before_group(description):
    _, result, packet = first_analysis(description)
    assert packet["status"] == "REPAIR_REQUIRED"
    assert packet["call"]["outcome"] == "FAILED"
    assert packet.get("validatedResult") is None and packet.get("correctedResult") is None
    assert [(issue["fieldPath"], issue["errorKind"], issue["category"])
            for issue in packet["validationIssues"]] == [
                ("hints[0].description", "CRITICAL_FACT_UNSUPPORTED", "BREACH_LIABILITY")]
    assert result["next"] is not None and result["next"]["purpose"] == "REPAIR"


@pytest.mark.parametrize("description", [
    "乙方应于2026-10-20交付材料。",
    "乙方应在30天内提交。",
    "乙方应按合同约定的5%承担责任。",
    "乙方应交付材料。",
    "核对本段责任约定。",
], ids=["equivalent-date", "equivalent-duration", "equivalent-rate", "same-actor", "no-critical-fact"])
def test_supported_cited_hint_still_verifies_without_rewriting(description):
    _, _, packet = first_analysis(description)
    assert packet["status"] == "VERIFIED" and packet["call"]["outcome"] == "SUCCEEDED"
    assert packet["validatedResult"]["hints"][0]["description"] == description
    assert packet["monetaryObservation"]["mode"] == "OBSERVE"


def test_summary_without_hint_refs_is_not_blindly_checked_against_empty_evidence():
    _, _, packet = first_analysis("", no_hints=True,
        summary="本段约定乙方于2026年10月20日交付材料。")
    assert packet["status"] == "VERIFIED" and packet["validatedResult"]["hints"] == []


def test_analysis_amount_remains_observed_not_rejected_or_rewritten():
    description = "合同金额100元，需人工复核。"
    _, _, packet = first_analysis(description)
    assert packet["status"] == "VERIFIED" and packet["call"]["outcome"] == "SUCCEEDED"
    assert packet["validatedResult"]["hints"][0]["description"] == description
    observation = packet["monetaryObservation"]
    assert observation["mode"] == "OBSERVE"
    assert [(warning["fieldPath"], warning["errorKind"])
            for warning in observation["warnings"]] == [("hints[0].description", "MONETARY_VALUE")]


@pytest.mark.parametrize("citation_index,expected_status", [(0, "REPAIR_REQUIRED"), (1, "VERIFIED")],
                         ids=["20percent-elsewhere-does-not-support-wrong-citation", "true-20percent-citation"])
def test_rate_support_is_bound_to_the_cited_fragment_not_whole_catalog(citation_index, expected_status):
    description = "本段约定违约责任比例为20%。"
    provider, _, packet = first_analysis(description,
        source_text="本段约定违约责任比例为2%或10%。",
        extra_text="另一条约定违约责任比例为20%。", citation_index=citation_index)
    operation = provider.calls[0]
    # Both facts are visible in this actual ANALYZE operation and its catalog;
    # the difference is the one preserved citation, never absent global text.
    assert "2%或10%" in operation.source_text and "20%" in operation.source_text
    ids = re.findall(r"\[evidenceId=([^;]+);", operation.source_text)
    assert len(ids) == 2
    raw = packet["completion"]["payload"]["hints"][0]
    assert raw["comparisonEvidenceIds"] == [ids[citation_index]] and raw["problemEvidenceIds"] == []
    assert raw["description"] == description and packet["status"] == expected_status
    if expected_status == "REPAIR_REQUIRED":
        assert packet.get("validatedResult") is None
        assert packet["validationIssues"][0]["errorKind"] == "CRITICAL_FACT_UNSUPPORTED"
    else:
        assert packet["validatedResult"]["hints"][0]["description"] == description


@pytest.mark.parametrize("description", [
    "乙方应于2026年11月20日交付材料。", "乙方应在三十一日内提交。",
    "乙方应按合同约定的百分之六承担责任。", "甲方应交付材料。",
], ids=["legacy-date", "legacy-duration", "legacy-rate", "legacy-actor"])
def test_absent_hierarchy_keeps_old_single_synthesis_analysis_behavior(description):
    provider, _, packet = first_analysis(description, hierarchy=False)
    assert packet["status"] == "VERIFIED" and packet["call"]["outcome"] == "SUCCEEDED"
    assert packet["validatedResult"]["hints"][0]["description"] == description
    assert "hierarchy" not in provider.plan
    assert "hierarchyAnalysisCriticalFacts" not in provider.plan["policy"]
    assert sum(unit["purpose"] == "SYNTHESIZE" for unit in provider.plan["units"]) == 1


def test_new_analysis_gate_has_explicit_frozen_policy_identity():
    provider, _, packet = first_analysis("核对本段责任约定。")
    assert packet["status"] == "VERIFIED"
    assert provider.plan["policy"]["hierarchyAnalysisCriticalFacts"] == "contract-cited-analysis-critical-facts-v1"
