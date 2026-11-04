# ADR-007: AuditTask lifecycle, optimistic concurrency, and durable idempotency

## Context

F02 introduces the first real business write command, `POST /api/v1/contracts/{id}/agent-review`. The original `AuditTask` baseline exposed a generic `save` operation over only an ID and status. It could not state which source/version was reviewed, enforce legal lifecycle transitions, distinguish create from update, or reject concurrent stale writers. A browser retry could also create duplicate tasks and duplicate formal check results.

The command must remain locally runnable without DM8/Redis while production facts belong to DM8. Future OCR/AI processing may become asynchronous, but no durable queue product or TongRDS MQ compatibility has been approved.

## Decision

- Model `AuditTask` with source type/ID/version, review type, status, creator, Request ID, Trace ID, lifecycle timestamps, and `VERSION_NO`.
- New F02 writes allow only `PENDING -> PROCESSING` and `PROCESSING -> DONE|FAILED`. Legacy values `PARSING`, `REVIEWING`, and `NEEDS_MANUAL_REVIEW` remain read-compatible only; the new state machine cannot transition into them and does not guess their meaning.
- Replace generic `save` with explicit `create`, `findById`, `findLatest`, and conditional `transition` operations. A transition updates only when ID, expected status, and expected version all match, increments `VERSION_NO`, and requires exactly one affected row.
- Map stale, illegal, or competing transitions to HTTP 409 at the Application/API boundary. A conflict is not an internal 500 and must never be converted into a fake `DONE` state.
- Give the local repository equivalent atomic semantics with `ConcurrentHashMap.compute`; local behavior may not bypass concurrency rules.
- Introduce an Application-owned `IdempotencyPort`. `local` uses an in-memory atomic adapter. `dev29`/`customer` use DM8 `IDEMPOTENCY_RECORD`, because durable business retries and their result identity are system-of-record facts.
- Claim a key by first inserting an `IN_PROGRESS` row under unique `(SCOPE, IDEMPOTENCY_KEY)`. On duplicate key, read the existing row and apply its state/fingerprint semantics. Do not implement select-then-insert.
- Bind the key to a SHA-256 request fingerprint containing operation scope, actor, contract ID/version, review type, and enabled rule versions. It never contains password, Cookie, Session ID, or response JSON.
- `SUCCEEDED` replays reconstruct the response from `AUDIT_TASK` plus `AUDIT_CHECK_RESULT`; `IN_PROGRESS`, `FAILED`, and a different fingerprint return 409 and do not create another task.
- Keep the claimed `AUDIT_TASK` resource type/ID on both `SUCCEEDED` and `FAILED` idempotency rows so an unsuccessful attempt remains correlated to its durable task. Never store the complete HTTP response.
- Do not use `CachePort` or Redis for F02 durable idempotency. Cache loss must not permit duplicate formal facts.
- Use `ContractReviewService` only as Application orchestration and place database writes in a separate Application-owned transaction coordinator. It depends on Ports and contains no Mapper/Adapter imports; transaction propagation is therefore applied through a separate Spring bean rather than self-invocation.
- Commit a short **START transaction** before rule execution. It atomically claims the key, creates `AuditTask(PENDING)`, transitions it to `PROCESSING`, and appends `CONTRACT_REVIEW_STARTED`. Duplicate/fingerprint/status decisions happen inside this boundary and cannot create a second task.
- Run deterministic rules after START commit and without a long write transaction.
- Commit a short **COMPLETE transaction** that atomically appends check results, transitions `PROCESSING -> DONE`, appends `CONTRACT_REVIEW_COMPLETED`, and marks idempotency `SUCCEEDED`, in that order.
- On ordinary execution/persistence failure, use an independent **FAILURE transaction** with `REQUIRES_NEW` to atomically transition `PROCESSING -> FAILED`, append `CONTRACT_REVIEW_FAILED`, and mark idempotency `FAILED`. A failed closure is logged by safe exception type and never represented as persisted success/failure. A stale COMPLETE transition is a 409 conflict and must not trigger a forced FAILED transition.
- `OperationAuditResultStatus` remains an action outcome (`SUCCESS`/`FAILURE`), not a task lifecycle. `CONTRACT_REVIEW_STARTED` therefore uses event type `CONTRACT_REVIEW_STARTED`, action `REVIEW_START`, and result status `SUCCESS`; task lifecycle remains in `AuditTaskStatus`.
- Execute the current deterministic F02 review synchronously. Do not introduce `@Async`, `CompletableFuture`, in-memory queues, Redis queue patterns, or an unused `TaskQueuePort`. A future asynchronous design requires an approved durable queue, delivery/acknowledgement semantics, trace-context propagation, and a verified adapter.

## Consequences

Task and idempotency records preserve the original Request ID/Trace ID and source version for correlation and replay. START facts become durable before calculation begins; COMPLETE facts share one Spring/DM8 transaction; failure closure never relies on a transaction already marked rollback-only. Durable audit failures propagate rather than being silently swallowed.

Local memory state is intentionally process-scoped and exists only for tests/development; it proves state/concurrency behavior but is not durability evidence. Spring transaction-interceptor tests verify START/COMPLETE/FAILURE structure and ordering. SQL and mapper tests still do not prove actual DM8 commit/rollback behavior. Stale `IN_PROGRESS` recovery, retention/archival, and the future durable queue product remain open operational decisions.

## Status

Accepted for Round B F02. Domain, local concurrency, API/idempotency replay, audit correlation, transaction structure, and mapper-mocked adapters are automated-test verified. **TRANSACTION STRUCTURE VERIFIED; REAL DM8 TRANSACTION NOT VERIFIED.** Real DM8, dev29/customer transactions, asynchronous queues, and TongRDS MQ are **NOT VERIFIED**; asynchronous execution is **NOT IMPLEMENTED**.
