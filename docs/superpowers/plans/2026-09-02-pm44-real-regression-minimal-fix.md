# PM44 Real Regression Minimal Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore PM44 line-item ingestion, stable canonical AI audit slots, and verified problem
evidence while preserving deterministic F02, helper authority, durable UX, and viewer behavior.

**Architecture:** Add non-persistent typed DOCX table metadata for structural extraction; add a
presentation-only canonical slot layer over the existing dynamic semantic hints; and strengthen the
existing bounded AI normalization/retry path so problem evidence is exact or explicitly unavailable.

**Tech Stack:** Java 17, Spring Boot, Apache POI, JUnit 5, Python 3/Pydantic/FastAPI/pytest,
React/TypeScript, Node test runner.

**Spec:** `docs/superpowers/specs/2026-09-02-pm44-real-regression-minimal-fix-design.md`

## Global Constraints

- Work only in `D:\code\audit_agent_1D\.worktrees\ai-ocr-project-intake-demo` on `feature/ai-ocr-project-intake-demo`.
- Keep `D:\code\audit-agent-demo` read-only and `.runtime/pm44-e2e` ignored and untracked.
- Do not change database schema, durable Contract DTOs, F02 rules, V016, acknowledgement/history persistence, or `ProjectMaterialsViewer` matching behavior.
- Do not stop or restart AI 8001, backend 8080, or frontend 5173.
- Do not describe code-level fixtures as real PM44 E2E.
- Model operations remain bounded to two attempts and the request retains one shared retry allowance;
  operations reached after that allowance is consumed advertise and execute `maxAttempts=1`.

---

### Task 1: Typed DOCX table-row extraction

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ParsedProjectDocument.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ProjectDocxTextParser.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/extraction/ProjectFactExtractor.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/parse/ProjectDocumentParserTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/extraction/ProjectFactExtractorTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionServiceTest.java`

**Interfaces:**
- Produces: `ParsedProjectDocument.tables()` containing immutable `Table`, `Row`, and `Cell` values with locators.
- Produces: the existing five `LINE_ITEM_*` facts grouped by the item-key cell.
- Preserves: `ParsedProjectDocument(List<Segment>)`, `segments()`, and `fullText()` behavior.

- [ ] Add a parser test that builds an Apache POI table with the PM44 headers and three rows, then
  asserts table/row/cell locators and values while `fullText()` contains each cell only once.
- [ ] Add an extractor test with typed rows for SOFT-01, SERV-01, and TRAIN-01; assert exactly 15
  grouped facts and numeric values `1/40000/40000`, `1/17000/17000`, and `1/10000/10000`.
- [ ] Add an ingestion-service test that uploads the generated DOCX and reads a durable in-memory
  candidate with three ordered line items totaling 67000.
- [ ] Run:
  `backend\mvnw.cmd -Dtest=ProjectDocumentParserTest,ProjectFactExtractorTest,ProjectFileIngestionServiceTest test`
  and record the pre-fix failures.
- [ ] Implement immutable typed table records and the one-argument compatibility constructor.
- [ ] Populate typed rows in `ProjectDocxTextParser` without changing flat segments.
- [ ] Map required header aliases to cell indexes and emit facts directly from cells; keep the
  legacy per-segment regex path for PDFs/old fixtures and reject incomplete/non-numeric rows.
- [ ] Re-run the focused command and require PASS.

### Task 2: Durable PM44-like 01 and 05 F02 regression

**Files:**
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidatePromotionApiIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/domain/AmountAndLineRulesTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/domain/AwardRulesAndRiskTest.java`

**Interfaces:**
- Consumes: Task 1 candidate line items through the existing promotion request.
- Verifies: existing durable `Contract.lineItems`, GET contract mapping, and Java BigDecimal F02 rules.

- [ ] Add a PM44-like 01 API test: upload a three-row DOCX, confirm candidate lines, promote the
  confirmed values with matching award facts, GET three durable contract lines, and assert all five
  distinct F02 rule categories have no non-PASS result.
- [ ] Add a PM44-like 05 API/domain case with the real two-row arithmetic shape and mismatched amount
  words, line arithmetic, line sum, award amount, and supplier; assert all five rule categories still
  contain their intended risk and are not converted to all PASS.
- [ ] Run the new methods before production changes and record the Task 1 dependent failure.
- [ ] Make no F02 production change; if a rule assertion fails independently, stop and return to root-cause investigation.
- [ ] Run the two new cases plus `AmountAndLineRulesTest` and `AwardRulesAndRiskTest` and require PASS.

### Task 3: Canonical presentation slots and term-helper fact fallback

