# audit-ai

Independent Python 3.11 FastAPI service for PaddleOCR extraction and two bounded textual-model operations. It does not embed Python native dependencies in Java and does not decide amounts, quantity × unit-price results, differences, or thresholds.

## Verification status

| Scope | Status |
| --- | --- |
| Config, schemas, chunking, error mapping, lazy adapter, safe logs | `LOCAL_CONTRACT_VERIFIED` on repository-local Python 3.11.16; Python 3.14 is supplemental test evidence only |
| 29 Python 3.11.16, FastAPI 0.141.1, Uvicorn 0.52.1, PaddlePaddle 3.3.1, PaddleOCR 3.7.0, OpenCV 4.10.0, model files and prior standalone OCR | `TARGET_RUNTIME_READY` from the supplied 29 evidence |
| This branch's Paddle constructor/result shape and Java → FastAPI → PaddleOCR flow on 29 | `REMOTE_E2E_PENDING_EXECUTION` |
| This branch's loopback PaddleOCR and Qwen calls | `LOCAL_RUNTIME_VERIFIED` on 2026-09-01 through the real Paddle adapter, `qwen-plus`, Java redactor and Java FastAPI adapter; authenticated Java Controller/browser flow remains separately pending |

Local Python 3.14 is not a deployment target and is not Paddle evidence. Do not change the Python 3.11 target or the working 29 OCR environment to accommodate it.

## Runtime boundaries

- `audit_ai.main` is import-safe: Paddle is loaded only by the first real OCR extraction.
- OCR accepts bounded multipart uploads, copies them to a private temporary file, then calls the adapter by path.
- The Paddle adapter requires exactly one CPU thread and MKLDNN disabled.

### Rotated scans and embedded pictures

OCR normalizes EXIF orientation and transparent backgrounds per image/frame, and
reads PDF pages individually. When the local `PP-LCNet_x1_0_doc_ori` model is
available, a confident direction classification selects one full-resolution OCR
pass. Uncertain direction falls back to four bounded preview orientations. The
adapter chooses one result rather than concatenating competing directions, and
only accepts small deskew corrections when recognition quality improves. Source
files and evidence locators are unchanged. Native prediction and lazy result
consumption share a single lock, including across all orientation candidates.

The optional model directory is `services/audit-ai/models/PP-LCNet_x1_0_doc_ori`,
or `AUDIT_OCR_ORIENTATION_MODEL_DIR`. Include `inference.json`,
`inference.pdiparams`, and `inference.yml` in offline releases and their manifest;
never download weights while processing an upload. The official Paddle model
archive used for the test release has SHA-256
`282337df5c41f7cdf8dacd5acf71fddfdc10218399f4b318463c17f4eae96c97`.
`AUDIT_OCR_ORIENTATION_ENABLED=false` retains the old direction behavior for
controlled comparison. No Paddle/OpenCV core upgrades are required.

`AUDIT_OCR_PROCESS_ISOLATION=true` opts Linux into a persistent single OCR
child, avoiding native GIL holds on the HTTP process. The child installs
`PR_SET_PDEATHSIG=SIGKILL` before initialization and verifies its original
parent on both sides of that installation. Close and timeout reap that exact
child, and uncertain work is never automatically replayed. This worker uses
native threads and must not spawn subprocesses; Linux does not provide the
Windows Job descendant guarantee here. Existing Linux environments retain
their previous process mode unless explicitly enabled and platform-tested.

Direction correction cannot recover illegible, obscured or severely distorted
source content. Mixed independent regions within one raster may need region
processing; embedded Office images and separate PDF pages are corrected
independently. Regression samples live in `tools/create-rotation-fixtures.py`;
live acceptance uses `tools/test-rotation-runtime.py` and
`tools/test-rotation-application.py` with generated materials only.
- The model gateway exposes contract-text and budget-semantic operations, not a generic prompt endpoint.
- Text sent to the model may assist semantics only. Java `BigDecimal` and deterministic rules remain authoritative for money.
- Runtime logs use allowlisted provenance. They do not log documents, prompts, model responses, credentials, or authorization headers.

## Dependency policy

