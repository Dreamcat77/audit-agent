# Contract Intelligent Review Demo Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a truthful Project preprocessing to selected-material Contract intelligent-review flow where AI provides text-only assistance beside unchanged deterministic F02 decisions.

**Architecture:** Reuse durable ProjectFile intake and frontend-orchestrate two separate application use cases. Add programmatic Java monetary redaction plus Python/Java response validation, and isolate UI responsibilities in focused presentation modules/components.

**Tech Stack:** Java 17, Spring Boot 3.5, React 18/TypeScript/Vite, Python 3.11/FastAPI/Pydantic/httpx, JUnit 5/Mockito/MockMvc, Node test runner, pytest.

**Spec:** `docs/superpowers/specs/2026-08-31-contract-intelligent-review-demo-alignment-design.md`

## Global Constraints

- Work only in `.worktrees/ai-ocr-project-intake-demo` on `feature/ai-ocr-project-intake-demo`.
- Preserve `ProjectFileIngestionService`, `FileStoragePort`, archive security, idempotency, and recovery.
- Do not modify the five F02 rule semantics or their durable transaction flow.
- Java BigDecimal/deterministic rules retain exclusive monetary authority.
- Contract and Budget model operations remain separate typed ports; no generic prompt API.
- Python target remains 3.11; do not install or change system Python.
- No timer-driven progress, fake runtime result, fake metric, raw document logging, or committed secret.
- Every task follows red test → observed failure → minimal implementation → targeted green → regression → commit.

---

### Task 1: Durable preprocessing completion presentation

**Files:**
- Modify: `frontend/src/features/project/projectListViewModel.test.ts`
- Create: `frontend/src/features/project/projectIntakeCompletion.ts`
- Create: `frontend/src/features/project/projectIntakeCompletion.test.ts`
- Modify: `frontend/src/features/project/useProjectList.ts`
- Modify: `frontend/src/features/project/projectListViewModel.ts`
- Modify: `frontend/src/features/project/components/ProjectListPage.tsx`

**Interfaces:**
- Produces: `CompletedProjectIntake`, `completedProjectIntake(result)`, and `useProjectList().completedIntake`.
- Consumes: existing `ProjectIntakeResult` only after `COMPLETE`; does not retain its `File`.

- [ ] Add failing tests proving processed/text/OCR/failed counts, completion CTA copy, no raw list UUID markup, and no automatic `onOpenProject` call in the success callback.
- [ ] Run the two project tests with `node --experimental-strip-types --test ...`; expect failures for the missing completion mapper/copy.
- [ ] Implement the immutable completion projection, update the hook after proven completion, keep the page on the list, and add the explicit CTA.
- [ ] Run all Project frontend tests; expect pass and unchanged uncertain-operation assertions.
- [ ] Run `git diff --check` and commit `feat(project): surface durable preprocessing completion`.

### Task 2: Business-facing Project materials page

**Files:**
- Modify: `frontend/src/features/project/projectDetailViewModel.test.ts`
- Modify: `frontend/src/features/project/projectMaterialPresentation.ts`
- Modify: `frontend/src/features/project/projectDetailViewModel.ts`
- Modify: `frontend/src/features/project/components/ProjectDetailPage.tsx`
- Modify: `frontend/src/features/contract/workspaceTypes.ts`

**Interfaces:**
- Produces: friendly parse method/page/OCR-confidence presentation and typed material eligibility facts.
- Consumes: unchanged `ProjectFile` durable fields.

- [ ] Add failing assertions for Word/PDF/OCR labels, `98.7%`, confidence only for OCR, PACKAGE archive summary, semantic eligibility, and collapsed parsed text.
- [ ] Run the focused tests; expect current engine/raw-duration projection assertions to fail.
- [ ] Replace normal provenance with business labels, add typed statuses/eligibility to project material tabs, render PACKAGE once, close recognized text by default, rename contract sections, and omit supplier placeholders.
- [ ] Run Project and project-material frontend tests; expect pass.
- [ ] Run `git diff --check` and commit `feat(project): present processed materials for business review`.

### Task 3: Selected-file Contract AI use case and monetary redaction

