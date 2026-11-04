# Customer Portal + Contract UX Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the merged Contract Golden and F06 capabilities into one truthful customer portal with a direct contract material entry, exact-run results and persisted risk projection.

**Architecture:** Keep `App` and `AuthProvider` as the single application boundary. Add focused frontend shell, contract intake orchestration, contract portal and risk projection components that reuse existing Project and ReviewRun APIs; do not change backend, FastAPI or schema.

**Tech Stack:** React 18, TypeScript, Vite, Node test runner, esbuild test harness, existing Spring Boot/FastAPI regression suites.

**Spec:** `docs/superpowers/specs/2026-09-28-customer-portal-contract-flow-design.md`

## Global Constraints

- Exact baseline is `ccf674e0f6bc00fd6d6a8fb33acdfbdedf2c585e` on an isolated worktree.
- The ZIP is visual reference only; import no business logic, package/runtime files, mock data or configuration from it.
- Keep one App/Auth/API client and the fixed navigation order: 工作台 / 预算审查 / 采购审查 / 合同审核 / 黑名单 / 风险预估.
- Do not change F06 logic, Candidate authority, ReviewRun lifecycle, evidence authority, ACK/report, backend, FastAPI, DM8 schema, migration or deployment configuration.
- Cancelled upload performs zero business writes; normal multi-file selection creates one Project; ZIP uses secure archive intake.
- Risk uses persisted results only and shows no score when no real score exists.
- OA is explicitly unavailable and performs no request.
- No view, refresh, copy or history action may create a ReviewRun or call a model.
- Run only one authorized new 01 E2E after an exact committed and pushed checkpoint; do not start 02–06.

## Review Focus

- Browser file picker returns an empty list: Task 2 proves zero calls and `CANCELLED`.
- One ordinary selection contains an unsupported file among supported files: Task 2 proves validation happens before Project creation.
- Project creation succeeds and one later upload is outcome-uncertain: Task 2 proves Project and immutable file operation identity are retained.
- Risk API returns the same task in REVIEW and INSUFFICIENT queries: Task 3 proves durable-task deduplication.
- Internal Project destination is active while global navigation is 合同审核: Task 1 proves the parent tool remains highlighted.

---

### Task 1: Single portal shell and fixed business navigation

**Files:**
- Create: `frontend/src/app/PortalShell.tsx`
- Modify: `frontend/src/app/demoNavigation.ts`
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/features/dashboard/DeliveryWorkbenchPage.tsx`
- Modify: `frontend/src/styles/delivery-visual-v2.css`
- Test: `frontend/src/app/demoNavigation.test.ts`
- Test: `frontend/src/app/deliveryNavigation.test.ts`

**Interfaces:**
- Produces: `PortalShellProps` with `items`, `activePage`, `onNavigate`, `user`, `onLogout`, `notificationSlot`, `children`.
- Produces: visible page order `dashboard`, `budget-check`, `procurement`, `contract`, `blacklist`, `risk`.
- Produces: workbench callbacks `onOpenBlacklist` and `onOpenRisk`.

- [ ] Write failing navigation/shell tests for exact order, one authenticated shell, Project-as-contract active state and five workbench destinations.
- [ ] Run `node --test src/app/demoNavigation.test.ts src/app/deliveryNavigation.test.ts` from `frontend`; expect assertion failures for order/shell/callbacks.
- [ ] Implement `PortalShell`, update navigation and workbench callbacks, and add responsive orange/stone portal styles without importing ZIP code.
- [ ] Run the focused command again; expect PASS.
- [ ] Run the existing workbench presentation tests; expect PASS.
- [ ] Commit `feat: integrate customer portal shell`.

### Task 2: One-Project contract material intake

**Files:**
- Create: `frontend/src/features/contract/contractMaterialIntakeOperation.ts`
- Create: `frontend/src/features/contract/contractMaterialIntakeOperation.test.ts`
- Reuse: `frontend/src/features/project/projectIntakeOperation.ts`
- Reuse: `frontend/src/features/project/projectSupplementUpload.ts`

**Interfaces:**
- Produces: `createContractMaterialIntakeOperation(dependencies, keyFactory)`.
- Produces: `start(files: readonly File[]): Promise<ContractMaterialIntakeResult>` and `retry(): Promise<ContractMaterialIntakeResult>`.
- Result variants: `CANCELLED`, `COMPLETED`, `PARTIAL`, `OUTCOME_UNCERTAIN`, each carrying the durable Project identity when one exists and per-file outcomes where applicable.

- [ ] Write failing tests for cancel/no calls, exactly one Project for normal files, ZIP delegation, unsupported input prevalidation, partial success, double-click coalescing and uncertain retry with unchanged keys.
- [ ] Run `node --test src/features/contract/contractMaterialIntakeOperation.test.ts`; expect module-not-found/failing assertions.
- [ ] Implement the minimal stateful orchestration over existing Project and supplement operations.
- [ ] Run focused tests; expect PASS.
- [ ] Run existing project intake and supplement upload tests; expect PASS.
- [ ] Commit `feat: add contract material intake orchestration`.

### Task 3: Truthful persisted risk projection

**Files:**
- Create: `frontend/src/features/risk/riskAssessmentProjection.ts`
- Create: `frontend/src/features/risk/riskAssessmentProjection.test.ts`
- Create: `frontend/src/features/risk/RiskAssessmentPage.tsx`
- Create: `frontend/src/features/risk/RiskAssessmentPage.test.ts`
- Modify: `frontend/src/app/App.tsx`

**Interfaces:**
- Consumes: `ContractAuditTaskDataSource.list` with `risk: "REVIEW" | "INSUFFICIENT"`, `includeHistory: true`.
- Produces: `loadPersistedRiskAssessment(dataSource, signal)` with deduplicated items and explicit scope/availability.
- Produces: `RiskAssessmentPage({ dataSource?, onOpenTask })` that only routes to the exact existing task.

- [ ] Write failing projection/page tests for two real filtered GETs, deduplication, unavailable state, coverage wording, no score/model/write and exact task callback.
- [ ] Run focused risk tests; expect module-not-found/failing assertions.
- [ ] Implement projection and page, remove the inline mock risk dataset/page, and wire exact-run routing in `App`.
- [ ] Run focused tests; expect PASS.
- [ ] Run contract audit task data-source tests; expect PASS.
- [ ] Commit `feat: project persisted contract risks`.

### Task 4: Contract portal home and truthful OA state

**Files:**
- Create: `frontend/src/features/contract/components/ContractPortalHome.tsx`
- Create: `frontend/src/features/contract/contractPortalPresentation.ts`
- Create: `frontend/src/features/contract/contractPortalPresentation.test.ts`
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/features/project/components/ProjectManagementLandingPage.tsx`
- Modify: `frontend/src/features/contract/components/ContractOverviewList.tsx`
- Test: `frontend/src/app/deliveryNavigation.test.ts`
- Test: `frontend/src/features/project/projectListViewModel.test.ts`

