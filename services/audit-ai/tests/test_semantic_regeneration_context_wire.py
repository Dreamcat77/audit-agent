"""Offline wire regressions: lossless bounded repair data must stay bounded on wire.

The real gateway builds httpx request bytes; MockTransport only intercepts them
and raises Captured before a response or any network access.  No codec, schema,
phase, operation or request builder is replaced.
"""
import copy
import hashlib
import json

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.model import semantic_quality as quality
from audit_ai.model.contract_draft import ProviderValidationIssue
from audit_ai.model.gateway import ContractTextOperation, OpenAiCompatibleModelGateway


CONTEXT_V2 = "contract-semantic-regeneration-context-v2"
COLUMNS_V1 = "contract-regeneration-candidate-columns-v1"
OLD_FP = "b" * 64
SOURCE = json.dumps({
    "partialResults": [{"summary": "原分析观察保留。", "hints": []}],
    "evidenceCatalog": [
        {"evidenceId": "evidence-body-1", "fileId": "file-body", "materialCategory": "CONTRACT_BODY",
         "citable": True, "sourceStart": 0, "sourceEnd": 12, "exactTextSha256": "a" * 64},
        {"evidenceId": "evidence-supplement-1", "fileId": "file-supplement", "materialCategory": "SUPPLEMENT",
         "citable": True, "sourceStart": 0, "sourceEnd": 12, "exactTextSha256": "c" * 64},
    ], "omittedHintCount": 0,
}, ensure_ascii=False, separators=(",", ":"))
ANALYSIS_SOURCE = "[evidenceId=evidence-body-1;fileId=file-body;category=CONTRACT_BODY;citable=true]\n原始条款和证据身份不变。"
# Frozen from the unchanged formal 00F gateway using this exact public fixture.
# These are wire baselines, not runtime attestation or claims about a real model.
BASELINE_BODY_SHA = {
    "ANALYZE": "14363d977176d88069079ebbe2b724210e4c9b60a3b7268d86ff39c745f65645",
    "SYNTHESIZE": "27f53b76123ce16f2477eb53a5daa11266cf4c9a5897db86a6b6897e5bd24a52",
    "V1_REPAIR": "de87a41c142f2d0bc2c5fd659e2dfadfb7787d91a9355849f96eb24cd61befb0",
}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def issue(path, index):
    return ProviderValidationIssue(path, index, "TYPE_OR_BOUND", "INVALID_TYPED_QUALITY",
                                   schema_version="contract-semantic-quality-v1")


def hint():
    return {"category": "OTHER_TEXT", "attention": "REVIEW", "title": "条款安排待核实",
            "description": "保留来源、证据与有效关注，结合原件核实相应安排。",
            "problemEvidenceIds": ["evidence-body-1"],
            "comparisonEvidenceIds": ["evidence-supplement-1"],
            "unavailableMaterialCategory": None, "claimKind": "AMBIGUOUS_CLAUSE",
            "obligationScope": {"topic": "OTHER", "responsibleParty": "PARTY_B", "trigger": None}}


