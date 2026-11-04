# AI/OCR Project Intake Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a recoverable Project-package-first flow that reuses durable Project files for OCR and textual AI assistance while preserving deterministic Java money decisions.

**Architecture:** Extend the existing Project aggregate, ingestion service, secure archive reader, and FileStoragePort. Add nullable parse provenance to ProjectFile, reopen storage for streaming OCR, host PaddleOCR and an OpenAI-compatible gateway in an independent lazy-loaded Python 3.11 service, and expose two use-case-specific Java AI boundaries. UI state is derived from client requests and refreshed durable state only.

**Tech Stack:** Java 17, Spring Boot 3.5.16, MyBatis/DM8, PDFBox, Apache POI, React/Vite/TypeScript, Python 3.11, FastAPI 0.141.1, Uvicorn 0.52.1, PaddlePaddle 3.3.1, PaddleOCR 3.7.0, OpenCV 4.10.0.

**Spec:** `docs/superpowers/specs/2026-08-31-ai-ocr-project-intake-demo-design.md`

## Global Constraints

- Never modify system Python, `/usr/bin/python3`, the 29 OCR virtualenv, or existing model caches.
- The Java OCR production path has no `readAllBytes()`, whole-file `byte[]`, or Base64.
- V017 is additive; V010 and V011 are immutable.
- Project remains the aggregate root and all upload/archive/recovery work uses existing services.
- Paddle is lazy-loaded; local tests do not import or initialize it.
- Paddle runtime settings are CPU threads `1` and MKLDNN `false`.
- AI produces textual assistance only; Java `BigDecimal` remains authoritative for money.
- Contract and budget AI have separate application use cases and ports.
- No fake progress, fake accuracy, fake OCR evidence, or fake Qwen evidence.
- Every task runs its targeted tests before its independent commit.

---

### Task 1: Align product and architecture documentation

**Files:**
- Create: `docs/superpowers/specs/2026-08-31-ai-ocr-project-intake-demo-design.md`
- Create: `docs/superpowers/plans/2026-08-31-ai-ocr-project-intake-demo.md`
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/adr/ADR-009-project-intake-and-pm-product-alignment.md`

**Produces:** One current design authority that supersedes ADR-009's old Project/ZIP gate without rewriting migration history.

- [ ] **Step 1: Replace stale current-state claims**

Record that Project/ZIP is implemented in the existing aggregate pipeline, this approved slice may add real OCR/AI adapters, and F04 exists without authorizing F05-F15.

- [ ] **Step 2: Record target runtime facts without secret values**

Use exactly these status labels: `TARGET_RUNTIME_READY`, `REMOTE_E2E_PENDING_EXECUTION`, and conditional `BLOCKED_BY_MODEL_CONFIGURATION`.

- [ ] **Step 3: Verify documentation integrity**

Run: `git diff --check`

Expected: exit 0 and no output.

- [ ] **Step 4: Commit**

```powershell
git add AGENTS.md README.md docs/architecture.md docs/adr/ADR-009-project-intake-and-pm-product-alignment.md docs/superpowers/specs/2026-08-31-ai-ocr-project-intake-demo-design.md docs/superpowers/plans/2026-08-31-ai-ocr-project-intake-demo.md
git commit -m "docs: align project intake AI OCR demo architecture"
```

### Task 2: Make Project package upload the primary landing flow

**Files:**
- Modify: `frontend/src/features/project/components/ProjectListPage.tsx`
- Modify: `frontend/src/features/project/useProjectList.ts`
- Modify: `frontend/src/features/project/projectListViewModel.ts`
- Modify: `frontend/src/features/project/projectListViewModel.test.ts`
- Create: `frontend/src/features/project/projectIntakeOperation.ts`
- Create: `frontend/src/features/project/projectIntakeOperation.test.ts`

**Consumes:** Existing `ProjectDataSource.createBlank`, `upload`, retained operation-key utilities, and durable Project GET/list projections.

**Produces:** `ProjectIntakeStage = 'IDLE' | 'FILE_SELECTED' | 'CREATING_PROJECT' | 'UPLOADING_PACKAGE' | 'REFRESHING_DURABLE_STATE' | 'COMPLETE' | 'UNCERTAIN'` and a primary ZIP intake card.

- [ ] **Step 1: Write failing operation-state tests**

```typescript
test('one uncertain package intake retains both operation identities and selected file', async () => {
  // create succeeds, upload transport becomes uncertain, retry uses the same upload key/file
});

