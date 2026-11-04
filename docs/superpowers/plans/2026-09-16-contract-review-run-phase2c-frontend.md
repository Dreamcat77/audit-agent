# Contract Review Run Phase 2C Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing Project-backed contract review workspace create, execute, reopen, and present durable ContractReviewRun results while retaining the current two-panel experience and legacy/showcase behavior.

**Architecture:** Add a typed ReviewRun transport boundary beside the existing direct review data source, a session-scoped operation-identity store, a focused Project-backed orchestration hook, and pure presentation adapters. The existing workspace, material picker/viewer, F02 result panel, semantic panel, and five-stage pipeline remain the user-facing shell; Project-backed mode reads only durable ReviewRun state, while legacy/showcase mode keeps its existing direct APIs.

**Tech Stack:** React 18, TypeScript, Vite, browser `sessionStorage`, existing Session Cookie/CSRF `request` client, Node test runner, esbuild test harnesses.

**Spec:** `docs/superpowers/specs/2026-09-14-contract-audit-evolution-master-design.md`; approved Phase 2C task attachment dated 2026-09-16; Phase 2B API contract in `docs/openapi.yaml`.

## Global Constraints

- Work only in `.worktrees/contract-review-run-phase2c` on `feature/contract-review-run-phase2c-frontend-20260916`, starting at `f7445228b7d60af5fa6a01f1ad9c488065881614`.
- This slice changes frontend and this plan only. If the existing ReviewRun API cannot support a required behavior, stop with `PHASE2C_BACKEND_CONTRACT_BLOCKER`; do not patch backend lifecycle, schema, Python, rules, or semantic execution.
- DM8-backed ReviewRun responses are the Project-backed review truth. `sessionStorage` stores only a run pointer and in-flight idempotency keys, never findings, evidence, steps, or results.
- Preserve `ProjectDetailPage -> ContractReviewWorkspace`, its two-panel layout, `ProjectMaterialsViewer`, `ProjectReviewMaterialPicker`, `IntelligentReviewPipeline`, `F02ReviewResultPanel`, and `AiSemanticReviewPanel`. Do not create a second review workspace.
- Project-backed mode must not call direct `agent-review`, `contract-text-analysis`, breach, term, latest F02, or progress APIs. Legacy/showcase mode must retain those paths unchanged.
- Backend enums, UUIDs, hashes, policy versions, safe reason codes, and transport names never appear in customer-facing copy. Chinese presentation mappings are centralized and unknown codes fall back to safe Chinese text.
- Creating a run sends exactly `expectedContractVersion` and canonical `materialFileIds`; stable keys are persisted before POST. A recovered run is GET-only until the user explicitly continues eligible PENDING steps.
- Never execute `MATERIAL_VALIDATION`; never re-execute COMPLETED, FAILED, or UNKNOWN steps. Execute the four eligible first attempts with bounded concurrency and reconcile with one authoritative run GET.
- Evidence focus uses the server-owned file and bounded excerpt. It uses real locator fields only when available; otherwise it opens the source material and says precise positioning is unavailable, without fabricating coordinates.
- Every behavior begins with a focused RED test, followed by minimal GREEN and regression. No `.superpowers/sdd/**/task-*.md`, `RESULT.md`, dependency upgrades, lockfile rewrites, customer files, real DM8, Qwen, or PaddleOCR.

---

### Task 1: Typed ReviewRun transport and HTTP data source

**Files:** Create `frontend/src/features/contract/reviewRunTypes.ts`, `frontend/src/features/contract/data/reviewRunDataSource.ts`, `frontend/src/features/contract/data/httpReviewRunDataSource.ts`; test `frontend/src/features/contract/data/httpReviewRunDataSource.test.ts`.

**Interfaces:** Define exact transport types for run, material, step, attempt, output, finding, evidence, F02 task/check rows and statuses. Expose `createReviewRun(projectId,contractId,expectedContractVersion,materialFileIds,key,options)`, `getReviewRun(projectId,contractId,runId,options)`, `executeReviewStep(projectId,contractId,runId,stepId,key,options)`, and `getReviewStep(...)`.

- [x] **Step 1: RED.** Add request-spy tests requiring encoded Phase 2B paths, Session/CSRF-compatible shared `request`, exact create body, `Idempotency-Key` on commands only, empty execute body, and no `any` in the DTO/data-source files.
- [x] **Step 2: verify RED.** Run `node --test --experimental-strip-types src/features/contract/data/httpReviewRunDataSource.test.ts`; observe missing-module/interface failures.
- [x] **Step 3: minimal GREEN.** Implement the transport types and HTTP adapter without mapping or customer copy. Keep nullable API fields explicit and arrays readonly at the frontend boundary.
- [x] **Step 4: GREEN.** Rerun the focused test and a TypeScript/Vite build checkpoint.

