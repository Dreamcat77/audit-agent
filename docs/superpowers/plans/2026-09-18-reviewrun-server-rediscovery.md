# ReviewRun Server Rediscovery Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Allow the authenticated actor to reopen the latest owned ReviewRun after browser-local pointer loss, while preserving strict durable verification, pointer fast-path behavior, and explicit-new creation semantics.

**Architecture:** Add a deterministic, actor-filtered latest-run lookup to the repository boundary. A read-only application service obtains the actor from `AuthContext`, selects exactly one latest run, and delegates projection to `ReviewStepOutputReadService.getRun(...)`. Expose that verified output through a static GET route. The frontend uses that route only when its exact local pointer is absent, malformed, or proven missing; a hook-local volatile ref keeps an eligible explicit-new action from being swallowed by rediscovery.

**Tech Stack:** Java 17, Spring Boot, MyBatis/DM8 SQL, JUnit 5, Mockito, MockMvc, React 18, TypeScript, Node test runner, Vite, FastAPI pytest regression gate.

---

## Scope and invariants

- Keep `Project != Contract != ReviewRun`; discovery is scoped by project, contract, and current actor.
- Use exact latest ordering: `REQUESTED_AT DESC, RUN_ID DESC`.
- Include every ReviewRun status; do not filter or prefer successful history.
- Reopen the selected run only through `ReviewStepOutputReadService.getRun(...)`.
- Return 404 only when the repository returns `Optional.empty()`; repository adapters do not throw application/HTTP exceptions for absence.
- Return the existing 409 when the selected latest run fails durable verification; never query an older run.
- Keep the static GET route free of request body, CSRF preflight, `Idempotency-Key`, workflow mutation, recomputation, and durable audit writes.
- Keep explicit-new intent only in `useProjectContractReviewRun` as volatile `explicitNewIntentRef`; do not store it in browser storage or on the server.
- Do not add a migration or index migration. Keep the actor-leading index as a separately evidenced follow-up.
- Do not change Candidate authority, F02 completeness, AI behavior, or ReviewRun history/UI selection.

## Prerequisite

From `D:\code\audit_agent_1D\.worktrees\reviewrun-server-rediscovery-20260918`, verify:

```powershell
git branch --show-current
git rev-parse HEAD
git status --short
```

Expected: branch `fix/reviewrun-server-rediscovery-20260918`, amended spec at HEAD, and no uncommitted files.

### Task 1: Add deterministic latest-owned repository lookup

**Files:**

- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/port/ContractReviewRunRepository.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/infra/memory/reviewrun/InMemoryContractReviewRunRepositories.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractReviewRunMapper.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ContractReviewRunRepositories.java`
- Modify: `backend/src/main/resources/mappers/dm8/ContractReviewRunMapper.xml`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/infra/memory/reviewrun/InMemoryContractReviewRunRepositoriesTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ContractReviewRunRepositoriesTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractReviewRunDm8MapperStructureTest.java`

**Step 1: Write repository RED tests**

Extend the in-memory test with runs that vary project, contract, actor, status, `requestedAt`, and UUID. Assert that the method returns the maximum matching row by timestamp and then `runId.toString()`, includes all six statuses, ignores a newer other-actor row, and returns empty when only another actor has rows.

Extend the DM8 adapter test to stub and verify this mapper signature:

```java
ContractReviewRunPersistenceRow findLatestByProjectIdAndContractIdAndRequestedBy(
        @Param("projectId") String projectId,
        @Param("contractId") String contractId,
        @Param("requestedBy") String requestedBy);
```

Extend the mapper structure test to require all three predicates, exact descending order, and `FETCH FIRST 1 ROW ONLY`, and to reject a status predicate and `FOR UPDATE` in the new select.

**Step 2: Run tests and prove RED**

From `backend`:

```powershell
.\mvnw.cmd -Dtest=InMemoryContractReviewRunRepositoriesTest,Dm8ContractReviewRunRepositoriesTest,ContractReviewRunDm8MapperStructureTest test
```

