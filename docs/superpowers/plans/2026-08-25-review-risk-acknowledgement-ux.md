# Review Risk Acknowledgement & F02 UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a durable, immutable acknowledgement for non-green F02 findings and rebuild the F02 result experience from server-owned progress facts, including progressive replay, risk summary, timestamps, and a right-side history drawer.

**Architecture:** The `review` module owns `FindingAcknowledgement`, its repository/query abstractions, and acknowledgement HTTP endpoints because those semantics depend only on `AuditCheckResult` and `AuditTask`. The `contract` module owns the F02-specific progress assembler, source-preparation presentation item, rule titles, and display ordering. The dependency direction remains `contract -> review`; `review` must never import or call `contract`.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Security session/CSRF, MyBatis/MyBatis-Plus, DM8, React 18, TypeScript, Vite 6, Node built-in test runner, JUnit 5, Mockito, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-25-pre-f04-product-alignment-design.md`

## Global Constraints

- Work from a freshly updated `main`; before any edit, require `git branch --show-current` to return `main` and `git status --short` to be empty.
- Implement only acknowledgement, F02 progress projection, durable operation audit, technical flow diagnostics, and the F02 user experience described here.
- `FindingAcknowledgement` is an immutable business fact. It has no update, revoke, delete, active flag, or soft-delete operation.
- Acknowledgement is allowed only for `NOT_EVALUATED/YELLOW` and `FAIL/RED`; `PASS/GREEN` is rejected.
- One `AuditCheckResult.id` has at most one acknowledgement, enforced by a DM8 unique constraint as well as application conflict handling.
- The acknowledgement write, `REVIEW_FINDING_ACKNOWLEDGED` operation-audit event, and idempotency success transition commit in one short transaction.
- Actor identities are not business UUID types: `CurrentUser.userId`, `FindingAcknowledgement.acknowledgedBy`, and `OperationAuditEvent.userId` are Java `String`; every DM8 actor/user column is `VARCHAR(36)` and references `SYS_USER(ID)`. `FindingAcknowledgement.id`, `AuditCheckResult.id`, and `AuditTask.id` remain UUID business identities.
- The idempotency fingerprint binds actor ID, check-result ID, real task ID, and rule version. A key replay with the same fingerprint returns the original fact; changed fingerprint or a competing key returns HTTP 409.
- The F02 progress endpoint is contract-owned. Do not add a generic review progress endpoint and do not weaken `ReviewModuleDependencyTest`.
- `SOURCE_PREPARATION` is an API presentation item only: it is not an `AuditCheckResult`, has no risk or acknowledgement, and writes no row.
- Progress total is always `1 + findings.size()`. Never persist a cursor and never assume six items.
- Acknowledgement never changes finding status, risk, `blockSign`, or the risk summary.
- The drawer reads progress/result/acknowledgement facts, never a general operation-audit query.
- Keep F02 rule execution, `AuditTask` lifecycle, durable review idempotency, and START/COMPLETE/FAILURE transaction structure unchanged.
- Keep existing session-cookie, CSRF, `ApiResponse`, Request ID, Trace ID, authorization roles, and `GlobalExceptionHandler` conventions.
- Do not add report export, approval, acceptance, workflow, deletion, OCR, or AI behavior.
- Flow diagnostics may contain semantic result/task IDs, status, rule version, duration, and safe reason codes. They must not contain an idempotency key, cookies, tokens, finding detail, source text, or raw exception messages.
- Durable `OperationAuditEvent` is independently bounded: allow event/module/action, actor ID/username, semantic result/task resource IDs, result status, bounded business result/action description, Request ID, Trace ID, safe reason code, and safe counts/status/rule code only. Its `detail` must not contain finding detail, source text/excerpts, document bytes, paths/storage keys, idempotency keys, Cookie/Session/CSRF/Authorization, credentials, raw exception messages, or unbounded content.
- Verification is layered: unit/transaction-structure tests prove proxy boundary, ordering, failure propagation, and DM8 adapter participation; local memory tests prove acknowledgement/idempotency/concurrency/read-after-write semantics only; atomic database commit/rollback is `VERIFIED` only after representative real-DM8 evidence. Do not add delete/revoke compensation to simulate rollback.
- Every task ends with review. Fix all Critical and Important findings before starting the next task.
- Do not edit an existing migration. Resolve the next filename only at implementation time with `NEXT_AVAILABLE_VERSION_AT_IMPLEMENTATION_TIME`.

## Current-Code Reconnaissance Baseline

Before implementation, re-open each named symbol on current `main`; these are verified locations from the planning baseline, not permission to rely on stale signatures:

- Domain facts: `review/domain/AuditCheckResult.java` (record includes `id`, `taskId`, `checkStatus`, `riskLevel`, `ruleVersion`, `createdAt`) and `review/domain/AuditTask.java`.
- Ports: `review/application/port/AuditCheckResultRepository.java`, `review/application/port/AuditTaskRepository.java`, `common/application/port/IdempotencyPort.java`, `common/application/port/OperationAuditPort.java`, `common/observability/FlowDebugLogger.java`, and `security/application/AuthContext.java`.
- Identity baseline: `security/domain/CurrentUser.java` uses `String userId`; `security/application/port/UserAccountRepository.java` has no by-ID display-name query; `security/infra/memory/InMemoryUserAccountRepository.java` owns `accountsById`; `integration/dm8/UserAccountMapper.java` already has `findById(String)` over `SYS_USER`, and `V002__local_account_auth_observability.sql` declares `SYS_USER.ID VARCHAR(36)` plus `REAL_NAME`.
- F02 orchestration: `contract/application/ContractReviewService.java`, `ContractReviewTransactionCoordinator.java`, and `ContractReviewResult.java`.
- F02 API: `contract/api/ContractController.java` currently owns `/{id}/agent-review` and `/{id}/agent-results`; its nested `FindingResponse` does not yet return `createdAt`.
- DM8 conventions: `integration/dm8/Dm8AuditCheckResultRepository.java`, `AuditCheckResultMapper.java`, `AuditCheckResultPersistenceRow.java`, `Dm8OperationAuditAdapter.java`, `Dm8IdempotencyAdapter.java`, and their XML files under `backend/src/main/resources/mappers/dm8/`.
- Local seams: `review/infra/memory/InMemoryAuditCheckResultRepository.java`, `review/infra/memory/InMemoryAuditTaskRepository.java`, and `integration/audit/memory/InMemoryOperationAuditAdapter.java`.
- Regression anchors: `review/ReviewModuleDependencyTest.java`, `contract/api/ContractReviewApiIntegrationTest.java`, `contract/application/ContractReviewTransactionStructureTest.java`, `integration/dm8/Dm8AuditCheckResultRepositoryTest.java`, and `integration/dm8/F02Dm8SqlStructureTest.java`.
- Frontend types/data: `frontend/src/features/contract/types.ts`, `data/contractDataSource.ts`, `data/httpContractDataSource.ts`, and `useContractReviewWorkspace.ts`.
- Shared frontend HTTP contract: `frontend/src/api/client.ts` owns cookie credentials and performs its own CSRF GET before every state-changing request; transport assertions belong in the colocated `data/httpContractDataSource.test.ts` and must exercise this real shared client through a controlled global `fetch` seam.
- Frontend presentation: `reviewFindingsViewModel.ts`, `reviewExecutionPresentation.ts`, `components/ReviewProgressTimeline.tsx`, `ReviewStepIndicator.tsx`, `CompactReviewProgress.tsx`, `F02ReviewResultPanel.tsx`, and `ContractReviewWorkspace.tsx`.

At implementation start run:

```powershell
git branch --show-current
git status --short
rg -n "record AuditCheckResult|interface AuditCheckResultRepository|class ContractReviewService|class ContractReviewTransactionCoordinator|agent-results|F02_FINDING_VIEW_ORDER|revealedFindingCount" backend/src/main backend/src/test frontend/src
```

Expected: branch is `main`, status is empty, and every symbol above resolves. If a signature moved, update this plan's file paths and interface calls before writing tests.

---

### Task 1: Allocate the migration and specify the immutable acknowledgement schema

**Files:**

- Create: `db/dm8/NEXT_AVAILABLE_VERSION_AT_IMPLEMENTATION_TIME__review_finding_acknowledgement.sql`
- Modify: `db/dm8/README.md`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/ReviewFindingAcknowledgementDm8SqlStructureTest.java`

