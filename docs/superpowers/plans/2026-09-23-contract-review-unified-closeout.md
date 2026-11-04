# Contract Review Unified Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the durable preparation gaps and present one exact ReviewRun as an eight-category contract review workspace with preserved source navigation and copyable opinions.

**Architecture:** Keep Project/Candidate/Contract/ReviewRun authority unchanged. Add one idempotent server command for unfinished machine-owned preparation stages, then build a pure frontend projection from frozen ReviewRun outputs into eight fixed business categories. Persist new F02 source evidence through the existing ReviewRun finding/evidence repositories so reopening never depends on mutable current Candidate data.

**Tech Stack:** Java 17, Spring Boot, MyBatis/DM8, React 18, TypeScript, Node test runner, Vitest/Vite build.

**Spec:** `docs/superpowers/specs/2026-09-23-contract-review-unified-closeout-increment.md`

## Global Constraints

- Baseline is `e320cd072b3be66fd38d9e566c71cad53a0d1b3f` on `feature/contract-eight-category-view-20260923`.
- Do not modify Candidate authority, Confirmation/Promotion meaning, ReviewRun lifecycle, F02 rules, ACK meaning, or report/artifact compatibility.
- GET remains read-only; mount, refresh, accordion, source focus, ACK, and copy never create ReviewRun or call a model.
- DM8 is the system of record. This plan adds no DM8 schema and runs no DDL.
- Historical views consume the exact run snapshot and persisted output only.
- Original materials and integration configuration remain read-only.
- A-mini, B, and C implementation are excluded until their separate implementation and DDL approvals exist.
- Do not push, merge, deploy customer environments, clear databases, flush Redis, or overwrite stored objects.
- C02/C03, eight-category UI, F02 source persistence, isolated integration writes and limited provider transmission were approved on 2026-09-23 subject to the revised spec. Every task below must honor those attached conditions.

## Review Focus

- A stale identity decision after Project metadata version change must produce a recoverable current assessment without reusing the old human decision; Task 2 adds the adversarial test.
- An outcome-unknown resume request must retain the same operation key and avoid duplicate Candidate creation; successful response loss must replay the original result after the blocker advances; Task 3 adds double-submit/reconciliation tests.
- A request with the same blocker but a changed Project/file/parse/role/identity input must fail closed through `expectedRecoveryInputFingerprint`.
- A single-file resume may not scan, assess, or call a model for sibling files; GET remains side-effect free.
- A completed step with no category-specific check must remain `NOT_COVERED` or `PARTIALLY_COVERED`, never PASS; Task 4 pins this mapping.
- A historical run with a missing current Project file must keep the exact frozen material identity and fail closed for the unavailable object; Task 7 covers it.
- Rapid evidence A→B focus followed by a late A result must leave B selected; Task 7 adds the interaction test.
- F02 V2 evidence must pass completion, read, persistence and recovery validators; old empty-evidence runs remain readable while new-format evidence cannot silently downgrade.

---

### Task 1: C01/C04 truthful preparation presentation

**Status:** Implemented in the working tree; review and broader verification remain.

**Files:**
- Modify: `frontend/src/features/project/projectProgressionPresentation.ts`
- Modify: `frontend/src/features/project/components/ProjectFileProgressionPanel.tsx`
- Modify: `frontend/src/features/project/useProjectWorkspace.ts`
- Modify: `frontend/src/features/project/components/ProjectDetailPage.tsx`
- Test: `frontend/src/features/project/projectProgressionPresentation.test.ts`
- Test: `frontend/src/features/project/useProjectWorkspaceCandidateLoading.test.ts`

**Interfaces:**
- Consumes: existing `ProjectFileProgression.stage`, `blocker`, `availableActions`.
- Produces: blocker-first `statusLabel/detailLabel`; `candidateLoadState` and `candidateError` from `useProjectWorkspace`.

- [x] **Step 1: Add failing blocker-first presentation tests**

Assert `PROJECT_IDENTITY_NOT_CONFIRMED`, `CANDIDATE_MATERIALIZATION_PENDING`, and `PROJECT_CONTRACT_ASSOCIATION_UNPROVEN` never render “项目归属已确认”.

- [x] **Step 2: Run RED**

