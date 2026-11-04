# F02 contract review evidence — 2026-08-17

## Scope and status

This evidence covers only the Round B F02 deterministic contract quantitative-consistency vertical slice and the required AuditTask/idempotency/audit foundations.

- F02 domain/rules: **LOCAL VERIFIED**
- Spring Security + CSRF + role-protected F02 API via Spring local integration tests: **LOCAL VERIFIED**
- In-memory task/idempotency concurrency: **LOCAL VERIFIED**
- DM8 adapters: **MOCK VERIFIED**
- `V003__f02_contract_review.sql`: **STATICALLY INSPECTED / REAL DM8 NOT VERIFIED**
- dev29: **DEV29 NOT VERIFIED**
- customer: **CUSTOMER NOT VERIFIED**
- real DM8 transaction/DDL/concurrency behavior: **REAL DM8 NOT VERIFIED**
- Spring transaction boundary/ordering: **TRANSACTION STRUCTURE VERIFIED**
- Redis use by F02: **NOT USED**
- AI/OCR use by F02: **AI NOT USED / OCR NOT USED**
- asynchronous execution: **ASYNC QUEUE NOT IMPLEMENTED**
- TongRDS MQ: **TONGRDS MQ NOT VERIFIED**

No F01 or F03-F15 behavior, Prompt/Qwen call, OCR workflow, `@Async`, `CompletableFuture`, Redis queue, workflow engine, dynamic DSL, or `TaskQueuePort` was added.

## Implemented facts

F02 has five Java domain rules:

1. `F02_AMOUNT_WORDS_MATCH`
2. `F02_LINE_ITEM_ARITHMETIC`
3. `F02_LINE_SUM_MATCH`
4. `F02_AWARD_AMOUNT_MATCH`
5. `F02_AWARD_SUPPLIER_MATCH`

`Money`/`BigDecimal.compareTo` provides amount equality. The independent uppercase-RMB parser covers zero, integer/small/large units, jiao/fen, RMB prefix, yuan variants, zheng variants, and spaces. Invalid or missing evidence does not pass: it produces `NOT_EVALUATED`, `YELLOW`, and `blockSign=true`. Explicit mismatch produces `FAIL`, `RED`, and blocking; any blocking finding blocks the aggregate sign checklist.

The local fixtures are synthetic:

- PASS: `00000000-0000-0000-0000-000000000101`
- FAIL: `00000000-0000-0000-0000-000000000102`, with contract amount `876000.00` and award amount `849000.00`

The FAIL fixture deterministically produces a red/blocking award-amount finding. No real contract text or customer data is present.

## AuditTask and durable idempotency

`AuditTask` records source type/ID/version, review type, status, creator, Request ID, Trace ID, created/started/finished times, and `VERSION_NO`. New F02 transitions are only `PENDING -> PROCESSING` and `PROCESSING -> DONE|FAILED`. Legacy enum values `PARSING`, `REVIEWING`, and `NEEDS_MANUAL_REVIEW` remain available only to read possible V001-era rows; no new transition targets them and no migration meaning is guessed. The Repository Port exposes create/read/latest/conditional transition instead of generic save. DM8 SQL matches expected status/version and increments the version; local uses atomic `ConcurrentHashMap.compute`. DM8 duplicate task inserts are translated to `AuditTaskConcurrencyException`. Unit tests prove stale-version rejection and exactly one winner for two competing threads.

`Idempotency-Key` is mandatory and limited to a safe 1–128-character grammar. The SHA-256 fingerprint binds scope, actor, contract ID/version, review type, and rule versions. The local adapter uses atomic `putIfAbsent`; the DM8 adapter first inserts under unique `(SCOPE, IDEMPOTENCY_KEY)` and reads the existing row on duplicate-key translation. Both adapters retain `AUDIT_TASK/taskId` when a claim becomes FAILED, preserving failure correlation without storing response JSON. F02 never uses Redis/`CachePort`.

Tests prove first claim, concurrent single winner, success replay with the same task/result, fingerprint conflict 409, in-progress no-second-task, failed-key no silent retry with retained task identity, different-key attempts, conditional completion, task-create duplicate translation, and duplicate-key handling without a database-detail leak.

`AuditCheckResult`, `CheckStatus`, `RiskLevel`, and `AuditCheckResultRepository` now belong to the shared `review` module. Contract-specific `ContractReviewFinding -> AuditCheckResult` mapping remains in the contract Application. A source dependency test proves that `review` does not import `contract`. The table remains `AUDIT_CHECK_RESULT`; no migration was added or modified for this ownership correction.

## Audit, trace, technical flow, and security

F02 reuses `AuthContext`, `OperationAuditPort`, Round A Request ID/Trace context, technical logging, CSRF, and Session authentication. Contract APIs allow `PROCUREMENT_MANAGER`, `AUDITOR`, and `LEGAL` (plus the existing system administrator role) through method security; unauthenticated and unauthorized cases are 401/403.