### Task 2: Active-run pointer and immutable operation identities

**Files:** Create `frontend/src/features/contract/reviewRunOperation.ts`; test `frontend/src/features/contract/reviewRunOperation.test.ts`.

**Interfaces:** Store an active pointer under `(actorId,projectId,contractId)` and separate create/step operation records. Create keys before network calls; reuse them until the server proves an outcome. Persist only runId and operation keys.

- [x] **Step 1: RED.** Test scope isolation, stable create retry, stable per-run/per-step execute key, malformed-storage fail-closed behavior, pointer clearing with expected identity, and serialized payloads containing no result/finding/evidence/material text.
- [x] **Step 2: verify RED.** Run the focused operation test and observe absent implementation failures.
- [x] **Step 3: minimal GREEN.** Implement validated `sessionStorage` persistence using `createOperationKey`, namespaced keys, immutable expected identities, and safe Chinese storage errors.
- [x] **Step 4: GREEN.** Rerun focused tests, including simulated refresh with a fresh persistence instance.

### Task 3: ReviewRun presentation model and safe Chinese mappings

**Files:** Create `frontend/src/features/contract/reviewRunPresentation.ts`; test `frontend/src/features/contract/reviewRunPresentation.test.ts`.

**Interfaces:** Convert transport DTOs into customer-facing `DurableReviewPresentation`: five pipeline stages, run/step status copy, action eligibility, terminal advice, safe reason copy, and source/finding labels. Transport DTOs never cross into JSX directly.

- [x] **Step 1: RED.** Cover all run/step statuses, enum-order-independent input, `UNKNOWN != FAILED`, mixed semantic/breach/term outcomes, terminal advice, unknown reason fallback, and static assertions that projected text excludes ReviewRun/ReviewStep/Finding/Evidence, UUIDs, hashes, policy names, and raw enum literals.
- [x] **Step 2: verify RED.** Run the focused presentation test; observe missing mappings.
- [x] **Step 3: minimal GREEN.** Centralize mappings: 材料确认、精确校验、智能语义审查、违约条款审查、履约期限与终止条件审查; use 审核记录/审核环节/审核问题/审核依据 and safe fallback copy.
- [x] **Step 4: GREEN.** Rerun tests and scan the presentation output fixtures for forbidden technical language.

### Task 4: Project-backed ReviewRun orchestration hook

**Files:** Create `frontend/src/features/contract/useProjectContractReviewRun.ts`; test `frontend/src/features/contract/useProjectContractReviewRun.test.ts`.

**Interfaces:** The hook accepts actor/project/contract/version/selected files plus injected ReviewRun data source/persistence. It returns frozen selection, durable run, projected state, `start`, `continuePending`, `startNew`, reload/error state, and never calls legacy direct APIs.

- [x] **Step 1: RED.** Test initial pointer GET, invalid/404 pointer clearing, no auto-execution on mount, create exact command/key, lost-response create retry, stable execute keys, bounded four-step `Promise.allSettled`, no material-validation execute, one final GET, and stale/unmounted response suppression.
- [x] **Step 2: verify RED.** Run the hook test harness and observe missing hook behavior.
- [x] **Step 3: minimal GREEN.** Implement focused orchestration with AbortController/generation guards. On new run execute only PENDING F02/semantic/breach/term steps; on recovery require explicit continue; after branch settlement reload the complete durable run.
- [x] **Step 4: GREEN.** Rerun focused tests for create/refresh/recovery/concurrency and verify FAILED/UNKNOWN/COMPLETED are never resubmitted.

### Task 5: Existing workspace integration without a second shell

**Files:** Modify `frontend/src/features/project/components/ProjectDetailPage.tsx`, `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`; create or extend `frontend/src/features/contract/contractReviewRunWorkspace.test.ts`.

**Interfaces:** ProjectDetail supplies the existing workspace with explicit Project-backed ReviewRun dependencies/context. ContractReviewWorkspace selects durable orchestration only when project materials/project identity exist; showcase/legacy retains current hooks.

- [x] **Step 1: RED.** Static/component harness tests prove one workspace entry, the existing two panels/components remain, Project-backed mode wires ReviewRun source, and legacy/showcase paths still wire direct sources.
- [x] **Step 2: verify RED.** Run the workspace test plus current `projectDetailViewModel`/workspace tests; observe missing durable integration assertions.
- [x] **Step 3: minimal GREEN.** Add the smallest mode boundary. Keep legacy hooks mounted/used only for legacy presentation; use durable hook state for all Project-backed actions and results.
- [x] **Step 4: GREEN.** Rerun focused workspace and Project entry regressions; search for a second review workspace or duplicated viewer shell.

