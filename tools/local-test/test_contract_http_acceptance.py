"""Offline regressions only: MockTransport never reaches a service or model."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from uuid import uuid4

import httpx

SPEC = importlib.util.spec_from_file_location(
    "contract_http_acceptance", Path(__file__).with_name("contract-http-acceptance.py"))
subject = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = subject
SPEC.loader.exec_module(subject)


def envelope(data, status=200, code=200):
    return httpx.Response(status, json={"code": code, "data": data, "message": "not-for-evidence"})


class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=subject.TEST_ROOT)
        self.path = Path(self.temp.name) / "receipt.json"
        self.receipt = subject.Receipt.create(self.path)

    def tearDown(self):
        self.temp.cleanup()

    def api(self, handler):
        return subject.Api(self.receipt, transport=httpx.MockTransport(handler))

    def test_receipt_precedes_dispatch_and_contains_no_request_body(self):
        def handler(request):
            persisted = json.loads(self.path.read_text(encoding="utf-8"))
            operation = persisted["operations"]["confirmMetadata"]
            self.assertEqual("SENDING", operation["status"])
            self.assertEqual(operation["key"], request.headers["Idempotency-Key"])
            self.assertNotIn("synthetic private body", self.path.read_text())
            self.assertEqual("127.0.0.1", request.url.host)
            self.assertEqual(8888, request.url.port)
            return envelope({"id": str(uuid4())})
        self.api(handler).write("confirmMetadata", "PATCH", "/api/v1/projects/" + str(uuid4()),
                                {"name": "synthetic private body"})
        self.assertEqual("CONFIRMED", self.receipt.value["operations"]["confirmMetadata"]["status"])

    def test_uncertain_write_never_retries_or_replaces_key(self):
        calls = []
        def handler(request):
            calls.append(request.headers["Idempotency-Key"])
            raise httpx.ReadTimeout("secret endpoint and body must not escape", request=request)
        api = self.api(handler)
        with self.assertRaisesRegex(subject.SafeFailure, "WRITE_OUTCOME_UNKNOWN"):
            api.write("resumeFactExtraction", "POST", "/api/v1/projects/" + str(uuid4()), {})
        key = self.receipt.value["operations"]["resumeFactExtraction"]["key"]
        with self.assertRaisesRegex(subject.SafeFailure, "OPERATION_ALREADY_RECORDED"):
            api.write("resumeFactExtraction", "POST", "/api/v1/projects/" + str(uuid4()), {})
        self.assertEqual([key], calls)
        self.assertEqual("WRITE_OUTCOME_UNKNOWN", self.receipt.value["operations"]["resumeFactExtraction"]["status"])

    def test_unproven_5xx_bad_json_and_uncertain_409_are_unknown(self):
        for response in [httpx.Response(504, text="secret"), httpx.Response(200, text="not json"),
                         envelope({"classification": "RECOVERY_OUTCOME_UNCERTAIN"}, 409, 409)]:
            with self.subTest(status=response.status_code):
                receipt = subject.Receipt.create(Path(self.temp.name) / (str(uuid4()) + ".json"))
                api = subject.Api(receipt, transport=httpx.MockTransport(lambda request: response))
                with self.assertRaisesRegex(subject.SafeFailure, "WRITE_OUTCOME_UNKNOWN"):
                    api.write("createProject", "POST", "/api/v1/projects", {})

    def test_http200_business_refusal_is_not_success_or_identity_failure(self):
        api = self.api(lambda request: envelope({"classification": "REQUEST_REJECTED"}, 200, 400))
        with self.assertRaisesRegex(subject.SafeFailure, "WRITE_REJECTED_400"):
            api.write("resumeFactExtraction", "POST", "/api/v1/projects/" + str(uuid4()), {})
        self.assertEqual("REJECTED", self.receipt.value["operations"]["resumeFactExtraction"]["status"])

    def test_redirects_external_urls_and_path_escape_fail_closed(self):
        calls = []
        api = self.api(lambda request: calls.append(request) or envelope({}))
        for path in ["http://example.invalid/api/v1/projects", "//example.invalid/api/v1/projects",
                     "/api/v1/../auth/login", "/api/v1/projects?url=http://example.invalid"]:
            with self.subTest(path=path), self.assertRaises(subject.SafeFailure):
                api.get(path)
        self.assertEqual([], calls)
        api = self.api(lambda request: httpx.Response(302, headers={"Location": "http://example.invalid"}))
        with self.assertRaisesRegex(subject.SafeFailure, "READ_HTTP_FAILURE"):
            api.get("/api/v1/projects/" + str(uuid4()))

    def test_sensitive_receipt_fields_rejected_and_original_is_not_overwritten(self):
        before = self.path.read_bytes()
        for field in ["password", "cookie", "token", "providerUrl", "rawResponse", "parsedText"]:
            with self.subTest(field=field):
                value = dict(self.receipt.value)
                value[field] = "secret"
                with self.assertRaisesRegex(subject.SafeFailure, "UNSAFE_RECEIPT"):
                    subject.validate_receipt(value)
        self.assertEqual(before, self.path.read_bytes())
        with self.assertRaisesRegex(subject.SafeFailure, "EXISTING_RECEIPT_REOPEN_REQUIRED"):
            subject.Receipt.create(self.path)

    def test_login_is_memory_only_and_csrf_is_refreshed_after_login(self):
        calls = []
        def handler(request):
            calls.append(request)
            if request.url.path.endswith("/csrf"):
                return envelope({"headerName": "X-CSRF-TOKEN", "token": "private-token"})
            self.assertEqual("X-CSRF-TOKEN", next(k for k in request.headers if k.lower() == "x-csrf-token").upper())
            return envelope({})
        self.api(handler).login({"username": "private-user", "password": "private-password"})
        self.assertEqual(["GET", "POST", "GET"], [r.method for r in calls])
        self.assertEqual({}, self.receipt.value["operations"])
        for secret in ["private-user", "private-password", "private-token"]:
            self.assertNotIn(secret, self.path.read_text())

    def test_credentials_schema_rejects_embedded_configuration(self):
        for data in [{"username": "u", "password": "p", "baseUrl": "http://elsewhere"},
                     {"username": "u"}, {"username": "", "password": "p"}]:
            with self.subTest(data=list(data)), self.assertRaises(subject.SafeFailure):
                subject.credentials(json.dumps(data).encode())

    def test_pasted_credential_argument_never_echoed_by_option_error(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = subject.main(["--phase", "reopen", "--password", "DO_NOT_ECHO_THIS"])
        self.assertEqual(1, code)
        self.assertNotIn("DO_NOT_ECHO_THIS", output.getvalue())
        self.assertIn("INVALID_OPTION", output.getvalue())

    def test_readonly_guard_covers_raw_transport_not_only_write_helper(self):
        calls = []
        api = self.api(lambda request: calls.append(request) or envelope({}))
        api.readonly = True
        with self.assertRaisesRegex(subject.SafeFailure, "READ_ONLY_PHASE_WRITE_FORBIDDEN"):
            api._request("POST", "/api/v1/projects", json={})
        self.assertEqual([], calls)

    def test_async_unknown_closes_receipt_unknown_not_failed(self):
        output = io.StringIO()
        stdin = SimpleNamespace(buffer=io.BytesIO(b'{"username":"fake","password":"fake"}'), isatty=lambda: False)
        with patch.object(subject.sys, "stdin", stdin), patch.object(subject.Receipt, "create", return_value=self.receipt), \
                patch.object(subject, "Api"), patch.object(subject, "long_review", side_effect=subject.SafeFailure("REVIEW_RUN_OUTCOME_UNKNOWN")), \
                contextlib.redirect_stdout(output):
            result = subject.main(["--phase", "long-review"])
        self.assertEqual(1, result)
        self.assertEqual("UNKNOWN", self.receipt.value["status"])
        self.assertNotIn("fake", output.getvalue())


class FixtureAndAuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures = subject.verify_fixtures()

    def test_manifest_and_actual_docx_canonical_tail_are_exact(self):
        fixtures = self.fixtures
        self.assertEqual(8235, len(fixtures.source_text))
        self.assertEqual(8257, len(fixtures.canonical_text))
        self.assertEqual("2a4ca8bf088224bc98a9d5314d8b146b164d19fef3fee26e779a210d36ccd47f",
                         subject.sha(fixtures.canonical_text))
        self.assertEqual("ad6309de779d8a0d52ba89b173649ce26f47a59766edf763dbc46c0a63295116",
                         subject.sha(fixtures.canonical_text[-256:]))
        self.assertTrue(fixtures.canonical_text.endswith(fixtures.source_text))
        self.assertGreater(subject.segment_count(fixtures.canonical_text), 32)

    def test_modified_manifest_and_parent_path_escape_rejected(self):
        with tempfile.TemporaryDirectory(dir=subject.TEST_ROOT) as directory:
            root = Path(directory)
            (root / "manifest.json").write_text('{"synthetic": true}')
            with self.assertRaisesRegex(subject.SafeFailure, "UNAPPROVED_MANIFEST"):
                subject.verify_fixtures(root)
            with self.assertRaisesRegex(subject.SafeFailure, "UNSAFE_FIXTURE_PATH"):
                subject.read_inside(root, "../customer.docx")

    def test_confirmation_is_exact_declared_synthetic_not_ai_fields(self):
        payload = subject.confirmation_payload(self.fixtures, 7)
        self.assertEqual(7, payload["expectedVersion"])
        self.assertEqual("900000.00", payload["contractAmount"])
        self.assertEqual("GOODS", payload["contractType"])
        self.assertEqual(self.fixtures.contract_title, payload["contractName"])
        self.assertEqual(["300000.00", "600000.00"], [x["declaredAmount"] for x in payload["lineItems"]])
        self.assertIsNone(payload["awardNotice"])
        self.assertTrue(all(isinstance(x["unitPrice"], str) for x in payload["lineItems"]))

    def projection(self):
        input_hash = "a" * 64
        batches = (subject.segment_count(self.fixtures.canonical_text) + 31) // 32
        attempt_id = str(uuid4())
        ids = {key: str(uuid4()) for key in ["projectId", "fileId", "parseResultId", "roleDecisionId", "identityDecisionId"]}
        attempt = dict(ids, attemptId=attempt_id, effectiveRole="CONTRACT_BODY", status="SUCCEEDED",
                       fileSha256=self.fixtures.docx_sha256, canonicalTextSha256=subject.sha(self.fixtures.canonical_text),
                       inputFingerprint=input_hash, acceptedCount=0, rejectedCount=0)
        calls = []
        for index, (scope, batch) in enumerate((s, b) for s in ["CORE", "LINE_ITEMS"] for b in range(1, batches + 1)):
            calls.append(dict(callAuditId=str(uuid4()), factExtractionAttemptId=attempt_id, scope=scope,
                              callOrdinal=index * 2 + 1, phase="TRANSPORT", outcome="SUCCEEDED", batchStatus="EMPTY",
                              requestFingerprint=subject.batch_fingerprint(input_hash, scope, batch, batches)))
        return {"attempt": attempt, "currentParseResultId": ids["parseResultId"], "candidates": [],
                "rejections": [], "modelCalls": calls}, ids

    def test_all_batches_scopes_are_proved_even_when_business_facts_empty(self):
        projection, ids = self.projection()
        summary = subject.validate_fact_audit(projection, self.fixtures, ids)
        self.assertGreater(summary["batchesPerScope"], 1)
        self.assertEqual(summary["batchesPerScope"] * 2, len(summary["audit"]))

    def test_missing_final_batch_ordinal_collision_or_unknown_is_not_pass(self):
        for mode in ["missing", "collision", "unknown", "identity"]:
            projection, ids = self.projection()
            if mode == "missing": projection["modelCalls"].pop()
            elif mode == "collision": projection["modelCalls"][-1]["callOrdinal"] = 1
            elif mode == "unknown": projection["attempt"]["status"] = "OUTCOME_UNKNOWN"
            else: projection["attempt"]["parseResultId"] = str(uuid4())
            with self.subTest(mode=mode), self.assertRaises(subject.SafeFailure):
                subject.validate_fact_audit(projection, self.fixtures, ids)

    def test_fact_evidence_must_match_exact_unicode_slice(self):
        projection, ids = self.projection()
        projection["candidates"] = [dict(candidateId=str(uuid4()), attemptId=projection["attempt"]["attemptId"],
            excerptStart=0, excerptEnd=2, excerpt="wrong", validationStatus="ACCEPTED", publishedExtractionId=str(uuid4()))]
        with self.assertRaisesRegex(subject.SafeFailure, "FACT_EVIDENCE_MISMATCH"):
            subject.validate_fact_audit(projection, self.fixtures, ids)

    def test_historical_repair_failure_and_rejected_invalid_evidence_are_honest_audit(self):
        projection, ids = self.projection()
        first = projection["modelCalls"][0]
        first["outcome"] = "FAILED"
        repaired = dict(first, callAuditId=str(uuid4()), callOrdinal=2, phase="REPAIR", outcome="SUCCEEDED")
        projection["modelCalls"].append(repaired)
        projection["attempt"]["rejectedCount"] = 1
        projection["candidates"] = [dict(candidateId=str(uuid4()), attemptId=projection["attempt"]["attemptId"],
            excerptStart=0, excerptEnd=2, excerpt="rejected invalid evidence", validationStatus="REJECTED", publishedExtractionId=None)]
        audit = subject.validate_fact_audit(projection, self.fixtures, ids)
        self.assertEqual(0, audit["acceptedCount"])
        self.assertEqual("FAILED", audit["audit"][0]["outcome"])
        self.assertEqual("REPAIR", audit["audit"][1]["phase"])

    def test_budget_is_bounded_not_a_global_unlimited_timeout(self):
        self.assertEqual(1000, subject.AMINI_TIMEOUT_SECONDS)
        self.assertLessEqual(subject.RUN_TIMEOUT_SECONDS, 1800)
        self.assertLessEqual(subject.FILE_TIMEOUT_SECONDS, 1200)

    def test_poll_deadline_does_not_send_another_read_and_unknown_does_not_retry(self):
        ticks = [0]
        calls = []
        def pause(seconds): ticks[0] += seconds
        with self.assertRaisesRegex(subject.SafeFailure, "TEST_TIMEOUT"):
            subject.poll(lambda: calls.append(1) or {"status": "PENDING"}, {"SUCCEEDED"}, {"PENDING"}, 2, "TEST",
                         pause=pause, monotonic=lambda: ticks[0])
        self.assertEqual([1], calls)
        calls.clear()
        with self.assertRaisesRegex(subject.SafeFailure, "TEST_OUTCOME_UNKNOWN"):
            subject.poll(lambda: calls.append(1) or {"status": "UNKNOWN"}, {"SUCCEEDED"}, {"PENDING"}, 2, "TEST")
        self.assertEqual([1], calls)


class JourneyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=subject.TEST_ROOT)
        self.receipt = subject.Receipt.create(Path(self.temp.name) / "receipt.json")
        self.fixtures = subject.verify_fixtures()
        self.ids = {key: str(uuid4()) for key in subject.ID_FIELDS}
        self.step_ids = [str(uuid4()) for _ in range(5)]
        self.calls = []
        self.file = dict(id=self.ids["fileId"], projectId=self.ids["projectId"], fileSha256=self.fixtures.docx_sha256,
                         canonicalTextSha256=subject.sha(self.fixtures.canonical_text), parseResultId=self.ids["parseResultId"],
                         parsedText=self.fixtures.canonical_text, parseStatus="PARSED", ingestionStatus="AVAILABLE",
                         roleAssessmentId=self.ids["roleAssessmentId"], roleDecisionVersion=0, identityDecisionVersion=0,
                         identityAssessmentId=self.ids["identityAssessmentId"], progression={"blocker": "NEEDS_ROLE_CONFIRMATION"})
        self.projection = dict(version=1, runId=self.ids["runId"], runStatus="COMPLETED", scopeStatement="fixture scope",
            categories=[dict(id=c, title=c, statusLabel="fixture insufficient", summary="fixture summary " + c,
                             uncoveredScopes=["fixture gap"], items=[]) for c in subject.CATEGORY_IDS])
        self.report_bytes = ("<html><body>fixture scope " + " ".join(
            c["title"] + c["statusLabel"] + c["summary"] + "fixture gap" for c in self.projection["categories"]) + "</body></html>").encode()
        self.report = dict(reportId=self.ids["reportId"], reviewRunId=self.ids["runId"], reportSchemaVersion=3,
                           status="AVAILABLE", artifactSize=len(self.report_bytes))
        helper = FixtureAndAuditTests()
        helper.fixtures = self.fixtures
        self.fact, _ = helper.projection()
        self.fact["attempt"].update({key: self.ids[key] for key in ["projectId", "fileId", "parseResultId", "roleDecisionId", "identityDecisionId"]})
        self.fact["attempt"]["attemptId"] = self.ids["extractionAttemptId"]
        self.fact["currentParseResultId"] = self.ids["parseResultId"]
        for call in self.fact["modelCalls"]: call["factExtractionAttemptId"] = self.ids["extractionAttemptId"]
        self.run = dict(runId=self.ids["runId"], projectId=self.ids["projectId"], contractId=self.ids["contractId"],
            sourceCandidateId=self.ids["candidateId"], sourceConfirmationId=self.ids["confirmationId"], executionMode="SERVER_WORKER",
            status="COMPLETED", businessProjection=self.projection, materials=[dict(
                fileId=self.ids["fileId"], fileSha256=self.fixtures.docx_sha256, projectId=self.ids["projectId"],
                parseResultId=self.ids["parseResultId"], roleDecisionId=self.ids["roleDecisionId"], identityDecisionId=self.ids["identityDecisionId"],
                canonicalTextSha256=subject.sha(self.fixtures.canonical_text), effectiveRole="CONTRACT_BODY")],
            steps=[dict(stepId=identifier, runId=self.ids["runId"], stepType=kind, status="COMPLETED")
                   for identifier, kind in zip(self.step_ids, sorted(subject.STEP_TYPES))])

    def tearDown(self):
        self.temp.cleanup()

    def handler(self, request):
        self.calls.append(request)
        path = request.url.path
        ids = self.ids
        if request.method != "GET":
            persisted = json.loads(self.receipt.path.read_bytes())
            name = next(k for k, v in persisted["operations"].items() if v["key"] == request.headers["Idempotency-Key"])
            self.assertEqual("SENDING", persisted["operations"][name]["status"])
            if name == "createProject": return envelope(dict(id=ids["projectId"], versionNo=0))
            if name == "confirmMetadata": return envelope({})
            if name == "uploadContract":
                return envelope(dict(projectId=ids["projectId"], fileId=ids["fileId"], jobId=ids["jobId"], file=self.file), 202)
            if name == "confirmRole":
                self.file.update(roleDecisionId=ids["roleDecisionId"], effectiveRole="CONTRACT_BODY", effectiveRoleSource="HUMAN_CONFIRMATION")
                return envelope(dict(decisionId=ids["roleDecisionId"]))
            if name == "confirmIdentity":
                self.file.update(identityDecisionId=ids["identityDecisionId"], identityCurrent=True, effectiveIdentityStatus="SAME_PROJECT")
                self.file["progression"] = dict(blocker="FACT_EXTRACTION_PENDING", recoveryInputFingerprint="a" * 64)
                return envelope(dict(decisionId=ids["identityDecisionId"]))
            if name == "resumeFactExtraction":
                self.file["progression"] = dict(blocker="CANDIDATE_MATERIALIZATION_PENDING", recoveryInputFingerprint="b" * 64)
                return envelope(self.file)
            if name == "resumeCandidateMaterialization": return envelope(self.file)
            if name == "confirmCandidate": return envelope(dict(confirmationId=ids["confirmationId"], projectId=ids["projectId"],
                candidateId=ids["candidateId"], sourceFileId=ids["fileId"], sourceParseResultId=ids["parseResultId"],
                sourceRoleDecisionId=ids["roleDecisionId"], sourceIdentityDecisionId=ids["identityDecisionId"],
                payloadFingerprint="c" * 64, resultCandidateVersion=1))
            if name == "promoteCandidate": return envelope(dict(projectId=ids["projectId"], candidateId=ids["candidateId"], contractId=ids["contractId"], versionNo=0))
            if name == "createReviewRun": return envelope(self.run, 202)
            if name == "generateReport": return envelope(self.report, 202)
            self.fail("unexpected write")
        if path.endswith("/processing"): return envelope(dict(status="SUCCEEDED", jobId=ids["jobId"], projectId=ids["projectId"], fileId=ids["fileId"], parseResultId=ids["parseResultId"]))
        if path.endswith("/projects/" + ids["projectId"]): return envelope(dict(files=[self.file]))
        if path.endswith("/contract-fact-extractions/latest"): return envelope(self.fact)
        if path.endswith("/contract-candidates"): return envelope([dict(id=ids["candidateId"], sourceContractFileId=ids["fileId"])])
        if path.endswith("/contract-candidates/" + ids["candidateId"]):
            return envelope(dict(candidate=dict(id=ids["candidateId"], versionNo=0, promotedContractId=ids["contractId"],
                promotedConfirmationId=ids["confirmationId"]), sourceParseResultId=ids["parseResultId"], sourceRoleDecisionId=ids["roleDecisionId"],
                sourceIdentityDecisionId=ids["identityDecisionId"], candidateAuthoritySource="HUMAN_CONFIRMATION"))
        if path.endswith("/role-decisions/" + ids["roleDecisionId"]): return envelope(dict(decisionId=ids["roleDecisionId"], fileId=ids["fileId"]))
        if path.endswith("/identity-decisions/" + ids["identityDecisionId"]): return envelope(dict(decisionId=ids["identityDecisionId"], fileId=ids["fileId"]))
        if path.endswith("/confirmations/" + ids["confirmationId"]): return envelope(dict(confirmationId=ids["confirmationId"], payloadFingerprint="c" * 64))
        if path.endswith("/review-runs/" + ids["runId"]): return envelope(self.run)
        if "/steps/" in path:
            step = next(s for s in self.run["steps"] if path.endswith(s["stepId"]))
            return envelope(dict(step=step, auditCheckResults=[dict(ruleCode=r, checkStatus="NOT_EVALUATED" if "AWARD" in r else "PASS") for r in subject.F02_RULES]))
        if path.endswith("/reports/" + ids["reportId"]): return envelope(self.report)
        if path.endswith("/artifact"): return httpx.Response(200, content=self.report_bytes, headers={"Content-Type": "text/html; charset=utf-8"})
        self.fail("unexpected GET")

    def test_normal_journey_then_exact_readonly_reopen_and_no_raw_receipt(self):
        api = subject.Api(self.receipt, transport=httpx.MockTransport(self.handler))
        result = subject.long_review(api, self.receipt, self.fixtures)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(self.ids, result["ids"])
        amini = next(r for r in self.calls if r.method == "POST" and json.loads(self.receipt.path.read_bytes())["operations"]["resumeFactExtraction"]["key"] == r.headers["Idempotency-Key"])
        self.assertEqual(1000, amini.extensions["timeout"]["read"])
        before = len(self.calls)
        reopened = subject.reopen(api, self.receipt, self.fixtures)
        self.assertTrue(reopened["checks"]["readOnlyReopen"])
        self.assertTrue(all(r.method == "GET" for r in self.calls[before:]))
        self.assertFalse(any("execute" in r.url.path or "retry" in r.url.path for r in self.calls))
        self.assertNotIn("测试供应商乙", self.receipt.path.read_text(encoding="utf-8"))
        self.assertNotIn("fixture summary", self.receipt.path.read_text(encoding="utf-8"))

    def test_reopen_refuses_changed_saved_report_or_fact_attempt(self):
        api = subject.Api(self.receipt, transport=httpx.MockTransport(self.handler))
        subject.long_review(api, self.receipt, self.fixtures)
        self.fact["attempt"]["attemptId"] = str(uuid4())
        with self.assertRaises(subject.SafeFailure): subject.reopen(api, self.receipt, self.fixtures)

    def test_unknown_run_is_terminal_and_does_not_execute_or_retry(self):
        self.run["status"] = "UNKNOWN"
        api = subject.Api(self.receipt, transport=httpx.MockTransport(self.handler))
        with self.assertRaisesRegex(subject.SafeFailure, "REVIEW_RUN_OUTCOME_UNKNOWN"):
            subject.long_review(api, self.receipt, self.fixtures)
        self.assertNotIn("generateReport", self.receipt.value["operations"])
        self.assertFalse(any("execute" in r.url.path or "retry" in r.url.path for r in self.calls))


if __name__ == "__main__":
    unittest.main()
