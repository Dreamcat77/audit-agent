# ReviewRun Server-Side Rediscovery Design

> Status: design complete, pending human review. This document defines P1-A only; it does not authorize implementation.
>
> Baseline: branch `fix/reviewrun-server-rediscovery-20260918`, commit `782778bc9f4c2b512f824cce69d9c48e1795a628`.

## Problem

`ContractReviewRun` and its material manifest, steps, attempts, outputs, findings, evidence, acknowledgements, reports, and artifact identities are durable server-side facts. The current frontend cannot rediscover that chain without a browser-local pointer:

```text
useProjectContractReviewRun
  -> ProjectContractReviewRunCoordinator.restore()
  -> loadActiveReviewRun(sessionStorage, actor, project, contract)
  -> no pointer: return null
```

The backend can reopen a known run through:

```http
GET /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}
```

but it has no operation that discovers the durable `runId`. A new browser, an InPrivate window, cleared `sessionStorage`, or a new tab without the pointer therefore presents no run even though DM8 still contains the complete chain. This violates the repository invariant that every durable resource has a server-authoritative rediscovery/reopen path.

The gap is discovery, not persistence and not execution. The solution must locate the current actor's latest durable run, then pass that identity through the existing verified read path. It must not reconstruct results, run steps, or infer completion in the browser.

## Goals

- Add one read-only server operation that discovers the latest `ContractReviewRun` for the exact tuple `(projectId, contractId, current authenticated actor)`.
- Return the existing fully verified `ReviewRunResponse`, including the current `ReviewExecutionOwnership` mode.
- Make a missing or malformed browser pointer fall back to server discovery while retaining a valid pointer as the fast path.
- Restore every durable run status without skipping inconvenient history: `PENDING`, `RUNNING`, `COMPLETED`, `PARTIALLY_COMPLETED`, `FAILED`, and `UNKNOWN`.
- Make latest-run selection deterministic with `REQUESTED_AT DESC, RUN_ID DESC`.
- Fail closed when the selected latest run cannot pass existing manifest, snapshot, attempt, finding, evidence, or output verification.
- Preserve current explicit continuation, polling, retry, and new-run policies.
- Enable fresh-browser reopening of findings, evidence, acknowledgements, reports, and report artifacts without creating or executing a run.

## Non-goals

- ReviewRun history list, pagination, or choosing an arbitrary historical run.
- Administrator discovery of another actor's run.
- ReviewRun deletion, superseding, or automatic rerun.
- Candidate cross-material authority, new candidate extraction, or F02 structured completeness.
- A new Project/File authority calculation or material snapshot.
- Changes to ReviewRun, Step, Attempt, Finding, Evidence, acknowledgement, report, or artifact schemas.
- A new migration or an edit to a controlled migration.
- A server-side replacement for the existing local restart-required navigation marker.
- Any AI, OCR, F02, breach, term, retry, acknowledgement, or report command initiated by discovery.
- An implementation plan. Implementation requires a separate reviewed phase.

## Current-state evidence

The design is based on the following code facts at the baseline revision:

- `ContractReviewRunController.get(...)` already maps `ReviewStepOutputReadService.getRun(...)` to the complete `ReviewRunResponse`.
- `ReviewStepOutputReadService` documents and enforces persisted-read-only reconstruction. It validates the frozen manifest, current attempts, output resource identities, F02 task/check provenance, finding ownership, and evidence ownership. It never invokes a step executor or materializes output.
- `ContractReviewRunService.get(...)` verifies project/contract ownership, material ownership, the five-step manifest, the canonical contract snapshot hash, and `inputSnapshotIdentity`.
- `ContractReviewRunRepository` has only ID-based run lookup; neither its DM8 nor local adapter can select the latest owned run.
- `CONTRACT_REVIEW_RUN` already stores `PROJECT_ID`, `CONTRACT_ID`, `REQUESTED_BY`, `REQUESTED_AT`, and `RUN_ID`. Its current status constraint also includes `PARTIALLY_COMPLETED`.
- The current index is `(PROJECT_ID, CONTRACT_ID, REQUESTED_AT DESC, RUN_ID)`. It does not lead through `REQUESTED_BY` and does not declare the tie-breaker direction as descending.
- The shared frontend `request()` requires a JSON API envelope with `code=200`. An HTTP 204 response has no envelope and is treated as an error. It already exposes 404 as `ApiError.status === 404`.
- `loadActiveReviewRun(...)` removes malformed local pointer data and returns `null`.
- Current restore behavior clears an exact pointer on GET 404, but then returns `null`; it does not discover a replacement.
- `assertOwnedRun(...)` already validates actor, project, contract, optional run identity, and the strict `REOPEN` projection.
- `SERVER_WORKER` PENDING/RUNNING runs already use GET-only polling. `CLIENT_COMPATIBILITY` runs already use the explicit `continuePending()` path.
- The closeout context is derived from the restored durable `runId`; acknowledgement and report components already reload their facts by GET once that run is available.

