"""Native reference-wire observation regressions; no encoder, HTTP or model.

Only the existing strict service accepts/rejects candidates. ``decode_claim``
reads the service-owned positional input fixture; it is not an output parser.
"""
import copy
import hashlib
import json
from dataclasses import replace

import pytest

from audit_ai.model.gateway import ModelCompletion
from audit_ai.model.semantic_work import ContractSemanticWorkService
from test_semantic_hierarchy_execution import Provider, decode_claim, setup


PROTOCOL = "contract-hierarchy-provider-references-v1"
MONEY = "合同金额100元，需人工复核。"
ORDINARY = "仅核对本段可见交付约定。"


def test_untrusted_reference_tag_cannot_hide_ordinary_analysis_money_warnings():
    class TaggedAnalysis(NativeReferenceProvider):
        def analyze_contract(self, operation):
            completion = super().analyze_contract(operation)
            if not operation.hierarchy_role:
                completion.payload['protocol'] = PROTOCOL
                completion.payload['hints'][0]['description'] = MONEY
            return completion
    _, packets, last = run_native(TaggedAnalysis('GROUP', invalid=False, money=False))
    assert last['result'] is None
    assert packets and all(p['status'] != 'VERIFIED' for p in packets)
    assert all(any(w['fieldPath'] == 'hints[0].description'
                   for w in p['monetaryObservation']['warnings']) for p in packets)


class NativeReferenceProvider(Provider):
    """Emit actual reference payloads directly, before any acceptance logic."""

    def __init__(self, role, *, invalid, money, inherited_money=False):
        super().__init__()
        self.target_role = role
        self.invalid = invalid
        self.money = money
        self.inherited_money = inherited_money
        self.sent = {}

    def analyze_contract(self, operation):
        role = operation.hierarchy_role
        if not role:
            completion = super().analyze_contract(operation)
            if self.inherited_money:
                for hint in completion.payload["hints"]:
                    hint["description"] = MONEY
            return completion

        self.calls.append(operation)
        source = json.loads(operation.source_text)
        originals = []
        if role == "GROUP":
            for analysis in source["analyses"]:
                originals.extend((leaf[0], decode_claim(leaf[1:], source))
                                 for leaf in analysis["leaves"])
            evidence_indexes = {row[1]: row[0] for row in source["evidence"]}
            rows = [[alias, "KEEP", alias, None, None] for alias, _ in originals]
            field = "lineage"
        else:
            for gi, group in enumerate(source["groups"]):
                originals.extend((f"G{gi + 1}:H{hi + 1}", decode_claim(row, source))
                                 for hi, row in enumerate(group["claims"]))
            evidence_indexes = {row[0]: i for i, row in enumerate(source["evidence"])}
            rows = [[gi, hi, "RETAINED", [f"G{gi + 1}:H{hi + 1}"], None, None]
                    for gi, group in enumerate(source["groups"])
                    for hi, _ in enumerate(group["claims"])]
            field = "resolutions"

        wire = {"protocol": PROTOCOL, "summary": "保留冻结范围全部观察。",
                "newHints": [], field: rows}
        if role == self.target_role:
            assert originals
            new_hint = copy.deepcopy(originals[0][1])
            new_hint["description"] = MONEY if self.money else ORDINARY
            wire["newHints"] = [new_hint]
            basis = list(dict.fromkeys(new_hint["problemEvidenceIds"]
                                       + new_hint["comparisonEvidenceIds"]))
            indices = [evidence_indexes[eid] for eid in basis]
            if role == "GROUP":
                rows[0][2:] = ["N0", indices, "按完整证据保留同一观察。"]
                if self.invalid:
                    rows[0][0] = "A999:H1"
            else:
                rows[0][3:] = [["N0"], indices, "按完整证据保留同一观察。"]
                if self.invalid:
                    rows[0][0] = 999

        key = (role, operation.chunk_index, operation.attempt)
        self.sent[key] = copy.deepcopy(wire)
        return ModelCompletion(payload=wire, model="unit-model", duration_ms=10,
                               finish_reason="STOP")