test('progress exposes request or durable facts and has no timer transition', () => {
  assert.deepEqual(stageFromFacts({ uploadPending: true }), 'UPLOADING_PACKAGE');
});
```

- [ ] **Step 2: Verify RED**

Run: `node --experimental-strip-types --test src/features/project/projectIntakeOperation.test.ts src/features/project/projectListViewModel.test.ts`

Expected: FAIL because the intake operation/stage does not exist.

- [ ] **Step 3: Implement the minimal Project-first operation and UI**

The primary action accepts `.zip`, calls `createBlank` once, uploads through the existing endpoint, refreshes durable Project state, and navigates to the resulting Project. Keep “新建空白项目” secondary and do not use `setTimeout`.

- [ ] **Step 4: Verify GREEN and build**

Run: `node --experimental-strip-types --test src/features/project/projectIntakeOperation.test.ts src/features/project/projectListViewModel.test.ts src/features/project/data/httpProjectDataSource.test.ts`

Run: `npm.cmd run build`

Expected: all tests pass and Vite build exits 0.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/features/project
git commit -m "feat(project): front-load project package intake"
```

### Task 3: Persist additive ProjectFile extraction provenance

**Files:**
- Create: `db/dm8/V017__project_file_parse_provenance.sql`
- Modify: `db/dm8/README.md`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectFile.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectFileParseProvenance.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectFileParseMethod.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectFilePersistenceRow.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectFileMapper.java`
- Modify: `backend/src/main/resources/mappers/dm8/ProjectFileMapper.xml`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectRepositories.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/dm8/ProjectIntakeDm8SqlStructureTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/domain/project/ProjectDomainTest.java`

**Produces:** `ProjectFileParseProvenance(method, engine, confidence, pageCount, durationMs)` with nullable persistence/API mapping.

- [ ] **Step 1: Write failing domain and SQL mapping tests**

```java
assertThatThrownBy(() -> new ProjectFileParseProvenance(
        ProjectFileParseMethod.OCR, "PADDLEOCR_3.7.0", new BigDecimal("1.0001"), 1, 10))
    .isInstanceOf(IllegalArgumentException.class);
```

The SQL test loads V017 and asserts only additive `ALTER TABLE PROJECT_FILE ADD ...` statements; it also asserts V010/V011 hashes remain unchanged in the task diff.

- [ ] **Step 2: Verify RED**

Run: `backend\.\mvnw.cmd -Dtest=ProjectDomainTest,ProjectIntakeDm8SqlStructureTest test`

Expected: FAIL because provenance types and V017 are absent.

- [ ] **Step 3: Implement migration, domain, mapper, repository, and API fields**

Map `PARSE_METHOD`, `PARSE_ENGINE`, `PARSE_CONFIDENCE`, `PAGE_COUNT`, and `PARSE_DURATION_MS` exactly in result maps, selects, inserts, and updates. Existing rows map to empty provenance.

- [ ] **Step 4: Verify GREEN**

Run: `backend\.\mvnw.cmd -Dtest=ProjectDomainTest,ProjectIntakeDm8SqlStructureTest,Dm8ProjectRepositoriesTest test`

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```powershell
git add db/dm8 backend/src/main/java/com/fuquan/audit/contract/domain/project backend/src/main/java/com/fuquan/audit/integration/dm8 backend/src/main/resources/mappers/dm8/ProjectFileMapper.xml backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java backend/src/test/java/com/fuquan/audit
git commit -m "feat(dm8): persist project parse provenance"
```