## Authority model

### Discovery authority

The only discovery key is:

```text
projectId from the authenticated route
+ contractId from the authenticated route
+ requestedBy = AuthContext.currentUser().userId
```

`requestedBy` is never accepted from a query parameter, request body, header, or frontend actor field. The frontend actor ID remains only a local projection check and session-storage namespace; it is not server authorization evidence.

The repository query itself must bind all three authority fields. It is not acceptable to query the latest project/contract run first and filter its owner in Java or React. Such a query could both expose another user's row and incorrectly hide the current user's older owned run.

All currently authorized controller roles remain unchanged. Role authorization grants access to the operation, but even a `SYSTEM_ADMIN` receives only the latest run whose `REQUESTED_BY` equals that authenticated user's ID. Cross-actor history is a separate capability and is not introduced here.

### Latest authority

“Latest” means the maximum tuple:

```text
(REQUESTED_AT, RUN_ID)
```

ordered as:

```sql
ORDER BY REQUESTED_AT DESC, RUN_ID DESC
```

`REQUESTED_AT` is the business creation timestamp. `RUN_ID` is the explicit stable tie-breaker. DM8 stores the canonical UUID string, so descending lexical `RUN_ID` order is deterministic; the local adapter must use the same UUID-string comparison rather than map iteration order.

The lookup has no status predicate. It therefore includes all current statuses, including compatibility status `PARTIALLY_COMPLETED`. A latest `UNKNOWN` or `FAILED` run is returned as-is; an older `COMPLETED` run is not substituted.

### Durable projection authority

The discovery query has authority only to choose a `runId`. It does not have authority to construct a response. Once selected, the ID must go through:

```text
ReviewStepOutputReadService.getRun(projectId, contractId, runId)
  -> ContractReviewRunService.get(...)
  -> frozen manifest and snapshot verification
  -> attempt and durable output verification
  -> finding/evidence ownership verification
  -> F02 task/check/idempotency provenance reads where applicable
```

Only the verified `RunOutput` may be mapped with the existing `ReviewRunResponse.from(...)` factory.

## API

### Endpoint

```http
GET /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/latest
```

Contract:

- no request body;
- no `Idempotency-Key`;
- Session Cookie authentication and the existing ReviewRun four-role guard;
- no actor parameter;
- standard `ApiResponse` envelope and `X-Request-Id` behavior;
- static `/latest` route, distinct from the UUID `{runId}` route;
- response data is the existing `ReviewRunResponse`;
- `executionMode` continues to come from `ReviewExecutionOwnership`;
- no second ReviewRun DTO.

Responses:

| HTTP | Meaning |
|---|---|
| `200` | The latest owned run was selected and its complete durable projection passed verification. |
| `401` | No valid authenticated actor is available. |
| `403` | The authenticated actor lacks one of the existing ReviewRun roles. |
| `404` | No run exists for the exact project, contract, and current actor tuple. This also prevents disclosure that another actor has a run. |
| `409` | The selected latest owned run exists, but its frozen manifest or durable output cannot be verified. |
| `500` | An unexpected infrastructure/read failure occurred; it is not converted to “no run.” |

### No-run decision

The repository represents “no owned run” only as `Optional.empty()` and does not throw `BusinessException` or own HTTP/application exception semantics. `ContractReviewRunDiscoveryService` maps that empty result to the existing `BusinessErrorCode.NOT_FOUND`, producing HTTP 404 with the standard error envelope. The frontend's ReviewRun data source catches only this endpoint's `ApiError` with status 404 and maps it to `null`.

HTTP 204 is rejected for this design because the shared client requires a JSON `ApiResponse` envelope and would classify an empty successful response as an error. Adding special 204 handling to the shared client would be broader than this feature, while the repository already has mature 404 handling.