Run: `node --test --experimental-strip-types src/features/project/projectProgressionPresentation.test.ts`

Expected: two new cases fail against the stage-only mapping.

- [x] **Step 3: Add blocker-first presentation and visible detail copy**

Map known blocker codes before stage labels and render `detailLabel` in `ProjectFileProgressionPanel`.

- [x] **Step 4: Add failing Candidate loading tests**

Exercise absent Candidate, Candidate GET failure, and old-project late response after a project switch with real React hooks.

- [x] **Step 5: Add request-generation isolation and Candidate stage state**

Expose `LOADING | ABSENT | READY | ERROR`, preserve Project detail on Candidate failure, and invalidate async writes when project/reload generation changes.

- [x] **Step 6: Run focused and nearby GREEN**

Run:

```powershell
node --test --experimental-strip-types src/features/project/projectProgressionPresentation.test.ts
node --test --experimental-strip-types src/features/project/useProjectWorkspaceCandidateLoading.test.ts
node --test --experimental-strip-types src/features/project/candidateSupportAuthorityPanel.test.ts
```

Expected: 5, 4, and 19 tests pass respectively.

### Task 2: C02/C03 server progression contract

**Approval:** Granted 2026-09-23 subject to input fingerprint and single-file execution revisions.

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileProgressionProjectionService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/identity/EffectiveProjectIdentity.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/identity/EffectiveProjectIdentityResolver.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileRecoveryInputFingerprint.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileProgressionRecoveryProjectionTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/identity/EffectiveProjectIdentityResolverTest.java`

**Interfaces:**
- Consumes: current Project version, file SHA, parse ID/version, role decision, identity assessment/decision, Candidate state.
- Produces: stable blockers, one authorized recovery action, and a versioned SHA-256 `recoveryInputFingerprint` bound to Project/file/parse/role/identity inputs.

- [x] **Step 1: Write failing stale-tuple and unfinished-stage tests**

Construct these inputs: unconfirmed Project with old assessment, confirmed Project after version advance, current role decision with missing identity assessment, current human SAME_PROJECT decision with missing Candidate, and unchanged fully current tuple. Assert the exact blocker/action matrix from the spec. Prove that the same blocker with changed Project version, file SHA, parse tuple, role decision or identity tuple yields a different fingerprint, while identical canonical input yields the same fingerprint.

- [x] **Step 2: Run tests to verify RED**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=ProjectFileProgressionRecoveryProjectionTest,EffectiveProjectIdentityResolverTest" test
```

Expected: stale states currently return `IDENTITY_CONFIRMED/PROJECT_IDENTITY_NOT_CONFIRMED` with no action.

- [x] **Step 3: Add typed recovery reason to effective identity**

Add `recoveryReason` with values `PROJECT_METADATA_CONFIRMATION_REQUIRED`, `IDENTITY_ASSESSMENT_PENDING`, `IDENTITY_ASSESSMENT_STALE`, or `null`. Derive it from exact current tuple checks; do not mark a stale decision current.

- [x] **Step 4: Project exact blocker and server-authorized action**

Update `ProjectFileProgressionProjectionService` to expose only the action owned by the current unfinished machine stage and the canonical input fingerprint for that action. Preserve existing human confirmation actions. GET computes only this projection and must not create facts or call a model.

