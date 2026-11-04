"""Offline boundary tests. httpx.MockTransport is the only external seam.

These tests never exercise live HTTP, DM8, OCR, inference or a browser.
"""
from __future__ import annotations

import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import shutil
import unittest
from unittest.mock import patch
from uuid import uuid4
from datetime import datetime, timezone

import httpx

SCRIPT = Path(__file__).with_name("v2-http-acceptance.py")
RUNTIME = SCRIPT.parents[2] / ".runtime" / "v2-http-unit"
P = "11111111-1111-4111-8111-111111111111"
F = "22222222-2222-4222-8222-222222222222"
J = "33333333-3333-4333-8333-333333333333"
PARSE = "44444444-4444-4444-8444-444444444444"
ROLE = "55555555-5555-4555-8555-555555555555"
IDENTITY = "66666666-6666-4666-8666-666666666666"
ATTEMPT = "77777777-7777-4777-8777-777777777777"
HASH = "a" * 64
EXPECTED_SHA = "d7edf92f8f0ae57ccc6254a295162bd386186e67"
BASE = f"/api/v1/projects/{P}"


def envelope(data, status=200, code=200):
    return httpx.Response(status, json={"code": code, "message": "suppressed", "data": data})


def load():
    if not SCRIPT.is_file():
        return None
    spec = importlib.util.spec_from_file_location("v2_acceptance", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


M = load()


class DriverBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(M, "V2 driver is not implemented")
        RUNTIME.mkdir(parents=True, exist_ok=True)
        # Python 3.14 Windows tempfile's private ACL is inaccessible to this runner.
        self.root = RUNTIME / ("v2test-" + str(uuid4()))
        self.root.mkdir()
        def cleanup():
            target = self.root.resolve()
            self.assertTrue(target.is_relative_to(RUNTIME.resolve()) and target.name.startswith("v2test-"))
            shutil.rmtree(target)
        self.addCleanup(cleanup)
        self.path = self.root / "ledger.jsonl"
        self.ledger = M.Ledger(self.path, root=self.root)
        self.addCleanup(self.ledger.close)
        self.calls = []

    def client(self, handle):
        def external(request):
            self.calls.append(request)
            return handle(request)
        api = M.Api(self.ledger, transport=httpx.MockTransport(external))
        self.addCleanup(api.close)
        return api

    def test_intent_is_durable_before_dispatch_and_contains_full_digest_not_body(self):
        def handle(request):
            events = [json.loads(line) for line in self.path.read_text(encoding="utf-8").splitlines()]
            intent = events[-1]["value"]
            self.assertEqual("INTENT", intent["event"])
            self.assertEqual(request.headers["Idempotency-Key"], intent["key"])
            self.assertEqual("POST", intent["command"]["method"])
            self.assertEqual("/api/v1/projects", intent["command"]["path"])
            self.assertEqual(64, len(intent["command"]["bodySha256"]))
            self.assertNotIn("private material", self.path.read_text(encoding="utf-8"))
            return envelope({"id": P, "versionNo": 0})
        api = self.client(handle)
        result = api.command("01", "create", "POST", "/api/v1/projects",
                             {"name": "private material"}, select=lambda d: {"projectId": d["id"], "versionNo": d["versionNo"]})
        self.assertEqual({"projectId": P, "versionNo": 0}, result)
        self.assertEqual("CONFIRMED", self.ledger.operation("01", "create")["state"])

    def test_confirmed_command_never_dispatches_twice(self):
        api = self.client(lambda r: envelope({"id": P}))
        selector = lambda d: {"projectId": d["id"]}
        api.command("01", "create", "POST", "/api/v1/projects", select=selector)
        api.command("01", "create", "POST", "/api/v1/projects", select=selector)
        self.assertEqual(1, len(self.calls))

    def test_unknown_survives_reopen_and_blocks_new_keys_or_other_case_writes(self):
        api = self.client(lambda r: (_ for _ in ()).throw(httpx.ReadTimeout("secret password prompt")))
        with self.assertRaisesRegex(M.SafeFailure, "WRITE_OUTCOME_UNKNOWN"):
            api.command("01", "create", "POST", "/api/v1/projects")
        before = self.path.read_bytes()
        self.ledger.close()
        ledger2 = M.Ledger(self.path, root=self.root)
        self.addCleanup(ledger2.close)
        api2 = M.Api(ledger2, transport=httpx.MockTransport(lambda r: self.fail("uncertain operation retransmitted")))
        self.addCleanup(api2.close)
        for operation in ["create", "upload:new"]:
            with self.assertRaisesRegex(M.SafeFailure, "CASE_UNRESOLVED_GET_ONLY"):
                api2.command("01", operation, "POST", "/api/v1/projects")
        self.assertEqual(before, self.path.read_bytes())
        self.assertNotIn(b"secret", before)

    def test_changed_command_cannot_reuse_confirmed_operation(self):
        api = self.client(lambda r: envelope({"id": P}))
        api.command("01", "metadata", "PATCH", BASE, {"expectedVersion": 0})
        with self.assertRaisesRegex(M.SafeFailure, "COMMAND_IDENTITY_CHANGED"):
            api.command("01", "metadata", "PATCH", BASE, {"expectedVersion": 1})
        self.assertEqual(1, len(self.calls))

    def test_partial_tail_and_edited_hashchain_fail_closed(self):
        self.ledger.note("01", "poll", {"projectId": P})
        original = self.path.read_bytes()
        self.ledger.close()
        # Test-only corruption emulates crash/tampering; production only appends.
        for corrupted in [original + b'{"seq":', original.replace(P.encode(), F.encode())]:
            self.path.write_bytes(corrupted)
            with self.assertRaisesRegex(M.SafeFailure, "LEDGER_CORRUPT"):
                M.Ledger(self.path, root=self.root)

    def test_two_process_owners_cannot_both_open_same_ledger(self):
        with self.assertRaisesRegex(M.SafeFailure, "LEDGER_BUSY"):
            M.Ledger(self.path, root=self.root)

    def test_ledger_escape_and_private_result_fields_rejected(self):
        with self.assertRaisesRegex(M.SafeFailure, "UNSAFE_LEDGER_PATH"):
            M.Ledger(self.root.parent / "escape.jsonl", root=self.root)
        with self.assertRaisesRegex(M.SafeFailure, "UNSAFE_RECEIPT"):
            self.ledger.note("01", "poll", {"password": "secret"})

    def test_5xx_redirect_malformed_and_recovery_unknown_are_never_rejected(self):
        for index, response in enumerate([httpx.Response(500), httpx.Response(302, headers={"Location": "https://outside.invalid"}),
                         httpx.Response(200, text="sensitive invalid JSON"),
                         envelope({"classification": "RECOVERY_OUTCOME_UNCERTAIN"}, 409, 409)]):
            with self.subTest(status=response.status_code):
                api = self.client(lambda r, value=response: value)
                with self.assertRaisesRegex(M.SafeFailure, "WRITE_OUTCOME_UNKNOWN"):
                    api.command(f"{index+1:02}", "create", "POST", "/api/v1/projects")

    def test_business_refusal_does_not_count_as_success(self):
        api = self.client(lambda r: envelope(None, 200, 400))
        with self.assertRaisesRegex(M.SafeFailure, "WRITE_REJECTED_400"):
            api.command("01", "create", "POST", "/api/v1/projects")
        self.assertEqual("REJECTED", self.ledger.operation("01", "create")["state"])

    def test_nonloopback_and_path_escape_never_dispatch(self):
        api = self.client(lambda r: self.fail("unsafe URL dispatched"))
        for path in ["https://outside.invalid/api/v1/projects", "/api/v1/../auth", "/api/v1/projects?x=../"]:
            with self.assertRaisesRegex(M.SafeFailure, "UNSAFE_HTTP_PATH"):
                api.get(path)
        self.assertEqual([], self.calls)

    def test_credentials_only_accept_exact_test_admin_memory_schema(self):
        self.assertEqual("test_admin", M.credentials('{"username":"test_admin","password":"ephemeral"}')["username"])
        for raw in ['{"username":"admin","password":"ephemeral"}',
                    '{"username":"test_admin","password":"x","config":"private"}']:
            with self.assertRaisesRegex(M.SafeFailure, "CREDENTIAL_INPUT_INVALID"):
                M.credentials(raw)

    def test_runtime_source_gate_is_explicit_and_never_uses_git_head(self):
        with self.assertRaisesRegex(M.SafeFailure, "RUNTIME_IDENTITY_UNPROVEN"):
            M.validate_runtime({"head": EXPECTED_SHA}, expected_sha=EXPECTED_SHA)
        with self.assertRaisesRegex(M.SafeFailure, "RUNTIME_IDENTITY_UNPROVEN"):
            M.validate_runtime({"sourceSha": "f" * 40, "artifactSha256": HASH, "processId": 1}, expected_sha=EXPECTED_SHA)

    def test_unknown_create_rediscovery_is_get_only_and_keeps_original_key(self):
        def handle(request):
            if request.method == "POST":
                raise httpx.ReadTimeout("uncertain")
            self.assertEqual("/api/v1/projects/creation-operations", request.url.path)
            return envelope({"id": P, "versionNo": 0})
        api = self.client(handle)
        with self.assertRaises(M.SafeFailure):
            api.command("01", "create", "POST", "/api/v1/projects")
        key = self.ledger.operation("01", "create")["key"]
        result = M.rediscover(api, "01", "create")
        self.assertEqual(P, result["projectId"])
        self.assertEqual(key, self.ledger.operation("01", "create")["key"])
        self.assertEqual(["POST", "GET"], [r.method for r in self.calls])

    def test_oracle_missing_candidate_fact_is_blocked_not_backfilled(self):
        oracle = {"contract_no": "QA-HT-2026-01", "contract_amount": 67000, "line_count": 1,
                  "facts": [{"row_id": "MX-001", "name": "line", "quantity": 1, "unit_price": 67000, "line_total": 67000}]}
        candidate = {"contractNo": None, "contractAmount": 67000, "lineItems": []}
        with self.assertRaisesRegex(M.SafeFailure, "FACT_GAP"):
            M.compare_facts(candidate, oracle)
        self.assertIsNone(candidate["contractNo"])

    def test_same_name_rows_remain_distinct_and_decimal_values_compare_numerically(self):
        oracle = {"contract_no": "C", "contract_amount": 30, "line_count": 2,
                  "facts": [{"row_id": "A", "name": "same", "quantity": 1, "unit_price": 10, "line_total": 10},
                            {"row_id": "B", "name": "same", "quantity": 2, "unit_price": 10, "line_total": 20}]}
        candidate = {"contractNo": "C", "contractAmount": "30.00", "lineItems": [
            {"itemKey": "A", "itemName": "same", "quantity": "1.00", "unitPrice": "10.00", "declaredAmount": "10.00"},
            {"itemKey": "B", "itemName": "same", "quantity": "2", "unitPrice": "10", "declaredAmount": "20"}]}
        self.assertEqual(2, M.compare_facts(candidate, oracle)["lineCount"])
        candidate["lineItems"][1]["itemKey"] = "A"
        with self.assertRaisesRegex(M.SafeFailure, "FACT_GAP"):
            M.compare_facts(candidate, oracle)

    def package_driver(self, handle, case="01"):
        package = M.Package()
        api = self.client(handle)
        api.command(case, "create", "POST", "/api/v1/projects", select=lambda d: {"projectId": P, "versionNo": 0})
        return M.Driver(api, package, case), package

    def observed_file(self, metadata, **changes):
        return {"id": F, "projectId": P, "parentArchiveFileId": None, "originalFilename": metadata["path"].name,
                "fileSha256": metadata["sha256"], "canonicalTextSha256": HASH,
                "parseStatus": "PARSED", "parseMethod": "DOCX_TEXT", "ingestionStatus": "AVAILABLE",
                "parseResultId": PARSE, "parseVersion": 1, "parsedText": "bounded fixture evidence",
                "roleAssessmentId": ROLE, "roleDecisionId": ROLE, "roleDecisionVersion": 0,
                "identityAssessmentId": IDENTITY, "identityDecisionId": IDENTITY, "identityDecisionVersion": 0,
                "effectiveRole": metadata["role"], "effectiveRoleSource": "HUMAN_CONFIRMATION",
                "effectiveIdentityStatus": "SAME_PROJECT", "identityCurrent": True,
                "excludedFromProjectUse": False,
                "progression": {"blocker": "FACT_EXTRACTION_PENDING", "availableActions": ["RESUME_FACT_EXTRACTION"],
                                "recoveryInputFingerprint": HASH}, **changes}

    def fact_plan(self, metadata):
        return {"kind": "ROOT_VERIFIED_OFFLINE_FACT_PLAN", "sourceSha": EXPECTED_SHA,
                "projectId": P, "fileId": F, "fileSha256": metadata["sha256"], "parseResultId": PARSE,
                "canonicalTextSha256": HASH, "scopeBatchCounts": {"CORE": 1, "LINE_ITEMS": 1},
                "plannedCalls": 2, "maximumModelCalls": 4}

    def explicit_extract(self, driver, metadata):
        return driver.extract(metadata["sha256"], plan=self.fact_plan(metadata), max_planned_calls=4, expected_sha=EXPECTED_SHA)

    def test_package_approval_verifies_exact_bytes_and_selects_only_case_plain_files(self):
        package = M.Package()
        self.assertEqual(8, len(package.files("01")))
        self.assertEqual(6, len(package.files("02")))
        self.assertTrue(all(item["path"].suffix in {".docx", ".pdf"} for item in package.files("01")))
        with self.assertRaisesRegex(M.SafeFailure, "PACKAGE_HASH_MISMATCH"):
            M.Package(expected_sha="0" * 64)

    def test_upload_uses_one_approved_file_each_and_resumes_without_post(self):
        uploads = []
        def handle(request):
            if request.url.path == "/api/v1/projects":
                return envelope({"id": P})
            self.assertEqual(BASE + "/file-jobs", request.url.path)
            self.assertEqual("POST", request.method)
            uploads.append(request)
            index = len(uploads)
            value = package.files("01")[index - 1]
            identifier = f"00000000-0000-4000-8000-{index:012d}"
            return envelope({"projectId": P, "fileId": identifier, "jobId": identifier,
                             "file": {"id": identifier, "projectId": P, "fileSha256": value["sha256"]},
                             "job": {"jobId": identifier, "fileId": identifier, "projectId": P}}, 202)
        driver, package = self.package_driver(handle)
        self.assertEqual(8, driver.upload()["fileCount"])
        first = self.path.read_bytes()
        self.assertEqual(8, driver.upload()["fileCount"])
        self.assertEqual(8, len(uploads))
        self.assertEqual(first, self.path.read_bytes())

    def test_uncertain_upload_stops_selection_before_next_file(self):
        def handle(request):
            if request.url.path == "/api/v1/projects":
                return envelope({"id": P})
            raise httpx.ReadTimeout("private source response")
        driver, package = self.package_driver(handle)
        with self.assertRaisesRegex(M.SafeFailure, "WRITE_OUTCOME_UNKNOWN"):
            driver.upload()
        self.assertEqual(2, len(self.calls))
        with self.assertRaisesRegex(M.SafeFailure, "CASE_UNRESOLVED_GET_ONLY"):
            driver.upload()
        self.assertEqual(2, len(self.calls))

    def test_poll_is_readonly_and_rejects_foreign_or_unreceipted_jobs(self):
        def handle(request):
            if request.method == "POST":
                return envelope({"id": P})
            if request.url.path == BASE:
                return envelope({"project": {"id": P}, "files": [], "candidates": []})
            return envelope({"projectId": P, "workerEnabled": True, "items": [
                {"projectId": P, "fileId": F, "jobId": J, "status": "SUCCEEDED", "parseResultId": PARSE}],
                "total": 1, "completed": 1, "failed": 0, "unknown": 0})
        driver, package = self.package_driver(handle)
        with self.assertRaisesRegex(M.SafeFailure, "JOB_IDENTITY_UNPROVEN"):
            driver.poll()
        self.assertEqual(["POST", "GET", "GET"], [request.method for request in self.calls])

    def test_foreign_case05_notice_cannot_be_confirmed_same_project(self):
        holder = {}
        def handle(request):
            if request.method == "POST":
                return envelope({"id": P})
            return envelope({"project": {"id": P}, "files": [holder["file"]], "candidates": []})
        driver, package = self.package_driver(handle, "05")
        metadata = next(value for value in package.files("05") if value["foreign"])
        holder["file"] = self.observed_file(metadata)
        with self.assertRaisesRegex(M.SafeFailure, "FOREIGN_MATERIAL_MUST_BE_EXCLUDED"):
            driver.authority(metadata["sha256"], "same")
        self.assertEqual(1, sum(request.method == "POST" for request in self.calls))

    def test_extract_requires_current_human_authority_before_any_model_command(self):
        holder = {}
        def handle(request):
            if request.method == "POST":
                return envelope({"id": P})
            return envelope({"project": {"id": P}, "files": [holder["file"]], "candidates": []})
        driver, package = self.package_driver(handle)
        metadata = next(value for value in package.files("01") if value["role"] == "CONTRACT_BODY")
        holder["file"] = self.observed_file(metadata, effectiveRoleSource="AI_SUGGESTION")
        with self.assertRaisesRegex(M.SafeFailure, "HUMAN_AUTHORITY_UNPROVEN"):
            self.explicit_extract(driver, metadata)
        self.assertEqual(1, sum(request.method == "POST" for request in self.calls))

    def test_explicit_extract_is_one_long_call_and_reading_again_never_repeats_model(self):
        holder = {}
        def handle(request):
            if request.url.path == "/api/v1/projects":
                return envelope({"id": P})
            if request.url.path == BASE:
                return envelope({"project": {"id": P}, "files": [holder["file"]], "candidates": []})
            if request.method == "POST":
                self.assertEqual(BASE + f"/files/{F}/progression/resume", request.url.path)
                self.assertEqual(1000, request.extensions["timeout"]["read"])
                self.assertEqual({"expectedBlocker": "FACT_EXTRACTION_PENDING", "expectedRecoveryInputFingerprint": HASH},
                                 json.loads(request.content))
                return envelope(holder["file"])
            return envelope({"currentParseResultId": PARSE, "currentParseVersion": 1,
                             "attempt": {"attemptId": ATTEMPT, "projectId": P, "fileId": F,
                                         "fileSha256": holder["file"]["fileSha256"], "parseResultId": PARSE,
                                         "canonicalTextSha256": HASH, "roleDecisionId": ROLE,
                                         "identityDecisionId": IDENTITY, "status": "SUCCEEDED", "inputFingerprint": HASH},
                             "candidates": [], "rejections": [], "modelCalls": [{"callOrdinal": 1}]})
        driver, package = self.package_driver(handle)
        metadata = next(value for value in package.files("01") if value["role"] == "CONTRACT_BODY")
        holder["file"] = self.observed_file(metadata)
        self.assertEqual(ATTEMPT, self.explicit_extract(driver, metadata)["attemptId"])
        self.assertEqual(ATTEMPT, self.explicit_extract(driver, metadata)["attemptId"])
        self.assertEqual(2, sum(request.method == "POST" for request in self.calls))

    def test_first_extract_failure_latches_case_even_when_server_rejection_is_definite(self):
        holder = {}
        def handle(request):
            if request.url.path == "/api/v1/projects":
                return envelope({"id": P})
            if request.method == "GET":
                return envelope({"project": {"id": P}, "files": [holder["file"]], "candidates": []})
            return envelope(None, 409, 409)
        driver, package = self.package_driver(handle)
        metadata = next(value for value in package.files("01") if value["role"] == "CONTRACT_BODY")
        holder["file"] = self.observed_file(metadata)
        with self.assertRaisesRegex(M.SafeFailure, "WRITE_REJECTED_409"):
            self.explicit_extract(driver, metadata)
        with self.assertRaisesRegex(M.SafeFailure, "EXTRACTION_FAILED_CASE_STOPPED"):
            self.explicit_extract(driver, metadata)
        self.assertEqual(2, sum(request.method == "POST" for request in self.calls))

    def test_upload_filename_and_media_type_are_part_of_immutable_command(self):
        api = self.client(lambda r: envelope({"id": P}))
        api.command("01", "upload:file", "POST", BASE + "/file-jobs", upload=("a.pdf", b"%PDF-safe", "application/pdf"))
        with self.assertRaisesRegex(M.SafeFailure, "COMMAND_IDENTITY_CHANGED"):
            api.command("01", "upload:file", "POST", BASE + "/file-jobs", upload=("renamed.docx", b"%PDF-safe", "application/octet-stream"))

    def test_readonly_transport_guard_rejects_writes_even_outside_command_helper(self):
        api = M.Api(self.ledger, readonly=True, transport=httpx.MockTransport(lambda r: self.fail("write dispatched")))
        self.addCleanup(api.close)
        with self.assertRaisesRegex(M.SafeFailure, "READ_ONLY_PHASE_WRITE_FORBIDDEN"):
            api._request("POST", "/api/v1/projects")

    def test_create_uses_explicit_oracle_metadata_and_is_resumable_without_second_post(self):
        def handle(request):
            if request.method == "POST":
                return envelope({"id": P, "versionNo": 0})
            self.assertEqual(BASE, request.url.path)
            self.assertEqual("QA-MX-2026-01", json.loads(request.content)["projectNo"])
            self.assertIsNone(json.loads(request.content)["category"])
            return envelope({"id": P, "versionNo": 1, "metadataConfirmed": True})
        api = self.client(handle)
        driver = M.Driver(api, M.Package(), "01")
        self.assertEqual(P, driver.create()["projectId"])
        self.assertEqual(P, driver.create()["projectId"])
        self.assertEqual(["POST", "PATCH"], [r.method for r in self.calls])

    def test_extract_readback_failure_latches_case_and_does_not_issue_second_model_call(self):
        holder = {}
        def handle(request):
            if request.url.path == "/api/v1/projects":
                return envelope({"id": P})
            if request.url.path == BASE:
                return envelope({"project": {"id": P}, "files": [holder["file"]], "candidates": []})
            if request.method == "POST":
                return envelope(holder["file"])
            return httpx.Response(500)
        driver, package = self.package_driver(handle)
        metadata = next(value for value in package.files("01") if value["role"] == "CONTRACT_BODY")
        holder["file"] = self.observed_file(metadata)
        with self.assertRaises(M.SafeFailure):
            self.explicit_extract(driver, metadata)
        with self.assertRaisesRegex(M.SafeFailure, "EXTRACTION_FAILED_CASE_STOPPED"):
            self.explicit_extract(driver, metadata)
        self.assertEqual(2, sum(request.method == "POST" for request in self.calls))

    def test_known_failed_extraction_keeps_real_attempt_instead_of_appending_unknown(self):
        holder = {}
        def handle(request):
            if request.url.path == "/api/v1/projects":
                return envelope({"id": P})
            if request.url.path == BASE:
                return envelope({"project": {"id": P}, "files": [holder["file"]], "candidates": []})
            if request.method == "POST":
                return envelope(holder["file"])
            return envelope({"currentParseResultId": PARSE, "currentParseVersion": 1,
                             "attempt": {"attemptId": ATTEMPT, "projectId": P, "fileId": F,
                                         "fileSha256": holder["file"]["fileSha256"], "parseResultId": PARSE,
                                         "canonicalTextSha256": HASH, "roleDecisionId": ROLE,
                                         "identityDecisionId": IDENTITY, "status": "FAILED", "inputFingerprint": HASH},
                             "candidates": [], "rejections": [], "modelCalls": [{"callOrdinal": 1}]})
        driver, package = self.package_driver(handle)
        metadata = next(value for value in package.files("01") if value["role"] == "CONTRACT_BODY")
        holder["file"] = self.observed_file(metadata)
        with self.assertRaisesRegex(M.SafeFailure, "EXTRACTION_NOT_SUCCEEDED_READ_ONLY"):
            self.explicit_extract(driver, metadata)
        observations = [event["result"] for event in self.ledger.events
                        if event["operation"] == "extract:" + metadata["sha256"] and "result" in event]
        self.assertEqual("FAILED", observations[-1]["status"])
        self.assertEqual(ATTEMPT, observations[-1]["attemptId"])
        self.assertFalse(any(value.get("status") == "OUTCOME_UNKNOWN" for value in observations))
        with self.assertRaisesRegex(M.SafeFailure, "EXTRACTION_FAILED_CASE_STOPPED"):
            self.explicit_extract(driver, metadata)
        self.assertEqual(2, sum(request.method == "POST" for request in self.calls))

    def test_known_in_progress_extraction_preserves_attempt_and_blocks_later_case_write(self):
        holder = {}
        def handle(request):
            if request.url.path == "/api/v1/projects":
                return envelope({"id": P})
            if request.url.path == BASE:
                return envelope({"project": {"id": P}, "files": [holder["file"]], "candidates": []})
            if request.method == "POST":
                return envelope(holder["file"])
            return envelope({"currentParseResultId": PARSE, "currentParseVersion": 1,
                             "attempt": {"attemptId": ATTEMPT, "projectId": P, "fileId": F,
                                         "fileSha256": holder["file"]["fileSha256"], "parseResultId": PARSE,
                                         "canonicalTextSha256": HASH, "roleDecisionId": ROLE,
                                         "identityDecisionId": IDENTITY, "status": "IN_PROGRESS", "inputFingerprint": HASH},
                             "candidates": [], "rejections": [], "modelCalls": [{"callOrdinal": 1}]})
        driver, package = self.package_driver(handle)
        metadata = next(value for value in package.files("01") if value["role"] == "CONTRACT_BODY")
        holder["file"] = self.observed_file(metadata)
        with self.assertRaisesRegex(M.SafeFailure, "EXTRACTION_NOT_SUCCEEDED_READ_ONLY"):
            self.explicit_extract(driver, metadata)
        observations = [event["result"] for event in self.ledger.events
                        if event["operation"] == "extract:" + metadata["sha256"] and "result" in event]
        self.assertEqual("IN_PROGRESS", observations[-1]["status"])
        self.assertEqual(ATTEMPT, observations[-1]["attemptId"])
        dispatched = len(self.calls)
        with self.assertRaisesRegex(M.SafeFailure, "EXTRACTION_FAILED_CASE_STOPPED"):
            driver.api.command("01", "later-business-write", "PATCH", BASE, {"versionNo": 0})
        with self.assertRaisesRegex(M.SafeFailure, "EXTRACTION_FAILED_CASE_STOPPED"):
            self.explicit_extract(driver, metadata)
        self.assertEqual(dispatched, len(self.calls))
        with self.assertRaisesRegex(M.SafeFailure, "EXTRACTION_NOT_SUCCEEDED_READ_ONLY"):
            driver.extraction_read(holder["file"])
        self.assertEqual("GET", self.calls[-1].method)

    def test_unknown_identity_discovery_uses_get_and_never_claims_exact_key_reconciliation(self):
        path = BASE + f"/files/{F}/identity-confirmations"
        def handle(request):
            if request.method == "POST":
                raise httpx.ReadTimeout("uncertain")
            self.assertEqual(BASE + f"/files/{F}/identity-decisions/{IDENTITY}", request.url.path)
            return envelope({"projectId": P, "fileId": F, "decisionId": IDENTITY,
                             "resolution": "CONFIRM_SAME_PROJECT", "versionNo": 1})
        api = self.client(handle)
        with self.assertRaises(M.SafeFailure):
            api.command("01", "same:" + HASH, "POST", path, {"assessmentId": IDENTITY})
        result = M.rediscover(api, "01", "same:" + HASH, decision_id=IDENTITY)
        self.assertEqual(IDENTITY, result["decisionId"])
        self.assertFalse(result["reconciled"])
        self.assertEqual("UNKNOWN", self.ledger.operation("01", "same:" + HASH)["state"])
        self.assertEqual(["POST", "GET"], [r.method for r in self.calls])

    def test_runtime_attestation_validates_actual_artifact_bytes_without_git_fallback(self):
        artifact = self.root / "artifact.jar"
        artifact.write_bytes(b"controlled artifact bytes")
        evidence = {"kind": "ROOT_VERIFIED_RUNNING_ARTIFACT", "origin": "http://127.0.0.1:8888",
                    "sourceSha": EXPECTED_SHA,
                    "java": {"artifactSha256": "0" * 64, "artifactPath": str(artifact),
                             "processId": os.getppid(), "inputSha256": HASH},
                    "python": {"processId": os.getpid(), "inputSha256": HASH},
                    "observedAtUtc": datetime.now(timezone.utc).isoformat()}
        path = self.root / "runtime.json"
        path.write_text(json.dumps(evidence), encoding="utf-8")
        with self.assertRaisesRegex(M.SafeFailure, "RUNTIME_ARTIFACT_HASH_MISMATCH"):
            M.runtime_evidence(path, expected_sha=EXPECTED_SHA, root=self.root)

    def test_cli_offline_reports_no_business_pass_and_option_errors_never_echo_secrets(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, M.main(["--phase", "verify"]))
        self.assertEqual("VERIFIED_OFFLINE", json.loads(output.getvalue())["status"])
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, M.main(["--password", "do-not-echo"]))
        self.assertNotIn("do-not-echo", output.getvalue())
        self.assertEqual("INVALID_OPTIONS", json.loads(output.getvalue())["safeCode"])

    def test_unimplemented_phase_is_explicitly_unsupported_before_credentials_or_network(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, M.main(["--phase", "confirm", "--case", "01"]))
        self.assertEqual("SAFE_UNSUPPORTED", json.loads(output.getvalue())["safeCode"])

    def test_quantitative_check_includes_supplier_and_award_gap_without_filling(self):
        oracle = {"contract_no": "C", "contract_amount": 10, "line_count": 1, "supplier": "expected",
                  "award_amount": 10, "facts": [{"row_id": "A", "name": "item", "quantity": 1, "unit_price": 10, "line_total": 10}]}
        candidate = {"contractNo": "C", "contractAmount": 10, "partyB": "other", "awardAmount": 10,
                     "awardSupplier": "expected", "lineItems": [
                         {"itemKey": "A", "itemName": "item", "quantity": 1, "unitPrice": 10, "declaredAmount": 10}]}
        with self.assertRaisesRegex(M.SafeFailure, "FACT_GAP"):
            M.compare_facts(candidate, oracle)
        candidate["partyB"] = "expected"
        candidate["awardAmount"] = None
        with self.assertRaisesRegex(M.SafeFailure, "FACT_GAP"):
            M.compare_facts(candidate, oracle)
        self.assertIsNone(candidate["awardAmount"])

    def test_extract_without_bound_or_62_call_plan_cannot_dispatch_model(self):
        holder = {}
        def handle(request):
            if request.method == "POST":
                return envelope({"id": P})
            return envelope({"project": {"id": P}, "files": [holder["file"]], "candidates": []})
        driver, package = self.package_driver(handle)
        metadata = next(value for value in package.files("01") if value["role"] == "CONTRACT_BODY")
        holder["file"] = self.observed_file(metadata)
        with self.assertRaisesRegex(M.SafeFailure, "CAPACITY_BLOCKED"):
            driver.extract(metadata["sha256"])
        plan = self.fact_plan(metadata)
        plan.update(scopeBatchCounts={"CORE": 9, "LINE_ITEMS": 53}, plannedCalls=62, maximumModelCalls=124)
        with self.assertRaisesRegex(M.SafeFailure, "CAPACITY_BLOCKED"):
            driver.extract(metadata["sha256"], plan=plan, max_planned_calls=8, expected_sha=EXPECTED_SHA)
        self.assertEqual(1, sum(request.method == "POST" for request in self.calls))

    def test_extract_plan_for_different_parse_is_not_authorization_for_current_input(self):
        file = {"projectId": P, "id": F, "fileSha256": HASH, "parseResultId": PARSE, "canonicalTextSha256": HASH}
        plan = {"kind": "ROOT_VERIFIED_OFFLINE_FACT_PLAN", "sourceSha": EXPECTED_SHA,
                "projectId": P, "fileId": F, "fileSha256": HASH, "parseResultId": J,
                "canonicalTextSha256": HASH, "scopeBatchCounts": {"CORE": 1}, "plannedCalls": 1, "maximumModelCalls": 2}
        with self.assertRaisesRegex(M.SafeFailure, "CAPACITY_BLOCKED"):
            M.extraction_budget(file, plan, 2, EXPECTED_SHA)

    def test_actual_runtime_and_budget_producers_are_accepted_by_real_journal(self):
        evidence = {"kind": "ROOT_VERIFIED_RUNNING_ARTIFACT", "origin": "http://127.0.0.1:8888",
                    "sourceSha": EXPECTED_SHA, "observedAtUtc": datetime.now(timezone.utc).isoformat(),
                    "java": {"processId": 101, "artifactPath": "not-opened-by-this-validator.jar",
                             "artifactSha256": "b" * 64, "inputSha256": "c" * 64},
                    "python": {"processId": 202, "inputSha256": "d" * 64}}
        identity = M.validate_runtime(evidence, expected_sha=EXPECTED_SHA)
        binding = {"sourceSha": EXPECTED_SHA, "packageSha256": M.PACKAGE_SHA,
                   "artifactSha256": identity["artifactSha256"], "javaInputSha256": identity["javaInputSha256"],
                   "pythonInputSha256": identity["pythonInputSha256"]}
        self.ledger.note("01", "binding", binding)
        self.ledger.note("01", "runtime", identity)
        metadata = {"sha256": HASH}
        file = {"projectId": P, "id": F, "fileSha256": HASH, "parseResultId": PARSE, "canonicalTextSha256": HASH}
        budget = M.extraction_budget(file, self.fact_plan(metadata), 4, EXPECTED_SHA)
        self.ledger.note("01", "plan:" + HASH, budget)
        self.ledger.close()
        reopened = M.Ledger(self.path, root=self.root)
        self.addCleanup(reopened.close)
        self.assertEqual(identity, reopened.events[1]["result"])
        self.assertEqual(2, reopened.events[2]["result"]["plannedCalls"])
        self.assertEqual(4, reopened.events[2]["result"]["maximumModelCalls"])

    def test_runtime_process_and_budget_receipts_require_strictly_positive_integer_fields(self):
        for field in ("javaProcessId", "pythonProcessId", "plannedCalls", "maximumModelCalls", "maxPlannedCalls"):
            for invalid in (0, -1, True, "1", 1.5):
                with self.subTest(field=field, invalid=invalid):
                    with self.assertRaisesRegex(M.SafeFailure, "UNSAFE_RECEIPT"):
                        self.ledger.note("01", "runtime", {field: invalid})
        for field in ("javaInputSha256", "pythonInputSha256", "planSha256"):
            for invalid in ("plain text", "a" * 63, "A" * 64, 123):
                with self.subTest(field=field, invalid=invalid):
                    with self.assertRaisesRegex(M.SafeFailure, "UNSAFE_RECEIPT"):
                        self.ledger.note("01", "runtime", {field: invalid})

    def test_main_positive_live_phase_uses_real_runtime_ledger_and_only_fake_http_boundary(self):
        artifact = self.root / "controlled-unit.jar"
        artifact.write_bytes(b"synthetic unit artifact")
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({
            "kind": "ROOT_VERIFIED_RUNNING_ARTIFACT", "origin": "http://127.0.0.1:8888", "sourceSha": EXPECTED_SHA,
            "observedAtUtc": datetime.now(timezone.utc).isoformat(),
            "java": {"processId": os.getppid(), "artifactPath": str(artifact),
                     "artifactSha256": M.file_digest(artifact), "inputSha256": "b" * 64},
            "python": {"processId": os.getpid(), "inputSha256": "c" * 64}}), encoding="utf-8")
        main_root = M.TEST_ROOT / ("unit-main-" + str(uuid4()))
        main_root.mkdir(parents=True)
        def cleanup():
            target = main_root.resolve()
            self.assertTrue(target.is_relative_to(M.TEST_ROOT.resolve()) and target.name.startswith("unit-main-"))
            shutil.rmtree(target)
        self.addCleanup(cleanup)
        ledger_path = main_root / "ledger.jsonl"
        calls = []
        def transport(request):
            calls.append((request.method, request.url.path))
            if request.url.path == "/api/v1/auth/csrf":
                return envelope({"headerName": "X-CSRF-TOKEN", "token": "UNIT_CSRF_MEMORY_ONLY"})
            if request.url.path == "/api/v1/auth/login":
                return envelope({"userId": P, "username": "test_admin", "roles": ["SYSTEM_ADMIN"]})
            if request.method == "POST" and request.url.path == "/api/v1/projects":
                return envelope({"id": P, "versionNo": 0})
            if request.method == "PATCH" and request.url.path == BASE:
                return envelope({"id": P, "versionNo": 1, "metadataConfirmed": True})
            self.fail("unexpected fake boundary operation")
        real_api = M.Api
        def api_factory(ledger, *, readonly=False):
            return real_api(ledger, readonly=readonly, transport=httpx.MockTransport(transport))
        arguments = ["--phase", "create", "--case", "01", "--expected-sha", EXPECTED_SHA,
                     "--runtime-evidence", str(identity_path), "--ledger", str(ledger_path), "--credentials-stdin"]
        for _ in range(2):
            with (patch.object(M, "Api", side_effect=api_factory), patch.object(M.sys, "stdin", io.StringIO(
                    '{"username":"test_admin","password":"UNIT_PASSWORD_MEMORY_ONLY"}')),
                    contextlib.redirect_stdout(io.StringIO()) as output):
                status = M.main(arguments)
            self.assertEqual(0, status, output.getvalue())
            self.assertEqual(P, json.loads(output.getvalue())["observed"]["projectId"])
        self.assertEqual(1, calls.count(("POST", "/api/v1/projects")))
        self.assertEqual(1, calls.count(("PATCH", BASE)))
        persisted = ledger_path.read_text(encoding="utf-8")
        self.assertNotIn("UNIT_PASSWORD_MEMORY_ONLY", persisted)
        self.assertNotIn("UNIT_CSRF_MEMORY_ONLY", persisted)
        opened = M.Ledger(ledger_path)
        self.addCleanup(opened.close)
        runtime = next(e["result"] for e in opened.events if e["operation"] == "runtime")
        self.assertEqual(os.getpid(), runtime["pythonProcessId"])


if __name__ == "__main__":
    unittest.main()