**Interfaces:**

- Consumes: existing `AUDIT_CHECK_RESULT.ID`, `AUDIT_CHECK_RESULT.TASK_ID`, `SYS_USER.ID`, and DM8 migration naming conventions.
- Produces: `REVIEW_FINDING_ACKNOWLEDGEMENT` with UUID business columns `ID VARCHAR(36)` and `CHECK_RESULT_ID VARCHAR(36)`, actor column `ACKNOWLEDGED_BY VARCHAR(36)`, plus immutable `ACKNOWLEDGED_AT`, `REQUEST_ID`, `TRACE_ID`, and `RULE_VERSION`; a database unique constraint on `CHECK_RESULT_ID`; foreign keys to the result and `SYS_USER(ID)` facts.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | `REVIEW_FINDING_ACKNOWLEDGEMENT` row, unique by `CHECK_RESULT_ID` |
| OPERATION AUDIT EVENT | `REVIEW_FINDING_ACKNOWLEDGED` |
| FLOWDEBUG EVENT | `REVIEW_ACK_START`, `REVIEW_ACK_TX_ENTER`, `REVIEW_ACK_TX_COMMIT`, `REVIEW_ACK_CONFLICT` |
| IDEMPOTENCY FACT | scope `REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE`; result resource type `REVIEW_FINDING_ACKNOWLEDGEMENT` |

- [ ] Re-scan migration allocation immediately before creating the file:

```powershell
Get-ChildItem db/dm8/V*.sql | Sort-Object Name | Select-Object -ExpandProperty Name
```

Choose the next unused number from the refreshed list, replace the token in the planned filename, and record the allocation in the implementation notes. Do not rename or edit an existing migration.

- [ ] **RED:** Create `ReviewFindingAcknowledgementDm8SqlStructureTest` to load the newly allocated SQL and assert the exact table/columns, `ID VARCHAR(36)`, `CHECK_RESULT_ID VARCHAR(36)`, actor `ACKNOWLEDGED_BY VARCHAR(36)`, `UNIQUE(CHECK_RESULT_ID)`, both foreign keys including `ACKNOWLEDGED_BY -> SYS_USER(ID)`, non-null immutable metadata, and absence of update/delete/active/deleted columns. The test must fail if actor identity is declared as a UUID Java/DDL abstraction rather than the repository's String/VARCHAR user identity.

- [ ] Run the test and confirm the correct failure is a missing migration or missing acknowledgement table:

```powershell
cd backend
.\mvnw.cmd -Dtest=ReviewFindingAcknowledgementDm8SqlStructureTest test
```