- [x] **Step 5: Run focused GREEN and current progression regression**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=ProjectFileProgressionRecoveryProjectionTest,EffectiveProjectIdentityResolverTest,ProjectFileProgressionConfirmationVersionTest" test
```

Expected: all tests pass; existing Candidate confirmation/Promotion gates remain unchanged.

### Task 3: Explicit idempotent progression resume command

**Approval:** Granted 2026-09-23 subject to the revised replay, uncertainty, single-file and transaction protocol.

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileProgressionRecoveryService.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileProgressionRecoveryCommand.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileProgressionRecoveryResult.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectIntakeProgressionService.java`
- Modify or create: the single-file role assessment helper used by both resume and the existing batch entry point
- Modify: existing durable idempotency repository/transaction coordinator for scope `PROJECT_FILE_PROGRESSION_RESUME`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Modify: `frontend/src/features/project/data/projectDataSource.ts`
- Modify: `frontend/src/features/project/data/httpProjectDataSource.ts`
- Modify: `frontend/src/features/project/types.ts`
- Modify: `frontend/src/features/project/useProjectWorkspace.ts`
- Modify: `frontend/src/features/project/components/ProjectFileProgressionPanel.tsx`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectFileProgressionRecoveryApiIntegrationTest.java`
- Test: `frontend/src/features/project/projectProgressionRecovery.test.ts`

**Interfaces:**
- Consumes: `ProjectFileProgressionRecoveryCommand(projectId, fileId, expectedBlocker, expectedRecoveryInputFingerprint, idempotencyKey, actor)`.
- Produces: `POST .../progression/resume` returning current `ProjectFileResponse` and the original durable operation result on replay; frontend `resumeProgression(projectId, fileId, expectedBlocker, expectedRecoveryInputFingerprint, options)`.

- [x] **Step 1: Write backend failure-injection tests**

Cover upload committed then role assessment failure, role decision committed then identity assessment failure, identity decision committed then Candidate materialization failure, same blocker with a changed input fingerprint, successful completion followed by response loss and same-key replay, double click/concurrent requests, and outcome-unknown replay. Assert one file, one human role decision, one human identity decision, at most one Candidate, and stable result identity. Add two files and prove resuming A neither decides nor invokes the model for B. Add a GET-only test proving no fact, idempotency claim or model call is created.

- [x] **Step 2: Run backend test to verify RED**

Run: `.\mvnw.cmd -q "-Dtest=ProjectFileProgressionRecoveryApiIntegrationTest" test`

Expected: route not found and no server recovery service.

- [x] **Step 3: Implement the recovery service**

After authentication and path identity checks, read durable idempotency by `scope + actor + key` before evaluating the current blocker. A matching COMPLETE record replays its stored result after verifying result ownership even if the blocker advanced. A mismatched request fingerprint fails closed. START/OUTCOME_UNCERTAIN retains the same key and reconciles against durable result identities. Only a missing claim may compare current blocker and `expectedRecoveryInputFingerprint` and create a short START claim.

Execute exactly one single-file operation: role assessment helper, identity assessment helper, or Candidate materialization. The role helper must not call project-wide `ensureRoleAssessments`; refactor the old batch path to invoke the same helper per file. Do not hold a database lock/transaction across a remote model call. Publish result and COMPLETE in short transactions with guarded input ownership checks.

- [x] **Step 4: Expose the additive POST endpoint**

Validate `Idempotency-Key` with the existing safe pattern, accept `{ "expectedBlocker": "...", "expectedRecoveryInputFingerprint": "..." }`, and return the existing file response projection. Emit endpoint-local machine-readable errors including input changed, action not allowed, in progress, outcome uncertain, identity unproven and request mismatch. Do not modify GET behavior.

- [x] **Step 5: Write frontend retained-key tests**

Assert double click sends one logical command, an uncertain result retains the exact key and expected input fingerprint, successful response loss retries the same key and accepts the replayed result, refresh only GETs, guarded-clear occurs only after a proven no-effect rejection, and project switch prevents late results from mutating the new project. Current-state advancement alone must not clear the operation identity.

- [x] **Step 6: Implement the frontend command and action button**

Use browser retained operation storage scoped to actor/project/file/action/recovery input fingerprint. After a proven response, reload durable Project detail; never infer completion from HTTP timeout. OUTCOME_UNCERTAIN preserves the exact command/key; identity mismatch fails closed.

- [x] **Step 7: Run focused GREEN and continuity regression**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=ProjectFileProgressionRecoveryApiIntegrationTest,ProjectIntakeContinuityApiIntegrationTest" test
node --test --experimental-strip-types src/features/project/projectProgressionRecovery.test.ts src/features/project/useProjectWorkspaceCandidateLoading.test.ts
```

### Task 4: Exact-run eight-category projection and opinions

**Approval:** Granted 2026-09-23 for a pure exact-run frontend projection with the revised coverage rules.