Expected RED: compilation reports the missing repository/mapper method, or the mapper structure assertion reports the missing latest-owned SELECT.

**Step 3: Implement the repository port and both adapters**

Add exactly:

```java
Optional<ContractReviewRun> findLatestByProjectIdAndContractIdAndRequestedBy(
        UUID projectId,
        UUID contractId,
        String requestedBy);
```

The in-memory implementation filters exact equality on all three authority fields and uses:

```java
Comparator.comparing(ContractReviewRun::requestedAt)
        .thenComparing(run -> run.runId().toString())
```

with `max(...)`.

The mapper XML select must be equivalent to:

```sql
SELECT <include refid="runColumns"/>
FROM CONTRACT_REVIEW_RUN
WHERE PROJECT_ID = #{projectId}
  AND CONTRACT_ID = #{contractId}
  AND REQUESTED_BY = #{requestedBy}
ORDER BY REQUESTED_AT DESC, RUN_ID DESC
FETCH FIRST 1 ROW ONLY
```

The DM8 adapter wraps the nullable mapper row with `Optional.ofNullable(...).map(this::run)`. Do not add a second query, Java-side owner filter, status filter, or lock.

**Step 4: Run focused GREEN and inspect the diff**

```powershell
.\mvnw.cmd -Dtest=InMemoryContractReviewRunRepositoriesTest,Dm8ContractReviewRunRepositoriesTest,ContractReviewRunDm8MapperStructureTest test
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/port/ContractReviewRunRepository.java backend/src/main/java/com/fuquan/audit/contract/infra/memory/reviewrun/InMemoryContractReviewRunRepositories.java backend/src/main/java/com/fuquan/audit/integration/dm8/ContractReviewRunMapper.java backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ContractReviewRunRepositories.java backend/src/main/resources/mappers/dm8/ContractReviewRunMapper.xml
```

Expected GREEN: focused tests have zero failures/errors; the diff contains one SELECT-only repository seam and no migration.

**Step 5: Commit**

```powershell
git add backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/port/ContractReviewRunRepository.java backend/src/main/java/com/fuquan/audit/contract/infra/memory/reviewrun/InMemoryContractReviewRunRepositories.java backend/src/main/java/com/fuquan/audit/integration/dm8/ContractReviewRunMapper.java backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ContractReviewRunRepositories.java backend/src/main/resources/mappers/dm8/ContractReviewRunMapper.xml backend/src/test/java/com/fuquan/audit/contract/infra/memory/reviewrun/InMemoryContractReviewRunRepositoriesTest.java backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ContractReviewRunRepositoriesTest.java backend/src/test/java/com/fuquan/audit/integration/dm8/ContractReviewRunDm8MapperStructureTest.java
git commit -m "feat: discover latest owned ReviewRun"
```