### Task 4: Replace whole-file OCR requests with reopenable streaming

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/review/application/port/OcrDocumentSource.java`
- Modify: `backend/src/main/java/com/fuquan/audit/review/application/port/OcrRequest.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/ocr/fastapi/FastApiOcrAdapter.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/ocr/fake/FakeOcrAdapter.java`
- Modify: `backend/src/test/java/com/fuquan/audit/review/application/port/OcrRequestTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/ocr/fastapi/FastApiOcrAdapterTest.java`

**Produces:**

```java
public interface OcrDocumentSource {
    InputStream openStream() throws IOException;
    long contentLength();
}
```

- [ ] **Step 1: Write a failing reopen test**

Use a source that counts `openStream()` calls and returns a new stream each time. Exercise the actual multipart request and assert transmitted bytes, filename, media type, and request ID without any `byte[]` field on `OcrRequest`.

- [ ] **Step 2: Verify RED**

Run: `backend\.\mvnw.cmd -Dtest=OcrRequestTest,FastApiOcrAdapterTest test`

Expected: FAIL against the current byte-array request.

- [ ] **Step 3: Implement the streaming resource**

Use a Spring `AbstractResource` that delegates every `getInputStream()` to the source and returns the known content length and safe filename. Do not buffer or Base64 encode.

- [ ] **Step 4: Verify GREEN and scan the production path**

Run: `backend\.\mvnw.cmd -Dtest=OcrRequestTest,FastApiOcrAdapterTest,FastApiTransportErrorMappingTest test`

Run: `rg -n "readAllBytes|ByteArrayResource|Base64|byte\[\]" backend/src/main/java/com/fuquan/audit/integration/ocr backend/src/main/java/com/fuquan/audit/review/application/port/OcrRequest.java`

Expected: tests pass; scan has no production match.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/fuquan/audit/review/application/port backend/src/main/java/com/fuquan/audit/integration/ocr backend/src/test/java/com/fuquan/audit/review backend/src/test/java/com/fuquan/audit/integration/ocr
git commit -m "refactor(ocr): stream reopenable stored documents"
```

### Task 5: Add tested PDF usefulness and OCR fallback selection

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/PdfTextUsefulness.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ProjectDocumentExtraction.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ProjectPdfTextParser.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ProjectDocxTextParser.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFilePolicy.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectParsedTextReadService.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/parse/PdfTextUsefulnessTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionServiceTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileContentReadServiceTest.java`

**Consumes:** `FileStoragePort.open(storageKey)` and `OcrPort.extract(OcrRequest)`.

**Produces:** Deterministic PDFBox/DOCX/OCR selection and persisted parsed text/provenance.

- [ ] **Step 1: Write failing branch tests**

```java
assertThat(usefulness.isUseful("  \u0000\r\n\t")).isFalse();
assertThat(usefulness.isUseful("建设工程施工合同，承包范围与工期约定明确。")).isTrue();
```

Add service tests that prove useful PDF text makes zero OCR calls, blank PDF makes one OCR call,
PNG/JPG/JPEG make one OCR call, DOCX makes zero OCR calls, OCR failure becomes parse FAILED, and a
restart read returns persisted text without invoking a parser.

- [ ] **Step 2: Verify RED**

Run: `backend\.\mvnw.cmd -Dtest=PdfTextUsefulnessTest,ProjectFileIngestionServiceTest,ProjectFileContentReadServiceTest test`

Expected: new selection tests fail.

- [ ] **Step 3: Implement minimal extraction selection**

Construct `OcrDocumentSource` directly over `fileStorage.open(storageKey)`. Preserve existing secure archive classification and recovery transaction boundaries.

- [ ] **Step 4: Verify GREEN**

Run: `backend\.\mvnw.cmd -Dtest=PdfTextUsefulnessTest,ProjectFileIngestionServiceTest,ProjectFileContentReadServiceTest,ProjectArchiveStreamingTest,ProjectIntakeRecoveryDeduplicationTest test`

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/fuquan/audit/contract/application/project backend/src/test/java/com/fuquan/audit/contract/application/project
git commit -m "feat(project): add OCR fallback for scanned documents"
```

