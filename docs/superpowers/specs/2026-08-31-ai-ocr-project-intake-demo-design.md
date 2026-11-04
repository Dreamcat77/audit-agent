# AI/OCR Project Intake Demo Design

## 1. Goal and acceptance boundary

This change delivers one thin, recoverable path from a Project package upload to reusable Project
materials, deterministic Java review, optional textual AI assistance, and visible extraction
provenance. `Project` remains the aggregate root. Existing `ProjectFileIngestionService`,
`FileStoragePort`, archive security, idempotency, and recovery paths remain the only intake
pipeline.

The implementation is complete only when local Java, frontend, and Python contract tests pass. The
representative 29 runtime is already ready and has prior standalone PaddleOCR evidence, but the new
Java -> FastAPI -> PaddleOCR path remains `REMOTE_E2E_PENDING_EXECUTION` until it is run there.
Qwen is `BLOCKED_BY_MODEL_CONFIGURATION` if a safe SET/NOT_SET probe shows that its endpoint or key
is absent.

## 2. Non-negotiable invariants

- Deployment target for `services/audit-ai` is Python 3.11. Local Windows Python 3.14 is not a
  release target and will not be modified or used to claim Paddle compatibility.
- PaddleOCR is an infrastructure adapter that is lazy-loaded. Importing config, schemas, routes,
  chunking, error mapping, and safe logging must not import or initialize Paddle.
- On 29, Paddle must run with `AUDIT_OCR_CPU_THREADS=1` and
  `AUDIT_OCR_ENABLE_MKLDNN=false`. The existing virtualenv, model cache, and core OCR packages are
  not upgraded or replaced.
- Fake adapters are test/local seams only. Their output is never reported as real PaddleOCR or
  Qwen evidence.
- V017 is additive. V010 and V011 remain byte-for-byte unchanged.
- Java sends OCR files as reopenable multipart streams from `FileStoragePort.open(storageKey)`.
  The production OCR path has no `readAllBytes()`, whole-file `byte[]`, or Base64 representation.
- Java `BigDecimal` and deterministic rules retain exclusive authority over all amount, quantity x
  unit-price, difference, and threshold conclusions. AI outputs are non-authoritative textual
  assistance and cannot overwrite F01, F02, or F04 results.
- Contract and budget AI are separate application use cases and separate ports. There is no generic
  `ask(prompt)` API.
- UI stages derive only from the selected-file/client request state or refreshed durable server
  state. There are no timers that simulate OCR/AI progress.
- Logs may contain operation, request ID, model/engine identifier, document/file ID, outcome, and
  duration. They must not contain document text, prompts, credentials, authorization headers, or
  environment values.

## 3. Product flow

### 3.1 Project landing intake

The Project list page makes “上传项目资料包” the primary entry. A user selects one ZIP, the client
creates a blank Project with one retained create-operation key, then uploads the same selected file
through the existing Project upload endpoint with one retained upload-operation key. An uncertain
request preserves its key and selected file until a durable GET proves the terminal state or the
user explicitly abandons it.

Displayed stages are factual:

1. file selected locally;
2. create request in flight;
3. upload request in flight;
4. durable Project and ProjectFile state refreshed;
5. navigation to the actual Project detail.

“新建空白项目” remains a secondary action. Project detail labels its existing upload control as
“补充项目资料” and shows filename, category, ingestion state, parse state, extraction method,
engine, confidence when available, page count, duration, and parsed/OCR text.

### 3.2 Downstream reuse

Contract review reached from a Project uses the already durable Project files. It keeps the current
Project material viewer and does not expose a primary duplicate-upload path. ReviewSession remains
a separate contract-owned material preparation workflow for non-Project entry points.

## 4. Document extraction decision

The ingestion service selects extraction by verified file type:

| Input | Decision | Method |
| --- | --- | --- |
| DOCX | existing POI parser | `DOCX_TEXT` |
| PDF with useful text | PDFBox only; OCR is not called | `PDF_TEXT` |
| blank/obviously unusable PDF text | OCR | `OCR` |
| PNG/JPG/JPEG | OCR | `OCR` |
| unsupported verified type | existing unsupported state | none |

`PdfTextUsefulness` is deterministic and tested. It normalizes Unicode whitespace/control
characters, counts letters/digits and visible characters, and rejects empty/control-only or
obviously low-information output. It does not use `text != null` as the decision. Thresholds are
named constants and tests cover useful Chinese text, useful Latin text, whitespace-only text,
control-character noise, and too-short noise.

Extraction returns text plus provenance. Failures use the existing failed parse lifecycle and safe
error mapping; OCR unavailability never silently becomes a successful empty parse.

## 5. Durable provenance