Durable business events are limited to `CONTRACT_REVIEW_STARTED`, `CONTRACT_REVIEW_COMPLETED`, and `CONTRACT_REVIEW_FAILED`. They correlate user, contract, task, Request ID, and Trace ID. `OperationAuditResultStatus` contains only `SUCCESS` and `FAILURE`; START uses `eventType=CONTRACT_REVIEW_STARTED`, `action=REVIEW_START`, and `resultStatus=SUCCESS`, while `AuditTaskStatus` separately carries lifecycle. Per-rule execution is technical flow logging only and is not copied into `AUDIT_OPERATION_LOG`. Audit failures propagate. Tests assert that operation audit does not contain the synthetic contract title/supplier body and that failure detail stores only a safe exception class.

The local Spring integration log demonstrates the correlated boundary sequence: `HTTP_IN`, authorization/controller entry, idempotency claim, application start, contract read, task create/transition, five `RULE_START`/`RULE_END` pairs, result append, operation-audit writes, application end, and `HTTP_OUT`, with the same Request ID/Trace ID scope. This is automated Spring/MockMvc integration evidence, not an independently launched process/curl record.

## API evidence

Automated local integration tests cover:

- `GET /api/v1/contracts/{id}`
- `POST /api/v1/contracts/{id}/agent-review`
- `GET /api/v1/contracts/{id}/agent-results`
- `GET /api/v1/contracts/{id}/deviations`
- `GET /api/v1/contracts/{id}/sign-checklist`

A RED result returns HTTP 200. Missing contract is 404. Missing/invalid key is 400. Idempotency/task conflict is 409. Result, deviations, and checklist are projections of `AUDIT_TASK` plus append-only `AUDIT_CHECK_RESULT`; no duplicate review-record table exists.

An independent executable-JAR localhost HTTP run was requested twice but denied by the execution environment's safety approval layer, which treated the request as Round-A-only authorization. It was not bypassed. Therefore real process HTTP/curl evidence is **NOT VERIFIED in this run**; no Cookie, CSRF token, or password was recorded.

## Database and transaction evidence

`V003__f02_contract_review.sql` is the next migration after V002 and does not edit historical migrations. It evolves `AUDIT_TASK`, adds `AUDIT_OPERATION_LOG.TASK_ID`, and creates contract, line-item, award-reference, rule-config, check-result, and idempotency tables. Amount columns use DM8-oriented `DECIMAL`, UUIDs are application-generated `VARCHAR(36)`, and no auto-increment/upsert dialect was introduced.

The former long `ContractReviewService` transaction was removed. A separate Application coordinator now provides:

1. a START transaction for claim + PENDING create + PROCESSING + START audit, committed before rule evaluation;
2. deterministic calculation outside a write transaction;
3. a COMPLETE transaction for results + DONE + COMPLETED audit + SUCCEEDED;
4. a `REQUIRES_NEW` FAILURE transaction for FAILED task + FAILED audit + FAILED idempotency.

Spring transaction-interceptor tests record BEGIN/COMMIT/ROLLBACK and prove START commit occurs before the rule callback, COMPLETE success facts are ordered inside one boundary, a synthetic persistence failure rolls COMPLETE back before FAILURE begins, and FAILURE commits independently. A COMPLETE stale conflict maps to 409 and does not attempt a FAILED transition. If failure closure itself fails, the original cause remains the HTTP-safe 500 cause, the closure exception is suppressed for diagnosis, and technical logging contains only its safe type.

Mapper-mocked tests cover contract aggregate mapping, rule configuration, append-only result insert/read, conditional task transition row counts, task duplicate-key translation, atomic idempotency insert/duplicate handling, retained FAILED task identity, legacy status reads, and operation-audit task correlation. Static SQL/XML tests cover unique claim, conditional update, and DM8-compatible literal `F02_` prefix selection using `SUBSTR` rather than wildcard `_`. These facts are **TRANSACTION STRUCTURE VERIFIED / MOCK VERIFIED** only. Approved DM8 driver/media, ordered V001–V003 execution, DDL/check constraints, duplicate-key translation, affected-row behavior, and multi-table transaction commit/rollback all remain **REAL DM8 TRANSACTION NOT VERIFIED**.

## Automated verification

Commands executed during implementation included focused rule/concurrency/idempotency/API/DM8 tests and the full gate:

```powershell
cd backend
.\mvnw.cmd clean verify
```

Final strict-review result: **BUILD SUCCESS**, 46 suites and 183 tests, with zero failures, errors, or skips. The strict-review fix adds 12 net tests above the prior 171-test F02 baseline (two new test classes plus focused additions/changes covering transaction structure, concurrency, module ownership, legacy reads, task correlation, and SQL structure).

`git diff --check` passed before the final gate. No file was staged, committed, pushed, switched, merged, reset, or cleaned.