### Task 6: Create the Python 3.11 FastAPI service and contracts

**Files:**
- Create: `services/audit-ai/pyproject.toml`
- Create: `services/audit-ai/requirements-runtime.txt`
- Create: `services/audit-ai/src/audit_ai/__init__.py`
- Create: `services/audit-ai/src/audit_ai/main.py`
- Create: `services/audit-ai/src/audit_ai/config.py`
- Create: `services/audit-ai/src/audit_ai/schemas.py`
- Create: `services/audit-ai/src/audit_ai/errors.py`
- Create: `services/audit-ai/src/audit_ai/logging.py`
- Create: `services/audit-ai/tests/test_health.py`
- Create: `services/audit-ai/tests/test_config.py`
- Create: `services/audit-ai/tests/test_error_contract.py`

**Produces:** Import-safe application factory and the four approved routes with dependency-injected ports.

- [ ] **Step 1: Write failing API/config tests**

```python
def test_health_does_not_expose_configuration(client):
    response = client.get('/health')
    assert response.json() == {'status': 'UP', 'service': 'audit-ai'}

def test_unsafe_ocr_runtime_settings_are_rejected():
    with pytest.raises(ValueError):
        Settings(ocr_cpu_threads=2, ocr_enable_mkldnn=False)
```

- [ ] **Step 2: Verify RED**

Run: `python -m pytest services/audit-ai/tests/test_health.py services/audit-ai/tests/test_config.py services/audit-ai/tests/test_error_contract.py -q`

Expected: FAIL because the package does not exist.

- [ ] **Step 3: Implement import-safe service foundation**

Do not import `paddle` or `paddleocr` from module import paths used by these tests. Use request-ID middleware and structured safe fields only.

- [ ] **Step 4: Verify GREEN**

Run: `python -m pytest services/audit-ai/tests/test_health.py services/audit-ai/tests/test_config.py services/audit-ai/tests/test_error_contract.py -q`

Expected: all tests pass on the local non-Paddle environment.

- [ ] **Step 5: Commit**

```powershell
git add services/audit-ai
git commit -m "feat(ai): add Python 3.11 FastAPI service contracts"
```

### Task 7: Implement a lazy PaddleOCR 3.7 adapter

**Files:**
- Create: `services/audit-ai/src/audit_ai/ocr/port.py`
- Create: `services/audit-ai/src/audit_ai/ocr/paddle_adapter.py`
- Create: `services/audit-ai/src/audit_ai/ocr/service.py`
- Create: `services/audit-ai/src/audit_ai/ocr/router.py`
- Create: `services/audit-ai/tests/test_ocr_route.py`
- Create: `services/audit-ai/tests/test_paddle_adapter.py`

**Produces:** Lazy, locked initialization and a bounded multipart-to-temp-file OCR flow.

- [ ] **Step 1: Write failing fake-port and lazy-import tests**

Test the real route with a recording OCR port, assert page/text/confidence schema, reject oversized uploads, and assert importing `audit_ai.main` leaves `paddle`/`paddleocr` absent from `sys.modules`.

- [ ] **Step 2: Verify RED**

Run: `python -m pytest services/audit-ai/tests/test_ocr_route.py services/audit-ai/tests/test_paddle_adapter.py -q`

Expected: FAIL because the OCR route and adapter are absent.

- [ ] **Step 3: Implement minimal adapter**

Use `importlib.import_module` inside the first `extract` call, validate the runtime constructor/predict signature, pass only 3.7-supported arguments, and map the 3.x prediction result without a 2.x fallback claim.

- [ ] **Step 4: Verify GREEN without Paddle**

Run: `python -m pytest services/audit-ai/tests/test_ocr_route.py services/audit-ai/tests/test_paddle_adapter.py -q`

Expected: tests pass using injected module/port doubles; no real Paddle claim.

- [ ] **Step 5: Commit**

