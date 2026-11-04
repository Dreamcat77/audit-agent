# Task 9 Report — CONTRACT role-assessment SQL + repositories

## Scope

Implemented only Task 9 persistence foundations:

- strict machine assessment, candidate projection, role evidence, model provenance, and human decision domain records/enums;
- assessment/evidence/decision repository ports;
- Spring `local` ConcurrentMap adapters with DB-equivalent CAS/insert-only semantics;
- DM8 mapper rows, mapper interfaces/XML, and repository adapters;
- descriptive `CONTRACT__project_file_role_assessment.sql` defining exactly three additive tables;
- focused domain, local concurrency, DM8 adapter, and SQL structure tests.

No command service, transaction coordinator, API, AI/FastAPI/model call, frontend, numbered migration,
deployment, or customer material was added.

## TDD evidence

### Initial RED

```powershell
cd backend
.\mvnw.cmd '-Dtest=ProjectFileRoleAssessmentTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest' test
```

Observed: `BUILD FAILURE` during test compilation because all new Task 9 domain and repository types were absent.
The first shell attempt omitted quotes around Maven's comma-separated `-Dtest` value and was a PowerShell parse
error; it was corrected before counting the missing-feature compilation failure as RED.

### Adversarial RED refinements

- A test proved CAS incorrectly permitted `createdAt` drift before the timestamp validation was tightened.
- A test required equal evidence signals with different generated IDs to converge on one winner before the
  evidence business-key reload path was added.
- A test proved an assessment input unique key would wrongly suppress a new assessment identity for an
  authorized retry after a proven failure; the assessment store was corrected to use `ASSESSMENT_ID` as operation
  identity while keeping the input fingerprint immutable within that identity.

### Focused GREEN

```powershell
cd backend
.\mvnw.cmd '-Dtest=ProjectFileRoleAssessmentTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest,Dm8ProjectFileRoleRepositoriesTest' test
```

Observed: `BUILD SUCCESS`; 20 tests, 0 failures, 0 errors, 0 skipped.

### Task 7/8 + Task 9 role regression

```powershell
cd backend
.\mvnw.cmd '-Dtest=DocumentRoleSignalExtractorTest,DeterministicDocumentRoleFixtureMatrixTest,ProjectFileRoleAssessmentTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest,Dm8ProjectFileRoleRepositoriesTest' test
```

Observed: `BUILD SUCCESS`; 39 tests, 0 failures, 0 errors, 0 skipped.

### Full backend verification

```powershell
cd backend
.\mvnw.cmd clean verify
```

Observed: `BUILD SUCCESS`; 790 tests, 0 failures, 0 errors, 0 skipped.

Environment: local Windows worktree, Java 17, Maven Wrapper, Spring `local` test profile. No DM8, Redis,
FastAPI, PaddleOCR, Qwen, 26/29, or customer environment was contacted.

## Durable semantics delivered

- `RoleAssessmentExecutionStatus` is exactly `PENDING/RUNNING/COMPLETED/FAILED/UNKNOWN`.
- `RoleDecisionStatus` is exactly `PROPOSED/NEEDS_CONFIRMATION/CONFIRMED`; durable human decision rows require
  `CONFIRMED`, separate from machine execution state.
- Assessment identity binds project/file/SHA/parse result/parse version/source/assessment and policy versions,
  model provenance where applicable, and a lowercase SHA-256 input fingerprint.
- Only CAS `PENDING|UNKNOWN -> RUNNING` and `RUNNING -> COMPLETED|FAILED|UNKNOWN` are permitted in this slice. CAS updates
  cannot alter assessment inputs or creation/start timestamps.
- A failed attempt may be followed by a new assessment ID with the same business input. Duplicate publication of
  the same assessment ID reloads and validates that operation identity.
- Completed candidate roles are a typed domain list persisted as JSON CLOB; raw model responses are not stored.
- Deterministic rows reject model provenance; the future `MACHINE_AI` source requires both full model provenance
  and a preceding deterministic assessment ID. No AI behavior is implemented.
- Evidence is parse-bound and insert-only. The unique signal key converges concurrent different generated IDs,
  validates the winning immutable fact, and distinguishes duplicate races from DM8 connectivity failures.
