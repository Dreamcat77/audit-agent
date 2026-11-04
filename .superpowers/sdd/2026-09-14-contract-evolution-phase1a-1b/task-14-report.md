# Task 14 report — regression and migration policy gate

## Scope delivered

- Documented the exact non-numeric `CONTRACT` migration namespace, controlled execution order, and fail-closed schema preflight in `db/dm8/README.md`.
- Added a regression test that locks the numbered baseline to exactly `V001` through `V022` and verifies the two Contract SQL identities, dependencies, and `CONTROLLED_MANUAL` execution policy.
- Confirmed by an empty Git diff that all numbered `V001`–`V022` SQL files remain unchanged.
- Did not execute either Contract SQL file and did not connect to DM8 or any external environment.
- Recorded `STRETCH_FRONTEND=NOT_STARTED`: the optional frontend slice is deliberately left outside this backend durability rollback boundary.

## TDD evidence

RED:

```text
.\mvnw.cmd -Dtest=ContractMigrationNamespacePolicyTest test
Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
The README did not yet declare the Contract namespace/order/preflight contract.
```

GREEN:

```text
.\mvnw.cmd -Dtest=ContractMigrationNamespacePolicyTest test
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

.\mvnw.cmd clean verify
Tests run: 858, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 01:12 min
Finished at: 2026-09-15T01:36:20+08:00
```

## Verification boundary

- UNIT/INTEGRATION VERIFIED: local-profile unit/integration regression, migration namespace structure, immutable parse/role workflows, existing upload/OCR/candidate/promotion/F02 behavior covered by the full suite.
- REAL DM8 NOT VERIFIED: no migration was run and no DM8 connection was made.
- REAL CUSTOMER MATERIAL NOT VERIFIED: no customer file, derivative, or external customer environment was accessed.
- STRETCH_FRONTEND=NOT_STARTED: no React production code was changed.

## Final code-review fix

The branch-wide review found a local-profile publication race: a confirmation could expose a terminal
idempotency record before its staged decision became visible. A latch-based test reproduced the exact
window, then the coordinator was changed to publish the decision first and close idempotency second.
The intermediate state is now `IN_PROGRESS + visible decision`, which the existing same-key reconciliation
can prove and close; DM8 still publishes both inside one transaction.

```text
RED:  .\mvnw.cmd -Dtest=RoleConfirmationServiceTest test
      Tests run: 8, Failures: 0, Errors: 1, Skipped: 0
      role decision not found during concurrent same-key replay

GREEN: .\mvnw.cmd -Dtest=RoleConfirmationServiceTest test
       Tests run: 8, Failures: 0, Errors: 0, Skipped: 0

GREEN: .\mvnw.cmd '-Dtest=RoleConfirmationServiceTest,ProjectFileRoleApiIntegrationTest,EffectiveDocumentRoleResolverTest,RoleAssessmentServiceTest,InMemoryProjectFileRoleRepositoriesTest,Dm8ProjectFileRoleRepositoriesTest' test
       Tests run: 75, Failures: 0, Errors: 0, Skipped: 0

FINAL: .\mvnw.cmd clean verify
       Tests run: 859, Failures: 0, Errors: 0, Skipped: 0
       BUILD SUCCESS
       Total time: 01:13 min
       Finished at: 2026-09-15T01:57:27+08:00

Independent fix re-review: APPROVE; no Critical or Important findings remain.
```