The OpenAPI document must describe the additive static route, its existing response schema, 404 no-owned-run semantics, and 409 verification failure. Static-route controller tests must prove `/latest` is not parsed as a UUID `{runId}`.

## Backend architecture

### Application boundary

A focused read service, named `ContractReviewRunDiscoveryService`, owns the orchestration. Its conceptual contract is:

```java
@Transactional(readOnly = true)
RunOutput getLatest(UUID projectId, UUID contractId)
```

It has only these dependencies:

- `AuthContext`, to obtain the current server-authenticated actor;
- `ContractReviewRunRepository`, to select the latest owned run identity;
- `ReviewStepOutputReadService`, to reopen and verify the selected run.

Its behavior is fixed:

1. Resolve `AuthContext.currentUser()`; absence is `UNAUTHENTICATED`.
2. Query by `projectId`, `contractId`, and `currentUser.userId()`.
3. If no row exists, throw `NOT_FOUND` without making any secondary lookup.
4. If a row exists, call `ReviewStepOutputReadService.getRun(projectId, contractId, runId)`.
5. Return that verified `RunOutput` unchanged to the controller mapping.

The read-only transaction covers selection and verified reopening. Concurrent durable transitions remain governed by existing atomic write transactions; any unprovable mixed read fails through existing verification rather than inventing a projection.

### Controller boundary

`ContractReviewRunController` gains a static `@GetMapping("/latest")` method. The method delegates to the discovery service and maps the result with the existing:

```java
ReviewRunResponse.from(output, executionOwnership.mode())
```

The controller does not access a mapper, obtain a browser actor ID, reconstruct outputs, or handle fallback ordering.

### Verification reuse

No “fast DTO query” is introduced. In particular, discovery must not bypass:

- `ReviewRunManifestVerifier`;
- canonical contract snapshot hash verification;
- `inputSnapshotIdentity` recomputation;
- exact current-attempt ownership;
- completed output resource identity checks;
- F02 real `AuditTask`/`AuditCheckResult` and durable idempotency provenance reads;
- finding/step/attempt ownership;
- evidence/finding/material parse-tuple ownership.

The existing F02 idempotency lookup inside `ReviewStepOutputReadService` is a read-only provenance check. Discovery must not call a claim, put, complete, or failure operation on the idempotency port.

## Repository/DM8 query semantics

### Port

The run repository gains one read method with server-owned identity inputs:

```java
Optional<ContractReviewRun> findLatestByProjectIdAndContractIdAndRequestedBy(
    UUID projectId,
    UUID contractId,
    String requestedBy
);
```

The method returns at most one identity row and does not accept a status filter.

### DM8 mapper

The mapper uses the existing `runColumns` projection and this exact semantic shape:

```sql
SELECT <runColumns>
FROM CONTRACT_REVIEW_RUN
WHERE PROJECT_ID = #{projectId}
  AND CONTRACT_ID = #{contractId}
  AND REQUESTED_BY = #{requestedBy}
ORDER BY REQUESTED_AT DESC, RUN_ID DESC
FETCH FIRST 1 ROW ONLY
```

All three filters, both ordered fields with descending direction, and the single-row limit are mandatory. Filtering `REQUESTED_BY` outside SQL is forbidden. The query must not join mutable Project/File/Contract tables and must not lock the row with `FOR UPDATE`.

### DM8 adapter

`Dm8ContractReviewRunRepositories` maps the nullable mapper row through the existing `ContractReviewRun` row mapper and returns `Optional.empty()` only when the query returns no row. It does not retry with fewer predicates or a status preference.

### Local adapter parity

`InMemoryContractReviewRunRepositories` filters by exact project ID, contract ID, and requested-by string, then selects the maximum comparator:

```text
requestedAt ascending for max
then runId.toString() ascending for max
```

This is equivalent to DM8's descending query with first-row selection. `ConcurrentHashMap` iteration order is never used as an ordering fact.

### Corrupt latest row

Repository selection happens once. If the selected latest run later fails `ReviewStepOutputReadService`, discovery returns the existing 409 conflict. It must not issue a second query, exclude that ID, or choose the next older row. A corrupt latest durable fact remains visible as an integrity failure rather than being masked by older success.

## Frontend restore state machine