- Human decisions are insert-only and uniquely versioned by `(FILE_ID, VERSION_NO)`; duplicate races return only
  a matching winner, while payload collisions fail closed. Read-by-ID, read-by-version, and latest paths exist.
- Human confirmation cannot update the machine assessment table: it uses a separate port, mapper, repository,
  and table.
- Local adapters use `ConcurrentMap` atomic publication/CAS; they use no `synchronized`, Redis, executor, or
  process-local lock as durable truth.

## SQL contract

`db/dm8/CONTRACT__project_file_role_assessment.sql` has the exact six-line controlled header:

- namespace `CONTRACT`;
- ID `project-file-role-assessment`;
- dependency `project-file-parse-result`;
- controlled-manual execution and non-assumed idempotency.

It creates exactly:

1. `PROJECT_FILE_ROLE_ASSESSMENT`
2. `PROJECT_FILE_ROLE_EVIDENCE`
3. `PROJECT_FILE_ROLE_DECISION`

The SQL contains explicit Project/ProjectFile/parse/assessment/supersession FKs, binding and stream UKs, state,
role, provenance, hash, range and version CHECKs, and file/parse/assessment lookup indexes. Evidence and decision
mapper XML files contain no update/delete; the assessment mapper contains one CAS update only.

## Verification classification

- UNIT/INTEGRATION VERIFIED: domain invariants, local concurrency/CAS, adapter duplicate/collision/connectivity
  behavior, structured JSON round-trip, SQL structure, prior role signal/resolver regression, and full local suite.
- REAL DM8 NOT VERIFIED: SQL and mapper behavior were not executed against a DM8 instance.
- REAL CUSTOMER MATERIAL NOT VERIFIED: intentionally not read, copied, sanitized, or used for this slice.

## Review fix wave

One TDD review-fix wave was applied after commit `9bda6ef`:

- assessment confidence is now `DECIMAL(7,6)` and `0.666667` round-trips consistently with the structured
  candidate projection;
- the parse-result table exposes a composite binding candidate key, and assessment uses the complete
  `(parseResultId, projectId, fileId, fileSha256, parseVersion)` FK;
- durable `ATTEMPT_NO` supports `UNKNOWN -> RUNNING` recovery on the same assessment/input, increments the
  attempt and version, and clears the prior unknown outcome; terminal completion retains the running attempt;
- DM8 assessment CAS receives both updated and expected rows and fences every immutable input, including
  null-safe model provenance, while updating execution-result fields only;
- evidence/decision duplicate recovery checks primary-key identity first, then business-key winners; local
  adapters atomically maintain both ID and business-key maps without `synchronized`;
- preceding assessments and superseded decisions now use composite same-owner foreign keys.

Review-fix RED was observed as missing `attemptNo` and the expected-row CAS mapper contract. The first post-fix
focused run exposed a deliberately adversarial decision collision fixture that incorrectly discarded the changed
file ID in its row helper; correcting the fixture made the primary-key collision test exercise the real adapter.

Final review-fix verification:

```powershell
cd backend
.\mvnw.cmd '-Dtest=DocumentRoleSignalExtractorTest,DeterministicDocumentRoleFixtureMatrixTest,ProjectFileRoleAssessmentTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileParseResultDm8SqlStructureTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest,Dm8ProjectFileRoleRepositoriesTest' test
```

Observed: `BUILD SUCCESS`; 48 tests, 0 failures, 0 errors, 0 skipped.

```powershell
cd backend
.\mvnw.cmd clean verify
```

Observed: `BUILD SUCCESS`; 797 tests, 0 failures, 0 errors, 0 skipped.

REAL DM8 remains NOT VERIFIED; no customer environment or material was used.

## Final collision and SQL-fence follow-up

Two adversarial local tests first failed because an equivalent business-key winner could hide that the proposed
generated ID was already owned by another evidence signal or decision stream. Both ConcurrentMap adapters now
claim/check the primary-key map first and then atomically converge on the business-key winner; a losing generated
ID is never aliased to a winner with another ID.