**Files:**
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCaseTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractAiTextRedactorTest.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractAiTextRedactor.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCase.java`

**Interfaces:**
- Produces: `analyze(UUID, List<UUID>)` with 1–50 unique selected IDs and caller-order preservation.
- Produces: `ContractAiTextRedactor.redact(String): String`.
- Consumes: `ProjectApplicationService.files(projectId)` and `ProjectParsedTextReadService.read(file)`.

- [ ] Add failing use-case tests for selected-only send, unselected omission, empty/duplicate/foreign/PACKAGE/pending/failed/non-parsed/blank-text rejection, stable caller order, and the approved redaction fixture.
- [ ] Run `mvnw -Dtest=ContractTextAnalysisUseCaseTest,ContractAiTextRedactorTest test`; expect compile/test failures for the new signatures/classes.
- [ ] Implement exact selection validation and conservative label/uppercase monetary redaction before constructing port materials.
- [ ] Run the focused tests and Project parsed-text regressions; expect pass.
- [ ] Run `git diff --check` and commit `feat(ai): isolate contract semantics from monetary text`.

### Task 4: Typed semantic response and evidence allowlist

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisResult.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/ai/fastapi/FastApiAiReviewAdapter.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/ai/fastapi/FastApiAiReviewAdapterTest.java`
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/tests/test_model_routes.py`

**Interfaces:**
- Produces Java/Python semantic categories `PROJECT_IDENTITY|SUPPLIER_IDENTITY|DELIVERY_TERMS|SERVICE_WARRANTY|SCOPE_AND_SUBCONTRACTING|OTHER_TEXT`.
- Produces attention `INFO|REVIEW|INSUFFICIENT` and exact selected-file `evidenceRefs`.
- Preserves the Budget `items` response unchanged.

- [ ] Add failing Java adapter tests for valid typed hints, invented evidence IDs, and unchanged Budget mapping.
- [ ] Add failing pytest cases for typed semantics, evidence allowlist, strong verdict rejection, monetary conclusion rejection, and masked-value non-restoration.
- [ ] Run the focused Java and Python tests; expect failures against the current summary/hints contract.
- [ ] Implement the prompt constraints, typed Pydantic schema, semantic safety validator, request-aware Java mapping, and defensive evidence allowlist.
- [ ] Run all model-route, adapter, Budget semantic, and OCR contract tests; expect pass.
- [ ] Run `git diff --check` and commit `feat(ai): enforce typed contract semantic results`.

### Task 5: Controller request contract and safe AI metadata

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/ContractTextAnalysisController.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/ContractTextAnalysisApiIntegrationTest.java`
- Modify: `backend/src/main/java/com/fuquan/audit/common/observability/FlowDebugLogger.java`
- Modify: `backend/src/test/java/com/fuquan/audit/common/observability/FlowDebugLoggerTest.java`
- Modify: `services/audit-ai/src/audit_ai/logging.py`
- Modify: `services/audit-ai/tests/test_safe_logging.py`

**Interfaces:**
- Consumes JSON `ContractTextAnalysisRequestBody(List<UUID> fileIds)` validated to 1–50 entries.
- Produces safe AI counts: selected/material count and semantic check/hint count.

- [ ] Add failing MockMvc tests for JSON file IDs, empty/duplicate/oversized validation, Request ID, and safe counts.
- [ ] Add failing Java/Python logger tests allowing numeric counts while rejecting source/prompt/key fields or nonnumeric count values.
- [ ] Run the focused tests; expect current no-body endpoint and logging signatures to fail.
- [ ] Implement the request record, use-case call, count-only logger metadata, and Python numeric allowlist.
- [ ] Run focused plus API security and Budget API regressions; expect pass.
- [ ] Run `git diff --check` and commit `feat(observability): record safe semantic call metadata`.

### Task 6: Material picker and typed HTTP client

**Files:**
- Create: `frontend/src/features/contract/projectReviewMaterialSelection.ts`
- Create: `frontend/src/features/contract/projectReviewMaterialSelection.test.ts`
- Create: `frontend/src/features/contract/components/ProjectReviewMaterialPicker.tsx`
- Modify: `frontend/src/features/contract/workspaceTypes.ts`
- Modify: `frontend/src/features/contract/data/contractDataSource.ts`
- Modify: `frontend/src/features/contract/data/httpContractDataSource.ts`
- Modify: `frontend/src/features/contract/data/httpContractDataSource.test.ts`

**Interfaces:**
- Produces: `createProjectReviewMaterialSelection(tabs)`, eligible ID set, default selection, and sufficiency warnings.
- Changes: `analyzeContractText(projectId, fileIds, options?)` sends `{fileIds}`.