### Task 6: Durable F02 projection adapter

**Files:** Create `frontend/src/features/contract/reviewRunF02Adapter.ts`; test `frontend/src/features/contract/reviewRunF02Adapter.test.ts`; minimally modify `frontend/src/features/contract/components/F02ReviewResultPanel.tsx` only if its current props cannot consume the adapter output.

**Interfaces:** Adapt the F02 step's durable `auditTask` and `auditCheckResults` into existing `ReviewResult`/panel-compatible facts. No call to latest-result/progress/direct F02 APIs and no fabricated acknowledgement.

- [x] **Step 1: RED.** Test exact task/check identity, risk/block aggregation parity, COMPLETED output, FAILED/UNKNOWN/no-output states, preserved item values and ordering, and absence of direct API calls.
- [x] **Step 2: verify RED.** Run the focused adapter and F02 panel tests; observe missing adapter failures.
- [x] **Step 3: minimal GREEN.** Map only persisted F02 rows. Projected progress contains the source-preparation fact and check facts required by the current panel; acknowledgement stays unavailable rather than invented.
- [x] **Step 4: GREEN.** Rerun existing F02 presentation regressions and ensure the legacy acknowledgement path is unchanged.

### Task 7: Durable semantic, breach, and term presentation

**Files:** Create `frontend/src/features/contract/reviewRunSemanticAdapter.ts`; test `frontend/src/features/contract/reviewRunSemanticAdapter.test.ts`; minimally extend `frontend/src/features/contract/contractTextAnalysisPresentation.ts` and `components/AiSemanticReviewPanel.tsx` through presentation props rather than parsing summaries.

**Interfaces:** Normalize persisted finding/evidence rows by step into the existing semantic panel's business view. Preserve source (`MODEL_ASSISTED` vs deterministic), status/attention/risk, summary/recommendation, and evidence references without reconstructing old clause DTOs from prose.

- [x] **Step 1: RED.** Test semantic/breach/term sections, mixed sibling terminal states, model-assisted notice, deterministic clause labels, no raw response/prompt/provider claims, and no summary-string reverse parsing.
- [x] **Step 2: verify RED.** Run focused semantic adapter/panel tests; observe absent durable view path.
- [x] **Step 3: minimal GREEN.** Add a durable panel presentation input alongside the existing legacy DTO path. Render findings as persisted business issues with source material actions; retain completed siblings when another step fails or is unknown.
- [x] **Step 4: GREEN.** Rerun semantic presentation, clause review, and AI acknowledgement regressions; Project-backed local acknowledgement remains presentation-only only where already supported and is not server truth.

### Task 8: Evidence-to-material viewer focus

**Files:** Modify `frontend/src/features/contract/components/ProjectMaterialsViewer.tsx`, `frontend/src/features/contract/projectMaterialSourceSelection.ts`, and/or add `frontend/src/features/contract/reviewRunEvidencePresentation.ts`; test `frontend/src/features/contract/reviewRunEvidencePresentation.test.ts` and existing material viewer/source-selection tests.

**Interfaces:** Evidence action selects its exact run material by fileId and uses page/paragraph/segment/offset metadata only when server marks the locator available. Bounded excerpt is display-only context.

- [x] **Step 1: RED.** Test exact file focus, page/paragraph projection, locator-unavailable fallback, absent excerpt, file no longer present, and no fabricated `ai:validated-excerpt`/page/paragraph values.
- [x] **Step 2: verify RED.** Run focused evidence and current viewer tests; observe new durable evidence failures.
- [x] **Step 3: minimal GREEN.** Project a safe focus command and existing viewer props. Locator-unavailable opens the source file and displays “暂无法精确定位原文位置，可查看来源材料。”
- [x] **Step 4: GREEN.** Rerun viewer and source-selection regressions.

### Task 9: Customer-facing Chinese presentation static gate

**Files:** Create `frontend/src/features/contract/reviewRunCustomerLanguage.test.ts`; adjust only ReviewRun presentation/components found by the RED scan.

**Interfaces:** The test scans the Project-backed customer-rendering boundary, not transport/domain files, for forbidden technical tokens and raw identifiers.

