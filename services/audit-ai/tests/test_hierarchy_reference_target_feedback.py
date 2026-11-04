"""Native GROUP reference errors retain actionable, non-secret repair facts.

All sources are synthetic. The service, inverse, context codec and actual
gateway request builder are real; MockTransport only prevents network I/O.
"""
import copy
import hashlib
import json

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model.contract_draft import ProviderValidationIssue
from audit_ai.model.gateway import ModelCompletion
from audit_ai.model.semantic_hierarchy_provider import decode_provider
from audit_ai.model.semantic_quality import build_regeneration_context, decode_regeneration_context
from audit_ai.model.semantic_work import ContractSemanticWorkService
from test_semantic_hierarchy_execution import Provider, decode_claim, setup
from test_semantic_hierarchy_provider import group_wire, hint
from test_semantic_regeneration_context_wire import capture, context_on_wire


PROTOCOL = "contract-hierarchy-provider-references-v1"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


class NativeGroupProvider(Provider):
    def __init__(self, *, action="EXPLAIN", invalid=True, new_target=False):
        super().__init__()
        self.action, self.invalid, self.new_target = action, invalid, new_target
        self.group_wires = []

    def analyze_contract(self, operation):
        if operation.hierarchy_role is None:
            completion = super().analyze_contract(operation)
            original = completion.payload["hints"][0]
            completion.payload["hints"] = [dict(copy.deepcopy(original), title=f"本段期限核对项{i + 1}")
                                          for i in range(17)]
            return completion
        assert operation.hierarchy_role == "GROUP", "fixture never requests final publication"
        self.calls.append(operation)
        source = json.loads(operation.source_text)
        originals = [(leaf[0], decode_claim(leaf[1:], source))
                     for analysis in source["analyses"] for leaf in analysis["leaves"]]
        assert len(originals) >= 17
        wire = {"protocol": PROTOCOL, "summary": "保留本组全部来源观察。", "newHints": [],
                "lineage": [[alias, "KEEP", alias, None, None] for alias, _ in originals]}
        if self.invalid:
            # Match the observed structural defect without any real material:
            # rows 9/10/11 explain but target the inherited alias, with N empty.
            for index in (9, 10, 11):
                wire["lineage"][index][1] = self.action
        elif self.new_target:
            wire["newHints"] = [copy.deepcopy(originals[9][1])]
            indexes = {row[1]: row[0] for row in source["evidence"]}
            basis = originals[9][1]["problemEvidenceIds"] + originals[9][1]["comparisonEvidenceIds"]
            wire["lineage"][9][2:] = ["N0", list(dict.fromkeys(indexes[eid] for eid in basis)), "保留同一义务与来源。"]
        self.group_wires.append(copy.deepcopy(wire))
        return ModelCompletion(payload=wire, model="unit-model", duration_ms=10, finish_reason="STOP")


def through_group(provider):
    _, _, settings, request = setup(repeat=10)
    service = ContractSemanticWorkService(settings, provider)
    plan = service.plan(request)
    assert plan["callLimit"] <= 22 and plan["policy"]["maxOutputTokens"] == 6144
    assert plan["policy"]["automaticTransportRetries"] == 0 and plan["policy"]["budgetSeconds"] == 900
    packets, expected = [], plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        count = len(provider.calls)
        result = service.advance(request, plan["planFingerprint"], packets, expected,
                                 remaining_ms=800000, execute=True)
        assert len(provider.calls) == count + 1
        packet = result["checkpoint"]
        assert packet is not None and result["result"] is None
        packets.append(packet)
        if result["next"] is None or (packet["role"] == "GROUP" and packet["status"] == "VERIFIED"):
            return plan, packets, result
        expected = result["next"]["operationFingerprint"]
    pytest.fail("bounded GROUP fixture did not terminate")


@pytest.mark.parametrize("action", ["EXPLAIN", "MERGE"])
def test_self_target_failure_reports_first_exact_target_and_preserves_repeated_raw_candidate(action):
    provider = NativeGroupProvider(action=action)
    plan, packets, result = through_group(provider)
    groups = [packet for packet in packets if packet["role"] == "GROUP"]
    assert [packet["status"] for packet in groups] == ["REPAIR_REQUIRED", "FAILED"]
    assert result["result"] is None and result["next"] is None
    assert sum(packet["purpose"] == "REPAIR" for packet in packets) == 1
    assert len(provider.calls) <= plan["callLimit"]
    assert len(provider.group_wires) == 2 and provider.group_wires[0] == provider.group_wires[1]
    for packet, raw in zip(groups, provider.group_wires, strict=True):
        assert packet["completion"]["payload"] == raw and len(raw["lineage"]) == 17
        assert raw["newHints"] == [] and packet.get("validatedResult") is None
        assert packet["call"]["outcome"] == "FAILED" and packet["call"]["safeReason"] == "INVALID_TYPED_SCHEMA"
        issues = packet["validationIssues"]
        assert issues[0]["fieldPath"] == "lineage[9].target"
        assert issues[0]["itemIndex"] is None  # Not the ninth output hint.
        assert issues[0]["errorKind"] == "SEMANTIC_HIERARCHY_PROVIDER_INVALID"
        assert issues[0]["actualClass"] == "NEW_HINT_TARGET_REQUIRED"
        assert issues[0]["allowedValues"] == []
        assert "保留本组" not in canonical(issues) and "A1:H10" not in canonical(issues)