`pyproject.toml` and `requirements-runtime.txt` pin the application web dependencies to the validated 29 baseline. PaddlePaddle 3.3.1, PaddleOCR 3.7.0, and OpenCV 4.10.0 remain environment-managed because they already exist and have native/runtime coupling on 29. Deployment must first run the read-only probes; it must not replace the venv, core OCR packages, models, or caches by default.

Contract tests can run in an isolated environment without Paddle:

```text
<isolated-python> -m pytest services/audit-ai/tests -q
```

Passing these tests does not verify real Paddle or Qwen.

## Budget and procurement under local-model input limits

Material field extraction bounds each source envelope by both characters and
UTF-8 bytes. Defaults are `AUDIT_MATERIAL_CHUNK_CHARS=3200`,
`AUDIT_MATERIAL_CHUNK_BYTES=8000`, `AUDIT_MATERIAL_CONTEXT_CHARS=96`, and
`AUDIT_MATERIAL_OUTPUT_TOKENS=1536`. These are source-envelope limits, **not**
model token counts: system prompts, JSON and reserved output also consume the
provider context window. Preserve existing endpoint, key and model settings.

Explicit input-limit failures (HTTP 413, or recognized context errors in HTTP
400/422 or an HTTP-200 error envelope) split only the failed chunk. Truncated
output is discarded before the same bounded recovery. Auth, allowlist, rate,
protocol, timeout and server errors do not become blind smaller retries.
There are finite depth, minimum-span, call and time budgets; exceeding them
fails the whole operation, never returns a complete-looking partial success.

Paragraph/newline boundaries are preferred; long-line boundaries carry a small
verbatim window from the **same** source locator. Context-only facts are not
added as new rows. Source excerpts remain checked against supplied original
text; conflicts and independent budget row identities remain distinct.
Structured budget semantic rows stay intact, with exact item-ID coverage
validation. One atomic semantic row that cannot fit fails explicitly.

F10 tender compliance sends complete JSON envelopes with local source locators
and relevant rule findings, instead of cutting serialized JSON. Full text not
covered by source segments is preserved as non-citable supplementary context.
Adaptive calls keep evidence validation restricted to sources actually sent.
These bounded windows do not guarantee arbitrarily distant cross-page or
cross-file reasoning; real model quality still requires representative checks.

Validated material-extraction chunks can be reused on a retry with the same
material identity, content, source locators and gateway configuration. Default
reuse is in process memory. Optional `AUDIT_MATERIAL_CHECKPOINT_ROOT` enables
restart reuse in an absolute private directory owned by the AI service account
(0700 directory / 0600 records on Linux). Do not use a shared or public directory.
This opt-in directory contains **sensitive extracted fields and excerpts**, not
raw documents; it must not be exported with technical diagnostics. Completed
operations delete their records. Failed-operation records expire after
`AUDIT_MATERIAL_CHECKPOINT_TTL_SECONDS=900`, become unusable at expiry, and are
removed lazily on subsequent cache activity. Entry/byte limits bound retention.
Missing, expired, corrupt or evicted records trigger recomputation. Temporary
reuse is disposable and never replaces the existing DM8 task/result facts.
This does not introduce a new job scheduler or automatic restart of failed jobs.

Local socket smoke (synthetic provider, no external network or database):

```text
PYTHONPATH=services/audit-ai/src <python3.11> tools/test-material-model-limits-local.py
```

Logs include split reasons, chunk sizes/counts and validated reuse events. The
allowlisted journal exporter keeps those metadata fields without exporting
prompts, model content, credentials or excerpts.

## Configuration

The service reads only named environment variables. Secret values must come from the protected runtime environment file and remain absent from Git and logs.

