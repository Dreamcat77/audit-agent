# AI/OCR Project Intake dev29 runbook

Status before this runbook is executed:

- representative OCR runtime: `TARGET_RUNTIME_READY`;
- this branch's Java → FastAPI → PaddleOCR chain: `REMOTE_E2E_PENDING_EXECUTION`;
- model endpoint/key: `TO_PROBE_SAFELY`;
- real Qwen call when required model settings are absent: `BLOCKED_BY_MODEL_CONFIGURATION`.

This runbook is intentionally native Kylin/systemd; it does not introduce Docker or touch Nginx routing. Perform each gate in order. Stop on a mismatch, an occupied port owned by an unknown process, a storage semantic mismatch, an unexpected Paddle API shape, or an unapproved database state.

## 1. Fixed safety boundary

Use only `/opt/python/venvs/audit-ai/bin/python`. Do not alter Kylin's system Python, replace the existing virtualenv, upgrade the working Paddle/OpenCV stack, redownload models, expose Redis beyond loopback, reset DM8, or interrupt an unknown service. Never print the runtime environment file, credentials, API keys, authorization headers, prompts, OCR text, or model response text.

Known supplied target facts are Linux x86_64/glibc 2.28, BiSheng JDK 17.0.19, Python 3.11.16, FastAPI 0.141.1, Uvicorn 0.52.1, PaddlePaddle 3.3.1 CPU, PaddleOCR 3.7.0, OpenCV 4.10.0, `/home/admin/audit_ai_runtime/models`, DM8 `AUDITDB` on 5236, Redis 7.2.14 on `127.0.0.1:6379`, and Nginx 1.21.5 on port 80. The commands below reconfirm rather than overwrite them.

Set the immutable release checkout expected by the example unit, then verify the exact approved commit and a clean source tree:

```bash
export AUDIT_AI_RELEASE_ROOT=/home/admin/audit_ai_runtime/current
export AUDIT_AI_EXPECTED_COMMIT='<approved-feature-commit-sha>'
test "$(git -C "$AUDIT_AI_RELEASE_ROOT" rev-parse HEAD)" = "$AUDIT_AI_EXPECTED_COMMIT"
test -z "$(git -C "$AUDIT_AI_RELEASE_ROOT" status --short)"
git -C "$AUDIT_AI_RELEASE_ROOT" show --no-patch --format='%H %s' HEAD
```

Do not proceed if either `test` command fails.

## 2. Read-only target preflight

Run without installing or changing packages:

```bash
uname -m
getconf GNU_LIBC_VERSION
/opt/java/current/bin/java -version
/opt/python/venvs/audit-ai/bin/python --version
/opt/python/venvs/audit-ai/bin/python -m pip check
systemctl is-active redis.service
redis-cli -h 127.0.0.1 ping
systemctl is-active DmServiceAUDITDB.service
nginx -t
ss -lntp | grep ':8001 ' || printf 'PORT_8001=FREE\n'
test -d /home/admin/audit_ai_runtime/models && printf 'OCR_MODEL_ROOT=PRESENT\n'
find /home/admin/audit_ai_runtime/models -type f -print0 | awk 'BEGIN {RS="\0"} END {print "OCR_MODEL_FILE_COUNT=" NR}'
```

If port 8001 is occupied, identify its owner through the approved operations process. Reuse it only if it is the intended compatible audit-ai instance; otherwise stop. Do not terminate it to claim the port.

Load the existing environment only inside a subshell. The first script prints package versions and Paddle API signatures, never environment values:

```bash
(
  set -a
  . /home/admin/audit_ai_runtime/audit-ai.env
  set +a
  /opt/python/venvs/audit-ai/bin/python - <<'PY'
from inspect import signature
import cv2
import fastapi
import paddle
import paddleocr
import uvicorn
from paddleocr import PaddleOCR

print("FastAPI:", fastapi.__version__)
print("Uvicorn:", uvicorn.__version__)
print("PaddlePaddle:", paddle.__version__)
print("PaddleOCR:", paddleocr.__version__)
print("OpenCV:", cv2.__version__)
print("Device:", paddle.device.get_device())
print("PaddleOCR.constructor:", signature(PaddleOCR))
print("PaddleOCR.predict:", signature(PaddleOCR.predict))
PY
)
```

