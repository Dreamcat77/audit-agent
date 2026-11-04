# Customer Portal + Contract UX Integration Design

**Status:** Approved direction, ready for implementation
**Baseline:** `integration/pre-main-contract-f06-20260928@ccf674e0f6bc00fd6d6a8fb33acdfbdedf2c585e`
**Worktree:** `D:\code\audit_agent_1D\.worktrees\customer-portal-contract-flow-20260928`
**Visual reference:** `D:\code\audit_agent_md\frontend_agent_console.zip` (reference only; no code or runtime assets are imported)

## 1. Scope and invariants

This increment presents the already merged Contract Golden, F06, budget and blacklist capabilities through one customer-facing portal. It changes presentation and frontend orchestration only. It does not create a second application, authentication state, API client, contract review implementation or review result store.

The following production semantics remain authoritative and unchanged:

- Project intake, file storage, secure ZIP ingestion, role and identity decisions.
- Candidate generation, human confirmation, promotion and durable Contract authority.
- ReviewRun creation, reconciliation, frozen file/SHA/parse identity, exact-run reopen and polling.
- Server-owned findings/evidence, source navigation, ACK and report generation.
- F02, BREACH, TERM, SEMANTIC and F06 business logic.
- DM8 schema and migrations.

There are no backend, FastAPI, model, migration, environment or deployment changes in this increment.

## 2. Chosen architecture

### 2.1 One portal shell

`App.tsx` remains the single authenticated application and route-state owner. A focused `PortalShell` component renders the shared top navigation, current authenticated user, procurement notification entry and logout action. The visible navigation order is fixed:

1. 工作台
2. 预算审查
3. 采购审查
4. 合同审核
5. 黑名单
6. 风险预估

Project and procurement task pages remain internal destinations reached from their owning tool. When an internal contract Project page is open, the global navigation still marks 合同审核 active.

### 2.2 Workbench

The current `DeliveryWorkbenchPage` remains the data-backed workbench. Its three primary tool cards continue to open budget, procurement and contract. Two lower-weight entries open blacklist and risk. The workbench reads the logged-in user and persisted contract task summary. If data cannot be loaded it displays unavailable state rather than invented totals.

### 2.3 Contract center

`ContractPortalHome` becomes the contract entry. It has two tabs:

- **审核记录** (default): reuses `ContractOverviewList` and preserves exact ReviewRun navigation.
- **待继续**: embeds the existing Project intake/preparation workspace and resumes durable state.

The header has one primary **上传材料** action and a disabled **从 OA 导入（暂未接入）** action. The former accepts either one ZIP package or one normal multi-file selection. It never creates a ReviewRun and never bypasses material verification, Candidate confirmation or Promotion.

### 2.4 Upload orchestration

`createContractMaterialIntakeOperation` is a frontend orchestration boundary:

- Empty selection returns `CANCELLED` and performs no API call.
- One ZIP delegates to the existing secure `createProjectIntakeOperation` flow.
- Normal files create exactly one blank Project, then upload every file to that Project through the existing `projectSupplementUpload` operation and immutable per-file idempotency keys.
- Successful files remain attached when another file fails. The result identifies the Project and per-file successes/failures so the user can enter material verification and retry only unresolved work.
- Double clicks share the same in-flight promise.
- If creation or upload outcome is uncertain, the operation retains the Project/file operation identity for server reconciliation. It does not silently create a second Project or a new idempotency key.

The selected Project opens through `ProjectWorkspace`, where the current role, identity, A-mini candidate, evidence validation, human confirmation and promotion flow remains unchanged.

### 2.5 Risk projection

`RiskAssessmentPage` is a truthful read-only projection over `ContractAuditTaskDataSource`. It queries persisted records with `REVIEW` and `INSUFFICIENT` risk filters, deduplicates by durable task identity and shows:

- the stated query scope;
- review-required and material-insufficient counts;
- persisted progress/coverage;
- explicit unassessed/unfinished state;
- an action that opens the exact existing ReviewRun workspace.

It does not calculate a risk score, invoke a model, create a ReviewRun, or synthesize findings. Detailed findings and evidence remain in the existing exact-run workspace.

## 3. Component mapping

| Existing component/capability | New position | Data source | User action | Preserved contract | Verification |
| --- | --- | --- | --- | --- | --- |
| `App` + `AuthProvider` | Portal root | authenticated session | navigate/logout | one session and API client | navigation integration tests |
| Sidebar shell | `PortalShell` top navigation | `NAV_ITEMS` filtered by `demoNavigation` | six tool entries | internal destinations still reachable | shell and active-state tests |
| `DeliveryWorkbenchPage` | 工作台 | persisted task summary/list | open five business entries | no fake metrics | workbench presentation tests |
| `ContractOverviewList` | 合同审核 / 审核记录 | contract task APIs | filters, exact run reopen | exact `runId`, no view POST | existing + portal tests |
| `ProjectWorkspace` | 合同审核 / 待继续 and post-upload | Project APIs | resume material preparation | role/identity/Candidate/Promotion | project regression tests |
| `createProjectIntakeOperation` | one-ZIP upload | secure intake API | ingest package | secure archive and durable idempotency | existing intake tests + adapter test |
| `projectSupplementUpload` | normal multi-file upload | file upload API | upload/retry per file | per-file keys, partial success | existing + contract operation tests |
| Inline mock `RiskPage` | `RiskAssessmentPage` | persisted audit task read APIs | open exact task | no score/model/write | risk tests |
| OA placeholder | contract header | none | disabled information | OA remains disconnected | accessible disabled-state test |
| `DirectF02AuditTaskWorkspace` | exact result destination | exact ReviewRun | evidence/copy/ACK/report | frozen evidence and history | existing contract suite + browser E2E |

## 4. Failure and recovery behavior

- Contract task or risk list GET failure produces an explicit unavailable state with retry; it does not render zero as business truth.
- Upload validation rejects unsupported mixed input before creating a Project.
- Project creation success followed by file failure retains the Project and all successful uploads.
- An uncertain request retains its original operation key and disables fresh submission until reconciliation resolves it.
- Opening a record, refreshing, copying an opinion, expanding a category, navigating evidence, logging out/in and reopening history do not create a ReviewRun or call AI.
- Missing reliable evidence remains visibly unavailable. No locator, excerpt, page or risk result is fabricated.

## 5. Alternatives rejected

- **Import the ZIP application:** rejected because it contains separate state, mock business data and incompatible runtime/package assumptions.
- **Create backend portal aggregation APIs:** rejected because current task and Project read APIs contain the required truth and the change would add unnecessary public contracts.
- **Create a second contract upload API:** rejected because Project intake and supplement upload already implement the authority, storage and idempotency boundaries.
- **Reuse the existing mock risk page:** rejected because its counts, chart and actions are not persisted business facts.
- **Auto-start review after upload:** rejected because it bypasses preparation and human authority transitions.

## 6. Verification strategy

Implementation uses RED/GREEN tests for navigation, shell state, upload cancellation/grouping/partial failure/concurrency/uncertain recovery, contract tabs/OA truthfulness, risk truthfulness and exact-run routing. Then run both frontend test runners, frontend build, relevant and full backend/FastAPI gates, credential scan and `git diff --check`.

After all gates pass, create and push one pre-E2E checkpoint. Build and start the exact checkpoint against the approved local integration environment and run exactly one new 01 browser chain at 1366×768 and 1920×1080. No 02–06 run is authorized. A failed 01 is preserved and stops further business E2E.