- [ ] **GREEN:** Add the smallest additive DM8 DDL satisfying the test. Use the repository's UUID/text/timestamp conventions, make `RULE_VERSION` non-negative, and document that the table is append-only. Update `db/dm8/README.md` to mark real DM8 execution `NOT VERIFIED` until the smoke gate in Task 8.
- [ ] Run the same test and confirm it passes.
- [ ] Refactor only duplicated SQL-test helpers; preserve the exact constraint assertions.
- [ ] Run targeted DM8 structure regression:

```powershell
cd backend
.\mvnw.cmd -Dtest=ReviewFindingAcknowledgementDm8SqlStructureTest,F02Dm8SqlStructureTest test
```

- [ ] **Review checkpoint:** Verify migration allocation against refreshed `main`, immutable schema, unique identity, real FK targets, and no historical migration edits. Resolve all Critical/Important findings.

---

### Task 2: Add the review-owned domain fact, repository port, and local adapter

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/review/domain/FindingAcknowledgement.java`
- Create: `backend/src/main/java/com/fuquan/audit/review/application/port/FindingAcknowledgementRepository.java`
- Create: `backend/src/main/java/com/fuquan/audit/review/application/port/FindingAcknowledgementReadRepository.java`
- Create: `backend/src/main/java/com/fuquan/audit/review/application/FindingAcknowledgementView.java`
- Create: `backend/src/main/java/com/fuquan/audit/review/infra/memory/InMemoryFindingAcknowledgementRepository.java`
- Create: `backend/src/main/java/com/fuquan/audit/security/application/port/UserIdentityQueryPort.java`
- Modify: `backend/src/main/java/com/fuquan/audit/review/application/port/AuditCheckResultRepository.java`
- Modify: `backend/src/main/java/com/fuquan/audit/review/infra/memory/InMemoryAuditCheckResultRepository.java`
- Modify: `backend/src/main/java/com/fuquan/audit/security/infra/memory/InMemoryUserAccountRepository.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8UserAccountRepository.java`
- Test: `backend/src/test/java/com/fuquan/audit/review/domain/FindingAcknowledgementTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/review/infra/memory/InMemoryFindingAcknowledgementRepositoryTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/security/infra/memory/InMemoryUserAccountRepositoryTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8UserAccountRepositoryTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/UserAccountMapperXmlTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/LocalAdapterWiringTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/review/ReviewModuleDependencyTest.java`

**Interfaces:**

- Consumes: `AuditCheckResult.id/taskId/checkStatus/riskLevel/ruleVersion`; current `CurrentUser.userId: String`; the actual local `accountsById` account store; existing DM8 `UserAccountMapper.findById(String)` over `SYS_USER`.
- Produces: immutable `FindingAcknowledgement` with UUID `id/checkResultId` and `String acknowledgedBy`; `FindingAcknowledgementRepository.create` and `findByCheckResultId`; `FindingAcknowledgementReadRepository.findByTaskId(UUID)` returning durable acknowledgement facts; `AuditCheckResultRepository.findById`; security-owned `UserIdentityQueryPort.findDisplayNameByUserId(String): Optional<String>`; no persisted username/display-name duplication.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | `FindingAcknowledgement` for one `AuditCheckResult.id` |
| OPERATION AUDIT EVENT | `REVIEW_FINDING_ACKNOWLEDGED` emitted by Task 4, not by the repository |
| FLOWDEBUG EVENT | `REVIEW_ACK_*` emitted by Tasks 4-5, not by the domain record |
| IDEMPOTENCY FACT | `REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE` coordinated in Task 4 |

- [ ] **RED:** Write domain tests proving UUID `id/checkResultId`, String `acknowledgedBy`, all seven fields required, `ruleVersion >= 0`, server timestamps retained exactly, and no mutation/revocation API. Write acknowledgement repository tests proving one result can be created once, concurrent second creates conflict, and task reads order by acknowledgement time then ID.
- [ ] Extend `InMemoryUserAccountRepositoryTest` to seed the real repository and prove `findDisplayNameByUserId(String)` reads the same `accountsById` account and returns its current `realName`; unknown IDs return `Optional.empty()`. Extend `Dm8UserAccountRepositoryTest` to mock existing `UserAccountMapper.findById(String)` and prove current `REAL_NAME` mapping plus unknown-user empty behavior. Extend `UserAccountMapperXmlTest` to prove the by-ID query reads only the existing `SYS_USER` row contract and does not introduce a second user fact source.
- [ ] Extend `LocalAdapterWiringTest` to start the local profile with the configured account and prove the single `InMemoryUserAccountRepository` bean is injectable through both `UserAccountRepository` and `UserIdentityQueryPort`, and resolves that account's current display name without a test-only map.
- [ ] Extend `ReviewModuleDependencyTest` to scan the new review files and fail on any `com.fuquan.audit.contract` import or fully qualified reference.
- [ ] Run and confirm failures are missing types/methods:

```powershell
cd backend
.\mvnw.cmd -Dtest=FindingAcknowledgementTest,InMemoryFindingAcknowledgementRepositoryTest,InMemoryUserAccountRepositoryTest,Dm8UserAccountRepositoryTest,UserAccountMapperXmlTest,LocalAdapterWiringTest,ReviewModuleDependencyTest test
```

- [ ] **GREEN:** Implement the record and review repositories. Add `Optional<AuditCheckResult> findById(UUID id)` to the existing result port. Implement acknowledgement local storage with atomic put-if-absent semantics; do not implement replace, remove, or clear-as-business-operation methods.
- [ ] Create the narrow security application port exactly as `Optional<String> findDisplayNameByUserId(String userId)`. Make the existing `InMemoryUserAccountRepository` and `Dm8UserAccountRepository` implement it: local reads the same `accountsById` map populated by configured/seed accounts; DM8 uses the existing `UserAccountMapper.findById` result and returns `realName`. Do not extend `UserAccountRepository`, create another account store, or expose password/login state to review.
- [ ] Build `FindingAcknowledgementView` in the review application layer by composing durable acknowledgements with `UserIdentityQueryPort`. Return the fixed display fallback `未知或已删除用户` when the security query returns empty, while retaining the original String actor ID in the view; persist only `acknowledgedBy`, never mutable username/display name.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor shared deterministic comparators into review-owned package-private helpers only if two repositories need the same ordering.
- [ ] Run targeted review regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=AuditTaskStateMachineTest,InMemoryAuditTaskRepositoryConcurrencyTest,FindingAcknowledgementTest,InMemoryFindingAcknowledgementRepositoryTest,InMemoryUserAccountRepositoryTest,Dm8UserAccountRepositoryTest,UserAccountMapperXmlTest,LocalAdapterWiringTest,ReviewModuleDependencyTest test
```