After confirming the current domain/mapper/repository mapping, V017 adds nullable columns to
`PROJECT_FILE`:

- `PARSE_METHOD VARCHAR(32)` (`PDF_TEXT`, `DOCX_TEXT`, or `OCR`);
- `PARSE_ENGINE VARCHAR(100)`;
- `PARSE_CONFIDENCE DECIMAL(5,4)`;
- `PAGE_COUNT INTEGER`;
- `PARSE_DURATION_MS BIGINT`.

The columns are nullable so existing rows remain valid. Domain and persistence adapters validate
non-negative confidence/page/duration values and map them in both directions. The API exposes the
same provenance. Parsed content reads return the persisted `ProjectFile.parsedText`; they verify the
stored file can still be reopened but do not rerun PDF/OCR extraction after refresh or restart.

## 6. OCR boundary

Java owns a framework-free reopenable `OcrDocumentSource` with `openStream()` and known content
length. `OcrRequest` carries file ID, safe filename, media type, and that source. The FastAPI adapter
wraps it as a multipart `Resource` whose `getInputStream()` reopens storage on every call.

`services/audit-ai` exposes:

- `GET /health`;
- `POST /ai/v1/ocr/extract` (multipart file);
- `POST /ai/v1/agent/contract-text-analysis`;
- `POST /ai/v1/agent/budget-semantic-analysis`.

The OCR route streams the upload to a bounded temporary file because Paddle accepts filesystem
input. `PaddleOcrAdapter` imports and initializes Paddle only on its first real extraction under a
lock. Configuration rejects unsafe 29 values: CPU threads must be one and MKLDNN must be false.
The exact installed PaddleOCR 3.7.0 constructor and prediction response shape must be probed on 29
before the remote smoke; until then adapter integration is implemented and locally contract-tested,
while real initialization is not claimed locally.

## 7. Model and use-case boundaries

The Python model gateway implements OpenAI-compatible Chat Completions with externalized provider,
base URL, API key, model, connect/read timeouts, and bounded chunk sizes. It emits validated
structured JSON. The service never logs the prompt, source text, response body, API key, or
authorization header.

Java provides two explicit use cases:

- `ContractTextAnalysisUseCase` / `ContractTextAnalysisPort` consumes Project-owned parsed text and
  returns summaries, textual consistency hints, evidence references, and model provenance.
- `BudgetSemanticAnalysisUseCase` / `BudgetSemanticAnalysisPort` consumes budget item descriptions
  and returns semantic categorization/hints plus model provenance.

Neither request grants the model authority to calculate or decide amounts. Contract responses are
presented under “AI辅助分析”, beside but never merged into “确定性规则 · Java BigDecimal”. Budget
responses identify “AI语义来源” separately from “确定性金额来源”.

## 8. Configuration and deployment

The Java base URLs remain externalized through `AUDIT_OCR_BASE_URL` and `AUDIT_AI_BASE_URL`.
The Python service defaults to port 8001 through its run command, not hardcoded application source.
Its dependency declaration targets Python 3.11 and the verified 29 baseline: FastAPI 0.141.1,
Uvicorn 0.52.1, PaddlePaddle 3.3.1, PaddleOCR 3.7.0, and OpenCV 4.10.0. Core OCR packages may be
documented as target-environment managed rather than upgraded by the application install command.

The runbook provides read-only preflight, safe model SET/NOT_SET probes, port inspection, manual
Uvicorn smoke, Java-to-OCR checks, restart/reopen checks, and a systemd-ready unit template. It does
not print the environment file, interrupt unknown processes, modify Nginx, alter system Python, or
apply destructive DM8/Redis operations.

## 9. Verification and status language

Local verification:

- `backend/.\\mvnw.cmd clean verify`;
- all frontend Node test files and `npm.cmd run build`;
- Python 3.11-compatible unit/contract tests that do not require Paddle import;
- `git diff --check` and secret/source scans.

29 verification, when access is available, uses the exact feature commit and the dedicated
`/opt/python/venvs/audit-ai` environment. It verifies Paddle API signatures, real OCR, Java ->
FastAPI -> PaddleOCR, actual ProjectFile persistence/read/display, create-only FileStore semantics,
and optional Qwen calls if safe probes show configuration exists.

Until that remote run, final status is:

- `IMPLEMENTATION_COMPLETE` only after all local gates pass;
- `TARGET_RUNTIME_READY` for the recorded 29 Python/Paddle/FastAPI/model-file baseline and prior
  standalone OCR execution;
- `REMOTE_E2E_PENDING_EXECUTION` for this new chain;
- `BLOCKED_BY_MODEL_CONFIGURATION` only when Qwen configuration is safely proven absent.