### Data-source contract

`ReviewRunDataSource` gains a read method conceptually shaped as:

```typescript
getLatestReviewRun(
  projectId: string,
  contractId: string,
  options?: ReviewRunRequestOptions,
): Promise<ReviewRunDto | null>
```

`HttpReviewRunDataSource` sends GET to the static `/latest` route, uses `assertReviewRunProjection(value, "REOPEN")` for a 200 response, returns `null` only for `ApiError.status === 404`, and rethrows every other error. It sends neither a body nor an idempotency header.

### Restore algorithm

The browser pointer remains a cache/navigation accelerator. The server remains the workflow authority.

```text
restore
  |
  +-- load valid local pointer
  |     |
  |     +-- GET exact run succeeds
  |     |     -> assertOwnedRun(expected runId, REOPEN)
  |     |     -> use exact pointer run; do not call discovery
  |     |
  |     +-- GET exact run returns 404
  |     |     -> clear guarded pointer and its run-scoped restart marker
  |     |     -> continue to discovery
  |     |
  |     +-- response ownership/run identity mismatch
  |     |     -> clear guarded pointer and marker
  |     |     -> fail closed; do not call discovery
  |     |
  |     +-- any other failure
  |           -> surface failure; do not call discovery
  |
  +-- no pointer, malformed pointer removed, or pointer GET 404
        |
        +-- GET latest returns null
        |     -> clear any inapplicable restart marker
        |     -> return null
        |
        +-- GET latest returns run
        |     -> assertOwnedRun(REOPEN)
        |     -> saveActiveReviewRun(actor, project, contract, runId)
        |     -> assess an existing same-run restart marker
        |     -> return durable run
        |
        +-- mismatch, invalid projection, 409, 5xx, auth, or network error
              -> fail closed; do not save pointer; do not create a run
```

A valid pointer deliberately wins even if the server has a newer owned run. This retains current same-tab navigation/operation continuity and avoids silently changing the active operation. Latest discovery is used only when no usable pointer identifies a run.

Malformed pointer data is already removed by `loadActiveReviewRun`; its resulting `null` now enters discovery instead of terminating restore. A pointer GET 404 is the only server error that converts to discovery fallback. Ownership mismatch is not treated as staleness.

### Local restart marker

The existing marker remains run-scoped navigation state, not server truth:

- if a discovered run matches an existing valid marker, the existing assessment rules apply;
- if the marker does not match current durable F02/run state, existing code clears it;
- if discovery returns no run, the marker is cleared;
- a fresh browser has no marker and discovery does not invent one;
- discovery never attempts to infer an F02 policy conflict from incomplete local history.

## Terminal/Pending semantics

### Terminal and non-active runs

Discovery restores `COMPLETED`, `PARTIALLY_COMPLETED`, `FAILED`, and `UNKNOWN` exactly as stored. It never creates another run merely because the latest run is terminal or not startable.

`startOrResume()` must first honor a discovered existing run. `continuePending()` returns a non-PENDING/non-RUNNING run unchanged. Existing `canStartNewReviewRun(...)` policy remains authoritative: currently `COMPLETED`, `PARTIALLY_COMPLETED`, and `FAILED` can prepare a new run; `UNKNOWN` cannot silently start over because its outcome remains uncertain. Existing explicit retry/recovery policy governs UNKNOWN.

Server discovery introduces one necessary frontend distinction between “no active run is known” and “the user explicitly requested a new run.” Clearing the pointer alone is no longer enough, because normal restore would immediately rediscover the old terminal run. The `useProjectContractReviewRun` hook owns this distinction as a hook-local volatile ref/state, recommended as `explicitNewIntentRef`. The coordinator does not own or persist this UI intent. Its semantics are fixed:

- eligible `startNew()` first enforces the existing `canStartNewReviewRun(...)` or restart-required policy, then clears the old local active pointer, clears the corresponding restart marker, sets `explicitNewIntentRef.current = true`, and sets the UI run to `null`;
- on the next user-triggered `start()`, `explicitNewIntentRef.current === false` calls `coordinator.startOrResume(...)` with normal pointer/server rediscovery, while `explicitNewIntentRef.current === true` calls `coordinator.createAndExecute(...)` directly and bypasses restore/discovery for that Start action;
- only eligible `startNew()` can set the intent; it is never written to `sessionStorage`, `localStorage`, the backend, or DM8;
- the intent is cleared only by `onRunProven` after a new durable run identity is proven/published, or when the actor/project/contract workspace identity changes;
- network error, uncertain create outcome, or another transient create failure does not clear the intent, so a later explicit Start reuses the existing stable create-operation/idempotency identity;
- refresh or browser restart naturally discards the volatile hook ref and safely rediscovers the latest durable run.

