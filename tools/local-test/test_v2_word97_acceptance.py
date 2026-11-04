"""Word97 FORMAT tests only. The sole external seam is fake HTTP transport."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import unittest
from datetime import datetime, timezone
from unittest.mock import patch
from uuid import uuid4

import httpx

SCRIPT = Path(__file__).with_name("v2-word97-acceptance.py")
ROOT = SCRIPT.parents[2] / ".runtime" / "v2-word97-unit"
P = "11111111-1111-4111-8111-111111111111"
PZ = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
DOC = "22222222-2222-4222-8222-222222222222"
DOCX = "33333333-3333-4333-8333-333333333333"
ZIP = "44444444-4444-4444-8444-444444444444"
CHILD = "55555555-5555-4555-8555-555555555555"
PARSE = "66666666-6666-4666-8666-666666666666"
PARSEX = "77777777-7777-4777-8777-777777777777"
PARSEZ = "88888888-8888-4888-8888-888888888888"
SHA = "a" * 64


def load():
    if not SCRIPT.is_file():
        return None
    spec = importlib.util.spec_from_file_location("word97_acceptance", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


M = load()


def response(data, status=200):
    return httpx.Response(status, json={"code": 200, "data": data, "message": "suppressed"})


class Word97Tests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(M, "Word97 format driver is not implemented")
        ROOT.mkdir(parents=True, exist_ok=True)
        self.root = ROOT / ("word97test-" + str(uuid4()))
        self.root.mkdir()
        def cleanup():
            target = self.root.resolve()
            self.assertTrue(target.is_relative_to(ROOT.resolve()) and target.name.startswith("word97test-"))
            shutil.rmtree(target)
        self.addCleanup(cleanup)
        self.ledger = M.B.Ledger(self.root / "format.jsonl", root=self.root)
        self.addCleanup(self.ledger.close)
        self.calls = []
        self.catalog = M.FormatFiles()

    def api(self, handle):
        def transport(request):
            self.calls.append(request)
            return handle(request)
        api = M.FormatApi(self.ledger, transport=httpx.MockTransport(transport))
        self.addCleanup(api.close)
        return api

    def seed(self, api, target):
        driver = M.FormatDriver(api, self.catalog, target)
        project_id = P if target == "root" else PZ
        api.command(driver.case, "create", "POST", "/api/v1/projects", select=lambda d: {"projectId": project_id, "versionNo": 0})
        return driver

    def file(self, kind, identifier, project=P, parent=None, parsed=True, parse_id=PARSE):
        item = self.catalog.items[kind]
        return {"id": identifier, "projectId": project, "parentArchiveFileId": parent,
                "normalizedRelativePath": item["name"], "originalFilename": item["name"],
                "fileSha256": item["sha256"], "category": "PACKAGE" if kind == "zip" else "OTHER",
                "ingestionStatus": "AVAILABLE", "parseStatus": "NOT_APPLICABLE" if kind == "zip" else "PARSED" if parsed else "PENDING",
                "parseMethod": None if kind == "zip" else "DOCX_TEXT" if kind == "docx" else "DOC_TEXT",
                "parseResultId": None if kind == "zip" or not parsed else parse_id,
                "parseVersion": None if kind == "zip" or not parsed else 1,
                "canonicalTextSha256": None if kind == "zip" or not parsed else M.B.digest("body\nA\t1\nB\t2"),
                "parsedText": None if kind == "zip" or not parsed else "body\nA\t1\nB\t2"}

    def remember(self, driver, kind, identifier):
        # Durable fake admission preparation, using the production journal and command boundary.
        return driver.api.command(driver.case, "upload:" + kind, "POST", driver.base() + "/file-jobs",
                                  select=lambda d: {"projectId": driver.project_id(), "fileId": identifier,
                                                    "jobId": identifier, "fileSha256": self.catalog.items[kind]["sha256"]})

    def test_only_three_authenticated_inputs_include_real_ole2_and_exact_doc_child(self):
        self.assertEqual({"doc", "docx", "zip"}, set(self.catalog.items))
        self.assertEqual("bdb1f4b83300597d21169b0f71ebb41ddb928c4e1f65ead2fd9e32872e8064dd", self.catalog.items["doc"]["sha256"])
        self.assertEqual(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1", self.catalog.read("doc")[:8])
        self.assertEqual(self.catalog.items["doc"]["sha256"], self.catalog.zip_child_sha)

    def test_format_marker_prevents_reusing_an_existing_business_ledger(self):
        self.ledger.note("01", "business", {"status": "PENDING"})
        with self.assertRaisesRegex(M.B.SafeFailure, "FORMAT_LEDGER_REQUIRED"):
            M.FormatDriver(self.api(lambda r: response({})), self.catalog, "root")

    def test_root_and_zip_projects_are_independent_and_creation_is_not_repeated(self):
        creates = []
        def handle(request):
            if request.method == "POST":
                creates.append(request.headers["Idempotency-Key"])
                return response({"id": P if len(creates) == 1 else PZ, "versionNo": 0})
            identity = P if request.url.path.endswith(P) else PZ
            self.assertTrue(json.loads(request.content)["name"].startswith("Word97 格式兼容性验证"))
            return response({"id": identity, "versionNo": 1, "metadataConfirmed": True})
        api = self.api(handle)
        root = M.FormatDriver(api, self.catalog, "root")
        archive = M.FormatDriver(api, self.catalog, "zip")
        self.assertEqual(P, root.create()["projectId"])
        self.assertEqual(PZ, archive.create()["projectId"])
        root.create()
        archive.create()
        self.assertEqual(2, len(creates))
        self.assertEqual(2, len(set(creates)))

    def test_root_upload_is_doc_docx_only_and_original_operations_are_immutable(self):
        uploaded = []
        def handle(request):
            if request.url.path == "/api/v1/projects":
                return response({})
            kind = ("doc", "docx")[len(uploaded)]
            uploaded.append(request)
            self.assertIn(self.catalog.items[kind]["mime"].encode(), request.content)
            identity = DOC if kind == "doc" else DOCX
            return response({"projectId": P, "fileId": identity, "jobId": identity,
                             "file": {"id": identity, "projectId": P, "fileSha256": self.catalog.items[kind]["sha256"]},
                             "job": {"projectId": P, "fileId": identity, "jobId": identity}}, 202)
        driver = self.seed(self.api(handle), "root")
        self.assertEqual(2, driver.upload()["fileCount"])
        driver.upload()
        self.assertEqual(2, len(uploaded))
        self.assertNotIn(b"application/zip", uploaded[0].content)

    def test_uncertain_upload_never_retransmits_or_tries_next_input(self):
        def handle(request):
            if request.url.path == "/api/v1/projects":
                return response({})
            raise httpx.ReadTimeout("private provider detail")
        driver = self.seed(self.api(handle), "root")
        with self.assertRaisesRegex(M.B.SafeFailure, "WRITE_OUTCOME_UNKNOWN"):
            driver.upload()
        with self.assertRaisesRegex(M.B.SafeFailure, "CASE_UNRESOLVED_GET_ONLY"):
            driver.upload()
        self.assertEqual(2, len(self.calls))
        self.assertNotIn("private provider detail", self.ledger.path.read_text(encoding="utf-8"))

    def test_zip_root_success_without_child_is_pending_and_child_gets_own_parse(self):
        child_ready = False
        def handle(request):
            if request.method == "POST":
                return response({})
            if request.url.path.endswith("/content"):
                kind = "zip" if f"/{ZIP}/" in request.url.path else "doc"
                return httpx.Response(200, content=self.catalog.read(kind))
            files = [self.file("zip", ZIP, PZ)]
            if child_ready:
                files.append(self.file("doc", CHILD, PZ, ZIP, parse_id=PARSEZ))
            if request.url.path.endswith("/file-jobs"):
                jobs = [{"projectId": PZ, "fileId": file["id"], "jobId": file["id"], "status": "SUCCEEDED",
                         "parseResultId": file["parseResultId"]} for file in files]
                return response({"projectId": PZ, "items": jobs, "total": len(jobs), "completed": len(jobs), "failed": 0, "unknown": 0})
            return response({"project": {"id": PZ}, "files": files})
        driver = self.seed(self.api(handle), "zip")
        self.remember(driver, "zip", ZIP)
        self.assertEqual("PENDING", driver.poll()["status"])
        child_ready = True
        self.assertEqual("COMPLETED", driver.poll()["status"])
        receipts = [e["result"] for e in self.ledger.events if e["operation"] == "parsed:" + CHILD]
        self.assertEqual(PARSEZ, receipts[-1]["parseResultId"])
        self.assertEqual("DOC_TEXT", receipts[-1]["parseMethod"])
        self.assertEqual(1, receipts[-1]["parseVersion"])

    def test_download_must_match_source_sha_before_format_completion(self):
        def handle(request):
            if request.method == "POST":
                return response({})
            if request.url.path.endswith("/content"):
                return httpx.Response(200, content=b"wrong original bytes")
            files = [self.file("doc", DOC), self.file("docx", DOCX, parse_id=PARSEX)]
            if request.url.path.endswith("/file-jobs"):
                return response({"projectId": P, "items": [
                    {"projectId": P, "fileId": f["id"], "jobId": f["id"], "status": "SUCCEEDED", "parseResultId": f["parseResultId"]} for f in files],
                    "total": 2, "completed": 2, "failed": 0, "unknown": 0})
            return response({"project": {"id": P}, "files": files})
        driver = self.seed(self.api(handle), "root")
        self.remember(driver, "doc", DOC)
        self.remember(driver, "docx", DOCX)
        with self.assertRaisesRegex(M.B.SafeFailure, "ORIGINAL_HASH_MISMATCH"):
            driver.poll()

    def test_body_and_table_order_comparison_reports_only_hashes_and_counts(self):
        same = M.compare_text("header\nA\t1\nB\t2", "header\nA\t1\nB\t2")
        different = M.compare_text("header\nA\t1\nB\t2", "header\nB\t2\nA\t1")
        self.assertEqual("NORMALIZED_TEXT_AND_TABLE_ORDER_MATCH", same["status"])
        self.assertEqual("FORMAT_COMPARISON_DIFFERENCE", different["status"])
        self.assertEqual(2, same["doc"]["rowCount"])
        self.assertNotIn("header", json.dumps(same))

    def test_transport_rejects_model_or_candidate_writes_before_dispatch(self):
        api = self.api(lambda r: self.fail("forbidden request dispatched"))
        for path in [f"/api/v1/projects/{P}/files/{DOC}/progression/resume", f"/api/v1/projects/{P}/contract-candidates/{DOC}/confirmations"]:
            with self.assertRaisesRegex(M.B.SafeFailure, "FORMAT_WRITE_FORBIDDEN"):
                api._request("POST", path)

    def test_cli_unknown_phase_is_safe_unsupported_without_credential_prompt(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, M.main(["--phase", "confirm", "--target", "root"]))
        self.assertEqual("SAFE_UNSUPPORTED", json.loads(output.getvalue())["safeCode"])

    def root_with_complete_reads(self, state):
        def handle(request):
            if request.method == "POST":
                return response({})
            if request.url.path.endswith("/content"):
                return httpx.Response(200, content=self.catalog.read("doc" if f"/{DOC}/" in request.url.path else "docx"))
            files = [self.file("doc", DOC), self.file("docx", DOCX, parse_id=state["docxParseId"])]
            if request.url.path.endswith("/file-jobs"):
                return response({"projectId": P, "items": [
                    {"projectId": P, "fileId": f["id"], "jobId": f["id"], "status": "SUCCEEDED", "parseResultId": f["parseResultId"]} for f in files],
                    "total": 2, "completed": 2, "failed": 0, "unknown": 0})
            return response({"project": {"id": P}, "files": files})
        driver = self.seed(self.api(handle), "root")
        self.remember(driver, "doc", DOC)
        self.remember(driver, "docx", DOCX)
        return driver

    def test_reopen_keeps_exact_parse_ids_and_uses_business_gets_only(self):
        state = {"docxParseId": PARSEX}
        driver = self.root_with_complete_reads(state)
        self.assertEqual("COMPLETED", driver.poll()["status"])
        before = len(self.calls)
        self.assertTrue(driver.reopen()["reconciled"])
        self.assertTrue(all(request.method == "GET" for request in self.calls[before:]))
        state["docxParseId"] = PARSEZ
        with self.assertRaisesRegex(M.B.SafeFailure, "REOPEN_IDENTITY_CHANGED"):
            driver.reopen()

    def test_two_files_cannot_claim_same_parse_result_in_one_snapshot(self):
        driver = self.root_with_complete_reads({"docxParseId": PARSE})
        with self.assertRaisesRegex(M.B.SafeFailure, "CROSS_PROJECT_PARSE_REUSE"):
            driver.poll()

    def test_unknown_upload_exact_get_reconciliation_never_resends(self):
        uncertain = False
        def handle(request):
            nonlocal uncertain
            if request.url.path == "/api/v1/projects":
                return response({})
            if request.method == "POST":
                uncertain = True
                raise httpx.ReadTimeout("uncertain admission")
            self.assertTrue(uncertain)
            self.assertEqual(f"/api/v1/projects/{P}/file-jobs/by-operation", request.url.path)
            return response({"projectId": P, "fileId": DOC, "jobId": DOC,
                             "file": {"projectId": P, "id": DOC, "fileSha256": self.catalog.items["doc"]["sha256"]},
                             "job": {"projectId": P, "fileId": DOC, "jobId": DOC}})
        driver = self.seed(self.api(handle), "root")
        with self.assertRaises(M.B.SafeFailure):
            driver.upload()
        key = self.ledger.operation("01", "upload:doc")["key"]
        self.assertEqual(DOC, driver.reconcile("upload:doc")["fileId"])
        self.assertEqual(key, self.ledger.operation("01", "upload:doc")["key"])
        self.assertEqual(["POST", "POST", "GET"], [request.method for request in self.calls])

    def test_main_positive_runtime_binding_and_real_ledger_with_fake_http_only(self):
        expected_sha = "d7edf92f8f0ae57ccc6254a295162bd386186e67"
        artifact = self.root / "controlled-format-unit.jar"
        artifact.write_bytes(b"synthetic Word97 unit artifact")
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({
            "kind": "ROOT_VERIFIED_RUNNING_ARTIFACT", "origin": "http://127.0.0.1:8888", "sourceSha": expected_sha,
            "observedAtUtc": datetime.now(timezone.utc).isoformat(),
            "java": {"processId": os.getppid(), "artifactPath": str(artifact),
                     "artifactSha256": M.B.file_digest(artifact), "inputSha256": "b" * 64},
            "python": {"processId": os.getpid(), "inputSha256": "c" * 64}}), encoding="utf-8")
        main_root = M.FORMAT_ROOT / ("unit-main-" + str(uuid4()))
        main_root.mkdir(parents=True)
        def cleanup():
            target = main_root.resolve()
            self.assertTrue(target.is_relative_to(M.FORMAT_ROOT.resolve()) and target.name.startswith("unit-main-"))
            shutil.rmtree(target)
        self.addCleanup(cleanup)
        ledger_path = main_root / "format.jsonl"
        calls = []
        def transport(request):
            calls.append((request.method, request.url.path))
            if request.url.path == "/api/v1/auth/csrf":
                return response({"headerName": "X-CSRF-TOKEN", "token": "UNIT_CSRF_MEMORY_ONLY"})
            if request.url.path == "/api/v1/auth/login":
                return response({"userId": P, "username": "test_admin", "roles": ["SYSTEM_ADMIN"]})
            if request.method == "POST" and request.url.path == "/api/v1/projects":
                return response({"id": P, "versionNo": 0})
            if request.method == "PATCH" and request.url.path == "/api/v1/projects/" + P:
                return response({"id": P, "versionNo": 1, "metadataConfirmed": True})
            self.fail("unexpected fake format HTTP boundary operation")
        real_api = M.FormatApi
        def api_factory(ledger, *, readonly=False):
            return real_api(ledger, readonly=readonly, transport=httpx.MockTransport(transport))
        arguments = ["--phase", "create", "--target", "root", "--expected-sha", expected_sha,
                     "--runtime-evidence", str(identity_path), "--ledger", str(ledger_path), "--credentials-stdin"]
        for _ in range(2):
            with (patch.object(M, "FormatApi", side_effect=api_factory),
                  patch.object(M.sys, "stdin", io.StringIO('{"username":"test_admin","password":"UNIT_PASSWORD_MEMORY_ONLY"}')),
                  contextlib.redirect_stdout(io.StringIO()) as output):
                code = M.main(arguments)
            self.assertEqual(0, code, output.getvalue())
            self.assertEqual("FORMAT_WORD97", json.loads(output.getvalue())["receiptKind"])
        self.assertEqual(1, calls.count(("POST", "/api/v1/projects")))
        persisted = ledger_path.read_text(encoding="utf-8")
        self.assertNotIn("UNIT_PASSWORD_MEMORY_ONLY", persisted)
        self.assertNotIn("UNIT_CSRF_MEMORY_ONLY", persisted)
        opened = M.B.Ledger(ledger_path, root=M.FORMAT_ROOT)
        self.addCleanup(opened.close)
        runtime = next(event["result"] for event in opened.events if event["operation"] == "runtime")
        self.assertEqual(os.getpid(), runtime["pythonProcessId"])
        self.assertEqual("b" * 64, runtime["javaInputSha256"])


if __name__ == "__main__":
    unittest.main()