**Files:**
- Create: `frontend/src/features/contract/eightCategoryReviewProjection.ts`
- Create: `frontend/src/features/contract/eightCategoryReviewProjection.test.ts`
- Create: `frontend/src/features/contract/contractReviewOpinion.ts`
- Create: `frontend/src/features/contract/contractReviewOpinion.test.ts`
- Reuse: `frontend/src/features/contract/reviewRunF02Adapter.ts`
- Reuse: `frontend/src/features/contract/reviewRunSemanticAdapter.ts`

**Interfaces:**
- Consumes: `ReviewRunDto`, frozen material file-name map, optional persisted F02 source map.
- Produces: `ContractReviewCategoryView[8]`, `buildCategoryOpinion(category)`, and `buildOverallOpinion(categories, runStatus)`.

- [x] **Step 1: Write failing eight-category order and coverage tests**

Use fixtures for all steps complete, one step failed, one step unknown, absent category-specific checks, F02 `NOT_EVALUATED`, multiple line-item results, and duplicated semantic/BREACH finding references. Assert fixed IDs/order, honest coverage, complete F02 row preservation, stable-identity deduplicated attention counts, and exact-run-only inputs.

- [x] **Step 2: Run projection tests to verify RED**

Run: `node --test --experimental-strip-types src/features/contract/eightCategoryReviewProjection.test.ts`

Expected: module not found.

- [x] **Step 3: Implement pure projection**

Map all F02 check results to category 1; semantic categories, BREACH and TERM according to the spec’s table. Category 2 remains partial/uncovered without a specified template; category 5 distinguishes identity basics from qualification; category 6 is uncovered without actual attachment reference/match output; category 7 tracks effectiveness and copies separately. Never infer PASS from an empty list. If a partial category contains a persisted difference, keep that difference prominent and list the uncovered scope separately.

- [x] **Step 4: Write failing opinion tests**

Assert item opinion contains only persisted facts and recommendations; overall opinion lists unfinished/uncovered categories; duplicated finding IDs appear once; no output says “全部合规” when any category is partial, insufficient, unavailable, or unfinished.

- [x] **Step 5: Implement deterministic opinion builders**

Build plain text from the category view only. Include run identity/status and category headings; do not add legal citations or call a provider.

- [x] **Step 6: Run GREEN**

Run:

```powershell
node --test --experimental-strip-types src/features/contract/eightCategoryReviewProjection.test.ts
node --test --experimental-strip-types src/features/contract/contractReviewOpinion.test.ts
```

### Task 5: Eight-category accordion and copy interaction

**Approval:** Granted 2026-09-23; page skeleton remains left materials/right eight categories with overview→category→issue hierarchy.

**Files:**
- Create: `frontend/src/features/contract/components/EightCategoryReviewPanel.tsx`
- Create: `frontend/src/features/contract/eightCategoryReviewPanel.test.ts`
- Create: `frontend/src/features/contract/clipboard.ts`
- Modify: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`
- Test: `frontend/src/features/contract/contractReviewRunWorkspace.test.ts`

**Interfaces:**
- Consumes: eight category views, source-focus callback, ACK renderer, clipboard port.
- Produces: single-open accordion, per-category copy, overall preview/copy, and source buttons.

- [x] **Step 1: Write failing rendering and interaction tests**

Assert all eight fixed titles render collapsed; clicking category 3 opens only 3; clicking 4 closes 3; collapsed rows expose only title/status/count; copy success/failure is visible; copying and accordion changes do not call review/model data-source methods.

- [x] **Step 2: Run test to verify RED**

Run: `node --test --experimental-strip-types src/features/contract/eightCategoryReviewPanel.test.ts`

- [x] **Step 3: Implement the panel and clipboard port**

Keep `openCategoryId: CategoryId | null`; render item summary, facts/difference, evidence buttons, recommendation and original-source action only inside the open item. Use injected clipboard for deterministic tests and `navigator.clipboard.writeText` in production.

- [x] **Step 4: Replace technical top-level result blocks in Project-backed mode**

In `ContractReviewWorkspace`, derive the projection from `durableReview.run`. Render the eight-category panel as the main right-side result. Keep retry, ACK, report/artifact and legacy non-Project components as secondary compatibility controls.

- [x] **Step 5: Run focused GREEN and workspace regression**

Run:

```powershell
node --test --experimental-strip-types src/features/contract/eightCategoryReviewPanel.test.ts src/features/contract/contractReviewRunWorkspace.test.ts src/features/contract/reviewRunSemanticAdapter.test.ts
```

### Task 6: Persist F02 source evidence for exact-run navigation

**Approval:** Granted 2026-09-23 for existing ReviewRun tables only, subject to complete write/read/recovery validation. No DDL is used.

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/executor/FrozenF02ResultMapper.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/executor/FrozenF02ReviewStepExecutor.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/ReviewStepCompleteTransactionCoordinator.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/ReviewStepOutputReadService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/ReviewWorkerTransactionCoordinator.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/executor/FrozenF02ResultMapperTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ReviewStepOutputReadServiceTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ReviewWorkerF02RecoveryTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ReviewWorkerTransactionCoordinatorTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunApiIntegrationTest.java`
- Test: ReviewRun persistence adapter integration test selected after repository seam characterization
- Test: owner/non-owner ACK/report/artifact/original-object compatibility suites
- Modify: `frontend/src/features/contract/reviewRunF02Adapter.ts`
- Test: `frontend/src/features/contract/reviewRunF02Adapter.test.ts`

