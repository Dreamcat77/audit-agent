"""Offline RED-only proposal: initial synthesis is transport, not a repair.

Only the external provider boundary is synthetic.  Operations, call recording,
quality validation, checkpoint creation and replay are production components.
No phase field is supplied or rewritten by this fixture.
"""
import copy
import json
import re
import unittest

from audit_ai.config import Settings
from audit_ai.model.gateway import ContractTextOperation, ModelCompletion
from audit_ai.model.router import ContractAnalysisRequest, ModelApplicationService
from audit_ai.model.semantic_work import ContractSemanticWorkService


def completion(payload=None, ordinal=1):
    return ModelCompletion(
        payload=payload or {"summary": "离线记录测试。", "hints": []},
        model="offline-phase-model", provider="OPENAI_COMPATIBLE",
        duration_ms=10 + ordinal, finish_reason="STOP",
        prompt_tokens=1000 + ordinal, completion_tokens=100 + ordinal,
        total_tokens=1100 + 2 * ordinal,
    )


class CompletionRecordingTests(unittest.TestCase):
    def record(self, purpose, *, attempt=1, transport_attempt=1):
        operation = ContractTextOperation(
            project_id="offline-phase-project", chunk_index=1, chunk_count=8,
            source_text="仅用于离线回归。", purpose=purpose,
            attempt=attempt, max_attempts=2,
            transport_attempt=transport_attempt, max_transport_attempts=2,
        )
        calls = []
        ModelApplicationService._record_completion(
            calls, operation, completion(), outcome="SUCCEEDED")
        self.assertEqual(len(calls), 1)
        call = calls[0].model_dump(by_alias=True, exclude_none=True)
        self.assertEqual(
            {key: value for key, value in call.items() if key != "phase"},
            {"ordinal": 1, "outcome": "SUCCEEDED", "durationMs": 11,
             "finishReason": "STOP", "promptTokens": 1001,
             "completionTokens": 101, "totalTokens": 1102,
             "provider": "OPENAI_COMPATIBLE", "model": "offline-phase-model"})
        return call["phase"]

    def test_initial_analysis_is_transport(self):
        self.assertEqual(self.record("ANALYZE"), "TRANSPORT")

    def test_initial_synthesis_is_transport_not_a_new_repair(self):
        self.assertEqual(
            self.record("SYNTHESIZE"), "TRANSPORT",
            "purpose=SYNTHESIZE attempt=1 transport_attempt=1 must be TRANSPORT")

    def test_synthesis_second_semantic_attempt_remains_repair(self):
        self.assertEqual(self.record("SYNTHESIZE", attempt=2), "REPAIR")

    def test_synthesis_second_transport_attempt_remains_repair(self):
        self.assertEqual(self.record("SYNTHESIZE", transport_attempt=2), "REPAIR")

    def test_evidence_repair_remains_repair_even_at_first_attempt(self):
        self.assertEqual(self.record("EVIDENCE_REPAIR"), "REPAIR")


class SyntheticProvider:
    """Finite, locally generated valid payloads; never constructs call metadata."""
    def __init__(self):
        self.operations = []

    def analyze_contract(self, operation):
        self.operations.append(operation)
        if operation.purpose == "ANALYZE":
            ids = re.findall(r"\[evidenceId=([^;]+);", operation.source_text)
            if not ids:
                raise AssertionError("analysis must carry original evidence identities")
            payload = {
                "summary": "已核对本段可见说明。",
                "scopeStatus": "CHECKED_WITH_FINDINGS",
                "hints": [{
                    "category": "OTHER_TEXT", "attention": "INFO",
                    "title": "本段材料说明核对", "description": "本段载有材料说明，供结合原件核对。",
                    "problemEvidenceIds": [], "comparisonEvidenceIds": ids[:1],
                    "unavailableMaterialCategory": None, "claimKind": "CLAUSE_PRESENT",
                    "obligationScope": {"topic": "OTHER", "responsibleParty": "PARTY_B", "trigger": None},
                }],
            }
        elif operation.purpose == "SYNTHESIZE":
            source = json.loads(operation.source_text)
            hints, resolutions = [], []
            for p, partial in enumerate(source["partialResults"]):
                for h, original in enumerate(partial["hints"]):
                    hint = {key: copy.deepcopy(original[key]) for key in (
                        "category", "attention", "title", "description",
                        "problemEvidenceIds", "comparisonEvidenceIds")}
                    kind, scope = original["q"]
                    hint.update(
                        unavailableMaterialCategory=None,
                        claimKind=source["qualityRegistry"]["claimKinds"][kind],
                        obligationScope=copy.deepcopy(source["qualityRegistry"]["obligationScopes"][scope]),
                    )
                    target = len(hints)
                    hints.append(hint)
                    resolutions.append({
                        "observation": [p, h], "disposition": "RETAINED",
                        "finalHintIndexes": [target],
                        "basisEvidenceIds": list(hint["comparisonEvidenceIds"]),
                        "reason": "保留本段原说明及对应证据。",
                    })
            payload = {"summary": "已汇总各段可见说明。", "hints": hints, "resolutions": resolutions}
        else:
            raise AssertionError("valid synthetic inputs must not dispatch a correction")
        return completion(payload, ordinal=len(self.operations))