The adapter currently expects PaddleOCR 3.7.0 constructor parameters `device`, `use_doc_orientation_classify`, `use_doc_unwarping`, `use_textline_orientation`, `cpu_threads`, and `enable_mkldnn`, plus a `predict(input=...)` method. Stop and report the printed signatures if that contract differs; do not add a guessed PaddleOCR 2.x fallback.

Probe only presence of model settings and assert OCR safety without displaying any value:

```bash
(
  set -a
  . /home/admin/audit_ai_runtime/audit-ai.env
  set +a
  /opt/python/venvs/audit-ai/bin/python - <<'PY'
import os

for name in (
    "AUDIT_MODEL_PROVIDER",
    "AUDIT_MODEL_BASE_URL",
    "AUDIT_MODEL_API_KEY",
    "AUDIT_MODEL_NAME",
):
    print(f"{name}={'SET' if os.environ.get(name) else 'NOT_SET'}")

safe = (
    os.environ.get("AUDIT_OCR_CPU_THREADS") == "1"
    and os.environ.get("AUDIT_OCR_ENABLE_MKLDNN", "").lower() == "false"
)
print(f"OCR_SAFETY_CONFIG={'PASS' if safe else 'FAIL'}")
raise SystemExit(0 if safe else 2)
PY
)
```

Missing any of `AUDIT_MODEL_BASE_URL`, `AUDIT_MODEL_API_KEY`, or `AUDIT_MODEL_NAME` means real Qwen is `BLOCKED_BY_MODEL_CONFIGURATION`; continue OCR/Java/UI acceptance and do not substitute fake output.

## 3. V017 and FileStorage gate

V017 is additive and V010/V011 must remain byte-for-byte unchanged. Display the exact proposed SQL from the approved commit before any database session:

```bash
git -C "$AUDIT_AI_RELEASE_ROOT" show "$AUDIT_AI_EXPECTED_COMMIT:db/dm8/V017__project_file_parse_provenance.sql"
sha256sum "$AUDIT_AI_RELEASE_ROOT/db/dm8/V010__project_intake_core.sql"
sha256sum "$AUDIT_AI_RELEASE_ROOT/db/dm8/V011__project_contract_association.sql"
sha256sum "$AUDIT_AI_RELEASE_ROOT/db/dm8/V017__project_file_parse_provenance.sql"
command -v disql
```

The repository deliberately has no selected migration framework and does not establish the target's `disql` path or dedicated application username. Do not invent either or place a password on a process command line. Have the approved DM8 operator open `disql` interactively with the dedicated non-system application account and run these read-only statements:

```sql
SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, NULLABLE
FROM USER_TAB_COLUMNS
WHERE TABLE_NAME = 'PROJECT_FILE'
ORDER BY COLUMN_ID;

SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE, STATUS
FROM USER_CONSTRAINTS
WHERE TABLE_NAME = 'PROJECT_FILE'
ORDER BY CONSTRAINT_NAME;

SELECT COLUMN_NAME
FROM USER_TAB_COLUMNS
WHERE TABLE_NAME = 'PROJECT_FILE'
  AND COLUMN_NAME IN (
    'PARSE_METHOD', 'PARSE_ENGINE', 'PARSE_CONFIDENCE',
    'PAGE_COUNT', 'PARSE_DURATION_MS'
  )
ORDER BY COLUMN_NAME;
```

Expected before first V017 application: the base `PROJECT_FILE` table exists and the last query returns zero rows. If it returns some but not all rows, or constraints/types differ, stop with the query output. If all five rows exist, treat V017 as already present and inspect constraints; do not apply it again.