```powershell
git add services/audit-ai/src/audit_ai/ocr services/audit-ai/tests/test_ocr_route.py services/audit-ai/tests/test_paddle_adapter.py services/audit-ai/src/audit_ai/main.py
git commit -m "feat(ocr): add lazy PaddleOCR infrastructure adapter"
```

### Task 8: Add bounded OpenAI-compatible model gateway

**Files:**
- Create: `services/audit-ai/src/audit_ai/model/gateway.py`
- Create: `services/audit-ai/src/audit_ai/model/chunker.py`
- Create: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/src/audit_ai/main.py`
- Modify: `services/audit-ai/src/audit_ai/schemas.py`
- Create: `services/audit-ai/tests/test_chunker.py`
- Create: `services/audit-ai/tests/test_model_routes.py`
- Create: `services/audit-ai/tests/test_safe_logging.py`

**Produces:** Separate contract/budget routes over one infrastructure gateway with bounded chunks and validated structured responses.

- [ ] **Step 1: Write failing contract, budget, chunk, and log tests**

Assert the two routes build different typed operations, a long source splits at deterministic boundaries, invalid model JSON maps to a stable external-service error, and captured logs contain no source text/prompt/API key.

- [ ] **Step 2: Verify RED**

Run: `python -m pytest services/audit-ai/tests/test_chunker.py services/audit-ai/tests/test_model_routes.py services/audit-ai/tests/test_safe_logging.py -q`

Expected: FAIL because the model gateway is absent.

- [ ] **Step 3: Implement the gateway**

Send OpenAI-compatible `/chat/completions` requests with externalized base URL, key, model, and timeouts. Parse JSON content into the route-specific schema. Do not log bodies.

- [ ] **Step 4: Verify GREEN**

Run: `python -m pytest services/audit-ai/tests -q`

Expected: all Python tests pass without a real endpoint.

- [ ] **Step 5: Commit**

```powershell
git add services/audit-ai
git commit -m "feat(ai): add bounded Qwen-compatible model gateway"
```

### Task 9: Introduce explicit Java contract and budget AI use cases

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCase.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisPort.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisResult.java`
- Create: `backend/src/main/java/com/fuquan/audit/budget/application/ai/BudgetSemanticAnalysisUseCase.java`
- Create: `backend/src/main/java/com/fuquan/audit/budget/application/ai/BudgetSemanticAnalysisPort.java`
- Create: `backend/src/main/java/com/fuquan/audit/budget/application/ai/BudgetSemanticAnalysisResult.java`
- Replace: `backend/src/main/java/com/fuquan/audit/integration/ai/fastapi/FastApiAiReviewAdapter.java`
- Replace: `backend/src/main/java/com/fuquan/audit/integration/ai/fake/FakeAiReviewAdapter.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/http/FastApiContractPaths.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCaseTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/budget/application/ai/BudgetSemanticAnalysisUseCaseTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/ai/fastapi/FastApiAiReviewAdapterTest.java`

**Produces:** Two typed application ports; no `AiReviewPort.ask`-style consumer remains.

- [ ] **Step 1: Write failing boundary tests**

Contract tests prove only Project-owned parsed textual materials are sent. Budget tests prove descriptions/categories are sent without computed monetary conclusions. HTTP tests assert distinct paths and request-ID propagation.

- [ ] **Step 2: Verify RED**

Run: `backend\.\mvnw.cmd -Dtest=ContractTextAnalysisUseCaseTest,BudgetSemanticAnalysisUseCaseTest,FastApiAiReviewAdapterTest test`

Expected: FAIL because the typed use cases do not exist.

- [ ] **Step 3: Implement typed ports/adapters and local explicit-unavailable seams**

Fake/local results must identify themselves as unavailable and cannot contain showcase findings. Keep deterministic review services unchanged.

- [ ] **Step 4: Verify GREEN and money-boundary regression**