This preserves explicit new-run behavior without allowing restore, polling, or discovery to generate a POST.

### PENDING/RUNNING with server worker

When discovery returns a `SERVER_WORKER` run in `PENDING` or `RUNNING`, setting that run reuses the existing durable polling pointer. Polling remains GET-only, bounded, visibility-aware, and scoped to actor/project/contract/run. It never posts execute or retry commands.

### PENDING/RUNNING with client compatibility

When discovery returns a `CLIENT_COMPATIBILITY` run in `PENDING` or `RUNNING`, restore itself remains GET-only. The existing presentation exposes `continuePending()` when the verified manifest permits it. Only the user's existing continuation action invokes the existing execute path; the discovery endpoint never executes a step.

A fresh browser has no restart marker. The continuation path must assess current durable step outcomes through the existing logic rather than making discovery guess whether F02 requires restart.

## Failure behavior

| Condition | Required behavior |
|---|---|
| No local pointer | Call server discovery. |
| Malformed local pointer | Remove it, then call server discovery. |
| Exact pointer GET succeeds | Verify and restore that run; do not call discovery. |
| Exact pointer GET returns 404 | Clear the guarded pointer and old run-scoped marker, then call discovery. |
| Exact pointer response has wrong actor/project/contract/run | Clear local pointer/marker, surface ownership failure, and do not discover. |
| Exact pointer GET returns 401/403/409/5xx/network failure | Surface failure; do not discover and do not create. |
| Discovery returns 404/null | Return `run=null`; do not create until an explicit normal Start action. |
| Discovery response has wrong actor/project/contract | Fail closed and do not save a pointer. |
| Discovery response fails typed `REOPEN` validation | Fail closed and do not save a pointer. |
| Latest selected run fails durable verification | Return 409; do not fall back to an older run. |
| Other actor has the newest project/contract run | Ignore it in SQL; return the current actor's newest owned run. |
| Only another actor has a run | Return 404 for the current actor. |
| Discovery transport outcome is uncertain | Show restore error; no POST and no invented `run=null` success. |

`UNKNOWN` is displayed as UNKNOWN, not FAILED, missing, or permission denied. No failure branch fabricates a completed, failed, or absent business fact.

## Idempotency/read-only guarantees

The discovery request is a query and has no command identity. It must not:

- create or update a durable idempotency record;
- create a ReviewRun, Step, Attempt, Finding, Evidence, acknowledgement, report, or audit business command;
- transition run, step, attempt, report, or artifact state;
- claim a worker lease or extend a heartbeat;
- call execute/retry endpoints;
- call AI, OCR, F02, breach, or term computation;
- reread mutable Project/File/Contract authority to rebuild the frozen run;
- regenerate a material snapshot, finding, evidence, report, or artifact.

The application and repository methods are `readOnly=true`/SELECT-only. Existing request/technical access logging is allowed, but no durable business operation audit command is emitted. `ReviewStepOutputReadService` may read existing F02 idempotency provenance as part of validation; it may not mutate it.

Frontend restore, including server-worker polling, sends only GET requests. A new ReviewRun POST remains reachable solely through the existing user-triggered Start/create flow, with the explicit-new distinction described above.

## Security/ownership

- The existing controller role expression remains `SYSTEM_ADMIN`, `PROCUREMENT_MANAGER`, `AUDITOR`, or `LEGAL`.
- `AuthContext` is the only source of `requestedBy` for discovery.
- The repository WHERE clause binds `PROJECT_ID`, `CONTRACT_ID`, and `REQUESTED_BY` together before ordering or limiting.
- A browser cannot request another actor by supplying an actor ID.
- 404 has the same client-visible meaning for “no owned run” and “only another actor has a run,” avoiding cross-actor existence disclosure.
- The selected ID is reopened with the path project and contract through existing verification; an ID alone is insufficient.
- Frontend `assertOwnedRun(REOPEN)` remains defense in depth against routing, cache, proxy, or response substitution defects.
- A direct pointer response ownership mismatch is a security/integrity failure, not a reason to silently discover a different resource.
- Logs and diagnostics contain only bounded identities/statuses already allowed by current policy; no raw contract text, prompt, provider response, secret, report body, or artifact body is introduced.