def dense_candidate():
    candidate = {"summary": "保留完整候选、全部观察和证据。", "hints": [hint() for _ in range(8)],
                 "resolutions": [{"observation": [i // 5, i % 5], "disposition": "RETAINED",
                    "finalHintIndexes": [i % 8], "basisEvidenceIds": ["evidence-body-1"],
                    "reason": "同一范围原观察及有效关注保留。"} for i in range(39)]}
    candidate["resolutions"][4]["finalHintIndexes"] = "bad-target-index"
    candidate["resolutions"][38]["finalHintIndexes"] = [None]
    return candidate


def dense_context():
    candidate = dense_candidate()
    issues = (issue("resolutions[4].finalHintIndexes", 4), issue("resolutions[38].finalHintIndexes", 38))
    return candidate, issues, quality.build_regeneration_context(candidate, issues, OLD_FP)


def legacy_context():
    candidate = {"summary": "原小候选。", "hints": [hint()]}
    feedback = issue("hints[0].description", 0)
    value = {"protocol": "contract-semantic-regeneration-context-v1", "rejectedOperationFingerprint": OLD_FP,
             "rejectedCandidateSha256": hashlib.sha256(canonical(candidate).encode()).hexdigest(),
             "candidate": candidate, "focusedOldIndexes": [0], "issues": [feedback.as_dict()]}
    return canonical(value), (feedback,)


def operation(purpose="SYNTHESIZE", *, context=None, feedback=()):
    return ContractTextOperation(
        project_id="offline-wire-project", chunk_index=1, chunk_count=8,
        source_text=SOURCE if purpose == "SYNTHESIZE" else ANALYSIS_SOURCE,
        purpose=purpose, request_id="offline-wire-request",
        attempt=2 if context is not None else 1, max_attempts=2,
        transport_attempt=1, max_transport_attempts=1, provider_timeout_seconds=300,
        generation_policy="contract-semantic-work-v3", quality_protocol="contract-semantic-quality-v1",
        required_categories=("OTHER_TEXT",), validation_feedback=feedback,
        globally_available_material_categories=("CONTRACT_BODY", "SUPPLEMENT"),
        current_operation_material_categories=("CONTRACT_BODY", "SUPPLEMENT"),
        regeneration_context_json=context,
    )


class Captured(Exception):
    def __init__(self, request):
        self.body = bytes(request.content)
        self.method = request.method
        self.url = str(request.url)
        self.request_id = request.headers["X-Request-Id"]


def capture(op):
    def intercept(request):
        raise Captured(request)
    gateway = OpenAiCompatibleModelGateway(
        Settings(model_name="offline-wire-model", model_api_key="synthetic-test-placeholder",
                 model_base_url="https://provider.invalid/v1", semantic_structured_transport="PROMPT_ONLY"),
        transport=httpx.MockTransport(intercept),
    )
    with pytest.raises(Captured) as caught:
        gateway.analyze_contract(op)
    return caught.value


def context_on_wire(body):
    user = json.loads(body)["messages"][1]["content"]
    return user.split("regenerationContext=", 1)[1].split("\nsource:\n", 1)[0]


def test_v2_complete_candidate_and_resolution_coordinates_are_not_hint_indexes():
    candidate, issues, context = dense_context()
    decoded = quality.decode_regeneration_context(context)
    assert len(canonical(decoded).encode("utf-8")) > 8192
    assert len(context.encode("utf-8")) <= 8192
    assert json.loads(context)["protocol"] == CONTEXT_V2
    assert json.loads(context)["candidateEncoding"] == COLUMNS_V1
    assert decoded["candidate"] == candidate
    assert len(decoded["candidate"]["hints"]) == 8
    assert len(decoded["candidate"]["resolutions"]) == 39
    assert decoded["candidate"]["resolutions"][4]["finalHintIndexes"] == "bad-target-index"
    assert decoded["candidate"]["resolutions"][38]["finalHintIndexes"] == [None]
    assert decoded["focusedOldIndexes"] == []
    assert decoded["focusedOldCoordinates"] == [
        {"domain": "RESOLUTION", "fieldPath": "resolutions[4].finalHintIndexes", "index": 4},
        {"domain": "RESOLUTION", "fieldPath": "resolutions[38].finalHintIndexes", "index": 38},
    ]
    assert decoded["issues"] == [entry.as_dict() for entry in issues]
    assert decoded["rejectedOperationFingerprint"] == OLD_FP
    assert decoded["rejectedCandidateSha256"] == hashlib.sha256(canonical(candidate).encode()).hexdigest()
    assert quality.provider_regeneration_context_json(context) == context


def test_real_gateway_does_not_reinflate_bounded_context_on_wire():
    candidate, feedback, context = dense_context()
    before = copy.deepcopy(candidate)
    captured = capture(operation(context=context, feedback=feedback))
    sent = context_on_wire(captured.body)
    assert len(sent.encode("utf-8")) <= 8192, "gateway expanded the bounded encoded context before dispatch"
    assert sent == context, "validated encoded context must be sent byte-for-byte, not dump(decoded context)"
    assert quality.decode_regeneration_context(sent)["candidate"] == before
    assert candidate == before


def test_v2_actual_request_preserves_source_identity_schema_model_and_generation_limits():
    _, feedback, context = dense_context()
    captured = capture(operation(context=context, feedback=feedback))
    wire = json.loads(captured.body)
    assert captured.method == "POST"
    assert captured.url == "https://provider.invalid/v1/chat/completions"
    assert captured.request_id == "offline-wire-request"
    assert wire["model"] == "offline-wire-model"
    assert wire["max_tokens"] == 6144
    assert wire["response_format"] == {"type": "json_object"}
    assert wire["temperature"] == 0
    assert wire["reasoning_effort"] == "none"
    assert "stream" not in wire  # Existing OpenAI-compatible wire omits this field.
    assert set(wire) == {"model", "messages", "temperature", "response_format", "max_tokens", "reasoning_effort"}
    user = wire["messages"][1]["content"]
    assert user.split("source:\n", 1)[1] == SOURCE
    assert "purpose=SYNTHESIZE\nprojectId=offline-wire-project\nchunk=1/8\n" in user
    assert json.loads(user.split("frozenMaterialScope=", 1)[1].split("\n", 1)[0]) == {
        "version": "contract-material-scope-v1", "globallyAvailableCategories": ["CONTRACT_BODY", "SUPPLEMENT"],
        "currentOperationCategories": ["CONTRACT_BODY", "SUPPLEMENT"], "absenceScope": "GLOBAL_FROZEN_MATERIAL_SET"}
    system = wire["messages"][0]["content"]
    assert CONTEXT_V2 in system
    assert COLUMNS_V1 in system
    assert "focusedOldCoordinates" in system and "RESOLUTION" in system
    assert "AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=" in system
    initial_system = json.loads(capture(operation()).body)["messages"][0]["content"]
    schema_marker = "AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA="
    assert json.JSONDecoder().raw_decode(system.split(schema_marker, 1)[1])[0] == (
        json.JSONDecoder().raw_decode(initial_system.split(schema_marker, 1)[1])[0])
    assert "untrusted prior provider data" in system
    assert "bad-target-index" not in system


def test_handmade_v1_small_context_keeps_legacy_wire_bytes():
    context, feedback = legacy_context()
    assert quality.decode_regeneration_context(context)["protocol"].endswith("-v1")
    assert quality.provider_regeneration_context_json(context) == context
    captured = capture(operation(context=context, feedback=feedback))
    assert context_on_wire(captured.body) == context
    assert hashlib.sha256(captured.body).hexdigest() == BASELINE_BODY_SHA["V1_REPAIR"]
    system = json.loads(captured.body)["messages"][0]["content"]
    assert CONTEXT_V2 not in system and COLUMNS_V1 not in system


@pytest.mark.parametrize("purpose", ["ANALYZE", "SYNTHESIZE"])
def test_initial_analysis_and_synthesis_have_no_context_or_v2_only_instructions(purpose):
    captured = capture(operation(purpose))
    assert hashlib.sha256(captured.body).hexdigest() == BASELINE_BODY_SHA[purpose]
    wire = json.loads(captured.body)
    assert "regenerationContext=" not in wire["messages"][1]["content"]
    assert CONTEXT_V2 not in wire["messages"][0]["content"]
    assert COLUMNS_V1 not in wire["messages"][0]["content"]