V017 execution is a separate, explicitly approved DM8 operator action using the displayed file. After that action, rerun all three queries and record the output. The nullable additive columns permit application rollback without destructive schema rollback.

Probe FileStorage configuration without printing the path or writing to it:

```bash
(
  set -a
  . /home/admin/audit_ai_runtime/audit-ai.env
  set +a
  /opt/python/venvs/audit-ai/bin/python - <<'PY'
import os
from pathlib import Path

value = os.environ.get("AUDIT_FILE_STORAGE_ROOT")
print(f"AUDIT_FILE_STORAGE_ROOT={'SET' if value else 'NOT_SET'}")
if not value:
    raise SystemExit(2)
root = Path(value)
print(f"FILE_STORAGE_DIRECTORY={'PRESENT' if root.is_dir() else 'MISSING'}")
print(f"FILE_STORAGE_READABLE={'YES' if os.access(root, os.R_OK) else 'NO'}")
raise SystemExit(0 if root.is_dir() and os.access(root, os.R_OK) else 2)
PY
)
```

Writable, create-only publish, restart/reopen, archive-child reopen, and parsed-text durability are verified later through the actual Project workflow, not by replacing `FileStoragePort` with an ad-hoc probe.

## 4. Manual FastAPI smoke

Run this first in a dedicated terminal. It sources the environment without displaying it and binds loopback only:

```bash
cd "$AUDIT_AI_RELEASE_ROOT/services/audit-ai"
set -a
. /home/admin/audit_ai_runtime/audit-ai.env
set +a
exec /opt/python/venvs/audit-ai/bin/python -m uvicorn audit_ai.main:app \
  --app-dir "$AUDIT_AI_RELEASE_ROOT/services/audit-ai/src" \
  --host 127.0.0.1 --port 8001 --workers 1
```

From another terminal:

```bash
curl --fail --silent --show-error -H 'X-Request-Id: dev29-health-1' \
  http://127.0.0.1:8001/health
```

For real PaddleOCR, choose an approved non-sensitive scanned PDF or image. The response is stored mode 0600 and summarized without printing text:

```bash
export AUDIT_APPROVED_SCAN='/path/to/approved-scanned-document.pdf'
install -d -m 0700 /home/admin/audit_ai_runtime/evidence
umask 077
curl --fail --silent --show-error \
  -H 'X-Request-Id: dev29-real-ocr-1' \
  -F "file=@${AUDIT_APPROVED_SCAN}" \
  -o /home/admin/audit_ai_runtime/evidence/ocr-smoke.json \
  http://127.0.0.1:8001/ai/v1/ocr/extract
/opt/python/venvs/audit-ai/bin/python - <<'PY'
import json
from pathlib import Path

body = json.loads(Path("/home/admin/audit_ai_runtime/evidence/ocr-smoke.json").read_text())
data = body.get("data") or {}
print("code:", body.get("code"))
print("engine:", data.get("engine"))
print("durationMs:", data.get("durationMs"))
print("pageCount:", len(data.get("pages") or []))
print("textChars:", len(data.get("text") or ""))
assert body.get("code") == 200
assert data.get("engine") == "PADDLEOCR_3.7.0"
assert len(data.get("text") or "") > 0
PY
```

This is the first point where this branch may change real OCR status from `REMOTE_E2E_PENDING_EXECUTION` to standalone service `VERIFIED`; it still does not prove the Java chain.

## 5. Optional systemd-ready startup

Only after manual health/OCR succeeds, compare the example unit paths with the approved release root. Validate before installing:

```bash
systemd-analyze verify "$AUDIT_AI_RELEASE_ROOT/services/audit-ai/deploy/audit-ai.service.example"
```

If the release root is exactly `/home/admin/audit_ai_runtime/current`, install under the service's final name and start only that known unit:

```bash
sudo install -o root -g root -m 0644 \
  "$AUDIT_AI_RELEASE_ROOT/services/audit-ai/deploy/audit-ai.service.example" \
  /etc/systemd/system/audit-ai.service
sudo systemctl daemon-reload
sudo systemctl enable --now audit-ai.service
systemctl is-active audit-ai.service
curl --fail --silent --show-error http://127.0.0.1:8001/health
```

