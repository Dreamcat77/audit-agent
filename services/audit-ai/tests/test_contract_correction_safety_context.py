"""A filtered required category is not an unqualified omission; repair needs its safe cause."""
import json

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.model.gateway import ContractTextOperation, OpenAiCompatibleModelGateway
from test_contract_correction import (
    SequencedCorrectionGateway,
    contract_body_info_hints,
    post_contract,
)


def test_filtered_breach_reason_survives_into_targeted_correction_without_raw_output():
    rejected = {
        "category": "BREACH_LIABILITY", "attention": "REVIEW",
        "title": "违约责任", "description": "合同金额高于成交金额，金额不一致。",
        "problemEvidenceIds": ["C:S1"], "comparisonEvidenceIds": [],
    }
    correction = {
        "category": "BREACH_LIABILITY", "attention": "REVIEW",
        "title": "违约责任需复核", "description": "请结合来源核对违约触发条件和比例。",
        "problemEvidenceIds": ["C:S1"], "comparisonEvidenceIds": [],
    }
    gateway = SequencedCorrectionGateway([
        {"summary": "核对可见条款。", "hints": [*contract_body_info_hints(), rejected]},
        {"missingCategoryHints": [correction]},
    ])
    response = post_contract(gateway, "违约责任：按合同金额的2%/周计取违约金，累计上限10%。")
    assert response.status_code == 200
    assert len(gateway.operations) == 2
    source = json.loads(gateway.operations[1].source_text)
    assert source["correctionPlan"].get("rejectedHintReasons") == [
        {"providerOrdinal": 8, "category": "BREACH_LIABILITY", "reason": "MONETARY_CALCULATION"}
    ]
    assert rejected["description"] not in gateway.operations[1].source_text
    assert source["correctionPlan"].get("validationPolicy") == {
        "absoluteMonetaryValues": "FORBIDDEN", "monetaryArithmetic": "FORBIDDEN",
        "strongVerdicts": "FORBIDDEN", "evidenceIdentity": "SUPPLIED_CITABLE_ONLY",
        "allowedQuantifiedSemantics": ["PERCENTAGE_RATE", "DATE", "DURATION", "FREQUENCY", "NON_MONETARY_THRESHOLD"],
    }


@pytest.mark.parametrize("purpose", ["EVIDENCE_REPAIR", "SEMANTIC_COMPLETENESS_CORRECTION", "COMBINED_CORRECTION", "SYNTHESIZE"])
def test_every_delta_and_synthesis_transports_the_same_safety_boundary(purpose):
    observed = []
    def respond(request):
        observed.append(json.loads(request.content))
        return httpx.Response(200, json={"model": "unit-model", "choices": [{"finish_reason": "stop", "message": {"content": "{}"}}]})
    gateway = OpenAiCompatibleModelGateway(Settings(model_base_url="https://unit.invalid/v1", model_api_key="unit-key", model_name="unit-model"), transport=httpx.MockTransport(respond))
    gateway.analyze_contract(ContractTextOperation(project_id="unit-project", chunk_index=1, chunk_count=1, source_text="bounded-unit-source", purpose=purpose))
    prompt = observed[0]["messages"][0]["content"]
    # Production HTTP boundary: every standalone request must carry these rules, not refer to a prior request.
    assert "SEMANTIC_SAFETY_POLICY_V1" in prompt
    assert "absoluteMonetaryValues=FORBIDDEN" in prompt
    assert "monetaryArithmetic=FORBIDDEN" in prompt
    assert "strongVerdicts=FORBIDDEN" in prompt
    assert "percentages, dates, durations and frequencies remain allowed" in prompt


def test_unsafe_breach_correction_is_still_failed_not_rewritten_as_empty_success():
    gateway = SequencedCorrectionGateway([
        {"summary": "核对可见条款。", "hints": contract_body_info_hints()},
        {"missingCategoryHints": [{"category": "BREACH_LIABILITY", "attention": "REVIEW", "title": "违约责任", "description": "违约金额等于67000元。", "problemEvidenceIds": ["C:S1"]}]},
    ])
    response = post_contract(gateway, "违约责任：按合同金额的2%/周计取违约金。")
    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert len(gateway.operations) == 2