- [ ] **Review checkpoint:** Confirm the new module owns only generic result/task acknowledgement semantics and has no F02 title, step, or source-preparation types. Confirm review imports only the security application Port, local/DM8 adapters share their real account source, unknown/deleted users use the fixed fallback, and no test-supplied display map or second user store exists. Resolve all Critical/Important findings.

---

### Task 3: Implement the DM8 acknowledgement mapper and adapter

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/FindingAcknowledgementMapper.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/FindingAcknowledgementPersistenceRow.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8FindingAcknowledgementRepository.java`
- Create: `backend/src/main/resources/mappers/dm8/FindingAcknowledgementMapper.xml`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/AuditCheckResultMapper.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8AuditCheckResultRepository.java`
- Modify: `backend/src/main/resources/mappers/dm8/AuditCheckResultMapper.xml`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8FindingAcknowledgementRepositoryTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8AuditCheckResultRepositoryTest.java`

**Interfaces:**

- Consumes: `FindingAcknowledgementRepository`, `FindingAcknowledgementReadRepository`, `AuditCheckResultRepository.findById`, Task 2's separate `UserIdentityQueryPort`, DM8 mapper conventions, and the schema from Task 1.
- Produces: insert-only DM8 persistence, single-result lookup, task lookup joined only through `AUDIT_CHECK_RESULT`, and exact persistence-row/domain conversions. Display-name resolution remains the security-owned query seam and is not hidden in acknowledgement SQL.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | one insert into `REVIEW_FINDING_ACKNOWLEDGEMENT` |
| OPERATION AUDIT EVENT | `REVIEW_FINDING_ACKNOWLEDGED` remains transaction-coordinator responsibility |
| FLOWDEBUG EVENT | repository write/read uses `REVIEW_ACK_TX_ENTER` context without logging mutable text |
| IDEMPOTENCY FACT | no mapper-owned claim; Task 4 completes `REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE` |

- [ ] **RED:** Mock mapper behavior and assert exact insert parameters, UUID/String-actor/timestamp/rule-version round trips, empty lookup, duplicate insert translation to `CONFLICT`, task lookup through the real task foreign relation, and deterministic ordering. Extend the result-repository test for `findById`; Task 2's `Dm8UserAccountRepositoryTest` separately proves current display name comes from `SYS_USER`.
- [ ] Add XML structure assertions to the Task 1 SQL structure test: no `MERGE`, `UPDATE`, or `DELETE`; task query must join by `AUDIT_CHECK_RESULT.TASK_ID`; all column names must match the migration.
- [ ] Run and confirm failures are missing mapper/adapter statements:

```powershell
cd backend
.\mvnw.cmd -Dtest=Dm8FindingAcknowledgementRepositoryTest,Dm8AuditCheckResultRepositoryTest,ReviewFindingAcknowledgementDm8SqlStructureTest test
```

- [ ] **GREEN:** Implement one insert and read-only acknowledgement selects. The generic task read joins `AUDIT_CHECK_RESULT` for ownership and returns durable actor IDs; `FindingAcknowledgementService` separately resolves display names through `UserIdentityQueryPort`. Translate only the verified unique-constraint conflict to the domain conflict; do not flatten unknown database failures into “already acknowledged.”
- [ ] Implement result lookup using the existing result persistence row and exact `AuditCheckResult` constructor order.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor conversion functions into private methods; retain explicit field mapping so `taskId` and result ID cannot be interchanged.
- [ ] Run DM8 adapter regression:

```powershell
cd backend
.\mvnw.cmd -Dtest=Dm8FindingAcknowledgementRepositoryTest,Dm8AuditCheckResultRepositoryTest,Dm8AuditTaskRepositoryTest,Dm8IdempotencyAdapterTest test
```

- [ ] **Review checkpoint:** Verify insert-only SQL, unique-conflict discrimination, no reliance on row order, and no username denormalization. Resolve all Critical/Important findings.

---

### Task 4: Add the acknowledgement command and atomic transaction coordinator

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/review/application/AcknowledgeFindingCommand.java`
- Create: `backend/src/main/java/com/fuquan/audit/review/application/FindingAcknowledgementService.java`
- Create: `backend/src/main/java/com/fuquan/audit/review/application/FindingAcknowledgementTransactionCoordinator.java`
- Modify: None; the task composes existing ports through new application classes.
- Test: `backend/src/test/java/com/fuquan/audit/review/application/FindingAcknowledgementServiceTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/review/application/FindingAcknowledgementTransactionStructureTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/review/application/FindingAcknowledgementConcurrencyTest.java`