**Interfaces:**
- Consumes: frozen run/material identities, completed F02 `AuditCheckResult`s, validated source projections restricted to run materials.
- Produces: output discriminator `AUDIT_TASK_F02_NAVIGATION_V2`, finding schema version `2`, locator schema version `1`, stable `checkResultId → findingId → evidenceId` relations and ordered evidence rows; adapter source map keyed by actual checkResult ID. `AuditTask/AuditCheckResult` remains the only F02 result authority.

- [x] **Step 1: Write failing backend evidence tests**

Cover internal contract amount source, contract↔award dual sources, multiple line items, supplier NFKC variant, unavailable locator, source outside frozen materials, field length/null/source-role/parse-tuple constraints, and reopen after mutable Candidate changes. Assert stable independent IDs and persisted evidence remains byte-for-byte stable after repository reload. Characterize old-format empty evidence as legal and new-format missing/invalid evidence as rejected rather than silently downgraded.

- [x] **Step 2: Run backend RED**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=FrozenF02ResultMapperTest,ContractReviewRunApiIntegrationTest" test
```

Expected: F02 executor emits empty findings/evidence; completion and read validators reject F02 findings/evidence; recovery completes internal F02 with empty lists.

- [x] **Step 3: Implement deterministic F02 finding/evidence mapping**

Create an explicit versioned relation from each checkResult ID to an independent finding ID and evidence IDs. Validate every source against frozen `fileId/SHA/parseResultId/parseVersion`, source role and current table constraints. Persist only verified excerpts/locators; when location is unproven record the honest unavailable state without invented excerpt/page/offset. Do not query current Candidate/support or current files while reading a historical run. If existing columns cannot carry the version and stable relation without abusing summary text, stop and report the exact schema constraint.

- [x] **Step 4: Update completion and read contracts**

Pass lists through `StepComputationResult`. Update `ReviewStepCompleteTransactionCoordinator.validateResult` and `ReviewStepOutputReadService.output/validateFindings` so legacy output type `AUDIT_TASK` may have empty evidence, while `AUDIT_TASK_F02_NAVIGATION_V2` requires a complete, uniquely related and valid set. Persist findings/evidence atomically with outer step completion. Ensure report/ACK projections do not double count the AuditCheckResult and its locator carrier.

- [x] **Step 5: Make interrupted F02 recovery equivalent**

Update `ReviewWorkerTransactionCoordinator.recover` so an already committed internal F02 AuditTask is read once and mapped through the same versioned evidence mapper. Complete the existing outer step without rerunning rules and without creating another AuditTask. Test interruption after internal commit, response/restart recovery, and stable IDs across repeated recovery.

- [x] **Step 6: Add frontend adapter tests and mapping**

Match findings to audit check results using only the persisted stable relation. Produce the same `FindingSourceProjection` shape consumed by `FindingComparisonSources`; two sources remain separately clickable and all multi-line results remain present. Never parse summary text or infer association from array position.

- [ ] **Step 7: Prove durable read, restart, old/new coexistence and permissions**

Persist through the real repository adapter, GET the run, create a new authenticated browser/session, restart the backend, and GET the exact run again. Prove old empty-evidence runs remain readable, new-format incomplete evidence is rejected, owner permissions remain, and another user cannot read run/evidence/original object or operate ACK/report/artifact.

- [x] **Step 8: Run focused GREEN**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=FrozenF02ResultMapperTest,ReviewStepOutputReadServiceTest,ReviewWorkerF02RecoveryTest,ReviewWorkerTransactionCoordinatorTest,ContractReviewRunApiIntegrationTest" test
node --test --experimental-strip-types src/features/contract/reviewRunF02Adapter.test.ts src/features/contract/f02FindingComparisonSources.test.ts
```

