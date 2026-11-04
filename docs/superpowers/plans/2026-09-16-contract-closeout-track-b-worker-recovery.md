# Contract Closeout Track B Worker Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute durable review attempts with DM8 leases, fenced terminal writes, conservative recovery, explicit append-only retries and browser GET polling.

**Architecture:** Reuse the frozen ReviewRun/Step/Attempt, existing executor interfaces, F02 truth, output validation and DM8 idempotency. A focused worker repository adds bounded discovery and lease CAS over the existing attempt table; worker-owned transaction coordinators validate caller-observed fences before writes. Additive retry transitions are isolated from the existing synchronous transition guard; the existing browser workspace is wired only during integration.

**Tech Stack:** Java 17, Spring Boot, MyBatis, DM8, React 18, TypeScript, Node tests; no new dependency.

**Spec:** `docs/superpowers/specs/2026-09-16-contract-audit-parallel-closeout-design.md`, approved at `2ed1eb9906d2c67d3c673906d5f1b27b434af3a9`; `docs/superpowers/specs/2026-09-14-contract-audit-evolution-master-design.md`; approved Track B task.

## Global Constraints

- Branch: `feature/contract-closeout-worker-recovery-20260916`; worktree: `.worktrees/contract-closeout-worker-recovery`; initial HEAD exactly `2ed1eb9906d2c67d3c673906d5f1b27b434af3a9`, clean.
- DM8 is System of Record. Redis is not a queue or business truth. No Kafka/RabbitMQ, second storage, customer material, real-provider call or real/customer DM8 access.
- Fixed five-step profile, frozen inputs, existing F02 AuditTask/AuditCheckResult and aggregate priority remain unchanged.
- UNKNOWN != FAILED. No automatic UNKNOWN retry or invented semantic supersede policy.
- All terminal attempts remain immutable. Retry creates previous attemptNo + 1 and new attempt/operation identity.
- No shared controller/service/domain-record/aggregate-guard/ReviewRun mapper/security/OpenAPI/workspace/migration-README edits. Only mechanical integration is deferred; a required semantic common-contract change stops this track.
- No historical migration edits or V023+. No JVM lock establishes production exclusivity.
- Worker actor is the run's server-owned requestedBy; roles are never reconstructed or granted. Technical workerId is separate audit metadata.

## Baseline and migration necessity

Observed local Windows/JDK17 baseline: `backend/.\mvnw.cmd clean test '-Dtest=*ReviewStep*,*ReviewRun*,FrozenF02ReviewStepExecutorTest'`: 110 tests, zero failures/errors/skips, BUILD SUCCESS.

MIGRATION_NECESSITY = NONE. `CONTRACT__review_run.sql` already provides ATTEMPT_NO, OPERATION_IDENTITY, STATUS, LEASE_OWNER, LEASE_UNTIL, CLAIMED_AT, EXTERNAL_CALL_STATE, EXTERNAL_REQUEST_FINGERPRINT and VERSION_NO; unique `(STEP_ID, ATTEMPT_NO)` and operation identity; index `IDX_CONTRACT_REVIEW_STEP_ATTEMPT_PENDING(STATUS, LEASE_UNTIL, ATTEMPT_ID)`. Bounded discovery uses that leading status/lease order and existing step/run keys. No new queue or SQL migration is justified. Representative DM8 query plan/locking execution remains NOT VERIFIED.

## Exact file and interface map

Java application root below is `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/`.