- [ ] Add failing selection tests for eligible-only/default-all/deselect/zero-disabled/missing-contract/cross-material warning.
- [ ] Change the HTTP test to require the exact JSON file ID body and add Mock source compatibility assertions.
- [ ] Run focused frontend tests; expect missing module/signature failures.
- [ ] Implement typed selection helpers/component and the data-source request signature.
- [ ] Run contract/project frontend tests; expect pass.
- [ ] Run `git diff --check` and commit `feat(contract): select prepared project materials for review`.

### Task 7: Independent AI and F02 intelligent-review orchestration

**Files:**
- Create: `frontend/src/features/contract/intelligentReviewOperation.ts`
- Create: `frontend/src/features/contract/intelligentReviewOperation.test.ts`
- Create: `frontend/src/features/contract/components/IntelligentReviewPipeline.tsx`
- Create: `frontend/src/features/contract/components/AiSemanticReviewPanel.tsx`
- Create: `frontend/src/features/contract/components/TechnicalCallDetails.tsx`
- Modify: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`
- Modify: `frontend/src/features/contract/contractTextAnalysisPresentation.ts`
- Modify: `frontend/src/features/contract/contractTextAnalysisPresentation.test.ts`
- Modify: `frontend/src/features/contract/types.ts`

**Interfaces:**
- Produces: one orchestration start that invokes supplied `runDeterministic` and `runSemantic(fileIds)` exactly once and exposes independent states.
- Consumes: selected eligible file IDs and existing `useContractReviewWorkspace` F02 operations.

- [ ] Add failing pure-operation tests for once-only parallel start and independent success/failure state.
- [ ] Add presentation/static tests for real AI loading copy, friendly evidence filenames, auditor-hidden technical values, and collapsed admin details.
- [ ] Run focused tests; expect missing modules and old standalone AI button assertions to fail.
- [ ] Implement focused components and wire the single “开始智能审核” action without placing AI in the F02 transaction or replacing either result on peer failure.
- [ ] Run all Contract frontend tests; expect pass.
- [ ] Run `git diff --check` and commit `feat(contract): integrate semantic and deterministic review UX`.

### Task 8: Supportive F02 presentation and demo-safe timeout

**Files:**
- Modify: `frontend/src/features/contract/reviewFindingPresentation.test.ts`
- Modify: `frontend/src/features/contract/pmUiAlignmentPresentation.test.ts`
- Modify: `frontend/src/features/contract/reviewFindingPresentation.ts`
- Modify: `frontend/src/features/contract/components/F02ReviewResultPanel.tsx`
- Modify: `frontend/src/features/contract/reviewRulePresentation.ts`
- Modify: `backend/src/main/resources/application-local-ai.yml`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/AiOcrAdapterProfileWiringTest.java`

**Interfaces:**
- Produces presentation-only “需复核/建议重点复核/材料不足” copy and deterministic group headings.
- Produces local-ai defaults: AI 5000/60000 ms and OCR 5000/120000 ms.

- [ ] Add failing frontend tests for supportive copy/grouping and a config test for exact timeout placeholders/defaults.
- [ ] Run focused tests; expect old risk/high-risk/technical wording and missing local-ai timeout properties to fail.
- [ ] Update presentation only, preserving stored enums/facts, and externalize the timeout settings.
- [ ] Run the frontend presentation, config wiring, all F02, Budget, and OCR regressions; expect pass.
- [ ] Run `git diff --check` and commit `fix(config): make intelligent review demo-safe`.

### Task 9: Full verification and branch publication

**Files:**
- Verify all changed and existing files; no generated/runtime artifacts are added.

**Interfaces:**
- Produces final evidence: test totals, build results, runtime status, commit map, and pushed feature branch.

- [ ] Run `backend/.\mvnw.cmd clean verify`; require `BUILD SUCCESS` and record totals.
- [ ] Run every frontend `*.test.ts` with the existing Node command, then `npm.cmd run build`; require both pass.
- [ ] Run `.runtime/venvs/audit-ai/Scripts/python.exe -m pytest`; require Python 3.11 and all tests pass without Paddle initialization.
- [ ] Run `git diff --check`, inspect `git status`, and scan tracked changes for `sk-`, `AUDIT_MODEL_API_KEY=`, `password=`, `Authorization:`, and `Bearer `; classify documentation/test literals rather than deleting legitimate contract tests.
- [ ] Check only SET/NOT_SET status for model runtime variables; run a real smoke only if all required variables are already present, otherwise record `REAL_MODEL_SMOKE_NOT_RUN_IN_THIS_PROCESS`.
- [ ] Commit any final test-only alignment as `test: cover intelligent review integration`, verify the requirement-to-commit map, and push only `feature/ai-ocr-project-intake-demo` without merging main.
