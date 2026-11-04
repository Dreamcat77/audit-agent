"""RED-only failure-path regression; no provider call or phase mock is used."""
import sys
import unittest

from audit_ai.config import Settings
from audit_ai.model.gateway import ContractTextOperation, ModelGatewayFailure
from audit_ai.model.router import ModelApplicationService


class ForbiddenProvider:
    def analyze_contract(self, operation):
        raise AssertionError("this recording-only regression must never call a provider")


class FailureRecordingTests(unittest.TestCase):
    def record(self, purpose, *, reason="PROVIDER_5XX", attempt=1, transport_attempt=1):
        service = ModelApplicationService(
            Settings(model_name="offline-failure-model", semantic_structured_transport="PROMPT_ONLY"),
            ForbiddenProvider(),
        )
        calls = []
        prior_operation = ContractTextOperation(
            project_id="offline-phase-project", chunk_index=1, chunk_count=8,
            source_text="仅用于离线记录回归。", purpose="ANALYZE",
        )
        service._record_failure(calls, prior_operation, "CONNECT_ERROR", 3)
        prior = calls[0].model_dump(by_alias=True, exclude_none=False)
        self.assertEqual(prior["phase"], "TRANSPORT")
        self.assertEqual(prior["ordinal"], 1)
        operation = ContractTextOperation(
            project_id="offline-phase-project", chunk_index=1, chunk_count=8,
            source_text="仅用于离线记录回归。", purpose=purpose,
            attempt=attempt, max_attempts=2,
            transport_attempt=transport_attempt, max_transport_attempts=2,
        )
        failure = ModelGatewayFailure(reason=reason, retryable=False, duration_ms=57)
        service._record_failure(calls, operation, failure.reason, failure.duration_ms)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0].model_dump(by_alias=True, exclude_none=False), prior)
        call = calls[1].model_dump(by_alias=True, exclude_none=False)
        self.assertEqual(
            {key: value for key, value in call.items() if key != "phase"},
            {"ordinal": 2, "outcome": "FAILED", "safeReason": reason, "durationMs": 57,
             "finishReason": None, "promptTokens": None, "completionTokens": None,
             "totalTokens": None, "provider": "OPENAI_COMPATIBLE", "model": "offline-failure-model"},
        )
        wire = service._call_error_data(calls)["modelCalls"][1]
        for field in ("finishReason", "promptTokens", "completionTokens", "totalTokens"):
            self.assertIsNone(wire[field], field + " must remain unknown, never fabricated as zero")
            self.assertNotIn(field, calls[1].model_dump(by_alias=True, exclude_none=True))
        return call["phase"]

    def test_initial_synthesis_http_failure_is_transport(self):
        self.assertEqual(self.record("SYNTHESIZE", reason="PROVIDER_5XX"), "TRANSPORT",
                         "SYNTHESIZE attempt=1 transport_attempt=1 reason=PROVIDER_5XX")

    def test_initial_synthesis_connect_timeout_is_transport(self):
        self.assertEqual(self.record("SYNTHESIZE", reason="CONNECT_TIMEOUT"), "TRANSPORT",
                         "SYNTHESIZE attempt=1 transport_attempt=1 reason=CONNECT_TIMEOUT")

    def test_initial_synthesis_read_timeout_is_transport(self):
        self.assertEqual(self.record("SYNTHESIZE", reason="READ_TIMEOUT"), "TRANSPORT",
                         "SYNTHESIZE attempt=1 transport_attempt=1 reason=READ_TIMEOUT")

    def test_synthesis_second_semantic_attempt_remains_repair(self):
        self.assertEqual(self.record("SYNTHESIZE", attempt=2), "REPAIR")

    def test_synthesis_second_transport_attempt_remains_repair(self):
        self.assertEqual(self.record("SYNTHESIZE", transport_attempt=2), "REPAIR")

    def test_evidence_repair_failure_remains_repair(self):
        self.assertEqual(self.record("EVIDENCE_REPAIR"), "REPAIR")

    def test_completeness_correction_failure_remains_repair(self):
        self.assertEqual(self.record("SEMANTIC_COMPLETENESS_CORRECTION"), "REPAIR")

    def test_combined_correction_failure_remains_repair(self):
        self.assertEqual(self.record("COMBINED_CORRECTION"), "REPAIR")


if __name__ == "__main__":
    unittest.main(testRunner=unittest.TextTestRunner(stream=sys.stdout, verbosity=2))