class WorkServiceEightAnalysisAndSynthesisTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.provider = SyntheticProvider()
        service = ContractSemanticWorkService(
            Settings(model_name="offline-phase-model", model_chunk_chars=650, model_max_chunks=30),
            cls.provider,
        )
        request = ContractAnalysisRequest.model_validate({
            "projectId": "offline-phase-project",
            "semanticQualityProtocol": "contract-semantic-quality-v1",
            "materials": [{"fileId": f"offline-file-{i}", "category": "SUPPLEMENT",
                           "text": "本段记录离线测试材料说明。\n" * 30} for i in range(1, 9)],
        })
        plan = service.plan(request)
        if [unit["purpose"] for unit in plan["units"]] != ["ANALYZE"] * 8 + ["SYNTHESIZE"]:
            raise AssertionError("fixture must plan exactly eight analyses and one synthesis")
        cls.packets = []
        expected = plan["next"]["operationFingerprint"]
        for _ in range(11):
            outcome = service.advance(
                request, plan["planFingerprint"], cls.packets, expected,
                remaining_ms=800_000, execute=True,
            )
            packet = outcome["checkpoint"]
            if packet is None or packet["status"] != "VERIFIED":
                raise AssertionError(f"synthetic payload must validate without repair: {outcome}")
            cls.packets.append(packet)
            if outcome["result"] is not None:
                cls.result = outcome["result"]
                break
            if outcome["next"] is None:
                raise AssertionError("valid synthetic work must reach a final result")
            expected = outcome["next"]["operationFingerprint"]
        else:
            raise AssertionError("bounded synthetic work did not complete")

    def test_all_nine_initial_calls_are_transport_including_first_synthesis(self):
        self.assertEqual(
            [packet["call"]["phase"] for packet in self.packets],
            ["TRANSPORT"] * 9,
            "8A+S all attempt=1/transport_attempt=1; S ordinal=9 must not be REPAIR",
        )

    def test_usage_and_dispatch_ordinals_survive_actual_checkpoint_replay(self):
        self.assertEqual(len(self.packets), 9)
        for ordinal, packet in enumerate(self.packets, start=1):
            with self.subTest(ordinal=ordinal):
                self.assertEqual(packet["call"]["ordinal"], ordinal)
                self.assertEqual(packet["call"]["outcome"], "SUCCEEDED")
                self.assertEqual(packet["call"]["durationMs"], 10 + ordinal)
                self.assertEqual(packet["call"]["finishReason"], "STOP")
                self.assertEqual(packet["call"]["promptTokens"], 1000 + ordinal)
                self.assertEqual(packet["call"]["completionTokens"], 100 + ordinal)
                self.assertEqual(packet["call"]["totalTokens"], 1100 + 2 * ordinal)
        self.assertEqual(self.result["modelCalls"], [packet["call"] for packet in self.packets])

    def test_valid_eight_analysis_and_synthesis_have_no_actual_added_correction(self):
        self.assertTrue(self.result["available"])
        self.assertEqual(len(self.provider.operations), 9)
        self.assertEqual([op.purpose for op in self.provider.operations], ["ANALYZE"] * 8 + ["SYNTHESIZE"])
        self.assertEqual([packet["purpose"] for packet in self.packets], ["ANALYZE"] * 8 + ["SYNTHESIZE"])
        self.assertEqual([(op.attempt, op.transport_attempt) for op in self.provider.operations], [(1, 1)] * 9)
        self.assertEqual([packet["status"] for packet in self.packets], ["VERIFIED"] * 9)


if __name__ == "__main__":
    unittest.main(verbosity=2)
