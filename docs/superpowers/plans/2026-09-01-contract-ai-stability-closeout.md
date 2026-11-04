# Contract AI Stability Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Contract semantic analysis bounded, correlated, race-safe, and truthful under real model variability.

**Architecture:** Python owns one request-wide extra-call budget and strict provider/safety classification. A focused React hook owns ephemeral AI request state and immutable request material snapshots. Java retains its existing application port and only widens the outer HTTP timeout.

**Tech Stack:** Python 3.11/FastAPI/httpx/Pydantic, Java 17/Spring Boot/RestClient, React 18/TypeScript/Node test runner.

**Spec:** `docs/superpowers/specs/2026-09-01-contract-ai-stability-closeout-design.md`

## Global Constraints

- Maximum Contract provider calls are `chunk_count + 1`; Budget semantics do not gain Contract retry behavior.
- Never weaken monetary, verdict, evidence, or typed-schema validation and never resend rejected output.
- AI state never clears F02 durable state; F02 state never implicitly clears a valid AI result.
- No persistence, migration, deployment, remote probe, secret access, or system environment change.

---

### Task 1: Restore the pre-existing frontend test gate

**Files:**
- Modify: `frontend/src/features/project/projectIntakeCompletion.test.ts`

- [ ] Make the test install its local TypeScript resolution hook before dynamically importing the production module.
- [ ] Run only `projectIntakeCompletion.test.ts`, then the existing full frontend test command.
- [ ] Commit separately as `test(frontend): restore intake completion node test`.

### Task 2: Add bounded Contract retry and classified gateway failures

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/src/audit_ai/logging.py`
- Modify: `services/audit-ai/src/audit_ai/main.py`
- Test: `services/audit-ai/tests/test_model_routes.py`
- Test: `services/audit-ai/tests/test_safe_logging.py`

- [ ] Add failing fixtures proving safety retry, global multi-chunk budget, transient/provider classification, non-retryable failures, cumulative duration, request ID, and no sensitive logging.
- [ ] Run focused tests and confirm failures name the missing behavior.
- [ ] Implement explicit internal attempt metadata, one request-wide retry token, classified `ModelGatewayFailure`, safe retry prompt, cumulative duration, and allowlisted logs.
- [ ] Run focused tests green and commit the retry/classification slice.

### Task 3: Guard the frontend semantic lifecycle and request snapshot

**Files:**
- Create: `frontend/src/features/contract/useContractSemanticAnalysis.ts`
- Create: `frontend/src/features/contract/useContractSemanticAnalysis.test.ts`
- Modify: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`
- Modify: `frontend/src/features/contract/components/AiSemanticReviewPanel.tsx`
- Modify: `frontend/src/features/contract/contractTextAnalysisPresentation.ts`

- [ ] Add failing hook tests for double-click, abort, stale contract/project/selection responses, immediate invalidation, current-selection retry, and request-snapshot evidence.
- [ ] Implement the minimal hook with pending ref, generation, AbortController, ordered selection key, and immutable material snapshot.
- [ ] Integrate it without changing F02 state ownership; run focused tests/build and commit.

### Task 4: Warn about duplicate-looking materials

**Files:**
- Modify: `frontend/src/features/contract/projectReviewMaterialSelection.ts`
- Modify: `frontend/src/features/contract/projectReviewMaterialSelection.test.ts`
- Modify: `frontend/src/features/contract/components/ProjectReviewMaterialPicker.tsx`

- [ ] Add a failing behavior test for normalized filename plus category duplicates.
- [ ] Return and render the non-blocking warning without deselecting or blocking files.
- [ ] Run focused frontend tests and commit.

### Task 5: Widen the Java outer timeout and preserve adapter boundaries

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-local-ai.yml`
- Modify: `.env.example`
- Modify: `docs/runbook/ai-ocr-project-intake-dev29.md`
- Test: `backend/src/test/java/com/fuquan/audit/integration/AiOcrAdapterProfileWiringTest.java`

- [ ] Change the config assertion first and confirm it fails at 60000 ms.
- [ ] Set the AI outer read timeout default to 150000 ms in application/config templates and runbook; keep OCR at 120000 ms.
- [ ] Run focused backend adapter/config, request-ID, redactor, Budget, and F02 tests; commit.

### Task 6: Full verification and delivery

- [ ] Run Python 3.11 full tests, `backend/.\\mvnw.cmd clean verify`, every frontend test, and `npm.cmd run build`.
- [ ] Run `git diff --check`, scoped secret scans, tracked-artifact checks, and a requirements-to-diff self-review.
- [ ] Commit any verified final corrections and push `feature/ai-ocr-project-intake-demo` without merge, tag, deployment, or remote probe.