### Task 2: Add the read-only discovery application service

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/ContractReviewRunDiscoveryService.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ContractReviewRunDiscoveryServiceTest.java`

**Step 1: Write service RED tests**

Create Mockito tests for:

- missing current user returns `BusinessErrorCode.UNAUTHENTICATED` and never queries the repository;
- current user ID is passed as `requestedBy` with the exact project and contract;
- `Optional.empty()` becomes `BusinessErrorCode.NOT_FOUND` and never calls the output reader;
- each of `PENDING`, `RUNNING`, `COMPLETED`, `PARTIALLY_COMPLETED`, `FAILED`, and `UNKNOWN` delegates the selected ID to `ReviewStepOutputReadService.getRun(...)` and returns the exact `RunOutput`;
- an existing 409 from the output reader is propagated and the repository is called exactly once, proving no older-run fallback;
- mocks verify no repository insert/transition or output-side write is invoked.

**Step 2: Run tests and prove RED**

```powershell
.\mvnw.cmd -Dtest=ContractReviewRunDiscoveryServiceTest test
```

Expected RED: the discovery service class is missing.

**Step 3: Implement the minimal service**

Use this complete service shape:

```java
package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.security.application.AuthContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractReviewRunDiscoveryService {
    private final AuthContext auth;
    private final ContractReviewRunRepository runs;
    private final ReviewStepOutputReadService outputs;

    public ContractReviewRunDiscoveryService(
            AuthContext auth,
            ContractReviewRunRepository runs,
            ReviewStepOutputReadService outputs) {
        this.auth = auth;
        this.runs = runs;
        this.outputs = outputs;
    }

    @Transactional(readOnly = true)
    public ReviewStepOutputReadService.RunOutput getLatest(UUID projectId, UUID contractId) {
        var actor = auth.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        var run = runs.findLatestByProjectIdAndContractIdAndRequestedBy(
                        projectId, contractId, actor.userId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        return outputs.getRun(projectId, contractId, run.runId());
    }
}
```

Do not construct controller DTOs or catch/translate the output reader's 409.

**Step 4: Run focused GREEN and inspect the diff**

```powershell
.\mvnw.cmd -Dtest=ContractReviewRunDiscoveryServiceTest,ReviewStepOutputReadServiceTest test
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/ContractReviewRunDiscoveryService.java backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ContractReviewRunDiscoveryServiceTest.java
```

Expected GREEN: actor authority, 404 ownership privacy, all statuses, verified-reader delegation, and corrupt-latest no-fallback pass.

**Step 5: Commit**

```powershell
git add backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/ContractReviewRunDiscoveryService.java backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ContractReviewRunDiscoveryServiceTest.java
git commit -m "feat: verify latest owned ReviewRun"
```

### Task 3: Expose the static latest-run API and OpenAPI contract

**Files:**

- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ContractReviewRunController.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunApiIntegrationTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunOpenApiStructureTest.java`
- Modify: `docs/openapi.yaml`

**Step 1: Write API RED tests**

Extend MockMvc coverage to prove:

- `GET /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/latest` is resolved as the static route rather than UUID `{runId}`;
- no owned run and only-other-actor run both return 404;
- the current actor's older run wins over another actor's newer run;
- an owned run returns 200 with the same `runId`, full verified outputs, and current `executionOwnership.mode()`;
- the route accepts the existing allowed roles and rejects an unauthorized role;
- the request has no body, CSRF, or idempotency requirement;
- before/after in-memory counts and identities for runs, steps, attempts, findings, evidence, and idempotency records do not change;
- corrupt latest plus older valid returns 409 and never returns the older run.

Extend the OpenAPI structure test to require the static path, 200 `ApiResponseReviewRunResponse`, 401/403/404/409 responses, and absence of a request body or `Idempotency-Key` parameter.

**Step 2: Run tests and prove RED**

```powershell
.\mvnw.cmd -Dtest=ContractReviewRunApiIntegrationTest,ContractReviewRunOpenApiStructureTest test
```

Expected RED: `/latest` is not implemented and the OpenAPI path is absent.

**Step 3: Implement the controller route**

Inject `ContractReviewRunDiscoveryService` and add:

```java
@GetMapping("/latest")
public ApiResponse<ReviewRunResponse> getLatest(
        @PathVariable UUID projectId,
        @PathVariable UUID contractId) {
    return ApiResponse.success(ReviewRunResponse.from(
            discovery.getLatest(projectId, contractId),
            executionOwnership.mode()));
}
```

Keep `ReviewRunResponse.from(ReviewStepOutputReadService.RunOutput, ...)` as the only response mapper. Add the OpenAPI static path before the parameterized run path for human clarity. Do not add a body, request header, or mutation call.

**Step 4: Run focused GREEN and inspect the diff**

```powershell
.\mvnw.cmd -Dtest=ContractReviewRunApiIntegrationTest,ContractReviewRunOpenApiStructureTest,ContractReviewRunDiscoveryServiceTest test
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/api/project/ContractReviewRunController.java docs/openapi.yaml
```

Expected GREEN: 200/404/409, role policy, static routing, verified projection reuse, and read-only evidence all pass.

**Step 5: Commit**

```powershell
git add backend/src/main/java/com/fuquan/audit/contract/api/project/ContractReviewRunController.java backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunApiIntegrationTest.java backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunOpenApiStructureTest.java docs/openapi.yaml
git commit -m "feat: expose ReviewRun rediscovery API"
```

### Task 4: Add the frontend latest-run data-source seam

**Files:**

- Modify: `frontend/src/features/contract/data/reviewRunDataSource.ts`
- Modify: `frontend/src/features/contract/data/httpReviewRunDataSource.ts`
- Modify: `frontend/src/features/contract/data/httpReviewRunDataSource.test.ts`

**Step 1: Write transport RED tests**

Add tests for this exact interface:

```ts
getLatestReviewRun(
  projectId: string,
  contractId: string,
  options?: ReviewRunRequestOptions,
): Promise<ReviewRunDto | null>;
```

Prove a 200 response is validated with `assertReviewRunProjection(value, "REOPEN")`; 404 maps to `null`; 401, 403, 409, 500, and `NETWORK_ERROR` are rethrown unchanged. Inspect captured fetch requests to prove the method sends one credentialed GET to `${root}/latest` with the supplied abort signal and sends no body, CSRF preflight, or `Idempotency-Key`.

**Step 2: Run tests and prove RED**

From `frontend`:

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/contract/data/httpReviewRunDataSource.test.ts
```

Expected RED: `getLatestReviewRun` is missing.

**Step 3: Implement endpoint-local 404 handling**

Import `ApiError` alongside `request` and implement an `async` method that catches only:

```ts
if (cause instanceof ApiError && cause.status === 404) return null;
throw cause;
```

Do not change the shared `request()` function or global 404 behavior.

**Step 4: Run focused GREEN and build the type boundary**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/contract/data/httpReviewRunDataSource.test.ts
npm.cmd run build
git diff --check
```

Expected GREEN: the focused transport suite passes and build errors, if any, identify every test fake that must implement the additive required method in later tasks.

**Step 5: Commit**

```powershell
git add src/features/contract/data/reviewRunDataSource.ts src/features/contract/data/httpReviewRunDataSource.ts src/features/contract/data/httpReviewRunDataSource.test.ts
git commit -m "feat: request latest owned ReviewRun"
```

### Task 5: Implement pointer-first restore with server fallback

**Files:**

- Modify: `frontend/src/features/contract/useProjectContractReviewRun.ts`
- Modify: `frontend/src/features/contract/useProjectContractReviewRun.test.ts`

**Step 1: Write restore state-machine RED tests**

Update ReviewRun data-source fakes with `getLatestReviewRun` and add assertions for:

- valid pointer exact GET succeeds and discovery call count remains zero;
- missing pointer calls discovery;
- malformed pointer is removed and calls discovery;
- pointer exact GET 404 clears pointer and same-run restart marker, then discovers;
- pointer ownership/run mismatch clears pointer and marker, rejects, and does not discover;
- pointer 401/403/409/5xx/network rejects and does not discover;
- discovery 404/null returns `null` without creating;
- discovery returns `COMPLETED`, `FAILED`, `UNKNOWN`, and `PARTIALLY_COMPLETED` exactly;
- discovery ownership mismatch or invalid `REOPEN` projection rejects and does not save a pointer;
- a discovered run saves its pointer and assesses only an existing marker for that same run.

**Step 2: Run tests and prove RED**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/contract/useProjectContractReviewRun.test.ts
```

Expected RED: pointerless restore still returns `null`, pointer 404 does not discover, and the new discovery call expectations fail.

**Step 3: Implement the minimal coordinator fallback**

Keep `restore(...)` pointer-first. Add a private GET-only helper equivalent to:

```ts
private async discover(
  signal?: AbortSignal,
  onExecutionAssessed?: ExecutionAssessmentPublisher,
): Promise<ReviewRunDto | null> {
  const discovered = await this.dataSource.getLatestReviewRun(
    this.projectId, this.contractId, { signal },
  );
  if (!discovered) {
    this.clearStoredRestartMarker();
    onExecutionAssessed?.({ restartRequired: false });
    return null;
  }
  const durable = assertOwnedRun(
    discovered, this.actorId, this.projectId, this.contractId, undefined, "REOPEN",
  );
  saveActiveReviewRun(
    this.persistence.storage, this.actorId, this.projectId, this.contractId, durable.runId,
  );
  onExecutionAssessed?.(this.assessStoredRestartMarker(durable));
  return durable;
}
```

Call it only when no valid pointer exists or after the exact pointer GET returns 404. Preserve fail-closed behavior for ownership/identity mismatch and every other exact-pointer error.

**Step 4: Run focused GREEN and inspect the diff**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/contract/useProjectContractReviewRun.test.ts src/features/contract/reviewRunOperation.test.ts
git diff --check
git diff -- src/features/contract/useProjectContractReviewRun.ts src/features/contract/useProjectContractReviewRun.test.ts
```

Expected GREEN: pointer fast path, 404 fallback, discovery restore, marker handling, and fail-closed branches pass without POST calls.

**Step 5: Commit**

```powershell
git add src/features/contract/useProjectContractReviewRun.ts src/features/contract/useProjectContractReviewRun.test.ts
git commit -m "feat: restore ReviewRun from server authority"
```

### Task 6: Preserve explicit-new intent and stable uncertain-create retry

**Files:**

- Modify: `frontend/src/features/contract/useProjectContractReviewRun.ts`
- Modify: `frontend/src/features/contract/useProjectContractReviewRun.test.ts`

**Step 1: Write hook-level RED tests**

Use the existing React hook harness to prove:

- eligible `startNew()` clears the active pointer and matching restart marker, sets UI run to `null`, and establishes explicit-new intent;
- the next Start calls `createAndExecute(...)` directly and does not call exact restore or discovery;
- after `onRunProven`, the intent is cleared and a later Start uses normal `startOrResume(...)` behavior;
- a create network/uncertain failure keeps the intent and a retry still bypasses discovery;
- both create attempts use the same stored idempotency key;
- actor, project, or contract identity change clears the intent before Start in the new workspace;
- unmount/remount after `startNew()` loses the volatile intent, then pointerless restore rediscovers the old durable latest run and does not create.

**Step 2: Run tests and prove RED**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/contract/useProjectContractReviewRun.test.ts
```

Expected RED: normal `startOrResume(...)` rediscovery still captures the run cleared by `startNew()`, and uncertainty/identity lifecycle assertions fail.

**Step 3: Implement hook-local volatile intent**

Add only inside `useProjectContractReviewRun`:

```ts
const explicitNewIntentRef = useRef(false);
```

Clear it with an effect keyed only by `[actorId, projectId, contractId]`. In eligible `startNew()`, call `coordinator.clear(run.runId)`, set the ref to `true`, and clear UI state. In `start()`, branch inside the existing `execute(...)` callback:

```ts
await execute((signal, publish, assess) => {
  if (!explicitNewIntentRef.current) {
    return coordinator.startOrResume(
      expectedContractVersion, selectedFileIds, signal, publish, assess,
    );
  }
  return coordinator.createAndExecute(
    expectedContractVersion,
    selectedFileIds,
    signal,
    (proven) => {
      explicitNewIntentRef.current = false;
      publish(proven);
    },
    assess,
  );
}, "CREATE");
```

The wrapper clears the ref only after a durable run is proven and then publishes the run. Do not clear the ref in `catch`/`finally`, and do not add it to `reviewRunOperation.ts` or any storage key. Existing `loadOrCreateReviewRunCreateOperation(...)` must remain the stable operation identity source.

**Step 4: Run focused GREEN and inspect storage boundaries**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/contract/useProjectContractReviewRun.test.ts src/features/contract/reviewRunOperation.test.ts
rg -n "explicitNewIntent" src/features/contract
git diff --check
```

Expected GREEN: only the hook production file and hook test contain explicit-new intent; browser persistence still contains only pointer, restart marker, and operation identities.

**Step 5: Commit**

```powershell
git add src/features/contract/useProjectContractReviewRun.ts src/features/contract/useProjectContractReviewRun.test.ts
git commit -m "fix: preserve explicit new ReviewRun intent"
```

### Task 7: Close backend/frontend integration regressions

**Files:**

- Modify as needed for additive interface compatibility: existing `frontend/src/**/*.test.ts` ReviewRun data-source fakes only
- Modify: `frontend/src/features/contract/useDurableReviewRunPolling.test.ts`
- Modify: `frontend/src/features/contract/contractReviewRunWorkspace.test.ts`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunApiIntegrationTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ContractReviewRunDiscoveryServiceTest.java`

**Step 1: Run broader gates to expose integration RED**

```powershell
Set-Location backend
.\mvnw.cmd -Dtest=ContractReviewRunDiscoveryServiceTest,ContractReviewRunApiIntegrationTest,ReviewStepOutputReadServiceTest test
Set-Location ..\frontend
node --test --experimental-strip-types --test-isolation=none src/features/contract/data/httpReviewRunDataSource.test.ts src/features/contract/useProjectContractReviewRun.test.ts src/features/contract/useDurableReviewRunPolling.test.ts src/features/contract/reviewRunPolling.test.ts src/features/contract/contractReviewRunWorkspace.test.ts
npm.cmd run build
```

Expected RED, if present: additive test doubles lack `getLatestReviewRun`, or a polling/continuation integration assertion is missing.

**Step 2: Add only missing regression assertions and fake methods**

Complete the required matrix without changing production behavior:

- discovered PENDING/RUNNING `SERVER_WORKER` publishes the polling pointer and performs GET-only polling with zero execute calls;
- discovered PENDING/RUNNING `CLIENT_COMPATIBILITY` exposes explicit continuation and does not auto-execute during restore;
- discovery of every terminal status preserves that status and does not create;
- fresh hook mount with empty storage saves the server-discovered pointer;
- closeout context continues to derive from the restored durable `runId`;
- backend latest selection remains actor-filtered, status-free, deterministic, read-only, and corrupt-latest fail-closed.

For required interface compatibility, add a fake `getLatestReviewRun` that throws `new Error("not used")` when the test does not exercise discovery. Do not make the interface optional.

**Step 3: Run focused GREEN and full build**

```powershell
Set-Location ..\backend
.\mvnw.cmd -Dtest=ContractReviewRunDiscoveryServiceTest,ContractReviewRunApiIntegrationTest,ReviewStepOutputReadServiceTest test
Set-Location ..\frontend
node --test --experimental-strip-types --test-isolation=none src/features/contract/data/httpReviewRunDataSource.test.ts src/features/contract/useProjectContractReviewRun.test.ts src/features/contract/useDurableReviewRunPolling.test.ts src/features/contract/reviewRunPolling.test.ts src/features/contract/contractReviewRunWorkspace.test.ts
npm.cmd run build
git diff --check
```

Expected GREEN: all focused backend/frontend integration tests pass and TypeScript build has no interface errors.

**Step 4: Review the scope diff**

```powershell
Set-Location ..
git diff --name-only 782778bc9f4c2b512f824cce69d9c48e1795a628..HEAD
git diff --stat 782778bc9f4c2b512f824cce69d9c48e1795a628..HEAD
```

Confirm no file under migration or `services/audit-ai` changed and no P1-B/P1-C behavior was introduced.

**Step 5: Commit**

```powershell
git add backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunApiIntegrationTest.java backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ContractReviewRunDiscoveryServiceTest.java frontend/src/features/contract/useDurableReviewRunPolling.test.ts frontend/src/features/contract/contractReviewRunWorkspace.test.ts frontend/src
git commit -m "test: cover ReviewRun server rediscovery"
```

Before committing, inspect `git diff --cached --name-only` and unstage any production file not already required by Tasks 1–6.

### Task 8: Run full verification, fresh-context browser acceptance, and final review

**Files:** No source modification expected. Record evidence in the final handoff; do not create an empty commit.

**Step 1: Run full automated verification from fresh command invocations**

Backend:

```powershell
Set-Location backend
.\mvnw.cmd clean verify
```

Expected: existing baseline plus new tests, zero failures and zero errors.

Frontend:

```powershell
Set-Location ..\frontend
node --test --experimental-strip-types --test-isolation=none "src/**/*.test.ts"
npm.cmd run build
```

Expected: zero failed Node tests and successful Vite build.

FastAPI regression gate, after confirming `services/audit-ai` has no production diff:

```powershell
Set-Location ..\services\audit-ai
python -m pytest
```

Expected: full pytest passes; no real provider call is required for this P1-A feature.

Repository checks:

```powershell
Set-Location ..\..
git diff --check
git status --short
git diff --name-only 782778bc9f4c2b512f824cce69d9c48e1795a628..HEAD
```

Expected: no migration or AI production files and a clean working tree.

**Step 2: Start the representative local environment without printing secrets**

Reuse the environment configuration at:

```text
D:\code\audit_agent_1D\.worktrees\ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.ps1
```

Start the current branch's backend and frontend against the representative database/services. Do not echo environment values, raw passwords, tokens, contract text, prompts, or report contents.

**Step 3: Establish pre-reopen durable evidence**

Use an existing safe Project/Contract with a `COMPLETED` ReviewRun containing findings/evidence, at least one acknowledgement, and an `AVAILABLE` report. Record only bounded identifiers and counts:

```text
projectId, contractId, runId, reportId
run/step/attempt/output/finding/evidence/ack/report counts
```

Use normal GET APIs plus DM8 read-only SELECTs. Do not create or recompute a run for this acceptance.

**Step 4: Perform true fresh-context browser rediscovery**

Open a new Edge InPrivate context, or clear every ReviewRun-related `sessionStorage` key. Sign in as the same actor, navigate through the UI to the same Project, Contract, and Review Workspace, and do not seed a pointer manually.

Capture network evidence that:

- `GET .../review-runs/latest` returns 200 and the original `runId`;
- subsequent reads reopen the same run, findings/evidence, acknowledgement, report metadata, and available artifact;
- there is no POST to run creation, step execute, step retry, acknowledgement, or report generation;
- there is no AI, F02, breach, or term recomputation request.

**Step 5: Prove read-only durability**

Repeat the same DM8 read-only SELECTs and confirm all recorded IDs and counts are unchanged. If the original and rediscovered IDs differ, any unexpected POST appears, or counts change, mark acceptance failed and use systematic debugging before any correction.

**Step 6: Request final code review**

Review the complete range from the production baseline through HEAD, emphasizing:

- actor predicate is enforced in SQL before order/limit;
- no status filter, no lock, and deterministic tie-breaker;
- repository absence remains `Optional.empty()` and service owns 404;
- corrupt latest never falls back;
- endpoint is verified and read-only;
- pointer fast path and exact-GET error matrix are preserved;
- explicit-new intent is hook-local, uncertainty-safe, and operation-identity-stable;
- polling/continuation behavior is unchanged;
- no migration, P1-B/P1-C, AI, or new server restart-state scope.

Fix every Critical or Important finding with a new RED test, focused GREEN, and a dedicated corrective commit. Then rerun Step 1 and the affected browser evidence.

**Step 7: Final handoff check**

```powershell
git log --oneline 782778bc9f4c2b512f824cce69d9c48e1795a628..HEAD
git diff --check
git status --short
```

Do not push, merge, create a PR, modify `main`, or modify the old acceptance worktree.
