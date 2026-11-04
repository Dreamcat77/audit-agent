# Task 11 report — durable human role confirmation

## Scope delivered

- Added `RoleConfirmationCommand`, `RoleConfirmationService`, `RoleConfirmationTransactionCoordinator`, and `RoleConfirmationResult`.
- Confirmation actor is read only from `AuthContext`; the command contains no actor/user field.
- Added length-prefixed SHA-256 fingerprinting over actor, authoritative project/file/SHA/parse identity, assessment, selected role, expected version, and normalized optional reason.
- Added one short `REQUIRES_NEW` command transaction for authoritative revalidation, durable idempotency claim, append-only `CONFIRMED` decision, operation audit, and idempotency success.
- Added real `PROJECT_FILE_ROLE_DECISION` result identity and repository-backed reopen/replay.
- Added expected-version append/supersession and unique stream-version conflict handling. The DM8 unique constraint remains the production race arbiter; the local adapter uses one atomic state with an unpublished staged row for equivalent tests.
- No API, `effectiveRole`, candidate, upload, parser/OCR, AI, or migration behavior was added in this task.

## TDD evidence

RED:

```text
.\mvnw.cmd -Dtest=RoleConfirmationServiceTest test
BUILD FAILURE: RoleConfirmationCommand, RoleConfirmationResult,
RoleConfirmationService, and RoleConfirmationTransactionCoordinator missing
```

GREEN:

```text
.\mvnw.cmd '-Dtest=RoleConfirmationServiceTest,*ProjectFileRole*Test' test
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The focused suite covers AuthContext ownership, normalized-reason replay, same-key fingerprint conflicts,
expected-version history, binding drift, incomplete assessments, distinct-key concurrency with exactly one winner,
and local failed-audit compensation.

## Verification boundary

- UNIT/INTEGRATION VERIFIED: Java unit, local concurrency, repository contract, mapper, and SQL structure tests above.
- REAL DM8 NOT VERIFIED: no DM8 instance or migration was executed.
- REAL CUSTOMER MATERIAL NOT VERIFIED: fixtures are synthetic parsed text; no customer files or 26/29/customer environment were accessed.