def run_native(provider):
    # Do not use drive(): its legacy fixture adapter intentionally encodes old
    # canonical test responses. These regressions must reach the raw new wire.
    _, _, settings, request = setup()
    service = ContractSemanticWorkService(settings, provider)
    plan = service.plan(request)
    packets = []
    expected = plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        before = len(provider.calls)
        last = service.advance(request, plan["planFingerprint"], packets, expected,
                               remaining_ms=800000, execute=True)
        assert len(provider.calls) == before + 1
        assert last["checkpoint"] is not None
        packets.append(last["checkpoint"])
        if last["result"] is not None or last["next"] is None:
            break
        expected = last["next"]["operationFingerprint"]
    else:
        pytest.fail("bounded native reference execution did not terminate")
    assert len(provider.calls) <= plan["callLimit"] <= 22
    return plan, packets, last


def money_identity(hint, field_path):
    text = hint["title"] + "\n" + hint["description"]
    return (field_path, hint["category"], "MONETARY_VALUE",
            hashlib.sha256(text.encode("utf-8")).hexdigest())


def identities(warnings):
    return [(row["fieldPath"], row["category"], row["errorKind"], row["textSha256"])
            for row in warnings]


@pytest.mark.parametrize("role", ["GROUP", "FINAL"])
@pytest.mark.parametrize("money", [True, False], ids=["money", "nonmoney"])
def test_decode_failure_keeps_raw_new_hint_observation_without_publishing(role, money):
    provider = NativeReferenceProvider(role, invalid=True, money=money)
    _, packets, last = run_native(provider)
    failed = [packet for packet in packets if packet["role"] == role]
    assert [packet["status"] for packet in failed] == ["REPAIR_REQUIRED", "FAILED"]
    assert last["result"] is None and last["next"] is None
    assert sum(packet["purpose"] == "REPAIR" for packet in packets) == 1
    for packet in failed:
        assert packet["call"]["outcome"] == "FAILED"
        assert packet.get("validatedResult") is None
        raw = packet["completion"]["payload"]
        operation = next(op for op in provider.calls
                         if op.hierarchy_role == role and op.chunk_index == packet["ordinal"]
                         and op.attempt == (2 if packet["purpose"] == "REPAIR" else 1))
        assert raw == provider.sent[(role, operation.chunk_index, operation.attempt)]
        assert raw["newHints"][0]["description"] == (MONEY if money else ORDINARY)
        observation = packet["monetaryObservation"]
        assert observation["mode"] == "OBSERVE"
        expected = [money_identity(raw["newHints"][0], "newHints[0].description")] if money else []
        assert identities(observation["warnings"]) == expected


@pytest.mark.parametrize("role", ["GROUP", "FINAL"])
def test_valid_inverse_retains_raw_and_distinct_inherited_warnings_without_duplicates(role):
    provider = NativeReferenceProvider(role, invalid=False, money=True, inherited_money=True)
    _, packets, last = run_native(provider)
    assert last["result"] is not None
    assert all(packet["status"] == "VERIFIED" for packet in packets)
    assert all(packet["purpose"] != "REPAIR" for packet in packets)
    packet = next(packet for packet in packets if packet["role"] == role)
    raw = packet["completion"]["payload"]
    assert raw["newHints"][0]["description"] == MONEY and "hints" not in raw
    hints = packet["validatedResult"]["hints"]
    assert len(hints) >= 3 and all(hint["description"] == MONEY for hint in hints)
    expected = [money_identity(raw["newHints"][0], "newHints[0].description")]
    expected.extend(money_identity(hint, f"hints[{i}].description")
                    for i, hint in enumerate(hints) if i != 0)
    actual = identities(packet["monetaryObservation"]["warnings"])
    # Identical literal text/hash on different source aliases remains distinct;
    # the same newHint observed before and after inverse is recorded only once.
    assert len({identity[3] for identity in expected}) == 1
    assert len(actual) == len(set(actual)) == len(expected)
    assert set(actual) == set(expected)