**Interfaces:**

- Consumes: `AuditCheckResultRepository.findById`, `AuditTaskRepository.findById`, `FindingAcknowledgementRepository`, `FindingAcknowledgementReadRepository`, security-owned `UserIdentityQueryPort`, `IdempotencyPort`, `OperationAuditPort`, `FlowDebugLogger`, `CurrentUser.userId: String`, server `Clock`, Request ID, and Trace ID.
- Produces: `acknowledge(AcknowledgeFindingCommand, CurrentUser)` and `findByTaskId(UUID)`; replay/conflict semantics; one short atomic transaction for business fact, operation audit, and idempotency success.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | `FindingAcknowledgement(UUID id, UUID checkResultId, String acknowledgedBy, acknowledgedAt, requestId, traceId, ruleVersion)` |
| OPERATION AUDIT EVENT | event type `REVIEW_FINDING_ACKNOWLEDGED`; module `REVIEW`; resource type/id `AUDIT_CHECK_RESULT`/real result ID; `taskId` is the real `AuditTask.id` |
| FLOWDEBUG EVENT | `REVIEW_ACK_START`, `REVIEW_ACK_TX_ENTER`, `REVIEW_ACK_TX_COMMIT`, `REVIEW_ACK_CONFLICT` |
| IDEMPOTENCY FACT | scope `REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE`; fingerprint actor + result + task + rule version; result points to the acknowledgement ID |

- [ ] **RED:** Test `PASS/GREEN` rejection, allowed yellow/red combinations, missing result/task, result/task mismatch, same-key replay, changed-fingerprint 409, another-key prior acknowledgement 409, competing users, and immutable original actor.
- [ ] Write transaction-structure tests proving claim occurs inside the short transaction; acknowledgement insert, operation audit, and `markSucceeded` share the coordinator/DM8 transaction; injected failure at each write propagates and never executes a false success path; conflict never rewrites the existing fact. These tests establish structure, not in-memory rollback.
- [ ] Test that flow events contain only semantic IDs/status/rule version and omit key, detail, source text, token, and raw exception text.
- [ ] Independently capture the appended `OperationAuditEvent` and assert `userId` is the String actor, `resourceId` is the real result UUID text, and `taskId` is the real task UUID text. Allow only bounded status/rule code/safe reason metadata; assert `detail`, `businessResult`, and `actionDesc` contain no finding detail, actual/expected/source text, idempotency key, Cookie/Session/CSRF/Authorization, credential/token, path/storage key, raw exception message, or unbounded value.
- [ ] Run and confirm failures identify the absent service/coordinator:

```powershell
cd backend
.\mvnw.cmd -Dtest=FindingAcknowledgementServiceTest,FindingAcknowledgementTransactionStructureTest,FindingAcknowledgementConcurrencyTest test
```

- [ ] **GREEN:** In the service, load result and task, validate allowed status/risk, build the deterministic fingerprint, emit `REVIEW_ACK_START`, and delegate to a separate Spring transaction coordinator.
- [ ] In the coordinator, claim idempotency, return the original fact for a successful same-fingerprint replay, reject in-progress/different-fingerprint use, insert the acknowledgement, append the exact audit event, and mark success in the same transaction. On a unique race, reread and return 409 without changing the winner.
- [ ] Treat `FindingAcknowledgementTransactionStructureTest` as structure/call-order/failure-propagation evidence, not as proof that `ConcurrentHashMap` rolls back. Local tests prove semantic conflict, concurrency, idempotency, and read-after-write only. Mark atomic commit/rollback of acknowledgement + operation audit + idempotency `VERIFIED` only after Task 8's representative DM8 smoke.
- [ ] Use the existing exception types and `BusinessErrorCode.NOT_FOUND`, `CONFLICT`, and `BUSINESS_VALIDATION_FAILED`; do not add a response envelope.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor fingerprint canonicalization into one private deterministic function and verify its test vectors remain stable.
- [ ] Run idempotency/audit regression:

```powershell
cd backend
.\mvnw.cmd -Dtest=FindingAcknowledgementServiceTest,FindingAcknowledgementTransactionStructureTest,FindingAcknowledgementConcurrencyTest,InMemoryIdempotencyAdapterTest,Dm8IdempotencyAdapterTest test
```

- [ ] **Review checkpoint:** Inspect transaction annotations and failure injection evidence. Confirm the design places business fact, audit, and idempotency in one DM8 transaction while FlowDebug remains non-durable diagnostics; do not label database rollback verified from local memory tests. Resolve all Critical/Important findings.

---

