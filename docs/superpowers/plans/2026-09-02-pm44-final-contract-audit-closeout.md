# PM44 Final Contract Audit Closeout Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Deliver the final five-stage, evidence-grounded contract internal-audit experience for the
2026-09-02 customer demonstration while preserving the existing Project/F02/F01/F03 boundaries and
all pre-existing worktree changes.

**Architecture:** Python tolerantly normalizes untrusted provider JSON and filters unsafe hints per
item. Java validates locator availability against durable ProjectFile parsed text. React presents
exactly five top-level stages and groups semantic/breach/term reviews under stage four while reusing
the project-backed source viewer. Existing DM8-backed task/dashboard and Project upload APIs remain
authoritative.

**Tech Stack:** Java 17, Spring Boot, MyBatis/DM8 ports, Python 3.11/FastAPI/Pydantic, React 19,
TypeScript, Vite, Node test runner.

---

### Task 1: Lock tolerant AI normalization with adversarial tests

**Files:**
- Modify: `services/audit-ai/tests/test_model_routes.py`
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`

1. Add failing tests for extra provider fields, category/attention aliases, malformed sibling hints,
   invalid/mixed evidence, excerpt mismatch retention, strong-verdict and monetary per-hint filtering,
   neutral summaries, empty-safe results, and no retry for processable schema variance.
2. Run the focused Python test module and confirm the new tests fail for the old strict/request-wide
   behavior.
3. Add a tolerant raw-normalization layer, remove `locatorAvailable` from the Python response schema,
   filter each hint independently, and restrict retry to transport/parse/top-level failures.
4. Update the provider prompt to request only actual selected-file comparison evidence and no
   invented sources.
5. Re-run the focused module and all Python tests.

### Task 2: Make Java the locator authority

**Files:**
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCaseTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/ai/fastapi/FastApiAiReviewAdapterTest.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCase.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/ai/fastapi/FastApiAiReviewAdapter.java`

1. Add failing tests proving provider locator fields are ignored, selected-file evidence is retained,
   and NFKC/whitespace excerpt matching uses real unredacted parsed text.
2. Run the focused Maven tests and observe the red state.
3. Remove downstream locator ownership from the adapter DTO and post-process returned evidence in
   the use case against real ProjectFile text.
4. Re-run the focused Maven tests.

### Task 3: Replace seven top-level stages with the final five-stage state machine

**Files:**
- Modify: `frontend/src/features/contract/intelligentReviewPipelinePresentation.test.ts`
- Modify: `frontend/src/features/contract/intelligentReviewPipelinePresentation.ts`
- Modify: `frontend/src/features/contract/components/IntelligentReviewPipeline.tsx`
- Modify: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`

1. Add failing tests for the exact five keys/labels, three stage-four branches, all-terminal grouping,
   `RUNNING` precedence, semantic-failure attention, and completed assistance with two successful
   clause branches.
2. Run the focused TypeScript tests and observe failure.
3. Implement the grouped state machine and render its three branch rows beneath stage four.
4. Reorder the integrated DOM to material picker → progress → F02 → grouped semantic/clause panels →
   assistance, while retaining concurrent branch start behavior.
5. Re-run focused pipeline/workspace tests.

### Task 4: Unify truthful comparison sources and locator fallback

**Files:**
- Modify: `frontend/src/features/contract/contractTextAnalysisPresentation.test.ts`
- Modify: `frontend/src/features/contract/contractTextAnalysisPresentation.ts`
- Modify: `frontend/src/features/contract/components/AiSemanticReviewPanel.tsx`
- Modify: `frontend/src/features/contract/projectMaterialViewerPresentation.test.ts`
- Modify: `frontend/src/features/contract/projectMaterialViewerPresentation.ts`
- Modify: `frontend/src/features/contract/components/ProjectMaterialsViewer.tsx`
- Modify: `frontend/src/features/contract/components/F02ReviewResultPanel.tsx`
- Modify: `frontend/src/features/contract/components/ContractClauseReviewPanels.tsx`

1. Add failing presentation/source-viewer tests for INFO comparison sources, expanded REVIEW evidence,
   actual filenames, normalized unique matching, persistent amber marks, and the exact locator-
   unavailable notice.
2. Run focused frontend tests and observe failure.
3. Preserve real INFO evidence sources, label sources consistently, and open the real ProjectFile for
   both locatable and file-only evidence.
4. Make file-only selection show “已打开来源材料，未能自动定位到具体文字。” without a fake mark.
5. Re-run the focused tests.

### Task 5: Close deterministic clause regressions and preserve existing business slices

**Files:**
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/clause/BreachClauseAnalyzerTest.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/clause/BreachClauseAnalyzer.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/clause/ContractTermClauseAnalyzerTest.java`
- Modify if needed: `backend/src/main/java/com/fuquan/audit/contract/application/clause/ContractTermClauseAnalyzer.java`
- Verify existing: `frontend/src/features/dashboard/contractDashboard.test.ts`
- Verify existing: `frontend/src/features/contract/contractTaskSource.test.ts`
- Verify existing: `frontend/src/features/project/projectSupplementUpload.test.ts`
- Verify existing Project completeness/format-policy tests.

1. Add the exact semicolon rate/cap fixture and explicit quality/service scenario assertions.
2. Add Chinese date/signing/effective/termination regression variants.
3. Run focused Maven tests and observe failures where behavior is incomplete.
4. Adjust clause tokenization/context only as required, keeping `BigDecimal` calculations and no
   invented actual penalty or legal verdict.
5. Run focused backend tests plus existing dashboard/filter/supplement/material-completeness tests.

### Task 6: Full verification and customer-surface audit

**Files:**
- Inspect: all modified files and generated diffs.

1. Run `backend\\mvnw.cmd clean verify` from `backend`.
2. Run Python 3.11 `pytest` from `services/audit-ai`.
3. Run every `frontend/src/**/*.test.ts` with the Node test runner and `npm run build`.
4. Inspect `git diff --check`, `git diff --stat`, and the complete relevant diff.
5. Scan tracked source/config/docs for credential patterns and customer UI for forbidden technical
   vocabulary; review each match in context.
6. Run the real Qwen/local E2E only when existing protected configuration is usable without reading
   or printing secrets; otherwise record `REAL_QWEN_RETEST_PENDING_USER_RUNTIME`.

### Task 7: Review, commit, push, and post-push verification

**Files:**
- Review: all task-scope changes.

1. Use `superpowers:requesting-code-review` and resolve all Critical/Important findings.
2. Re-run the affected focused tests and the final verification gate after review changes.
3. Commit all preserved and final closeout changes on `feature/ai-ocr-project-intake-demo` with a
   conventional message.
4. Push the branch without force.
5. Verify local HEAD equals the remote branch and report the commit SHA, test evidence, working-tree
   state, and any external-runtime item as VERIFIED or NOT VERIFIED.