Run: `backend\.\mvnw.cmd -Dtest=ContractTextAnalysisUseCaseTest,BudgetSemanticAnalysisUseCaseTest,FastApiAiReviewAdapterTest,ContractReviewServiceTest,BudgetReviewServiceTest,BudgetApprovalPolicyEvaluatorTest test`

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/fuquan/audit/contract/application/ai backend/src/main/java/com/fuquan/audit/budget/application/ai backend/src/main/java/com/fuquan/audit/integration/ai backend/src/main/java/com/fuquan/audit/integration/http/FastApiContractPaths.java backend/src/test/java/com/fuquan/audit
git commit -m "feat(ai): separate contract and budget semantic use cases"
```

### Task 10: Expose typed Java AI APIs and safe observability

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/contract/api/ContractTextAnalysisController.java`
- Create: `backend/src/main/java/com/fuquan/audit/budget/api/BudgetSemanticAnalysisController.java`
- Modify: `backend/src/main/java/com/fuquan/audit/common/observability/FlowDebugLogger.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/api/ContractTextAnalysisApiIntegrationTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/budget/api/BudgetSemanticAnalysisApiIntegrationTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/integration/ai/AiSafeLoggingTest.java`

**Produces:** Authenticated synchronous APIs returning model/operation/request/duration provenance, with no raw source/prompt logging.

- [ ] **Step 1: Write failing API and safe-log tests**

Exercise controllers through MockMvc. Capture logs and assert safe identifiers are present while a unique source sentinel is absent.

- [ ] **Step 2: Verify RED**

Run: `backend\.\mvnw.cmd -Dtest=ContractTextAnalysisApiIntegrationTest,BudgetSemanticAnalysisApiIntegrationTest,AiSafeLoggingTest test`

Expected: FAIL because the endpoints do not exist.

- [ ] **Step 3: Implement controllers and safe events**

Return the shared `ApiResponse`; use authenticated current user/project/task checks already present in each module. Never return or log chain of thought.

- [ ] **Step 4: Verify GREEN**

Run: `backend\.\mvnw.cmd -Dtest=ContractTextAnalysisApiIntegrationTest,BudgetSemanticAnalysisApiIntegrationTest,AiSafeLoggingTest,CorsSecurityIntegrationTest test`

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/fuquan/audit/contract/api/ContractTextAnalysisController.java backend/src/main/java/com/fuquan/audit/budget/api/BudgetSemanticAnalysisController.java backend/src/main/java/com/fuquan/audit/common/observability backend/src/test/java/com/fuquan/audit
git commit -m "feat(ai): expose semantic analysis APIs with safe logs"
```

### Task 11: Show Project extraction provenance and supplemental upload

**Files:**
- Modify: `frontend/src/features/project/types.ts`
- Modify: `frontend/src/features/project/projectMaterialPresentation.ts`
- Modify: `frontend/src/features/project/projectDetailViewModel.ts`
- Modify: `frontend/src/features/project/projectDetailViewModel.test.ts`
- Modify: `frontend/src/features/project/components/ProjectDetailPage.tsx`
- Modify: `frontend/src/features/contract/projectMaterialViewerPresentation.ts`
- Modify: `frontend/src/features/contract/projectMaterialViewerPresentation.test.ts`
- Modify: `frontend/src/features/contract/components/ProjectMaterialsViewer.tsx`

**Produces:** Provenance display and “补充项目资料”; Project-backed Contract remains reuse-only.

- [ ] **Step 1: Write failing presentation tests**

Assert OCR/PDFBox/POI provenance maps to factual Chinese labels, absent confidence renders “—”, OCR text remains viewable, and Project-backed Contract has no primary reupload action.

- [ ] **Step 2: Verify RED**

Run: `node --experimental-strip-types --test src/features/project/projectDetailViewModel.test.ts src/features/contract/projectMaterialViewerPresentation.test.ts`

Expected: new provenance assertions fail.

- [ ] **Step 3: Implement UI fields and copy**

Render durable API fields only. Do not synthesize a percentage or background stage.

- [ ] **Step 4: Verify GREEN and build**

Run: `node --experimental-strip-types --test src/features/project/projectDetailViewModel.test.ts src/features/contract/projectMaterialViewerPresentation.test.ts`

Run: `npm.cmd run build`

Expected: tests and build pass.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/features/project frontend/src/features/contract
git commit -m "feat(ui): show reusable project extraction provenance"
```