def test_full_real_gateway_repair_request_retains_candidate_hash_and_exact_safe_target_feedback():
    provider = NativeGroupProvider()
    through_group(provider)
    repair = next(operation for operation in provider.calls
                  if operation.hierarchy_role == "GROUP" and operation.attempt == 2)
    body = json.loads(capture(repair).body)
    assert body["max_tokens"] == 6144 and body["reasoning_effort"] == "none"
    assert body["temperature"] == 0 and body["response_format"] == {"type": "json_object"}
    context = decode_regeneration_context(context_on_wire(canonical(body).encode("utf-8")))
    original = provider.group_wires[0]
    assert context["candidate"] == original
    assert context["rejectedCandidateSha256"] == hashlib.sha256(canonical(original).encode("utf-8")).hexdigest()
    assert context["issues"][0]["fieldPath"] == "lineage[9].target"
    assert context["issues"][0]["itemIndex"] is None
    assert context["issues"][0]["actualClass"] == "NEW_HINT_TARGET_REQUIRED"
    assert "NEW_HINT_TARGET_REQUIRED" in body["messages"][1]["content"]
    assert len(repair.regeneration_context_json.encode("utf-8")) <= 8192
    assert body["messages"][1]["content"].endswith("source:\n" + repair.source_text)


@pytest.mark.parametrize("defect", ["MERGE-self", "EXPLAIN-self", "KEEP-cross-alias",
                                   "unknown-target", "bool-basis", "duplicate-alias"])
def test_native_inverse_keeps_existing_422_protocol_gate_for_core_malformed_rows(defect):
    source = {"A1:H1": hint(), "A1:H2": hint(1)}
    raw = group_wire(source)
    if defect == "MERGE-self": raw["lineage"][0][1] = "MERGE"
    elif defect == "EXPLAIN-self": raw["lineage"][0][1] = "EXPLAIN"
    elif defect == "KEEP-cross-alias": raw["lineage"][0][2] = "A1:H2"
    elif defect == "unknown-target": raw["lineage"][0][2] = "PRIVATE_TARGET_SENTINEL"
    elif defect == "bool-basis": raw["lineage"][0][3] = [True]
    else: raw["lineage"][1][0] = "A1:H1"
    before = copy.deepcopy(raw)
    with pytest.raises(AuditAiError) as rejected:
        decode_provider(raw, role="GROUP", source_hints=source, evidence_ids=["e0", "e1"])
    assert rejected.value.status_code == 422
    assert rejected.value.code == "SEMANTIC_HIERARCHY_PROVIDER_INVALID"
    assert raw == before
    assert "PRIVATE_TARGET_SENTINEL" not in str(rejected.value) + rejected.value.public_message + str(rejected.value.data)


@pytest.mark.parametrize("new_target", [False, True], ids=["native-good-KEEP", "native-good-new-N"])
def test_valid_keep_or_new_n_target_still_reaches_verified_group(new_target):
    _, packets, result = through_group(NativeGroupProvider(invalid=False, new_target=new_target))
    assert packets[-1]["role"] == "GROUP" and packets[-1]["status"] == "VERIFIED"
    assert all(packet["status"] == "VERIFIED" and packet["purpose"] != "REPAIR" for packet in packets)
    assert result["result"] is None


def test_old_generic_context_remains_readable_without_fabricating_historical_row_coordinates():
    raw = group_wire({"A1:H1": hint()})
    raw["lineage"][0][1] = "EXPLAIN"
    issue = ProviderValidationIssue("lineage", None, "SEMANTIC_HIERARCHY_PROVIDER_INVALID", "INVALID_SCOPED_CLAIM")
    original = build_regeneration_context(raw, (issue,), "b" * 64, hierarchy=True)
    decoded = decode_regeneration_context(original)
    assert decoded["candidate"] == raw and decoded["issues"] == [issue.as_dict()]
    assert decoded["focusedOldCoordinates"] == [{"domain": "FIELD", "fieldPath": "lineage", "index": None}]
    assert "NEW_HINT_TARGET_REQUIRED" not in original and "lineage[0].target" not in original