- `ReviewWorkerLeasePolicy.java`: `claim(attempt, owner, now, until)`, `heartbeat(attempt, owner, version, now, until)`, `requireFence(attempt, owner, version, now)`, `recovery(attempt, now)`; pure immutable transition validation.
- `port/ContractReviewWorkerRepository.java`: `findClaimCandidates(int limit, Instant now)`, `findExpiredLeases(int limit, Instant now)`, `compareAndSetAttempt(expected, next, LeaseCondition condition, Instant now)`, `advanceRetry(step, next, run, resumed)`, `findBusinessInitiator(userId)`. Candidate carries run/step/attempt UUIDs and observed attempt version. LeaseCondition is PENDING, ACTIVE or EXPIRED.
- `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractReviewWorkerMapper.java`, `Dm8ContractReviewWorkerRepository.java`, `backend/src/main/resources/mappers/dm8/ContractReviewWorkerMapper.xml`: new mapper only; reuse immutable existing persistence rows/read repositories. Every update binds IDs, status, version, lease state and current attempt; no unconditional update.
- `ReviewWorkerTransactionCoordinator.java`: `claim(candidate, workerId)`, `heartbeat(execution)`, `markRequested(execution, fingerprint)`, `complete(execution, result)`, `close(execution, status, reason)`, `recover(candidate, workerId)`. Each durable operation is a short independent transaction; completion takes the run lock, CAS-locks the caller's exact lease and delegates existing result validation/publication in the same transaction. FAILED/UNKNOWN closure never nests an existing REQUIRES_NEW closure beneath a held fence.
- `ContractReviewWorker.java`: `scanOnce(int limit)` discovers DM8 work, executes existing typed executors outside write transactions and heartbeats while computation is outstanding. Process-local future is only in-flight computation, never pending-work truth.
- `ContractReviewWorkerConfiguration.java`: opt-in DM8-profile scheduler, bounded sequential worker execution, server-generated worker identity, controlled lifecycle and timeouts. No default activation before shared compatibility integration.
- `ReviewStepRetryPolicy.java`: frozen `worker-retry-v1` eligibility and normalized bounded reason; FAILED only with proven capability safety; UNKNOWN denied.
- `RetryReviewStepCommand.java`, `ReviewStepRetryService.java`: path/expected run-step-attempt versions and previous status/external state, reason, key, request metadata; AuthContext actor; scope `CONTRACT_REVIEW_STEP_RETRY`; fingerprint includes all approved identity inputs; original successful key replay precedes latest-state checks.
- `backend/src/main/java/com/fuquan/audit/contract/api/project/ContractReviewStepRetryController.java`: only `POST /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}/retries`; strict body, existing four-role guard, CSRF, Idempotency-Key, 202 durable attempt; no client actor/worker field.
- `frontend/src/features/contract/reviewRunPolling.ts`, `useDurableReviewRunPolling.ts`, `data/httpReviewStepRetryDataSource.ts`: bounded GET-only polling, visibility abort/stale response suppression, explicit retry data source retaining the supplied key.

Tests: matching `ReviewWorkerLeasePolicyTest.java`, `ReviewWorkerRepositoryTest.java`, `ReviewWorkerTransactionCoordinatorTest.java`, `ReviewStepRetryServiceTest.java`, `ContractReviewWorkerTest.java`, `ReviewWorkerF02RecoveryTest.java`, `ContractReviewStepRetryControllerTest.java` under matching test packages; test-only worker repository fixtures; `reviewRunPolling.test.ts`, `useDurableReviewRunPolling.test.ts`, `data/httpReviewStepRetryDataSource.test.ts`.

## Transactions, command identity and security

Claim locks run, verifies executable status/current attempt/frozen policy, claims stable internal REVIEW_STEP_EXECUTE identity, CASes pending attempt with lease and increments version, transitions step/run and audits before commit. All computation is after commit. Heartbeat and REQUESTED use caller token CAS and return the next token. Completion validates current attempt and unexpired owner/version before any finding, task-link or terminal write. The attempt row remains locked through output publication; a stale completion/FAILED/UNKNOWN cannot refresh its own fence from storage.

Recovery scans expired leases only. NOT_STARTED BREACH/TERM and semantic-before-REQUESTED may be reclaimed using unchanged operation identity after CAS; F02 first inspects its stable internal idempotency/task proof and never treats an in-progress F02 task as absent/failed. Unproven escaped external work closes UNKNOWN and leaves operation IN_PROGRESS. Valid leases are untouched; read/DB uncertainty makes no terminal assertion.

Retry locks the run and checks exact current terminal attempt, expected versions and policy. In one transaction it claims retry idempotency, appends new PENDING attempt, CASes step pointer and resumes run without altering frozen inputs, marks retry SUCCEEDED and audits the authenticated retry actor. Worker still computes as original requestedBy. Two retry commands cannot both allocate attempt 2. Same key reopens the same new attempt even after it advances; changed command conflicts.

New HTTP commands use SYSTEM_ADMIN/PROCUREMENT_MANAGER/AUDITOR/LEGAL, AuthContext, CSRF and safe ownership NOT_FOUND. Worker does no external RBAC decision and accepts no client identity. No raw input/model body or secrets in audit/logs; technical workerId is separate from business actor and TASK_ID is never a run/attempt UUID.

## Task 1: Lease policy and bounded DM8 repository

- [x] RED: hand-built PENDING/ACTIVE/EXPIRED fixtures test one CAS winner, no current-attempt mismatch, heartbeat version increment, old-token rejection and non-expired scanner exclusion. Name mutation caught: removing version/owner/expiry/current-attempt predicate permits stale writer.
  ```java
  assertThat(heartbeat.versionNo()).isEqualTo(2);
  assertThatThrownBy(() -> policy.requireFence(heartbeat, "worker-a", 1, now))
      .isInstanceOf(BusinessException.class);
  ```