### Task 12: Separate contract AI assistance from deterministic review UI

**Files:**
- Modify: `frontend/src/features/contract/types.ts`
- Modify: `frontend/src/features/contract/data/contractDataSource.ts`
- Modify: `frontend/src/features/contract/data/httpContractDataSource.ts`
- Modify: `frontend/src/features/contract/data/httpContractDataSource.test.ts`
- Create: `frontend/src/features/contract/contractTextAnalysisPresentation.ts`
- Create: `frontend/src/features/contract/contractTextAnalysisPresentation.test.ts`
- Modify: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`
- Modify: `frontend/src/features/contract/components/F02ReviewResultPanel.tsx`

**Produces:** Explicit “AI辅助分析” and “确定性规则 · Java BigDecimal” panels.

- [ ] **Step 1: Write failing transport/presentation tests**

Assert the semantic endpoint uses the shared CSRF/cookie client, model provenance is shown, unavailable/config-blocked is factual, and monetary findings remain sourced only from the existing deterministic projection.

- [ ] **Step 2: Verify RED**

Run: `node --experimental-strip-types --test src/features/contract/data/httpContractDataSource.test.ts src/features/contract/contractTextAnalysisPresentation.test.ts src/features/contract/reviewFindingPresentation.test.ts`

Expected: semantic presentation test fails.

- [ ] **Step 3: Implement the separate panel**

Do not merge AI hints into the existing finding list and do not expose prompts or hidden reasoning.

- [ ] **Step 4: Verify GREEN and build**

Run the same Node command, then `npm.cmd run build`.

Expected: tests and build pass.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/features/contract
git commit -m "feat(contract): separate AI assist from deterministic rules"
```

### Task 13: Add budget semantic assistance without money authority

**Files:**
- Modify: `frontend/src/app/BudgetCheckPage.tsx`
- Modify: `frontend/src/features/budget/BudgetAuditWorkspace.tsx`
- Create: `frontend/src/features/budget/budgetSemanticAnalysis.ts`
- Create: `frontend/src/features/budget/budgetSemanticAnalysis.test.ts`
- Modify: `frontend/src/features/budget/budgetAuditWorkspaceStaticContract.test.ts`

**Produces:** Real-call-capable semantic panel, factual processing trace, and separate source labels.

- [ ] **Step 1: Write failing tests**

Assert the page no longer says scanned PDF OCR is unsupported, semantic results are labeled “AI语义来源”, and amount validation remains labeled “确定性金额来源 · Java BigDecimal”.

- [ ] **Step 2: Verify RED**

Run: `node --experimental-strip-types --test src/features/budget/budgetSemanticAnalysis.test.ts src/features/budget/budgetAuditWorkspaceStaticContract.test.ts`

Expected: assertions fail against current copy/UI.

- [ ] **Step 3: Implement semantic request and factual state**

Use actual request/loading/success/error state; do not create timer phases. Keep F01/F04 monetary result components unchanged.

- [ ] **Step 4: Verify GREEN and build**

Run the same Node tests, then `npm.cmd run build`.

Expected: tests and build pass.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/BudgetCheckPage.tsx frontend/src/features/budget
git commit -m "feat(budget): add non-authoritative semantic assistance"
```

### Task 14: Remove unsupported dashboard accuracy

**Files:**
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/app/demoNavigation.test.ts`

**Produces:** No 97.x accuracy or replacement fake metric.

- [ ] **Step 1: Add a failing dashboard contract assertion**

Assert dashboard presentation has no accuracy label/value and retains only metrics backed by actual loaded data.

- [ ] **Step 2: Verify RED**

Run: `node --experimental-strip-types --test src/app/demoNavigation.test.ts`