- [x] **Step 1: RED.** Assert rendered/static customer copy does not expose ReviewRun, ReviewStep, Finding, Evidence, MATERIAL_VALIDATION, F02_DETERMINISTIC, SEMANTIC_AI, MODEL_ASSISTED, safeReasonCode, hashes, policy versions, UUID labels, or English backend errors.
- [x] **Step 2: verify RED.** Run the scan and record the exact existing/new leaks.
- [x] **Step 3: minimal GREEN.** Route every Project-backed status/error/label through centralized mappings; keep technical fields accessible only to transport and tests.
- [x] **Step 4: GREEN.** Rerun language, presentation, and component harness tests.

### Task 10: Refresh, reopen, UNKNOWN, and explicit continuation UX

**Files:** Modify `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`, `frontend/src/features/contract/components/IntelligentReviewPipeline.tsx`; extend `useProjectContractReviewRun.test.ts`, `contractReviewRunWorkspace.test.ts`, `intelligentReviewPipelinePresentation.test.ts`.

**Interfaces:** Frozen selection disables material checkboxes while a run pointer is active. Reopened runs display stored step truth. Only a user action continues eligible PENDING steps; a separate “开始新的审核” clears the pointer and returns to selection.

- [x] **Step 1: RED.** Test refresh restores outputs, re-login under another actor does not reuse the pointer, frozen picker, explicit continue button, no continuation for UNKNOWN/FAILED-only runs, GET truth overriding prior local loading, and new-review pointer clearing without deleting history.
- [x] **Step 2: verify RED.** Run focused workspace/hook/pipeline tests and observe missing UX state.
- [x] **Step 3: minimal GREEN.** Add Chinese status/actions and correct button disabling. Keep UNKNOWN visibly distinct (“结果状态待确认”) and FAILED as proven failure; do not manufacture runtime progress.
- [x] **Step 4: GREEN.** Rerun all Phase 2C tests and refresh/reopen regressions.

### Task 11: Legacy/showcase and Project workflow regression

**Files:** Extend existing tests only: `frontend/src/features/contract/useContractReviewWorkspace.test.ts`, `useContractSemanticAnalysis.test.ts`, `useContractClauseReviews.test.ts`, `contractTextAnalysisPresentation.test.ts`, `frontend/src/features/project/projectDetailViewModel.test.ts`, candidate/upload/material tests.

**Interfaces:** Legacy/showcase behavior remains the exact pre-Phase2C direct flow; Project-backed review exclusively uses ReviewRun APIs.

- [x] **Step 1: RED.** Add spies proving Project mode makes zero direct F02/semantic/breach/term/latest/progress calls and showcase/legacy still make their expected calls. Cover candidate promotion entry, upload, material selection, and existing source viewer behavior.
- [x] **Step 2: verify RED.** Run the regression set and confirm any failure represents mode leakage.
- [x] **Step 3: minimal GREEN.** Correct only dependency selection/mode guards; do not rewrite the legacy hooks or project intake behavior.
- [x] **Step 4: GREEN.** Run every contract/project test file with Node's test runner.

### Task 12: Full verification, static scope gates, review, and push

**Files:** No new production file expected; only fixes backed by new RED tests. Do not add task reports.

- [x] **Step 1: clean install and full frontend tests.** Run `npm.cmd ci`, focused Phase 2C tests, then `node --test --experimental-strip-types "src/**/*.test.ts"`; record tests/failures/skipped. If the shell cannot expand the glob, enumerate with `Get-ChildItem` and pass the explicit paths.
- [x] **Step 2: build.** Run `npm.cmd run build`; record success/failure and artifact build result. Browser visual verification is separate and may be `NOT VERIFIED` if Chromium is unavailable.
- [x] **Step 3: static gates.** Run `git diff --check`, `git status --short`, `git diff --stat`; prove zero changes under `backend/`, `db/`, `services/`, and no changes to Phase 1/2 migrations. Scan customer JSX for forbidden technical terms and production code for direct APIs in Project-backed mode.
- [x] **Step 4: independent code review.** Use `superpowers:requesting-code-review`, fix only actionable Phase 2C issues with RED regressions, and rerun focused/full gates.
- [x] **Step 5: commit and push.** Commit small frontend changes, then ordinary-push only `feature/contract-review-run-phase2c-frontend-20260916` to origin. Do not create a PR, merge, deploy, or start Phase 3.

## Compatibility and rollback

The frontend integration is additive and has no schema/API/backend mutation. Removing the Project-backed ReviewRun mode restores the pre-existing direct workspace behavior; already durable runs remain in DM8 and are not deleted. Legacy/showcase remains available throughout. Verification in this slice is local frontend UNIT/INTEGRATION only; real DM8, real customer material, and browser visual acceptance remain NOT VERIFIED.