- [x] Run `backend/.\mvnw.cmd test '-Dtest=ReviewWorkerLeasePolicyTest,ReviewWorkerRepositoryTest'`; observe expected missing behavior failure.
- [x] GREEN: implement policy and new MyBatis repository, limit 1..100, deterministic status/lease/attempt order, equality CAS with 0-row conflict, no mapper/shared schema edits.
- [x] Rerun focused tests and commit `feat: add durable review worker lease repository`.

## Task 2: Fenced claim/execution/closure and restart recovery

- [x] RED: two workers race; heartbeat then stale COMPLETE/FAILED/UNKNOWN all reject before facts; current-attempt switch rejects; valid lease no recovery; expired NOT_STARTED reacquires; REQUESTED becomes UNKNOWN; restart discovers pending; worker requestedBy and technical owner separate; F02 task provenance reopens correctly.
  ```java
  assertThat(results.findFindingsByAttemptId(old.attemptId())).isEmpty();
  assertThat(recovered.status()).isEqualTo(ContractReviewStepStatus.UNKNOWN);
  assertThat(task.createdBy()).isEqualTo(run.requestedBy());
  ```
- [x] Run `backend/.\mvnw.cmd test '-Dtest=ReviewWorkerTransactionCoordinatorTest,ContractReviewWorkerTest,FrozenF02ReviewStepExecutorTest'` and inspect RED.
- [x] GREEN: implement short coordinators, existing result validator delegation under the same fence transaction, independent failure/unknown closure, conservative F02 recovery and opt-in scheduler. Execution registry remains exact existing types.
- [x] Rerun tests and existing ReviewStep regressions; commit `feat: execute and recover fenced review attempts`.

## Task 3: Explicit append-only retry API

- [x] RED: FAILED retry creates attempt 2; attempt 1 byte-equivalent; two keys race one winner; same key after state advance returns same attempt; altered reason/version conflict; UNKNOWN fails closed; unauthenticated/wrong ownership/forged actor/CSRF rejection.
  ```java
  assertThat(retry.attemptNo()).isEqualTo(2);
  assertThat(history.get(0)).isEqualTo(originalTerminalAttempt);
  assertThat(replayed.attemptId()).isEqualTo(retry.attemptId());
  ```
- [x] Run `backend/.\mvnw.cmd test '-Dtest=ReviewStepRetryServiceTest,ContractReviewStepRetryControllerTest'` and inspect RED.
- [x] GREEN: implement focused retry policy/service/controller and repository pointer/resume CAS; preserve common aggregation priority and all frozen fields.
- [x] Rerun focused tests and commit `feat: append explicit durable review retries`.

## Task 4: Polling and retry frontend seams

- [x] RED: GET-only polling, bounded capped backoff, terminal stop, network error does not invent FAILED, hidden/unmounted abort, stale response ignored, restart reopens runId, exact retry path/payload/key retained.
  ```typescript
  assert.equal(executeCalls, 0);
  assert.equal(retryHeaders["Idempotency-Key"], originalKey);
  assert.equal(lastServerStatus, "RUNNING");
  ```
- [x] Run `node --test --experimental-strip-types src/features/contract/reviewRunPolling.test.ts src/features/contract/useDurableReviewRunPolling.test.ts src/features/contract/data/httpReviewStepRetryDataSource.test.ts` and inspect RED.
- [x] GREEN: implement independent poll/hook/retry modules without touching workspace or shared data-source/types. Retry caller supplies immutable operation identity; polling never issues execute/create/retry.
- [x] Rerun focused Node tests; `npm.cmd run build`; commit `feat: add durable review polling and retry client seams`.

## Task 5: Verification and handoff

- [x] Run backend focused suite, then `.\mvnw.cmd clean verify` (and at least `test` if unrelated verify gate fails). Record Surefire tests/failures/errors/skips and exit result. Do not fix unrelated budget static-contract baseline.
- [x] Run frontend focused tests/build if changed. No real browser/provider/DM8 claim without execution.
- [x] `git diff --check`, `git status --short`, `git diff --stat`; inspect diff against approved SHA; prove historical SQL/high-conflict files unchanged, no secret/customer data/Redis truth/new dependency.
- [x] Self-review exact fences, operation replay and append-only history; add RED regressions for any defect.
- [ ] Ordinary push only `origin/feature/contract-closeout-worker-recovery-20260916`; no PR, integration, merge, force, deploy or worktree removal. Report B13 sections and full pushed SHA.

## Required integration adaptations