### Task 7: L01—L12 navigation and historical isolation

**Files:**
- Modify: `frontend/src/features/contract/reviewRunEvidencePresentation.ts`
- Modify: `frontend/src/features/contract/components/ProjectMaterialsViewer.tsx`
- Modify: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`
- Test: `frontend/src/features/contract/contractEvidenceNavigationRegression.test.ts`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunOwnershipApiIntegrationTest.java`
- Evidence: `docs/evidence/contract-evidence-navigation-regression-20260925.md`

**Interfaces:**
- Consumes: exact persisted evidence and frozen material IDs.
- Produces: selected tab/file/version, verified locator focus, and honest unavailable-locator notice.

- [x] **Step 1: Write the L01—L12 characterization suite**

Exercise unique text highlight, paragraph locator, PDF page, same-file two locations, contract/award dual source, multi-source clause, same name/different frozen version, duplicate text downgrade, locator unavailable, unauthorized object, missing object, and hash/parse conflict.

- [x] **Step 2: Run the suite before UI integration**

Run: `node --test --experimental-strip-types src/features/contract/contractEvidenceNavigationRegression.test.ts`

Expected: existing semantic and F02 dual-source cases pass; any uncovered historical or race case fails before its minimal fix.

- [x] **Step 3: Add generation isolation to evidence focus**

Scope async material reads to project/run/evidence generation. Clear focus on manual tab change or run switch. A late prior focus may not restore an older tab/highlight.

- [x] **Step 4: Prove exact-run historical behavior and permissions**

Backend tests assert requested owner can read run/evidence and another same-role user cannot read run, ACK, report, artifact or original object. Current unrelated bad files must not block a valid frozen run; a missing frozen object must fail closed.

- [x] **Step 5: Run navigation and permission GREEN**

Run:

```powershell
node --test --experimental-strip-types src/features/contract/contractEvidenceNavigationRegression.test.ts src/features/contract/reviewRunFrozenMaterials.test.ts
.\mvnw.cmd -q "-Dtest=ContractReviewRunOwnershipApiIntegrationTest" test
```

### Task 8: P1/P2/P2a entry consolidation

**Approval:** Granted 2026-09-23 without changing the frozen P1/P2/P2a/P3 page skeleton.