### Task 5: Expose review-owned acknowledgement POST/GET APIs

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/review/api/FindingAcknowledgementController.java`
- Modify: `backend/src/main/java/com/fuquan/audit/security/infra/ApiSecurityConfiguration.java` only if the current role rules require an explicit matcher; prefer the existing authenticated baseline.
- Test: `backend/src/test/java/com/fuquan/audit/review/api/FindingAcknowledgementApiIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/common/api/ValidationIntegrationTest.java`

**Interfaces:**

- Consumes: `FindingAcknowledgementService`, `AuthContext.currentUser()`, `Idempotency-Key`, Request ID/Trace ID MDC, and `ApiResponse`.
- Produces: empty-body `POST /api/v1/audit-check-results/{checkResultId}/acknowledgement` and `GET /api/v1/audit-tasks/{taskId}/finding-acknowledgements`.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | POST creates `REVIEW_FINDING_ACKNOWLEDGEMENT`; GET writes none |
| OPERATION AUDIT EVENT | POST commits `REVIEW_FINDING_ACKNOWLEDGED`; GET emits none |
| FLOWDEBUG EVENT | POST invokes `REVIEW_ACK_*`; GET may use existing read diagnostics but no durable event |
| IDEMPOTENCY FACT | POST requires `REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE`; GET has none |

- [ ] **RED:** Add MockMvc cases for authenticated empty-body success, rejection of an unexpected business body, response field names, same-key replay, missing/blank key, 401, 403, PASS/GREEN 422, missing/mismatched resources 404, changed fingerprint and already-acknowledged 409, safe error body, and GET stable ordering/current display name. Prove `acknowledgedBy` is the exact String `CurrentUser.userId`, display name changes are read live, and a missing/deleted account returns `未知或已删除用户` without losing the actor ID.
- [ ] Assert CSRF is required for POST and not GET. Assert no route supports PUT, PATCH, DELETE, revoke, or bulk confirmation.
- [ ] Run and confirm 404/missing-bean failures:

```powershell
cd backend
.\mvnw.cmd -Dtest=FindingAcknowledgementApiIntegrationTest,ValidationIntegrationTest test
```

- [ ] **GREEN:** Add the controller under the existing role set `SYSTEM_ADMIN`, `PROCUREMENT_MANAGER`, `AUDITOR`, and `LEGAL`. Convert path/header/MDC/current-user values into the command and return the existing `ApiResponse` shape.
- [ ] Return the generic acknowledgement read projection from GET; do not add F02 titles or progress assembly to this controller.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor request/response conversion to nested records only when it reduces duplication; keep domain types out of JSON binding.
- [ ] Run security and trace regression:

```powershell
cd backend
.\mvnw.cmd -Dtest=FindingAcknowledgementApiIntegrationTest,TraceContextIntegrationTest,GlobalExceptionHandlerTest test
```

- [ ] **Review checkpoint:** Verify ownership, authorization, CSRF, IDOR-safe 404 behavior, immutable route surface, and safe diagnostics. Resolve all Critical/Important findings.

---

### Task 6: Assemble and expose contract-owned F02 ReviewProgress

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/F02ReviewProgress.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/F02ReviewProgressItem.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/F02ReviewProgressService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/ContractController.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/F02ReviewProgressServiceTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/ContractReviewApiIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/review/ReviewModuleDependencyTest.java`

**Interfaces:**

- Consumes: `ContractReviewService.getContract`, `AuditTaskRepository.findById`, `AuditCheckResultRepository.findByTaskId`, review-owned `FindingAcknowledgementService.findByTaskId(UUID)` views already enriched through `UserIdentityQueryPort`, and `F02RuleCodes.ALL`.
- Produces: `GET /api/v1/contracts/{contractId}/audit-tasks/{taskId}/review-progress`; `F02ReviewProgress(total, items, riskSummary)`; items with `type`, nullable `checkResultId`, `title`, `displayOrder`, `status`, nullable `risk`, nullable `completedAt`, and nullable acknowledgement.

This is a read-only task. It creates no business fact, operation-audit event, FlowDebug business surrogate, or idempotency record.

- [ ] **RED:** Test the source item at order 0 with title `读取合同及比对来源`, no result ID/risk/acknowledgement, and time derived only from `AuditTask.startedAt` or null. Prove it never calls result append or acknowledgement create.
- [ ] Test 0, 1, and N findings; `total == 1 + findings.size()`; titles from all five `F02RuleCodes`; line-item ordering by rule order then nullable item key then result ID; unknown rule fallback after known rules; stable order independent of repository return order.
- [ ] Test result `createdAt` projection, acknowledgement join by result ID, task source/type/contract ownership checks, no generic review route, and risk count summaries 0=`无风险`, 1-2=`低风险`, at least 3=`高风险`, labeled only as `审核完成`.
- [ ] Extend API assertions so `FindingResponse` includes `createdAt` consistently where it remains public.
- [ ] Run and confirm missing assembler/route failures:

```powershell
cd backend
.\mvnw.cmd -Dtest=F02ReviewProgressServiceTest,ContractReviewApiIntegrationTest,ReviewModuleDependencyTest test
```