**Files:**
- Modify: `frontend/src/features/contract/contractTextAnalysisPresentation.ts`
- Modify: `frontend/src/features/contract/components/AiSemanticReviewPanel.tsx`
- Modify: `frontend/src/features/contract/types.ts`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/clause/ContractTermClauseReview.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/clause/ContractTermClauseAnalyzer.java`
- Test: `frontend/src/features/contract/contractTextAnalysisPresentation.test.ts`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/clause/ContractTermClauseAnalyzerTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/ContractClauseReviewApiIntegrationTest.java`

**Interfaces:**
- Produces: slot `coverage: "ASSESSED" | "UNASSESSED"`, independent from AI attention and F02 status.
- Produces: term helper fact evidence with server-issued `locatorAvailable` for exact matched excerpts.
- Preserves: multiple hints per category and optional `OTHER_TEXT`.

- [ ] Add presentation tests proving all eight canonical slots remain visible with no hints, each is
  `UNASSESSED`, and no missing slot is labelled INFO/PASS.
- [ ] Add tests proving multiple same-category hints remain, `OTHER_TEXT` is appended, and normal
  4.1 categories do not disappear when another category has a warning.
- [ ] Add a 04 test where no main TERM hint exists but the helper supplies blank signing-date
  `INSUFFICIENT` evidence; assert the term slot remains `UNASSESSED` while exposing the exact problem
  file/excerpt as a helper fact.
- [ ] Add Java tests proving blank-date and event-effective facts retain exact server excerpts and
  explicitly locatable status without turning event effectiveness into insufficient.
- [ ] Run the focused frontend and backend tests and record their failures.
- [ ] Implement canonical slot presentation and UI copy “未形成可靠判断”; render helper facts in a
  separate block from semantic hint cards.
- [ ] Add the server-produced helper locator flag only to matched term facts; do not infer it in TypeScript.
- [ ] Re-run focused tests and require PASS.

### Task 4: Verified problem evidence with bounded retry

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Test: `services/audit-ai/tests/test_model_routes.py`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCaseTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/ai/fastapi/FastApiAiReviewAdapterTest.java`

**Interfaces:**
- Produces: evidence items whose non-null excerpts occur in the corresponding visible material.
- Uses: retry reason `MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE` on the existing one-retry gate.
- Preserves: `MISSING_REQUIRED_BREACH_CATEGORY` precedence and `MODEL_INVALID_RESULT` after exhaustion.

- [ ] Change the existing invented-excerpt test to require rejection, then add a two-response test:
  first REVIEW omits problem evidence, second supplies 07-like contract/tender exact excerpts; assert
  exactly two gateway calls and the retry reason.
- [ ] Add an exhaustion test where the second result still invents/omits evidence; require
  `MODEL_INVALID_RESULT` and exactly two calls.
- [ ] Add an explicit-unavailable test: INSUFFICIENT with an allowed fileId, null excerpt, and a
  named required material category is accepted only when that category is absent; present nonblank
  source text without that proof is rejected.
- [ ] Add a combined adversarial test proving breach-category omission consumes the same retry gate
  first and cannot cause a third call through evidence completeness.
- [ ] Run the focused pytest methods and record failures.
- [ ] Strengthen the system/retry prompts, validate NFKC whitespace-insensitive excerpt membership,
  and classify missing/invalid problem evidence without accepting invented text.
- [ ] Re-run Python focused tests, Java adapter/use-case locator tests, breach completeness tests, and
  all monetary-safety parametrizations.

### Task 5: Cross-feature and full verification

**Files:**
- Verify only; production changes require a new failing test and root-cause note.

**Interfaces:**
- Verifies: acknowledgement/history durability, monetary authority, source highlighting, build, and clean scope.

- [ ] Run all frontend tests and `npm.cmd run build`.
- [ ] Run all audit-ai pytest tests.
- [ ] Run focused backend acknowledgement, history/progress, F02, clause, AI adapter, and locator tests.
- [ ] Run `backend\mvnw.cmd clean verify`.
- [ ] Run `git diff --check`, inspect every changed file, scan for tracked `.runtime` material, and
  confirm V016 and viewer implementation are unchanged.
- [ ] Report `RUNTIME_RESTART_REQUIRED: AI=YES/NO BACKEND=YES/NO FRONTEND=YES/NO` and stop before
  runtime PM44 calls until the user confirms manual restarts.
- [ ] After user confirmation, run read-only health checks first, then authenticated PM44 01/03/04/05/07
  only if authentication is available. Otherwise report `AUTH_NOT_AVAILABLE` without substituting fixtures.