Expected: FAIL while the current 97.7% card exists.

- [ ] **Step 3: Remove the unsupported card**

Do not replace it with another percentage, model score, or invented count.

- [ ] **Step 4: Verify GREEN and build**

Run: `node --experimental-strip-types --test src/app/demoNavigation.test.ts`

Run: `npm.cmd run build`

Expected: test and build pass.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/App.tsx frontend/src/app/demoNavigation.test.ts
git commit -m "fix(dashboard): remove unsupported accuracy metric"
```

### Task 15: Add 29 deployment and acceptance runbook

**Files:**
- Create: `services/audit-ai/deploy/audit-ai.service.example`
- Create: `docs/runbook/ai-ocr-project-intake-dev29.md`
- Modify: `services/audit-ai/README.md`
- Modify: `.env.example`

**Produces:** Exact non-destructive human commands for remote acceptance.

- [ ] **Step 1: Document read-only preflight and safe probes**

Commands use `/opt/python/venvs/audit-ai/bin/python`, source the env file without printing it, report only model variable `SET`/`NOT_SET`, check port 8001, and inspect DM8 migration/file-store state before applying V017.

- [ ] **Step 2: Document manual and systemd-ready startup**

The unit uses environment file `/home/admin/audit_ai_runtime/audit-ai.env`, working directory supplied by deployment, `--host 127.0.0.1 --port 8001`, restart policy, and no embedded credentials.

- [ ] **Step 3: Verify source safety**

Run: `git diff --check`

Run: `rg -n "(/usr/bin/python3.*install|FLUSHALL|FLUSHDB|kill -9|pip install --upgrade|API_KEY=.+|PASSWORD=.+)" services/audit-ai docs/runbook .env.example`

Expected: diff check passes and unsafe scan returns no committed command/value.

- [ ] **Step 4: Commit**

```powershell
git add services/audit-ai/deploy services/audit-ai/README.md docs/runbook/ai-ocr-project-intake-dev29.md .env.example
git commit -m "docs: add safe 29 AI OCR acceptance runbook"
```

### Task 16: Run full local gate and publish the recoverable checkpoint

**Files:**
- Modify only files required by failures proven to be regressions from Tasks 2-15.

**Produces:** Local implementation evidence and a pushed feature branch; no merge.

- [ ] **Step 1: Run all Python tests**

Run: `python -m pytest services/audit-ai/tests -q`

Expected: all tests pass without Paddle installed/imported.

- [ ] **Step 2: Run all frontend tests and build**

Run: `$frontendTests = rg --files frontend/src | Where-Object { $_ -match '\.test\.ts$' }; node --experimental-strip-types --test $frontendTests`

Run: `npm.cmd --prefix frontend run build`

Expected: all Node tests and Vite build pass. The repository's missing `npm test` script remains explicitly reported unless Task 2 deliberately adds one.

- [ ] **Step 3: Run the backend clean gate**

Run: `backend\.\mvnw.cmd clean verify`

Expected: Maven BUILD SUCCESS with zero failures/errors.

- [ ] **Step 4: Run invariant scans**

Run: `git diff --check`

Run: `git status --short`

Run: `rg -n "readAllBytes|ByteArrayResource|Base64" backend/src/main/java/com/fuquan/audit/integration/ocr backend/src/main/java/com/fuquan/audit/review/application/port`

Expected: clean diff check; only intended files; no forbidden OCR buffering match.

- [ ] **Step 5: Push without merging**

```powershell
git push -u origin feature/ai-ocr-project-intake-demo
```

Expected: remote feature branch points at the verified local HEAD.

- [ ] **Step 6: Prepare requirement evidence**

Use `git log --oneline 6a2c279..HEAD` and test outputs to produce the final
`requirement -> commit SHA -> test command -> result` mapping. Keep remote statuses distinct:
`TARGET_RUNTIME_READY`, `REMOTE_E2E_PENDING_EXECUTION`, and conditional
`BLOCKED_BY_MODEL_CONFIGURATION`.