- [ ] **GREEN:** Implement the records and service inside `contract.application`. Map rule codes to Chinese titles and explicit primary order there. Use nullable item key plus result ID for deterministic fallback. Never persist `displayOrder`.
- [ ] Add the route to `ContractController`, preserving its current class authorization and `ApiResponse` style. Verify `{contractId}` matches the task's source ID and `F02_DETERMINISTIC`; return 404 for any mismatch without revealing another resource.
- [ ] Use task start time only as the safe source-preparation time. Use each result's real `createdAt` for finding completion. Do not manufacture a conclusion item.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor title/order mapping into a contract-owned immutable map and comparator; do not move it into `review`.
- [ ] Run F02 transaction and dependency regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=F02ReviewProgressServiceTest,ContractReviewApiIntegrationTest,ContractReviewServiceTest,ContractReviewTransactionStructureTest,ReviewModuleDependencyTest test
```

- [ ] **Review checkpoint:** Confirm route ownership, source/task binding, no `review -> contract` dependency, no sixth result, no persisted cursor/order, and no operation-audit query. Resolve all Critical/Important findings.

---

### Task 7: Replace timer-only F02 playback with durable progress and acknowledgement refresh

**Files:**

- Create: `frontend/src/features/contract/reviewProgressState.ts`
- Create: `frontend/src/features/contract/reviewProgressState.test.ts`
- Create: `frontend/src/features/contract/data/httpContractDataSource.test.ts`
- Modify: `frontend/src/features/contract/types.ts`
- Modify: `frontend/src/features/contract/data/contractDataSource.ts`
- Modify: `frontend/src/features/contract/data/httpContractDataSource.ts`
- Modify: `frontend/src/features/contract/useContractReviewWorkspace.ts`
- Modify: `frontend/src/features/contract/reviewFindingsViewModel.ts`
- Modify: `frontend/src/features/contract/reviewExecutionPresentation.ts`
- Test: `frontend/src/features/contract/reviewProgressState.test.ts`
- Test: `frontend/src/features/contract/reviewFindingPresentation.test.ts`
- Test: `frontend/src/features/contract/data/httpContractDataSource.test.ts`

**Interfaces:**

- Consumes: contract-owned progress GET, review-owned acknowledgement POST, `Idempotency-Key`, current HTTP client's cookie/CSRF/error behavior, and server `displayOrder`/timestamps.
- Produces: typed `ReviewProgress`, `ReviewProgressItem`, `FindingAcknowledgement`, `getReviewProgress`, `acknowledgeFinding`, deterministic `deriveProgressiveReplayState`, and `acknowledgeFindingAndReloadProgress(...)` whose only successful return is the forced GET projection after POST success/replay.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | UI POST requests one `REVIEW_FINDING_ACKNOWLEDGEMENT` |
| OPERATION AUDIT EVENT | server commits `REVIEW_FINDING_ACKNOWLEDGED`; client does not synthesize it |
| FLOWDEBUG EVENT | server emits `REVIEW_ACK_*`; browser console is not evidence |
| IDEMPOTENCY FACT | UI creates one immutable key per acknowledgement operation and reuses it while outcome is unknown |

- [ ] **RED:** Add Node tests for refresh reconstruction, first unacknowledged non-green pause, out-of-order acknowledgement, no unacknowledged risk, 0/1/2/3 risk-summary boundaries, `1/(1+N)` counts, and server order winning over local rule maps.
- [ ] In the exact file `frontend/src/features/contract/data/httpContractDataSource.test.ts`, install a controlled global `fetch` script around the real `HttpContractDataSource` and shared `request` client. First return the real CSRF envelope, make the acknowledgement POST outcome uncertain, retry with the same operation, then return POST success/replay followed by the progress GET. Assert both POST attempts carry the identical `Idempotency-Key`, each state-changing attempt obtains/applies the CSRF header and `credentials: include`, and `frontend/src/api/client.ts` is not modified or bypassed.
- [ ] In that same test, hold the final progress GET promise unresolved after POST success and prove `acknowledgeFindingAndReloadProgress(...)` has not resolved and `deriveProgressiveReplayState` cannot advance from the POST body/local state. Resolve GET without the acknowledgement and prove it remains paused; resolve a subsequent GET containing the durable acknowledgement and only then prove advancement/key clearing. Cover both a newly successful POST and a replayed POST response.
- [ ] Run and confirm missing types/functions fail:

```powershell
cd frontend
node --experimental-strip-types --test src/features/contract/data/httpContractDataSource.test.ts src/features/contract/reviewProgressState.test.ts src/features/contract/reviewFindingPresentation.test.ts
```

- [ ] **GREEN:** Extend API types with exact nullable fields and `Finding.createdAt`. Add data-source methods for progress and acknowledgement through the unchanged shared `request` client. Implement the small POST-then-GET coordinator used by the hook. Keep the acknowledgement key in per-result operation state until GET proves the durable fact; reuse it after network/5xx/unknown outcomes and clear it only after proof or an explicit terminal fingerprint/failed conflict.
- [ ] Replace `revealedFindingCount` as the source of truth. Timers may animate the already-derived completed prefix, but the prefix and pause position come from progress facts after every load/refresh.
- [ ] Remove `F02_FINDING_VIEW_ORDER` and the fixed six-step/conclusion presentation. Consume server `displayOrder` and dynamic total.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor pure state derivation away from the React hook so replay semantics stay unit-testable.
- [ ] Run frontend targeted regressions and production build:

```powershell
cd frontend
node --experimental-strip-types --test src/features/contract/data/httpContractDataSource.test.ts src/features/contract/reviewProgressState.test.ts src/features/contract/reviewFindingPresentation.test.ts
npm.cmd run build
```

- [ ] **Review checkpoint:** Verify the named transport test exercises the real CSRF-aware shared client, no durable fact is inferred from POST/timers/local state, uncertain keys remain stable, every success/replay POST is followed by GET, and order comes only from the API. Resolve all Critical/Important findings.

---

### Task 8: Deliver the F02 risk presentation, right drawer, and end-to-end evidence

**Files:**

- Create: `frontend/src/features/contract/components/F02ReviewHistoryDrawer.tsx`
- Create: `frontend/src/features/contract/reviewHistoryViewModel.ts`
- Modify: `frontend/src/features/contract/components/ReviewProgressTimeline.tsx`
- Modify: `frontend/src/features/contract/components/ReviewStepIndicator.tsx`
- Modify: `frontend/src/features/contract/components/CompactReviewProgress.tsx`
- Modify: `frontend/src/features/contract/components/F02ReviewResultPanel.tsx`
- Modify: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`
- Modify: `frontend/src/features/contract/reviewFindingPresentation.ts`
- Test: `frontend/src/features/contract/reviewHistoryViewModel.test.ts`
- Test: `frontend/src/features/contract/reviewProgressState.test.ts`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/ContractReviewApiIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/review/api/FindingAcknowledgementApiIntegrationTest.java`

**Interfaces:**

- Consumes: Task 7 replay state; source/result/acknowledgement times; acknowledgement actor display name; finding values/details; nullable source seam.
- Produces: green completion icon, yellow warning/exclamation and red risk detail without a giant failure cross; real `我已知悉` action; `审核完成` risk summary; `¥` amount formatting plus existing Chinese uppercase display; stable right-drawer timeline.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | acknowledgement button requests `REVIEW_FINDING_ACKNOWLEDGEMENT`; drawer only reads facts |
| OPERATION AUDIT EVENT | `REVIEW_FINDING_ACKNOWLEDGED` remains server-side evidence and is not queried by the drawer |
| FLOWDEBUG EVENT | `REVIEW_ACK_*` remains technical-only; UI does not render logs |
| IDEMPOTENCY FACT | button reuses `REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE` operation identity until outcome is proven |

- [ ] **RED:** Test timeline merging and tie-breaking by display order, event type, and fact ID. Assert source time labels, `核对完成时间`, acknowledgement actor/time, nullable `暂无可靠时间`, and multiple result/ack events.
- [ ] Test that copy never says approved, accepted, passed, failed as an overall decision; no giant red cross; no confirmation control for source or green findings; acknowledgement does not reduce risk counts; formal report export control is absent.
- [ ] Test amount presentation uses `¥` with fixed two-decimal numeric formatting while preserving any real Chinese uppercase value; missing sources show a neutral unavailable state rather than fabricated anchors.
- [ ] Run and confirm the new view model/component behavior fails:

```powershell
cd frontend
node --experimental-strip-types --test src/features/contract/data/httpContractDataSource.test.ts src/features/contract/reviewHistoryViewModel.test.ts src/features/contract/reviewProgressState.test.ts src/features/contract/reviewFindingPresentation.test.ts
```

- [ ] **GREEN:** Implement the drawer strictly from progress facts. Add accessible labels, focus return, Escape/close behavior, keyboard operation, and non-color status text.
- [ ] Update progress components to show the server total and current item, pause at the first unacknowledged non-green item, show `我已知悉`, and continue only after the refreshed GET contains the acknowledgement.
- [ ] Replace overall conclusion language with `审核完成` and factual counts. Keep red only for risk emphasis inside detail/badge; use an exclamation-style progress node.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor duplicated date/currency formatting into feature-local pure helpers and keep nullable behavior explicit.
- [ ] Run all targeted frontend/backend regressions:

```powershell
cd frontend
node --experimental-strip-types --test src/features/contract/data/httpContractDataSource.test.ts src/features/contract/reviewHistoryViewModel.test.ts src/features/contract/reviewProgressState.test.ts src/features/contract/reviewFindingPresentation.test.ts
npm.cmd run build
cd ..\backend
.\mvnw.cmd -Dtest=ContractReviewApiIntegrationTest,FindingAcknowledgementApiIntegrationTest,F02ReviewProgressServiceTest,ReviewModuleDependencyTest test
```

- [ ] Run the full backend verification from the backend directory:

```powershell
.\mvnw.cmd clean verify
```

- [ ] Run repository hygiene checks from the repository root:

```powershell
cd ..
git diff --check
git status --short --untracked-files=all
```

- [ ] Execute browser manual E2E with an authenticated session and CSRF enabled: run F02 review; observe dynamic progress; pause at yellow/red; confirm; verify network POST then GET; refresh at every pause; inspect drawer times/actor; verify 0/1/2/3-risk summaries; verify keyboard and narrow viewport behavior.
- [ ] Real DM8 smoke evidence is required before a `VERIFIED` database claim: apply the newly allocated migration to a representative DM8 instance, inspect constraints, create/replay/conflict acknowledgements, force rollback after each atomic write, restart the app, and prove the same acknowledgement is rediscovered. Record DM8 version, command, sanitized results, and row counts. Until then mark DM8 DDL/transaction behavior `NOT VERIFIED`.
- [ ] Redis/session impact: no new Redis business fact, lock, stream, or cache is permitted. Run the E2E with the existing session profile and verify Redis loss causes at most re-login while DM8 acknowledgement remains. Record the active profile and sanitized observation; otherwise mark real Redis behavior `NOT VERIFIED`.
- [ ] **Review checkpoint:** Review the whole slice for API ownership, correctly scoped transaction evidence, concurrency, accessibility, copy, independent OperationAudit/FlowDebug payload safety, F02/ReviewSession regression, and evidence quality. Resolve all Critical/Important findings before declaring completion.

## Completion Evidence Checklist

- [ ] Each specification requirement above maps to an implemented task and executable test.
- [ ] All new production behavior was introduced after a failing test and reached green with minimal implementation.
- [ ] `ReviewModuleDependencyTest` proves no reverse module dependency.
- [ ] The local and DM8 security adapters implement the single `UserIdentityQueryPort`; current names come from the real account source and missing/deleted actors use the fixed fallback without changing the String actor ID.
- [ ] Source preparation remains presentation-only; result count is dynamic; timestamps are factual or null.
- [ ] Durable acknowledgement, operation audit, FlowDebug, and idempotency retain separate responsibilities.
- [ ] Full backend verification and frontend production build pass in the recorded environment.
- [ ] Real DM8, real session-store behavior, and browser E2E are labeled `VERIFIED` only with the stated evidence; otherwise each is explicitly `NOT VERIFIED`.