## Testing

These are implementation-phase acceptance tests; they are specified here but not executed by this design-only change.

### Backend automated scenarios

1. No owned run: discovery returns the selected 404/no-result contract and performs no secondary lookup.
2. One owned `COMPLETED` run: return that run through the complete output reader.
3. One owned `RUNNING` run: return it without status mutation or execution.
4. Multiple owned runs: return the greatest `requestedAt` irrespective of terminal status.
5. Same `requestedAt`: return the lexically greatest canonical `runId`, consistently in DM8 and in-memory adapters.
6. Another actor has a newer run while the current actor has an older run: return the current actor's run.
7. Only another actor has a run: return 404 and never pass that run ID to the output reader.
8. Latest owned run is corrupt while an older owned run is valid: return existing 409 and prove no fallback/read of the older run.
9. GET discovery is read-only: idempotency record count, operation-audit business-command count, run/step/attempt versions, findings/evidence, worker claims, and executor/model invocation counts remain unchanged.
10. DM8 mapper structure contains all three equality predicates, `ORDER BY REQUESTED_AT DESC, RUN_ID DESC`, `FETCH FIRST 1 ROW ONLY`, and no status filter/`FOR UPDATE`.
11. Parameterized status coverage returns `PENDING`, `RUNNING`, `COMPLETED`, `PARTIALLY_COMPLETED`, `FAILED`, and `UNKNOWN` without filtering.
12. API coverage proves the static `/latest` route is not UUID-bound, reuses `ReviewRunResponse`/execution mode, enforces authentication/RBAC, and maps no-run to 404.
13. Service structure verifies the selected ID is delegated to `ReviewStepOutputReadService.getRun(...)`, not reconstructed in controller/discovery code.

Expected test homes are the focused discovery service test, existing in-memory and DM8 repository tests, mapper structure test, ReviewRun API integration/OpenAPI structure tests, and existing output-reader regressions.

### Frontend automated scenarios

1. Valid pointer exists and exact GET succeeds: no discovery call.
2. Pointer missing: discovery succeeds, strict `REOPEN` validation runs, pointer is saved, and the run is restored.
3. Local pointer is malformed: it is removed and discovery is called.
4. Exact pointer GET returns 404: guarded pointer/marker clear, then discovery runs.
5. Discovery maps endpoint 404 to `null`: coordinator returns `run=null` with no create/execute call.
6. Discovery returns `COMPLETED`: restore the existing run and never call create.
7. Discovery returns `FAILED` or `UNKNOWN`: restore the exact terminal state and never call create.
8. Discovery returns PENDING/RUNNING `SERVER_WORKER`: existing GET-only polling resumes and browser execute count stays zero.
9. Discovery returns a mismatched actor/project/contract: fail closed, do not save the pointer, and do not create.
10. Fresh-browser integration harness with empty storage: discovery restores the run and causes existing finding/evidence/acknowledgement/report GET reads.
11. Discovery returns PENDING/RUNNING `CLIENT_COMPATIBILITY`: restore is GET-only and the existing explicit continuation path remains available.
12. A discovered terminal run followed by ordinary Start does not create; only eligible `startNew()` plus the subsequent explicit Start performs one create using the existing stable operation key.
13. The first Start after `startNew()` bypasses restore/discovery exactly once; after `onRunProven` clears the intent, later Start behavior returns to normal pointer/server rediscovery.
14. A network or uncertain create failure preserves the explicit-new intent and the same stable create-operation/idempotency identity for the next Start retry.
15. Changing actor, project, or contract workspace identity clears the volatile intent before the new workspace can start.
16. Refresh after `startNew()` but before a durable run is proven loses the volatile intent and rediscovers the old durable latest run instead of creating accidentally.
17. Pointer ownership mismatch clears the local pointer and fails without calling discovery.

The HTTP data-source test also proves `/latest` has no CSRF preflight, request body, or `Idempotency-Key`, validates 200 as `REOPEN`, maps only 404 to `null`, and propagates 409/5xx/network failures.

## Browser acceptance

