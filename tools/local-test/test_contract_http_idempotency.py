"""Offline only: real POST shapes and identity/count proofs via MockTransport."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from uuid import uuid4

import httpx

SPEC = importlib.util.spec_from_file_location("contract_http_idempotency", Path(__file__).with_name("contract-http-idempotency.py"))
subject = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = subject
SPEC.loader.exec_module(subject)
h = subject.h


def response(data, status=200, code=200):
    return httpx.Response(status, json={"code": code, "data": data, "message": "not evidence"})


class IdempotencyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=h.TEST_ROOT)
        self.fixtures = h.verify_fixtures()
        self.old = h.Receipt.create(Path(self.temp.name) / "old.json")
        self.ids = {key: str(uuid4()) for key in h.ID_FIELDS}
        self.old.value["ids"] = self.ids
        self.old.value["stepIds"] = [str(uuid4()) for _ in range(5)]
        self.old.value["checks"] = {key: True for key in h.CHECK_FIELDS if key != "readOnlyReopen"}
        self.old.value["statuses"] = dict(fileJobStatus="SUCCEEDED", extractionStatus="SUCCEEDED", runStatus="COMPLETED", reportStatus="AVAILABLE")
        self.old.begin("createProject", subject.request_hash("/api/v1/projects"))
        self.old.finish("createProject", "CONFIRMED")
        self.upload_path = f"/api/v1/projects/{self.ids['projectId']}/file-jobs"
        self.old.begin("uploadContract", subject.request_hash(self.upload_path, self.fixtures.docx_sha256))
        self.old.finish("uploadContract", "CONFIRMED")
        self.projection = dict(version=1, runId=self.ids["runId"], runStatus="COMPLETED", scopeStatement="synthetic scope", categories=[
            dict(id=k, title=k, statusLabel="insufficient", summary="synthetic summary", uncoveredScopes=[], items=[]) for k in h.CATEGORY_IDS])
        self.artifact = ("<html>synthetic scope" + "".join(k + "insufficientsynthetic summary" for k in h.CATEGORY_IDS) + "</html>").encode()
        self.old.value["hashes"] = dict(sourceFileSha256=self.fixtures.docx_sha256, sourceTextSha256=h.sha(self.fixtures.source_text),
            canonicalTextSha256=h.sha(self.fixtures.canonical_text), reportSha256=h.sha(self.artifact), projectionSha256=h.sha(h.canonical_json(self.projection)))
        self.old.value["counts"]["reportBytes"] = len(self.artifact)
        self.old.value["status"] = "PASS"
        self.old.save()
        self.initial_old_bytes = self.old.path.read_bytes()
        self.original = dict(id=self.ids["fileId"], projectId=self.ids["projectId"], fileSha256=self.fixtures.docx_sha256,
            canonicalTextSha256=h.sha(self.fixtures.canonical_text), parsedText=self.fixtures.canonical_text,
            parseResultId=self.ids["parseResultId"], parseStatus="PARSED", ingestionStatus="AVAILABLE",
            originalFilename=self.fixtures.docx_path.name, mediaType=subject.DOCX_MEDIA_TYPE)
        self.files = [self.original]
        self.jobs = [dict(jobId=self.ids["jobId"], projectId=self.ids["projectId"], fileId=self.ids["fileId"], status="SUCCEEDED", parseResultId=self.ids["parseResultId"])]
        self.new_file, self.new_job = str(uuid4()), str(uuid4())
        self.calls = []
        self.fail_replay = False
        self.fail_new = False
        self.wrong_replay = False
        self.report_changed = False
        self.new_returns_old = False
        self.new_extra_growth = False
        self.new_changes_report = False
        self.result = subject.ProbeReceipt.create(Path(self.temp.name) / "probe.json", self.old, self.fixtures)

    def tearDown(self):
        self.temp.cleanup()

    def handler(self, request):
        self.calls.append(request)
        path = request.url.path
        if request.method == "POST":
            key = request.headers["Idempotency-Key"]
            journal = json.loads(self.result.path.read_bytes())
            operation = next(o for o in journal["operations"].values() if o["key"] == key)
            self.assertEqual("SENDING", operation["status"])
            self.assertEqual("127.0.0.1", request.url.host)
            self.assertEqual(8888, request.url.port)
            if key == self.old.value["operations"]["createProject"]["key"]:
                self.assertEqual("/api/v1/projects", path)
                if self.fail_replay: raise httpx.ReadTimeout("secret response", request=request)
                return response(dict(id=self.ids["projectId"]))
            self.assertEqual(self.upload_path, path)
            body = request.read()
            self.assertIn(self.fixtures.docx_path.read_bytes(), body)
            self.assertIn(subject.DOCX_MEDIA_TYPE.encode(), body)
            if key == self.old.value["operations"]["uploadContract"]["key"]:
                return response(dict(projectId=self.ids["projectId"], fileId=str(uuid4()) if self.wrong_replay else self.ids["fileId"],
                    jobId=self.ids["jobId"], file=self.original, job=self.jobs[0]), 202)
            self.assertEqual(self.result.value["operations"]["newUpload"]["key"], key)
            if self.fail_new: return httpx.Response(504, text="secret")
            if self.new_returns_old:
                return response(dict(projectId=self.ids["projectId"], fileId=self.ids["fileId"], jobId=self.ids["jobId"], file=self.original, job=self.jobs[0]), 202)
            new_file = dict(self.original, id=self.new_file, parsedText=None, parseStatus="PENDING")
            new_job = dict(jobId=self.new_job, fileId=self.new_file, projectId=self.ids["projectId"], status="QUEUED", parseResultId=None)
            self.files.append(new_file)
            self.jobs.append(new_job)
            if self.new_extra_growth:
                self.files.append(dict(new_file, id=str(uuid4())))
                self.jobs.append(dict(new_job, jobId=str(uuid4())))
            if self.new_changes_report:
                self.report_changed = True
            return response(dict(projectId=self.ids["projectId"], fileId=self.new_file, jobId=self.new_job, file=new_file, job=new_job), 202)
        if path == "/api/v1/projects": return response(dict(total=1, items=[]))
        if path.endswith("/files"): return response(self.files)
        if path.endswith("/file-jobs"): return response(dict(projectId=self.ids["projectId"], items=self.jobs, total=len(self.jobs)))
        if path.endswith("/processing"): return response(self.jobs[0])
        if path.endswith("/projects/" + self.ids["projectId"]): return response(dict(files=self.files))
        if path.endswith("/review-runs/" + self.ids["runId"]):
            return response(dict(runId=self.ids["runId"], projectId=self.ids["projectId"], contractId=self.ids["contractId"],
                sourceCandidateId=self.ids["candidateId"], sourceConfirmationId=self.ids["confirmationId"], status="COMPLETED", executionMode="SERVER_WORKER",
                businessProjection=self.projection, steps=[dict(stepId=i, runId=self.ids["runId"], stepType=k, status="COMPLETED")
                    for i, k in zip(self.old.value["stepIds"], sorted(h.STEP_TYPES))], materials=[dict(fileId=self.ids["fileId"], projectId=self.ids["projectId"],
                    fileSha256=self.fixtures.docx_sha256, parseResultId=self.ids["parseResultId"], roleDecisionId=self.ids["roleDecisionId"],
                    identityDecisionId=self.ids["identityDecisionId"], canonicalTextSha256=h.sha(self.fixtures.canonical_text), effectiveRole="CONTRACT_BODY")]))
        if path.endswith("/reports/" + self.ids["reportId"]):
            return response(dict(reportId=self.ids["reportId"], reviewRunId=self.ids["runId"], reportSchemaVersion=3, status="AVAILABLE", artifactSize=len(self.artifact)))
        if path.endswith("/artifact"):
            return httpx.Response(200, content=self.artifact + (b"changed" if self.report_changed else b""), headers={"Content-Type": "text/html"})
        self.fail("unexpected request")

    def test_true_post_replays_then_one_explicit_new_operation(self):
        api = h.Api(self.old, transport=httpx.MockTransport(self.handler))
        result = subject.run_probe(api, self.old, self.result, self.fixtures)
        posts = [r for r in self.calls if r.method == "POST"]
        self.assertEqual(3, len(posts))
        self.assertEqual(self.old.value["operations"]["createProject"]["key"], posts[0].headers["Idempotency-Key"])
        self.assertEqual(self.old.value["operations"]["uploadContract"]["key"], posts[1].headers["Idempotency-Key"])
        self.assertNotEqual(posts[1].headers["Idempotency-Key"], posts[2].headers["Idempotency-Key"])
        self.assertEqual("PASS", result["status"])
        self.assertEqual(1, result["counts"]["filesAfterReplay"])
        self.assertEqual(2, result["counts"]["filesAfterNew"])
        self.assertEqual(self.new_file, result["ids"]["newFileId"])
        self.assertEqual(self.initial_old_bytes, self.old.path.read_bytes())
        self.assertNotIn("synthetic summary", self.result.path.read_text())
        self.assertFalse(any("promote" in r.url.path or "execute" in r.url.path or "resume" in r.url.path for r in self.calls))

    def test_pre_dispatch_read_failure_reopens_only_the_identical_unsent_journal(self):
        self.assertTrue(callable(getattr(subject.ProbeReceipt, "reopen_unsent", None)), "unsent journal recovery is available")
        original_operations = json.loads(json.dumps(self.result.value["operations"]))
        self.result.value.update(status="FAILED", failureCode="READ_TRANSPORT_FAILURE")
        self.result.save()
        reopened = subject.ProbeReceipt.reopen_unsent(self.result.path, self.old, self.fixtures)
        self.assertEqual("PREPARED", reopened.value["status"])
        self.assertEqual(original_operations, reopened.value["operations"])
        self.assertNotIn("failureCode", reopened.value)
        self.assertEqual(0, len(self.calls))

    def test_pre_dispatch_reopen_never_replays_a_dispatched_or_unknown_operation(self):
        self.assertTrue(callable(getattr(subject.ProbeReceipt, "reopen_unsent", None)), "unsent journal recovery is available")
        for status in ["SENDING", "CONFIRMED", "WRITE_OUTCOME_UNKNOWN", "REJECTED"]:
            self.result.value.update(status="FAILED", failureCode="READ_TRANSPORT_FAILURE")
            self.result.value["operations"]["replayCreate"]["status"] = status
            self.result.save()
            with self.subTest(status=status), self.assertRaisesRegex(h.SafeFailure, "PROBE_NOT_PROVEN_UNSENT"):
                subject.ProbeReceipt.reopen_unsent(self.result.path, self.old, self.fixtures)

    def test_uncertain_original_replay_stops_before_new_upload_and_keeps_key(self):
        self.fail_replay = True
        api = h.Api(self.old, transport=httpx.MockTransport(self.handler))
        with self.assertRaisesRegex(h.SafeFailure, "WRITE_OUTCOME_UNKNOWN"):
            subject.run_probe(api, self.old, self.result, self.fixtures)
        self.assertEqual(1, sum(r.method == "POST" for r in self.calls))
        self.assertEqual("NOT_SENT", self.result.value["operations"]["newUpload"]["status"])
        self.assertEqual("WRITE_OUTCOME_UNKNOWN", self.result.value["operations"]["replayCreate"]["status"])
        with self.assertRaises(h.SafeFailure): subject.run_probe(api, self.old, self.result, self.fixtures)
        self.assertEqual(1, sum(r.method == "POST" for r in self.calls))

    def test_new_upload_unknown_never_rekeys_or_repeats(self):
        self.fail_new = True
        api = h.Api(self.old, transport=httpx.MockTransport(self.handler))
        key = self.result.value["operations"]["newUpload"]["key"]
        with self.assertRaisesRegex(h.SafeFailure, "WRITE_OUTCOME_UNKNOWN"):
            subject.run_probe(api, self.old, self.result, self.fixtures)
        self.assertEqual("WRITE_OUTCOME_UNKNOWN", self.result.value["operations"]["newUpload"]["status"])
        self.assertEqual(key, self.result.value["operations"]["newUpload"]["key"])
        with self.assertRaises(h.SafeFailure): subject.run_probe(api, self.old, self.result, self.fixtures)
        self.assertEqual(3, sum(r.method == "POST" for r in self.calls))

    def test_wrong_replay_identity_cannot_unlock_new_operation(self):
        self.wrong_replay = True
        api = h.Api(self.old, transport=httpx.MockTransport(self.handler))
        with self.assertRaisesRegex(h.SafeFailure, "UPLOAD_REPLAY_IDENTITY_MISMATCH"):
            subject.run_probe(api, self.old, self.result, self.fixtures)
        self.assertEqual("NOT_SENT", self.result.value["operations"]["newUpload"]["status"])

    def test_incomplete_or_unknown_old_receipt_rejected_before_network(self):
        for status in ["UNKNOWN", "FAILED", "RUNNING"]:
            self.old.value["status"] = status
            with self.subTest(status=status), self.assertRaisesRegex(h.SafeFailure, "COMPLETED_ORIGINAL_RECEIPT_REQUIRED"):
                subject.pin_original(self.old, self.fixtures)

    def test_original_filename_type_or_hash_must_match_and_receipt_is_create_only(self):
        self.original["mediaType"] = "application/octet-stream"
        api = h.Api(self.old, transport=httpx.MockTransport(self.handler))
        with self.assertRaisesRegex(h.SafeFailure, "ORIGINAL_UPLOAD_DESCRIPTOR_MISMATCH"):
            subject.run_probe(api, self.old, self.result, self.fixtures)
        self.assertEqual(0, sum(r.method == "POST" for r in self.calls))
        with self.assertRaisesRegex(h.SafeFailure, "EXISTING_PROBE_RECEIPT"):
            subject.ProbeReceipt.create(self.result.path, self.old, self.fixtures)

    def test_business_refusal_not_unknown_or_success(self):
        api = h.Api(self.old, transport=httpx.MockTransport(lambda request: response({}, 200, 400)))
        with self.assertRaisesRegex(h.SafeFailure, "WRITE_REJECTED_400"):
            subject.post(api, self.result, "replayCreate", "/api/v1/projects")
        self.assertEqual("REJECTED", self.result.value["operations"]["replayCreate"]["status"])

    def test_sensitive_output_receipt_fields_rejected(self):
        for field in ["password", "cookie", "providerUrl", "rawResponse", "parsedText"]:
            value = dict(self.result.value, **{field: "secret"})
            with self.subTest(field=field), self.assertRaisesRegex(h.SafeFailure, "UNSAFE_PROBE_RECEIPT"):
                subject.validate_probe(value)

    def test_frozen_report_change_is_not_pass(self):
        self.report_changed = True
        api = h.Api(self.old, transport=httpx.MockTransport(self.handler))
        with self.assertRaises(h.SafeFailure): subject.run_probe(api, self.old, self.result, self.fixtures)
        self.assertEqual(0, sum(r.method == "POST" for r in self.calls))

    def test_new_key_must_not_silently_reuse_old_file_or_admit_extra_rows(self):
        self.new_returns_old = True
        api = h.Api(self.old, transport=httpx.MockTransport(self.handler))
        with self.assertRaisesRegex(h.SafeFailure, "NEW_UPLOAD_NOT_DISTINCT"):
            subject.run_probe(api, self.old, self.result, self.fixtures)
        self.assertNotEqual("PASS", self.result.value["status"])

    def test_new_upload_count_growth_is_exactly_one(self):
        self.new_extra_growth = True
        api = h.Api(self.old, transport=httpx.MockTransport(self.handler))
        with self.assertRaisesRegex(h.SafeFailure, "NEW_UPLOAD_COUNT_OR_ID_DELTA_MISMATCH"):
            subject.run_probe(api, self.old, self.result, self.fixtures)

    def test_old_frozen_report_is_reverified_after_new_admission(self):
        self.new_changes_report = True
        api = h.Api(self.old, transport=httpx.MockTransport(self.handler))
        with self.assertRaises(h.SafeFailure): subject.run_probe(api, self.old, self.result, self.fixtures)
        self.assertEqual(3, sum(r.method == "POST" for r in self.calls))
        self.assertNotEqual("PASS", self.result.value["status"])


if __name__ == "__main__": unittest.main()