**Files:**
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/features/dashboard/DeliveryWorkbenchPage.tsx`
- Modify: `frontend/src/features/contract/components/ContractAuditLandingPage.tsx`
- Modify: `frontend/src/features/contract/components/ContractOverviewList.tsx`
- Modify: `frontend/src/features/project/components/ProjectDetailPage.tsx`
- Test: `frontend/src/app/deliveryNavigation.test.ts`
- Test: `frontend/src/features/contract/contractOverviewListPresentation.test.ts`

**Interfaces:**
- Consumes: existing workbench, contract task list, project detail and review navigation callbacks.
- Produces: three business-tool workbench, contract center pending/history, one centralized preparation route, and exact run open intent.

- [x] **Step 1: Write failing route/label tests**

Assert Project is no longer a required primary navigation concept for contract users; Contract Center exposes pending/history; opening pending goes to preparation; opening history carries exact `runId`; budget/procurement routes remain unchanged.

- [x] **Step 2: Run RED**

Run: `node --test --experimental-strip-types src/app/deliveryNavigation.test.ts src/features/contract/contractOverviewListPresentation.test.ts`

- [x] **Step 3: Consolidate the route state**

Use one contract-center navigation intent for pending/history. Keep internal `projectId/contractId/runId`; do not duplicate preparation form state between landing and Project detail.

- [x] **Step 4: Run GREEN and navigation regression**

Run the Step 2 command and verify direct F02 legacy compatibility still resolves existing records.

### Task 9: Automated final gate and controlled real-environment acceptance

**Files:**
- Create: `docs/evidence/contract-eight-category-acceptance-20260925.md`
- Update: `docs/evidence/contract-evidence-navigation-regression-20260925.md`
- Update: `docs/evidence/contract-review-release-ledger-20260922.md`
- Create: `docs/evidence/contract-review-self-verification-guide-20260925.md`

**Interfaces:**
- Consumes: approved environment operation sheet, read-only config, read-only originals 01—06, candidate build SHA.
- Produces: fresh automated counts and real acceptance matrix without secrets.

- [x] **Step 1: Run focused suites, both frontend runners, build, backend clean verify and FastAPI suite**

Run repository-standard Node runner and Vitest runner, `npm.cmd run build`, `backend\.\mvnw.cmd clean verify`, and the pinned Python 3.11 FastAPI suite. Record exact counts from output.

- [ ] **Step 2: Verify repository state**

Run:

```powershell
git status --short
git diff --check
git diff --stat
git rev-parse HEAD
```

- [x] **Step 3: Prove worker/database isolation before enabling a worker**

Using read-only queries derived from the actual worker claim predicate, inspect pending claimable steps, expired attempts/leases, worker ownership and any automatic restart/service processes connected to the same integration database. Port availability, a unique Redis namespace and a momentary zero-lease observation are insufficient. If an old task could be claimed, do not start the worker or mutate the task; report its IDs/status/age in sanitized form and request specific handling authorization. Record every process started by this run and only stop those PIDs.

- [ ] **Step 4: Execute one thin real chain after the worker preflight is safe**

Start backend/frontend/FastAPI from this worktree only, using session-local variable overrides. Log in through the application, create an isolated test object, upload one original set, complete real preparation, create one run, inspect all eight categories, focus one issue, copy an opinion, refresh, and reopen exact history.

- [ ] **Step 5: Verify provider identity, then expand to originals 01—06**

Before any original leaves the machine, verify the configured private-network host/port/service and actual model identity match the approved operation sheet. If they differ, pause external transmission and report. Only the approved non-sensitive 01—06 fixtures may be sent through this worktree's loopback FastAPI; never send credentials, tokens, database content, customer material, other project files, or switch provider. For each set record automatic extraction, human-corrected fields, all F02 outcomes (including multiple line-item rows), eight-category coverage, evidence navigation, provider calls, success/FAILED/UNKNOWN counts, refresh/restart and history permission result.

- [ ] **Step 6: Retain isolated test state and write the self-verification guide**

Keep the worktree, original source fixtures, isolated storage and created test results intact. Record candidate branch/HEAD and a deterministic dirty-build input hash, exact frontend/backend/FastAPI ports, complete start/stop commands, fixed storage path and Redis session namespace, 01—06 project names and Project/Contract/exact run IDs, two-entry/navigation steps, and absolute evidence paths. Distinguish fresh records from old records that remain unverified; do not record secrets or authentication bodies.

- [ ] **Step 7: Final evidence and completion review**

Write separate status for `CODE_REVIEW`, `UI_REQUIREMENTS`, `PREPARATION_FLOW`, `MATERIAL_EXTRACTION`, `EIGHT_CATEGORY_COVERAGE`, `EVIDENCE_NAVIGATION`, `HISTORY_SECURITY`, `ORIGINAL_E2E`, `OA_INTEGRATION`, and `CUSTOMER_MODEL_INTEGRATION`. Mark unverified items `PARTIAL` or `BLOCKED`.

## Explicit gated backlog retained by this plan

- **A-mini:** versioned AI fact extraction candidates; detailed design approved, implementation/provider material transmission not approved.
- **B:** amount-only line-item model; detailed design approved, implementation and DM8 DDL not approved.
- **C:** typed bilateral BREACH/TERM facts; detailed design approved, implementation/API/schema changes not approved.
- **Statistics:** deterministic persistent aggregation remains a following work package after P1/P2/P2a/P3 acceptance; no model arithmetic.
- **OA/customer model:** contract and adapter preparation only; no customer deployment or unapproved provider transmission.