Implementation is accepted only with a real fresh-context test against the representative environment:

1. In Browser A, the same actor creates a Project and Contract, completes a ReviewRun, acknowledges at least one finding, and generates an available report artifact. Record the durable `runId` and report identity.
2. Open a genuinely fresh Edge InPrivate context or clear all relevant `sessionStorage`; do not seed a pointer.
3. Sign in as the same actor and open the same Project, Contract, and Review Workspace.
4. Observe `GET .../review-runs/latest` return 200 with the recorded `runId` and a typed, verified durable projection.
5. Observe the existing GET-only closeout reads restore findings/evidence, acknowledgement, report list/report, and artifact availability.
6. Confirm the UI displays the prior terminal run and report rather than a new or empty review.
7. Confirm network traffic during reopen contains no POST to the ReviewRun, step execute/retry, acknowledgement, or report-generation paths and no AI/F02/breach/term recomputation call.
8. Confirm DM8 run/step/attempt/output/finding/evidence/ack/report identities and counts are unchanged by reopen.

The evidence record must label this runtime/browser gate `VERIFIED` only after the commands, environment, recorded identities, HTTP statuses, and observed no-POST condition are captured. Until then this design is `DESIGNED`, not runtime verified.

## Migration impact

Migration impact is `NONE`.

The existing `CONTRACT_REVIEW_RUN` columns are sufficient. P1-A adds a mapper SELECT only and does not modify `CONTRACT__review_run.sql`, any numbered migration, table, constraint, or index.

The current `(PROJECT_ID, CONTRACT_ID, REQUESTED_AT DESC, RUN_ID)` index is partially useful but does not exactly cover the actor predicate or descending tie-breaker. Current ReviewRun volume per Project/Contract is bounded, so this is acceptable for P1-A and avoids an unjustified controlled migration.

`INDEX FOLLOW-UP` is explicitly deferred: after implementation, inspect a representative real-DM8 execution plan and bounded-volume latency. Only if evidence demonstrates a need should a separate controlled migration review consider an index shaped like `(PROJECT_ID, CONTRACT_ID, REQUESTED_BY, REQUESTED_AT DESC, RUN_ID DESC)`. That follow-up is not part of P1-A.

## Rollout/compatibility

The endpoint and frontend data-source method are additive. Existing clients continue to use a known pointer and `GET .../{runId}` unchanged. Existing ReviewRun response, execution ownership, polling, continuation, retry, closeout, and report contracts remain intact.

Deployment ordering is strict because an older backend also returns 404 for an unknown `/latest` route, which the new frontend cannot distinguish from “no owned run”:

1. Deploy the additive backend route to every backend instance first and verify 200/404/409 behavior.
2. Only after all routed backend instances support `/latest`, deploy the frontend fallback.
3. For rollback, remove/rollback the frontend first. The additive backend endpoint may remain safely unused.

A mixed backend pool is not supported for frontend activation. No feature must interpret an old-node route 404 as authoritative absence.

Frontend rollback restores the known pointer-only limitation but does not affect durable server facts. Backend rollback removes only the additive read route/repository query. There is no schema or data rollback.

## Design decisions and self-review

- API: `GET /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/latest`.
- No run: standard 404, mapped to `null` only by the endpoint-specific frontend data source.
- Latest: `REQUESTED_AT DESC, RUN_ID DESC`, with no status filter.
- Ownership: the repository query binds current `AuthContext` actor; no trusted browser actor field.
- Projection: existing `ReviewStepOutputReadService.getRun(...)` and `ReviewRunResponse` only.
- Pointer: valid exact pointer remains the fast path; malformed/missing and exact-GET 404 use discovery.
- Ownership mismatch: fail closed; never converted into discovery fallback.
- Corrupt latest: existing 409; never fall back to an older run.
- Terminal run: restore it; never auto-create. Eligible new creation requires explicit `startNew()` intent and existing create policy.
- Pending run: worker mode polls; client compatibility retains explicit continuation; discovery never executes.
- Read-only: no idempotency write, business command audit, workflow mutation, computation, or material rebuild.
- Migration: none; performance index is a separately evidenced `INDEX FOLLOW-UP` only.
- Scope: no Candidate cross-material authority, F02 completeness, P1-B/P1-C, history UI, or administrator cross-user discovery.
- Spec contains no unresolved placeholder decision.