1. Existing synchronous execute must use worker claim state; `ReviewStepExecutionService.recoverCompletedF02` and old terminal coordinators currently reread newest attempt without caller fencing. Route compatibility invocation through the tested worker boundary; do not enable async scheduler until this is wired. Retain existing HTTP path/response and replay semantics.
2. Existing shared step CAS does not update CURRENT_ATTEMPT_NO and guard freezes it. New retry repository owns its exact approved pointer/resume transition; integration may delegate the shared guard to the additive policy only if required by later consumers, with no aggregate-priority redesign.
3. Connect existing workspace to the focused polling hook and retry source, retain run pointer and remove browser execution ownership in Project-backed async mode. Add retry OpenAPI documentation centrally.

## Plan self-review

Coverage: B1-B6 Tasks 1-2; B7 Task 3; B8 unchanged aggregator plus isolated retry transition; B9 adaptation 1; B10 Task 4; B11 no migration; B12 adversarial task assertions; B13 Task 5. No customer UNKNOWN supersede capability is enabled. No placeholder schema, alternate model or queue truth. If the new fenced path cannot reuse shared validation safely without semantic shared-contract changes, stop with CROSS_TRACK_CONTRACT_BLOCKER before production code.

## Observed implementation evidence and integration contract

- Plan-first commit `fbafd37`; owned implementation commits `a020f06`, `ebc094c`, `7e54c6a`, `29c2446`, `c272813`.
- Lease/repository/worker/retry/frontend tests each observed missing-behavior RED before production implementation. F02 crash regression observed expected COMPLETED but actual UNKNOWN before additive proven-task recovery; then GREEN. Sibling aggregate-version regression observed missing COMPLETE invocation before worker input normalization; no shared executor or aggregate semantics changed.
- Proven inner F02 SUCCEEDED is validated by the existing real output provenance reader (task DONE, checks, actor/resource identity), then reclaimed and completed in one fenced recovery transaction. Unproven inner START/FAILED remains UNKNOWN; no new F02 task or automatic UNKNOWN supersede.
- Existing legacy START is tested to reject a valid worker claim. Integration must also prevent `ReviewStepExecutionService.recoverCompletedF02` from bypassing the lease fence for the internal worker key; route active/expired worker attempts through `ReviewWorkerTransactionCoordinator` and retain legacy path/response/replay behavior. Do not enable `audit.contract.review-worker.enabled` until that integration is verified.
- Production opt-in profiles: local-dev/dev29/customer. Defaults: lease PT1M, heartbeat PT10S, computation timeout PT5M, batch 4 (1..100), scan delay 5000ms. No local production queue adapter is introduced.
- Retry endpoint body is exactly expectedRunVersion, expectedStepVersion, previousAttemptId, previousAttemptNo, previousAttemptVersion, previousStatus, previousExternalCallState, reason; Idempotency-Key header mandatory; 202 envelope contains attempt and replayed. Integrator owns shared OpenAPI entry and workspace wiring. Client must persist the explicit retry key and original body while uncertain; polling/reload never rotates it or posts a command.
- Frontend new tests: 8/8 pass. New plus existing ReviewRun regression command: 34/34 pass. `npm.cmd run build`: success (existing large-chunk warning only). Node v24.14.0; repository lockfile dependencies installed without dependency-file changes. Sandbox process/cache restrictions required approved command escalation, not test changes.
- Local actual curl retry POST without session/CSRF: HTTP 403, X-Request-Id curl-track-b-security, body {"code":403,"message":"access denied","data":null}. Successful authenticated retry/strict body and role/CSRF contracts verified with Spring MockMvc; no production DM8/API deployment claimed.
- First full backend clean verify: 1150 tests, zero failures/errors/skips, BUILD SUCCESS. Final fresh `backend/.\mvnw.cmd clean verify` after `73e096a`: 1151 tests, zero failures/errors/skips, BUILD SUCCESS, exit 0 (Windows/JDK 17.0.19, 2026-09-16 19:36:19 +08:00). Surefire XML independently totals 1151/0/0/0. Track-owned backend tests: 32 (28 worker/policy/repository/retry/F02, 4 retry API). Fresh frontend regression rerun: 34/34, zero failures/skips.
- Static checks: no historical SQL/migration, shared domain/service/controller/guard/mapper/security/OpenAPI/workspace or dependency manifest changed. `git diff --check` passed. Focused production scans found no queue/cache truth, new JVM lock, credential, hardcoded customer address or private API.
- REAL_DM8_STATUS: NOT VERIFIED. MyBatis runtime BoundSql tests prove generated SQL/bindings only, not real DM8 locking, query plans or affected-row semantics. No real/customer DM8, provider or customer environment was contacted. Scheduler activation remains integration-gated.