**Interfaces:**
- Consumes: Task 2 `createContractMaterialIntakeOperation` and its result variants.
- Produces: `ContractPortalHome({ onOpenTask, onOpenShowcase, onOpenProject })` with default records tab, pending tab, upload control and disabled OA control.
- Produces: successful/partial upload navigation to the single durable Project.

- [ ] Write failing tests for default records, pending switch, disabled OA/no request, upload-to-project navigation, no duplicate entry actions and exact ReviewRun routing.
- [ ] Run focused portal/navigation tests; expect assertion failures.
- [ ] Implement the portal home, replace the old contract landing route, and simplify only duplicate contract-mode Project calls to action.
- [ ] Run focused tests; expect PASS.
- [ ] Run nearby Project, Contract overview, evidence, ACK and report tests; expect PASS.
- [ ] Commit `feat: add contract portal entry flow`.

### Task 5: Responsive visual and regression gates

**Files:**
- Modify: `frontend/src/styles/delivery-visual-v2.css`
- Modify: tests only if a real regression requires a behavior fixture update

**Interfaces:**
- Consumes: Tasks 1–4 UI.
- Produces: usable portal at 1366×768 and 1920×1080 without clipped navigation or hidden primary actions.

- [ ] Add failing structural/presentation assertions for responsive navigation and contract toolbar states before CSS changes.
- [ ] Implement minimal responsive CSS and accessible focus/disabled/loading states.
- [ ] Run both frontend full test runners and `npm run build`; expect PASS.
- [ ] Run browser visual checks at 1366×768 and 1920×1080 against non-writing pages; record screenshots outside Git or sanitized evidence only.
- [ ] Run relevant backend and FastAPI tests; expect PASS.
- [ ] Commit `test: harden customer portal integration`.

### Task 6: Whole-branch review, full gates and exact checkpoint

**Files:**
- Create: `docs/evidence/customer-portal-contract-flow-verification-20260928.md`
- Modify: production/tests only for a confirmed Critical or Important review finding, with RED/GREEN proof.

**Interfaces:**
- Produces: review ledger, full-gate counts, artifact hashes and `PRE_UI_01_E2E_SHA`.

- [ ] Request one independent whole-branch review against the spec and classify findings.
- [ ] Fix only confirmed Critical/Important findings through RED/GREEN; record Minor findings.
- [ ] Run backend `clean verify`, both frontend full runners, frontend build, FastAPI full suite, `git diff --check` and credential scan.
- [ ] Write sanitized verification evidence with exact commands/results and no credentials/material text.
- [ ] Commit all approved source/tests/docs and non-force push `feature/customer-portal-contract-flow-20260928`.
- [ ] Verify local and remote SHA match; record it as `PRE_UI_01_E2E_SHA` and build exact artifacts.

### Task 7: Single authorized 01 browser E2E and handoff

**Files:**
- Modify: `docs/evidence/customer-portal-contract-flow-verification-20260928.md`

**Interfaces:**
- Consumes: exact Task 6 checkpoint and approved integration configuration/material set.
- Produces: one new Project/Contract/ReviewRun evidence set or one preserved failed attempt; never a second run.

- [ ] Safely load the approved integration configuration into the current PowerShell session, override code root to this worktree, and verify DB/Redis/storage/provider identities without printing secrets.
- [ ] Start backend, FastAPI and frontend from `PRE_UI_01_E2E_SHA`; verify ports and Vite `/api` proxy target.
- [ ] Through the visible browser, run the single 01 chain from login/upload through exact history reopen, including five review steps, eight categories, three representative cross-file source navigations, copy, ACK and report.
- [ ] Repeat display checks at 1366×768 and 1920×1080; refresh, logout/login and reopen the same exact run without new POST/model calls.
- [ ] Record Project/Contract/run IDs, automatic versus human fields, persisted results, provider calls, source navigation and any blocker in sanitized evidence.
- [ ] Stop after this one E2E, update/commit/push evidence only if successful or to preserve the failed attempt; clearly distinguish tested code SHA from evidence commit SHA.