| Variable | Purpose |
| --- | --- |
| `AUDIT_OCR_CPU_THREADS` | Must be `1` on 29 |
| `AUDIT_OCR_ENABLE_MKLDNN` | Must be `false` on 29 |
| `AUDIT_AI_MAX_UPLOAD_BYTES` | Maximum inbound OCR body |
| `AUDIT_AI_TEMP_ROOT` | Optional private temporary root |
| `AUDIT_MODEL_BASE_URL` | OpenAI-compatible API base; gateway appends `/chat/completions` |
| `AUDIT_MODEL_API_KEY` | Secret bearer credential |
| `AUDIT_MODEL_NAME` | Requested model identifier |
| `AUDIT_SEMANTIC_STRUCTURED_TRANSPORT` | Contract Semantic transport: `PROMPT_ONLY` (default), `OPENAI_COMPAT_STRUCTURED`, or `OLLAMA_NATIVE_STRUCTURED`; select a structured mode only after the exact provider/model passes the repository L1-L4 probe |
| `AUDIT_MODEL_CONNECT_TIMEOUT_SECONDS` | Per-attempt model connect timeout; default `5` |
| `AUDIT_MODEL_READ_TIMEOUT_SECONDS` | Global per-attempt provider read timeout for `DOCUMENT_ROLE` and Budget semantic operations; default `60` |
| `AUDIT_MATERIAL_MODEL_READ_TIMEOUT_SECONDS` | Budget/procurement material field extraction only; default and maximum `180`. Capped by remaining material budget. |
| `AUDIT_CONTRACT_MODEL_READ_TIMEOUT_SECONDS` | Per-attempt provider read timeout used only by `CONTRACT_TEXT_ANALYSIS`; default `600` |
| `AUDIT_MODEL_TOTAL_BUDGET_SECONDS` | Cumulative provider-call budget for one contract semantic operation; default `900` |
| `AUDIT_MODEL_CHUNK_CHARS` | Maximum characters per bounded chunk |
| `AUDIT_MODEL_MAX_CHUNKS` | Maximum chunks per request |
| `AUDIT_AI_DOCUMENT_ROLE_ENABLED` | Enables the bounded `DOCUMENT_ROLE` route; defaults to `false` and does not activate a production provider route |

The Java `dev29` process points both `AUDIT_OCR_BASE_URL` and `AUDIT_AI_BASE_URL` at this service. The URL remains deployment configuration, never application-source configuration.

Budget/procurement material field extraction uses one shared model admission slot.
Java durably records a 900-second extraction deadline in the DM8 ledger; reopening
an operation does not reset it. Waiting for admission (bounded at 1800 seconds)
and the preceding document/OCR stage are separate from that extraction deadline.
Each provider read waits at most 180 seconds, capped by the remaining extraction
budget. Read timeout is a transport inactivity bound, not proof that the remote
provider stopped executing. Late results are fenced and cannot be published.
Known call/deadline/queue timeouts terminate the local job without partial results;
temporary uploaded files are removed only after the terminal transaction commits.
Cleanup failures remain discoverable for the scheduled cleanup pass. Minimal task
and audit metadata remain to prevent uncertain operations from being silently
replayed. Other unknown outcomes are not automatically deleted or retried.
Deploy V057 with the schema guard before starting the updated Java application.

Analysis, semantic correction, transport retry, and synthesis calls in one
contract semantic operation share `AUDIT_MODEL_TOTAL_BUDGET_SECONDS`. Before each
provider call, the contract-specific read timeout is capped by the remaining
cumulative provider-call budget; no new provider call starts after exhaustion.
This budget is not an absolute FastAPI HTTP wall-clock deadline. The Java AI read
timeout must remain above it to allow chunk/evidence preparation, normalization,
typed validation, correction processing, and response serialization.

## HTTP routes

- `GET /health`
- `GET /ai/v1/materials/policy` (opaque fingerprint only; no model configuration)
- `POST /ai/v1/materials/extract-fields` (budget/procurement grounded candidates;
  optional frozen `policyId`, remaining `callLimit` and `timeLimitMillis`; returns
  `callCount` so Java can persist cumulative operation budgets in DM8)
- `POST /ai/v1/agent/procurement-tender-compliance` (F10 bounded local/cross-clause
  analysis and evidence-validated review of selected original qualifications;
  an incomplete reconciliation never becomes a clean result)
- `POST /ai/v1/ocr/extract`
- `POST /ai/v1/agent/contract-text-analysis`
- `POST /ai/v1/agent/budget-semantic-analysis`
- `POST /ai/v1/agent/document-role` (disabled by default; strict non-authoritative proposal over caller-supplied immutable parse segments)

The exact safe 29 preflight, startup, smoke, durable ProjectFile checks, and rollback sequence is in [the dev29 runbook](../../docs/runbook/ai-ocr-project-intake-dev29.md).