If the path differs, prepare and review a path-only copy of the example before installation. Do not edit the committed template to embed a machine credential.

## 6. Java → FastAPI → PaddleOCR and durable Project acceptance

For the Java `dev29` process, the protected runtime environment must resolve both service URLs to `http://127.0.0.1:8001`; do not hardcode them in source. Keep the Contract Semantic hierarchy at a 600-second contract-only provider read timeout, a 900-second cumulative provider-call budget, a 960-second outer Java AI read timeout, and a 20-minute review-worker timeout. The global provider read timeout remains 60 seconds for `DOCUMENT_ROLE` and Budget operations. The cumulative provider-call budget is not an absolute FastAPI HTTP wall-clock deadline; the Java margin covers FastAPI preparation, normalization, typed validation, correction processing, and serialization. Keep `AUDIT_OCR_READ_TIMEOUT_MS=120000` unchanged. Confirm only the expected non-secret values without printing unrelated variables:

```bash
(
  set -a
  . /home/admin/audit_ai_runtime/audit-ai.env
  set +a
  /opt/python/venvs/audit-ai/bin/python - <<'PY'
import os

expected = "http://127.0.0.1:8001"
for name in ("AUDIT_OCR_BASE_URL", "AUDIT_AI_BASE_URL"):
    value = os.environ.get(name)
    print(f"{name}={'MATCH' if value == expected else 'MISMATCH_OR_NOT_SET'}")
    if value != expected:
        raise SystemExit(2)
expected_timeouts = {
    "AUDIT_AI_READ_TIMEOUT_MS": "960000",
    "AUDIT_OCR_READ_TIMEOUT_MS": "120000",
    "AUDIT_MODEL_READ_TIMEOUT_SECONDS": "60",
    "AUDIT_CONTRACT_MODEL_READ_TIMEOUT_SECONDS": "600",
    "AUDIT_MODEL_TOTAL_BUDGET_SECONDS": "900",
    "AUDIT_CONTRACT_REVIEW_WORKER_TIMEOUT": "PT20M",
}
for name, expected_value in expected_timeouts.items():
    value = os.environ.get(name)
    print(f"{name}={'MATCH' if value == expected_value else 'MISMATCH_OR_NOT_SET'}")
    if value != expected_value:
        raise SystemExit(2)
PY
)
```

Start the approved Java feature commit through the existing JAR-first deployment process. Do not modify Nginx yet. Using the authenticated Project UI/API and a new immutable idempotency key per operation:

1. Create a Project before Contract creation and upload an approved ZIP package.
2. Confirm the existing secure ZIP pipeline persists and classifies children; do not use an `OTHER` ReviewDocument path.
3. Confirm a useful-text PDF shows `PDFBox` provenance and caused no OCR event.
4. Confirm DOCX shows Apache POI provenance and caused no OCR event.
5. Confirm a scanned/blank-text PDF and PNG/JPG/JPEG show `PaddleOCR 3.7.0` provenance, non-empty persisted parsed text, page count, confidence when supplied, and duration.
6. Download an original file through `/api/v1/projects/{projectId}/files/{fileId}/content` and compare its SHA-256 with the uploaded source.
7. Record Project ID, root ZIP file ID, archive-child file ID, OCR file ID, Request ID, storage key, and DB provenance row. Do not record document text in general logs.
8. Restart only the known Java and audit-ai services through the approved process. Reopen Project detail, original file content, archive children, and parsed OCR text using the same durable IDs.
9. Retry an uncertain upload with the same idempotency key; verify it rediscoveres the existing outcome rather than publishing another durable file.

Inspect `PROJECT_FILE` with the dedicated application/DBA session:

```sql
SELECT ID, PROJECT_ID, PARENT_ARCHIVE_FILE_ID, NORMALIZED_RELATIVE_PATH,
       INGESTION_STATUS, PARSE_STATUS, STORAGE_KEY,
       PARSE_METHOD, PARSE_ENGINE, PARSE_CONFIDENCE,
       PAGE_COUNT, PARSE_DURATION_MS
FROM PROJECT_FILE
WHERE PROJECT_ID = '<recorded-project-uuid>'
ORDER BY CREATED_AT, ID;
```

Expected OCR rows have `PARSE_STATUS='PARSED'`, `PARSE_METHOD='OCR'`, and `PARSE_ENGINE='PADDLEOCR_3.7.0'`. Useful-text PDF rows use `PDF_TEXT`; DOCX rows use `DOCX_TEXT`. No claim is complete until restart/reopen succeeds on the actual Kylin FileStorage.

Correlate the Java technical event with the request without exposing source text:

```bash
journalctl -u audit-backend.service -u audit-ai.service --since '15 minutes ago' --no-pager \
  | grep -E 'OCR_CALL_COMPLETE|OCR_EXTRACT|requestId=|documentId=|engine=|durationMs='
```

The Java event must include `operation=OCR_EXTRACT`, the recorded Request ID/document ID, engine, duration, and success. It must not contain the original filename or recognized text.

## 7. Conditional real Qwen acceptance

Run this section only when the safe probe reports the three consumed model settings as `SET`. Do not use local/fake responses as evidence. First use the Contract and Budget UI actions against real Project/ReviewTask state and verify each response reports its distinct operation, actual model identifier, Request ID, and duration.

Acceptance boundaries:

- Contract: only `CONTRACT_TEXT_ANALYSIS`; hints cite persisted ProjectFile evidence.
- Budget: only `BUDGET_SEMANTIC_ANALYSIS`; item descriptions/categories only.
- Neither model result modifies F01/F02/F04 amount findings, quantity × unit-price calculations, differences, or thresholds.
- Java deterministic findings remain identical before and after either AI call.

Correlate only safe metadata:

```bash
journalctl -u audit-backend.service -u audit-ai.service --since '15 minutes ago' --no-pager \
  | grep -E 'AI_SEMANTIC_ANALYSIS_COMPLETE|model request completed|CONTRACT_TEXT_ANALYSIS|BUDGET_SEMANTIC_ANALYSIS|requestId|model=|duration'
```

If configuration was reported as present but the real call fails, record `NOT_VERIFIED` with the safe HTTP/error category and Request ID; never print response content or credentials. If required settings are absent, record `BLOCKED_BY_MODEL_CONFIGURATION` and skip calls.

## 8. Evidence and result classification

Record exact commit, command, environment, timestamp, exit code, safe response summary, Project/file IDs, schema query output, and restart/reopen result. Use these statuses independently:

- `TARGET_RUNTIME_READY`: supplied 29 Python/Paddle/FastAPI/model baseline and prior standalone OCR.
- `REMOTE_E2E_PENDING_EXECUTION`: this branch has not completed the relevant 29 flow.
- `VERIFIED_ON_DEV29`: the exact command and observed result are attached for that flow.
- `BLOCKED_BY_MODEL_CONFIGURATION`: safe model probe lacks a required setting.
- `NOT_VERIFIED`: attempted but evidence is incomplete or the safe call failed.

Never promote a contract test, fake adapter, configuration file, or UI rendering to real Paddle/Qwen evidence.

## 9. Non-destructive rollback

If manual Uvicorn is running, stop it with its foreground terminal's normal interrupt. If the known systemd unit was installed by this runbook:

```bash
sudo systemctl stop audit-ai.service
systemctl is-active audit-ai.service
```

Restore the previously approved Java/release symlink or artifact through the existing deployment procedure, then start only the known prior services. Do not delete ProjectFile storage or business rows. Do not reverse V017 destructively: its columns are nullable and the prior application ignores them. Preserve the failed release's journal, restricted smoke-response file, DB query output, and exact commit for diagnosis.