The DM8 assessment CAS no longer fences on `CREATED_AT`, avoiding JDBC/DM8 timestamp-precision false misses, and
preserves the durable `STARTED_AT` when RUNNING transitions to a terminal state. It only writes a new start time
for `PENDING|UNKNOWN -> RUNNING`. The SQL now persists a predecessor-source discriminator and a source-inclusive
candidate key/FK, proving that every `MACHINE_AI` row references a same-input `MACHINE_DETERMINISTIC` assessment.

Final follow-up verification observed `BUILD SUCCESS`: the Task 7-9 focused regression ran 51 tests and the
backend `clean verify` ran 800 tests, both with 0 failures, 0 errors, and 0 skipped. REAL DM8 remains NOT VERIFIED.

## Atomic local publication follow-up

Independent re-review identified a return-before-rediscovery window in the two local dual-index repositories:
a loser could observe a business-key winner before that winner's ID index publication completed. Evidence and
decision repositories now keep both indexes in one immutable state and publish the complete state with one CAS.
The concurrency tests perform an immediate `findById` after every contender returns, before joining the other
contenders, and require the returned winner to be visible.

Focused verification:

```powershell
cd backend
.\mvnw.cmd -Dtest=InMemoryProjectFileRoleRepositoriesTest test
```

Observed: `BUILD SUCCESS`; 10 tests, 0 failures, 0 errors, 0 skipped. The broader Task 7-9 regression and full
backend verification are rerun after this follow-up before Task 9 is accepted.

## Active assessment fence follow-up for Task 10

Task 10 exposed an operation-identity gap that could not be closed by a non-atomic application pre-read:
two distinct idempotency keys could concurrently publish separate `PENDING` assessments for the same immutable
file/input fingerprint. The approved Task 9 persistence evolution adds `ASSESSMENT_FENCE_KEY` without adding a
fourth table or a numbered migration.

- `PENDING`, `RUNNING`, and `UNKNOWN` persist `ASSESSMENT_FENCE_KEY = INPUT_FINGERPRINT`.
- `COMPLETED` and `FAILED` persist `ASSESSMENT_FENCE_KEY = ASSESSMENT_ID`.
- `UNIQUE(FILE_ID, ASSESSMENT_FENCE_KEY)` is the DM8 race arbiter; a CHECK binds the discriminator to execution
  status without relying on nullable-unique behavior.
- DM8 duplicate handling checks the attempted primary key first, then reloads the active file/fingerprint winner
  and validates the full immutable input identity. Other duplicate/connectivity failures fail closed.
- The local repository publishes its ID map and active-fence map as one immutable state using `AtomicReference`
  CAS. Active-to-terminal CAS releases the fence atomically; no `synchronized` or Redis is used.

The fence-specific RED run observed six expected failures: three distinct-key active-state bypasses, one 20-way
local active-winner race, and two missing SQL/mapper fence assertions. Focused Task 9/10 verification then passed
40 tests; the broader Task 7-10 role regression passed 91 tests. Final local backend `clean verify` passed 816
tests with zero failures, errors, or skips. REAL DM8 remains NOT VERIFIED.

## Task 10 transaction-seam follow-up

Task 10 failure injection required two narrow repository semantics while preserving Task 9 durability rules:

- `rollbackUncommittedInsert` removes only an exact locally staged PENDING assessment and its active fence. The
  local execution CAS refuses staged rows until START audit succeeds and `commitStagedInsert` publishes them. DM8
  stage/commit/rollback defaults are intentionally no-ops because the enclosing START transaction is authoritative.
- `insertBatchIfAbsent` publishes the local evidence batch atomically, so a later collision cannot leak a prefix.
  The DM8 default remains sequential insert-only persistence inside the enclosing COMPLETE transaction.

Assessment and evidence history remain immutable outside the existing CAS transition contract; neither seam is a
business delete path. The final Task 7-10 regression passed 103 tests and full backend `clean verify` passed 826
tests, with zero failures, errors, or skips. REAL DM8 remains NOT VERIFIED.

The operational-lease/staged-START follow-up retained all Task 9 database and insert-only invariants. Focused
Task 9/10 verification passed 56 tests; Task 7-10 plus Spring binding passed 113 tests; full backend
`clean verify` passed 832 tests, all with zero failures, errors, or skips. REAL DM8 remains NOT VERIFIED.
