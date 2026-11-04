# Contract AI Final Runtime Closeout Implementation Plan

> **Execution note:** This plan is being executed in the existing isolated worktree. The user explicitly prohibited commit, push, merge, and deployment for this review cycle.

**Goal:** Close the verified Java/FastAPI timeout mismatch, strengthen monetary input/output isolation, preserve independent frontend results, and collect safe real-Qwen end-to-end evidence.

**Architecture:** Keep the synchronous Java-to-FastAPI contract and the existing two-attempt model budget. Java removes explicit monetary fields before the typed AI port; Python validates typed/evidence/verdict boundaries and may delete monetary-only output at the final attempt while retaining safe hints. React continues to run semantic and deterministic branches independently and displays only the current material selection's server result.

**Tech Stack:** Java 17/Spring Boot/JUnit, Python 3.11 target/FastAPI/pytest, React/TypeScript/Node test runner/Vite.

---

## Task 1: Lock the end-to-end timeout contract and safe startup evidence

**Files:**
- Modify: `.env.example`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/http/FastApiHttpClientConfiguration.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/AiOcrAdapterProfileWiringTest.java`
- Modify: `services/audit-ai/src/audit_ai/config.py`
- Modify: `services/audit-ai/tests/test_config.py`

1. Add failing assertions for Java default/local overlay values, Python model defaults, and safe Java startup timeout logging.
2. Run focused Java/Python tests and observe the old 3000ms/3s/30s defaults fail.
3. Set Java AI 5000/150000ms, OCR 5000/120000ms, and Qwen 5/60s consistently.
4. Log only adapter name and resolved connect/read timeout values while creating the real clients.
5. Re-run focused tests and scan tracked active configuration for a 60000ms regression.

## Task 2: Strengthen Java monetary input redaction

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractAiTextRedactor.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractAiTextRedactorTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCaseTest.java`

1. Add the required failing tests for bare 万元, multiple monetary forms, per-unit values, real procurement headers, and flattened tables containing arbitrary non-monetary headers.
2. Confirm the old implementation leaks at least the identified values.
3. Extend bounded monetary label/unit grammar; do not redact ordinary numbers.
4. Replace the fixed non-monetary header allowlist dependency with bounded structural inference: a candidate header block must contain an explicit monetary header and its following row must contain monetary-shaped values in every identified monetary column.
5. Re-run focused redactor/use-case tests and confirm semantic identifiers, quantities, dates, and service-hour expressions remain.

## Task 3: Strengthen Python output validation and final-attempt filtering

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/tests/test_model_routes.py`

1. Add failing output cases for 万元 and per-unit currency forms.
2. Add a failing sequence test for transient gateway failure followed by monetary-only typed output.
3. Extend concrete monetary-value detection while keeping 7×24, 5×8, identifiers, dates, and quantities valid.
4. Allow final-attempt monetary-only filtering regardless of the first retry reason; keep schema, evidence, and strong-verdict failures closed.
5. Verify safe hints remain, all-monetary output becomes a safe empty result, and logs contain only the existing safe metadata contract.

## Task 4: Lock frontend long-request and result-visibility behavior

**Files:**
- Modify: `frontend/src/features/contract/useContractSemanticAnalysis.test.ts`
- Modify: `frontend/src/features/contract/contractTextAnalysisPresentation.test.ts`
- Verify: `frontend/src/features/contract/intelligentReviewOperation.test.ts`

1. Add a controlled long-pending request test proving there is no local self-timeout and the eventual 200 result is rendered.
2. Keep the existing empty-success presentation, branch independence, and selection invalidation tests.
3. Only change production frontend code if a new regression test demonstrates a real gap.

## Task 5: Automated and real-runtime verification

1. Run focused tests after each red/green slice.
2. Run Python full tests (Python 3.11 if available; otherwise report the locally available interpreter exactly without changing the system runtime).
3. Run `backend/.\mvnw.cmd clean verify`.
4. Run every frontend `*.test.ts` and `npm.cmd run build`.
5. Run `git diff --check` and a tracked-secret scan without printing secret values.
6. Start the configured local FastAPI, Java `local-dev,local-ai`, and frontend runtimes without exposing credentials.
7. Exercise the required real fixtures and collect only safe HTTP/operation/model/duration/hint/retry/filter metadata.
8. Stop locally started processes and report the uncommitted diff for user review.
