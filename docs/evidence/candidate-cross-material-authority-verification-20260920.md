# Candidate Cross-Material Authority Verification

Full regression: PASS

## Task 20 local regression evidence (2026-09-21)

Starting revision: `28e57f116ab6bb11df3f3faa5cfef8a0835c7d14` in the existing
`candidate-cross-material-authority-20260920` worktree. These are local automated
results, not deployment, live HTTP, representative DM8, OCR/model inference, or browser acceptance.
Completion basis: approved frontend fixture isolation commit
`10ef0362bbce2436b2729fd79dd69ddfb6b5ab7b`, plus the reviewed architecture guard in
this completion change. All required local full gates exited zero after the fixture
repair; the exact focused backend/frontend and Python gates were rerun on that HEAD.

Environment: Windows 10 amd64; Eclipse Temurin JDK `17.0.19+10`; Maven wrapper
`3.9.16`; Node `v24.14.0`; npm `11.9.0`; Vite `6.3.5`; Python `3.11.16`;
pytest `9.1.1`; FastAPI `0.141.1`; Starlette `1.6.0`.
The Python interpreter is the existing repository-local environment at
`D:\code\audit_agent_1D\.worktrees\ai-ocr-project-intake-demo\.runtime\venvs\audit-ai\Scripts\python.exe`.
No packages or runtime dependencies were installed or upgraded.

## Architecture RED, diagnosis, and bounded repair

From `backend`, the initial command was:

```powershell
.\mvnw.cmd -Dtest=CandidateCrossMaterialAuthorityArchitectureGuardTest test
```

Observed: exit 1, 16 tests, 2 failures, 0 errors, 0 skipped.

- `enforcesExactTwoModeFactAuthorityAndFindingProjection` first failed with
  `active allowedFactTypes method absent`. Production already executes
  `ContractCandidateFactPolicy.authorizedFacts(...)`; the pre-implementation guard
  assumed an `allowedFactTypes(mode, role)` getter and a policy field. After that
  mismatch was removed, five assertions revealed another obsolete shape assumption:
  named Finding dispatcher/primary/explicit helpers. The actual public `project`
  method validates the Candidate's support cohort, returns `projectExplicit` for
  explicit authority, and retains the original primary-only fallback in its body.
- `keepsNumberedAndExistingContractMigrationsImmutableAndManual` rejected three
  read-only consumers: `ContractCandidateSupportMigrationCompatibilityTest`,
  `ContractCandidateSupportAuthorityDm8SqlStructureTest`, and
  `ContractEvolutionMigrationOrderDocumentationTest`. Its primitive allow-list
  omitted safe path/read/assert operations and applied migration restrictions to
  unrelated historical-domain tests in the mixed compatibility class.

The backend repair changes only the existing architecture guard. Production, historical compatibility
tests, and all migration SQL are unchanged. The repaired guard executes the actual
fact filter across both authority modes and every document role, checks connected
legacy assembler output, follows current-cohort validation into exact same-row
file/parse Finding reads, preserves the primary-only category fallback, and forbids
project-wide fallback in reachable explicit helpers. Existing immutable migration
fingerprints and controlled-manual/no-startup assertions remain active.

Migration consumer inspection follows migration-connected unqualified, `this`-qualified,
and parsed top-level-class-qualified local calls (including full qualification and
explicitly typed/simple local aliases); it also tracks referenced callers.
Fields, initializers, constructors, and nested declarations are always inspected.
Local callee bodies are checked before a same-name read/assert primitive is permitted.
This is a bounded source guard, not a general Java call-graph verifier. Unrelated
behavioral methods remain excluded; tested JDBC/process execution aliases, reflection,
and connected method references remain rejected.

Focused characterization commands were run before their corresponding guard fixes:

```powershell
.\mvnw.cmd '-Dtest=CandidateCrossMaterialAuthorityArchitectureGuardTest#policyProbeExecutesTheActualFactFilteringBoundary+legacyPolicySliceRecognizesConstructedFactPolicyAndRejectsDiscardedFacts+migrationReaderAcceptsSafeReadAssertionsAndUnrelatedBehaviorWithoutAllowingExecution' test
.\mvnw.cmd '-Dtest=CandidateCrossMaterialAuthorityArchitectureGuardTest#exactSourceReadAcceptsTheSameCandidateOwnerAcrossValidatedHelperBoundaries+migrationReaderAcceptsSafeReadAssertionsAndUnrelatedBehaviorWithoutAllowingExecution' test
.\mvnw.cmd '-Dtest=CandidateCrossMaterialAuthorityArchitectureGuardTest#migrationReaderRejectsExecutionThroughQualifiedCallersAndMethodReferences' test
```

Observed RED results respectively: 3/3 failures, 2/2 failures, and 1/1 failure;
all exited 1 with 0 errors. The last probe caught a qualified-caller gap during
self-review before the final focused gate. One intermediate new mutation fixture
also failed because Javac tree rendering does not round-trip `var`/switch syntax;
it was corrected to mutate original source, without changing production behavior.
The initial architecture result inside the exact focused backend command was 22 tests,
0 failures, 0 errors, 0 skipped. Independent review then exposed two additional guard
gaps; the review follow-up is recorded below.

### Independent review fix round 1

The first repair still accepted `Reader.of(sql)` when the local `of` implementation
executes JDBC, because only unqualified outgoing calls were followed and `of` was a
permitted primitive name. It also accepted an explicit-mode branch wrapped in
`if (false)`: finding that branch anywhere in the method did not prove that it could
control the returned projection. These were test-guard defects; production was unchanged.

The following added characterization command observed both defects before repair:

```powershell
.\mvnw.cmd '-Dtest=CandidateCrossMaterialAuthorityArchitectureGuardTest#migrationReaderRejectsClassQualifiedOutgoingHelpersBeforePrimitiveAllowlisting+findingGuardRejectsDisabledOrBypassedExplicitDispatch' test
```

RED: exit 1; 2 tests, 2 failures, 0 errors/skips, each reporting that an unsafe
mutation was accepted. The initial branch-mutation fixture had a whitespace mismatch;
the recorded RED uses a matched actual source branch and fails on the guard outcome.

The migration repair resolves parsed top-level class qualifiers before primitive
allowlisting. The adversarial probe covers `Reader.of`, `this.of`, unqualified
`of`, a fully qualified local class, and an explicitly typed local receiver alias;
removing the local call leaves a same-name unrelated helper excluded. An additional
focused RED observed the fully qualified variant being accepted before the receiver
resolution was extended (1 test, 1 failure, exit 1).
The Finding repair checks the bounded direct public cross-file/cohort/validation path:
the explicit return must immediately follow current-authority validation in the
cohort try block, with no wrapper, intervening return, or finally override. It retains
the actual public method's legacy fallback without requiring obsolete private helper
names. This structural check is not a claim of general Java reachability analysis.

Final combined review probes: 5 passed, exit 0, with 0 failures/errors/skips.
The standalone complete guard also passed 24 tests before the additional receiver
variants. Final focused backend after those variants passed 333 tests, including
the complete 24-test guard, with 0 failures/errors/skips. Final clean verify passed
1,753 tests with 0 failures/errors/skips and exit 0 after all guard edits.

## Required commands and observed results

From `backend`:

```powershell
.\mvnw.cmd -Dtest=CandidateCrossMaterialAuthorityArchitectureGuardTest,ContractCandidateSupportAuthorityDm8SqlStructureTest,ContractCandidateSupportAuthorityTest,ContractCandidateFactPolicyTest,ContractCandidateDerivedFactsCasRepositoryTest,CandidateSupportAuthorityValidatorTest,ContractCandidateSupportReplacementTransactionCoordinatorTest,ContractCandidateSupportReplacementReplayTest,ContractCandidateSupportAuthorityApiIntegrationTest,ProjectApplicationServiceTest,FindingSourceProjectionServiceTest test
.\mvnw.cmd clean verify
```

Focused backend on completion HEAD: PASS, exit 0; 333 tests, 0 failures, 0 errors, 0 skipped.
Backend clean verify after the final fixture changes: PASS, exit 0; 1,753 tests, 0 failures, 0 errors, 0 skipped;
JAR packaging and Spring Boot repackage succeeded. The previously reported
intermittent Intake assertion did not reproduce in this full run; no Intake file
or test was changed and no retry was needed. The run completed at
`2026-09-21 13:59:43 +08:00`; its raw log and recorded zero exit were independently
checked again during completion. The focused backend rerun completed at
`2026-09-21 14:10:55 +08:00`.

From `frontend`:

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/data/httpProjectDataSource.test.ts src/features/project/candidateSupportAuthorityOperation.test.ts src/features/project/candidateSupportAuthorityFlow.test.ts src/features/project/candidateSupportAuthorityPresentation.test.ts src/features/project/candidateSupportAuthorityPanel.test.ts
node --test --experimental-strip-types --test-isolation=none "src/**/*.test.ts"
npm.cmd run build
```

| Gate | Observed result |
| --- | --- |
| Exact focused frontend | PASS, exit 0; 143 tests, 143 passed, 0 failed/skipped |
| Exact full frontend with `--test-isolation=none`, after fixture isolation | PASS, exit 0; 582 tests, 582 passed, 0 failed/skipped/cancelled |
| Supplemental default process-isolated full frontend, after fixture isolation | PASS, exit 0; 582 tests, 582 passed, 0 failed/skipped/cancelled |
| Production build | PASS, exit 0; Vite built successfully, with the existing large-chunk warning |

The first sandboxed full frontend attempt could not spawn the local esbuild process
(`EPERM`). A scoped execution outside that sandbox reached the test assertions and
produced the historical four failures below. The PASS row records a new execution of
the identical required command after the approved fixture repair, not a replacement
command or a relabeling of that failed run.

From `services/audit-ai`:

```powershell
& 'D:\code\audit_agent_1D\.worktrees\ai-ocr-project-intake-demo\.runtime\venvs\audit-ai\Scripts\python.exe' --version
& 'D:\code\audit_agent_1D\.worktrees\ai-ocr-project-intake-demo\.runtime\venvs\audit-ai\Scripts\python.exe' -m pytest -q
```

FastAPI on completion HEAD: PASS, exit 0; 340 passed, 1 existing Starlette/httpx deprecation warning.
The first sandboxed attempt failed accessing the configured `.test-runtime/pytest`
directory (`WinError 5`); the identical pytest command passed outside that sandbox.
No Python production, test, or configuration file changed.

## Frontend fixture RED, diagnosis, and approved GREEN

The four failures of the required shared-process full command were:

1. `deliveryNavigation.test.ts`: `workbench routes analysis, full list, real task, projects and budget to distinct destinations`.
2. `deliveryNavigation.test.ts`: `contract landing preserves formal-contract creation, session success, full-list and old drilldowns`.
3. `deliveryNavigation.test.ts`: `project-backed review session routes directly to its Project workflow`.
4. `useContractReviewWorkspace.test.ts`: `an old acknowledgement forced GET cannot overwrite progress from a newer review run`.

The first three failed because an existing global test resolver rewrote Recharts'
extensionless CommonJS `./container/Surface` import to `./container/Surface.ts`.
The fourth failed at `acknowledgement forced GET did not start` when existing hook
tests shared global browser state. It passed alone. Existing hook fixtures installed
`window`/`document` during module import; the semantic fixture has no session storage,
and another test's cleanup can restore an earlier global window.

Minimal diagnostic commands from `frontend`:

```powershell
node --test --experimental-strip-types --test-isolation=none src/app/deliveryNavigation.test.ts src/features/budget/budgetSemanticAnalysis.test.ts
node --test --experimental-strip-types --test-isolation=none src/features/contract/useContractReviewWorkspace.test.ts
node --test --experimental-strip-types --test-isolation=none src/features/contract/useContractReviewWorkspace.test.ts src/features/contract/useContractSemanticAnalysis.test.ts
node --test --experimental-strip-types "src/**/*.test.ts"
```

Before the fixture repair, the delivery/budget pair reproduced the three module-resolution failures without
loading P1-B tests (7 tests, 4 passed, 3 failed, exit 1). The workspace test alone
passed both parent and nested tests (2 passed, exit 0); the two existing
workspace/semantic files reproduced shared-global failure (10 failed, exit 1,
including the same acknowledgement assertion and subsequent missing-window errors).
At `28e57f1`, these four fixtures had empty diffs against `9ef79de6d5d6fc53f9fe29699dd1934344b86106`:
the failure predated P1-B implementation. The full regression was not yet rerun
until the user authorized the separate fixture repair.

Approved commit `10ef0362bbce2436b2729fd79dd69ddfb6b5ab7b`
(`test: isolate shared-process frontend fixtures`) changes 22 existing frontend test
files and one 13-line descriptor-restoration helper, with no production, dependency,
assertion, skip, quarantine, or test-order changes. Each leaking resolver producer was
separately paired with the delivery test and observed RED before its fix, then GREEN.
Resolvers now deregister only their own hooks after their import batch settles.
Browser fixtures install their own globals during test execution, register cleanup
before mounting, unmount React, then restore prior descriptors or prior absence in
`finally`. The workspace fixture owns only its own session storage.

The original affected quartet was rerun using:

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/budget/budgetSemanticAnalysis.test.ts src/features/contract/useContractReviewWorkspace.test.ts src/features/contract/useContractSemanticAnalysis.test.ts src/app/deliveryNavigation.test.ts
```

Observed RED: 17 tests, 13 passed, 4 failed, exit 1. GREEN after repair: 18 passed,
exit 0; the previously unreachable nested showcase test now executes. The expanded
affected set passed 132/132, and the combined descriptor-restoration probe passed
18/18. Final default and exact shared-process full suites both passed 582/582,
with zero failures, skips, or cancellations. The required command was not changed.
Arbitrary test-order permutations were not exhaustively verified.

Completion independently checked the approved fixture commit, its SPEC/QUALITY PASS
review, and raw final logs for backend clean verify, default/shared-process frontend,
and build. The observed totals and successful termination agree with the recorded
zero exits. Fresh completion reruns passed backend focused 333/333, frontend focused
143/143, and Python 340/340.

Ignored local evidence under `.superpowers/sdd/2026-09-20-candidate-cross-material-authority-implementation/`:
`task-20-fixture-backend-full.log`, `task-20-fixture-default-final.log`,
`task-20-fixture-full-final.log`, `task-20-fixture-build.log`,
`task-20-completion-backend-focused.log`, `task-20-completion-frontend-focused.log`,
and `task-20-completion-fastapi.log`. The fixture report retains exact per-producer
RED/GREEN commands and descriptor-probe details; runtime logs are not committed.

## Recovery matrix covered by focused backend/frontend tests

| Durable/server condition | Required client outcome |
| --- | --- |
| Deterministic stale-version or stale-authority `REQUEST_REJECTED` | Clear only the exact retained old operation; do not issue a same-action second POST or erase a newer operation |
| `IN_PROGRESS` or `OUTCOME_UNCERTAIN` | Preserve the exact immutable key/payload and reconcile; reload must not allocate a replacement identity |
| Successful record with current durable result | Return the original result without current-authority revalidation or a second Candidate version increment |
| Proven `RESULT_SUPERSEDED` | Reload server state before guarded clear; never reapply the old support set |
| `INTEGRITY_CONFLICT`, unclassified/transport failure, or unproven result | Retain operation identity and fail closed; parse only `ApiError.data.classification` |
| Reload/storage failure or a different retained operation | Do not clear another operation or claim reconciliation succeeded |

## Remaining platform and acceptance gates

| Gate | Status |
| --- | --- |
| Task 19 representative DM8 catalog, composite constraints, row locks, CAS, rollback and concurrency | PASS on the approved local integration DM8; see the later controlled-migration and six-test proof |
| Task 21 explicit-support browser full chain through confirmation, promotion, ReviewRun and F02 | PASS; see final Task 21 evidence below |
| Task 21 reverse-to-empty primary compatibility, stale confirmation, refresh and backend restart | PASS; see final Task 21 evidence below |
| Live HTTP/curl, Redis, real Paddle/Qwen, deployment and customer acceptance | NOT VERIFIED by this local regression task |

At this Task 20 checkpoint, no push, merge, deployment, real migration, or
customer-data mutation had been performed. The later, separately authorized
Task 19 controlled migration and representative-platform proof are recorded
below. Full regression PASS here applies to the pre-runtime automated gate;
the additional Task 21 runtime regression is recorded separately.

Final repository checks from the worktree root:

```powershell
git diff --check
git status --short
git diff --stat
git diff -- db/dm8 backend/src/main frontend/src services/audit-ai
```

Observed: whitespace check exit 0; only the architecture guard and this evidence
document are modified relative to fixture commit `10ef0362bbce2436b2729fd79dd69ddfb6b5ab7b`.
The final command has empty output: no additional migration SQL, production,
frontend, or Python changes are part of this completion commit.
Runtime logs and the handoff report remain ignored under `.superpowers/sdd/`.

## Task 19 read-only preparation (2026-09-21)

Starting revision: `ab10a90e7c43714898f7fc677c12b627e0775652` in the same Windows
worktree. Environment identifier: operator-provided local integration DM8;
JDBC reports major version 8, driver `DmJdbcDriver18 8.1.3.140`, JDK `17.0.19`.
Target identity, complete dependency preflight, backup and rollback authorization
are still NOT VERIFIED. This is not customer or LAN full-chain acceptance.

The new read-only SQL probe executed through JDBC using the externally supplied
application credentials. The external PowerShell file was parsed as an AST; only
the three literal `AUDIT_DB_*` assignments were extracted into the child process.
The source script was neither invoked nor modified. No credential, endpoint,
username, business row content, filename or amount was emitted.

The reproducible tracked entry point is
`db/dm8/probes/CONTRACT__contract_candidate_support_authority_read_only.sql`:
execute its complete SELECT statements in the approved DM8 SQL client, authenticated
out of band as the dedicated application account in its own schema. For the Java
entry point, supply `AUDIT_DB_URL`, `AUDIT_DB_USERNAME` and `AUDIT_DB_PASSWORD` out of
band, set `AUDIT_P1B_DM8_VERIFY=true`, keep `AUDIT_P1B_DM8_WRITE_VERIFY=false`, then
run the exact Maven command below from `backend`. The ignored helpers are only
the local execution harness used for these observations; a future checkout does
not need them and must not commit an environment file.

From the worktree root, the ignored local helper ran:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/2026-09-20-candidate-cross-material-authority-implementation/task-19-readonly.ps1 -Mode Probe
powershell -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/2026-09-20-candidate-cross-material-authority-implementation/task-19-readonly.ps1 -Mode Red
```

The process-scoped script policy affects only this helper invocation. The probe
command exited 0 after all six SQL-file SELECT statements and two supplemental
catalog SELECT statements. The new support table, both Candidate columns, each
of the 18 named constraints and each of the five named indexes were individually
reported `NOT_APPLIED`. No partial new support effect was observed. Existing
enabled parent keys returned these ordered tuples:

| Parent key | Observed ordered columns |
| --- | --- |
| `UK_PROJECT_FILE_PROJECT_ID` | `PROJECT_ID, ID` |
| `UK_PROJECT_FILE_PARSE_RESULT_BINDING` | `PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION` |
| `PK_SYS_USER` | `ID` |

Baseline catalog values confirmed `BIGINT` width 8/scale 0, `INTEGER` width 4/scale 0,
`VARCHAR` width 64/scale 0, and `TIMESTAMP` width 8/scale 6. An existing FK reported
`NO ACTION`. DM8 renders `IS NOT NULL` as `NOT (column IS NULL)` in check expressions;
the test normalizes that observed equivalent rendering without removing logical
grouping. New-schema matching remains NOT VERIFIED until authorized migration.

The RED helper forces `AUDIT_P1B_DM8_VERIFY=true` and
`AUDIT_P1B_DM8_WRITE_VERIFY=false`, then runs the exact required backend command:

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityRepresentativeDm8IT test
```

Observed at `2026-09-21 14:44:32 +08:00`: compilation succeeded; command exited 1
on the real DM8 catalog assertion `support authority table absent; no write was
attempted` (expected count 1, observed 0). Surefire reported one class-level
setup failure, zero skips; its final summary classifies this as one error.
This is the intended real pre-migration RED, not a schema, transaction or Task 19 PASS.
The earlier no-opt-in execution also failed closed without connecting; it is
environment-gate evidence only, not representative-platform RED.

The opt-in IT now contains separately guarded constraint, production lock-order,
CAS and rollback scenarios. They have **not executed on DM8**. Write tests require
both `AUDIT_P1B_DM8_WRITE_VERIFY=true` and an approved existing test actor UUID in
`AUDIT_P1B_DM8_ACTOR_ID`, in addition to the normal external secrets. The schema
gate runs first. The test never reads or executes migration SQL and never starts
the application or an automatic migration runner.

Constraint fixtures are wholly rolled back. Cross-connection tests would commit
only generated synthetic Project fixtures; IDs are printed before any possible
commit, cleanup is bounded to those generated IDs, and an independent connection
must prove cleanup. A failed or uncertain fixture commit requires separate
operator recovery using those IDs. No fixture creation, cleanup, DML or DDL has
been executed in this task.

Proof boundaries of the unexecuted scenarios:

| Scenario | Intended proof / current status |
| --- | --- |
| Rolled-back tuple substitution, historical null/null, duplicate and independent Candidate ownership | Real DM8 integrity constraints; NOT VERIFIED |
| File-holder requests Project while production replacement waits; opposite input order | Actual production coordinator/repository lock acquisition, with a deliberate stop at authority validation; NOT VERIFIED |
| Same-version concurrent replacement | Production Repository/Mapper CAS, line replacement and unmixed committed cohort; NOT VERIFIED |
| Injected failure after delete, CAS/line replacement and insert | Real JDBC transaction rollback of Candidate, lines and cohort using production persistence operations; NOT VERIFIED |
| Support replacement to `[]` through the full production command | Not covered by the persistence fixture or executed here; NOT VERIFIED |
| Confirmation full-file authority fence on representative DM8 | Not covered by the replacement lock probe or executed here; NOT VERIFIED |
| Promotion full-file authority fence on representative DM8 | Not covered by the replacement lock probe or executed here; NOT VERIFIED |
| Full validator-to-commit, durable audit/idempotency and Confirmation/Promotion replay | Not exercised by these bounded probes; NOT VERIFIED |

The lock probes use a mocked validation boundary and unused idempotency lookup;
their planned result cannot prove the complete business transaction. Synthetic
persistence fixtures alone cannot establish the full current primary authority.
Task 19 GREEN and the full-path gates were not yet executed at that checkpoint. The only observed database
actions in this task were read-only metadata queries.

Nearby automated regressions were run from `backend`:

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityDm8SqlStructureTest,ContractCandidateSupportAuthorityMapperStructureTest,Dm8ContractCandidateSupportAuthorityRepositoryTest,ContractCandidateSupportReplacementLockOrderTest test
```

Observed `2026-09-21 14:46:51 +08:00`: exit 0, 20 tests, zero failures/errors/skips.
This gate compiled the IT but did not execute its real write scenarios.

Final local regression from `backend`: `.\mvnw.cmd clean verify` exited 0 at
`2026-09-21 14:52:30 +08:00`; 1,753 tests, zero failures/errors/skips, with JAR
packaging and repackage successful. The new `*IT` class is compiled but excluded
from default Surefire selection; this local PASS does not constitute a DM8 GREEN.
The ignored raw log is
`.superpowers/sdd/2026-09-20-candidate-cross-material-authority-implementation/task-19-clean-verify.log`.
`git diff --check` produced no whitespace diagnostics. The only tracked scope is
the new read-only SQL probe, opt-in Java IT, and this appended evidence section.
No production or migration SQL changed; no commit, push or merge was performed.

### Task 19 dependency and target follow-up (read-only)

The follow-up narrows the earlier dependency/target uncertainty; it does not
authorize database changes. On the same local DM8, JDBC reported version
`8.0.0.0`. The selected historical structural effects were compared against
their exact approved SQL at `ab10a90e7c43714898f7fc677c12b627e0775652`:

| Approved source | Matching effect checks | Drift | Unknown |
| --- | ---: | ---: | ---: |
| `V010__project_intake_core.sql` | 110 | 0 | 0 |
| `V017__project_file_parse_provenance.sql` | 9 | 0 | 0 |
| `CONTRACT__project_file_parse_result.sql` | 37 | 0 | 0 |
| `CONTRACT__project_file_role_assessment.sql` | 110 | 0 | 0 |
| `CONTRACT__contract_candidate_authority.sql` | 20 | 0 | 0 |
| `CONTRACT__project_file_identity_assessment.sql` | 89 | 0 | 0 |
| `V002__local_account_auth_observability.sql`, `SYS_USER` structure only | 17 | 0 | 0 |
| Total | 392 | 0 | 0 |

Each count is a table-presence, column-definition, named constraint or named-index
comparison, not a business test. Column checks cover type, scalar/text width,
decimal precision/scale, timestamp scale, nullability and default. Constraint
checks cover owning table, type, `ENABLED` status, exact ordered child/parent
tuples, enabled parent PK/UK, local schema ownership, `NO ACTION` delete policy,
and the normalized approved check expression. Index checks cover table,
uniqueness, validity, ordered columns and direction. SQL comments and unrelated
V002 tables/seeds are excluded. Later additive effects are not mistaken for
changes to these historical declarations. No historical migration was executed.

In particular, the Candidate authority source/parse ownership keys, role-decision
parse binding, identity assessment seven-column binding, identity decision
ownership/parse keys, and extraction ID/parse ownership key were all present and
enabled with their exact approved tuples. This includes the exact existing
ProjectFile, ParseResult and SYS_USER parent keys listed above. The new Candidate
support-cohort key and the expanded seven/eight-column support role/identity
keys remain absent, as expected effects of the **unapplied P1-B migration**.
Historical dependencies are `MATCHING / DO_NOT_REAPPLY`; this is not a P1-B PASS
or a migration-history reconciliation approval.

The first comparison intentionally remained fail closed: 389 matching, 0 drift,
4 unknown, exit 2. One unknown came from splitting a quoted COMMENT string at its
semicolon; three came from DM8 rendering approved `<>` comparisons as `!=` in
`CK_PROJECT_FILE_PARSE_RESULT_TEXT`,
`CK_PROJECT_FILE_ROLE_DECISION_SUPERSEDES`, and
`CK_PROJECT_FILE_IDENTITY_DECISION_SUPERSEDES`. The comparison harness was corrected
only to respect quoted statement separators and normalize these equivalent
inequality operators. Field, key, status and logical grouping comparisons were
not relaxed. The corrected run returned 392 matching, 0 drift, 0 unknown, exit 0.
Both sanitized outputs remain in ignored local evidence.

The tracked read-only probe now also includes dependency inventories for all
13 relevant tables, their columns, ordered constraints/parent tuples and indexes.
This is the reproducible SQL-client entry point for a future checkout; compare
its catalog results with the approved source files in the table above. The local
execution harness additionally derived expectations from those files in memory
and executed only fixed SELECT queries, never the source migration statements.

Commands from the worktree root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/2026-09-20-candidate-cross-material-authority-implementation/task-19-readonly.ps1 -Mode Dependencies
powershell -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/2026-09-20-candidate-cross-material-authority-implementation/task-19-readonly.ps1 -Mode Probe
```

The final dependency comparison exited 0. The expanded tracked probe also exited
0: ten SQL-file SELECTs plus two supplemental catalog SELECTs. Its dependency
inventory returned 13 tables, 225 column rows, 307 constraint-column rows and
255 index-column rows; these raw row counts include already deployed additive
effects and are distinct from the 392 approved-effect comparisons. The support
table, two columns, 18 constraints and five indexes were still individually
`NOT_APPLIED` in that fresh run.

Sanitized target corroboration (no connection URL, username, endpoint, account ID
or account contents were printed):

- JDBC uses a non-system account; the provided JDBC host is loopback.
- Windows service `DmServiceAUDITDB` is running. `sc.exe queryex` and captured
  `netstat -ano -p tcp` output proved that the configured JDBC listener belongs
  to that service process; only boolean results were emitted. A preceding CIM
  query was denied access, so it was not treated as proof.
- Read-only `V$INSTANCE` and `V$DATABASE` name comparisons both matched the
  local `AUDITDB` service identity; only match booleans were emitted.
- An existence-only parameterized query confirmed the named `test_admin`
  application account. No account ID, password hash or other account fields
  were selected or printed; existence does not establish operator authority.
- The external source file is explicitly named `local-demo.env.integration.ps1`.
  Its session namespace does **not** contain the literal `integration` marker;
  the filename alone is not used as proof of approval or customer isolation.

The endpoint/service/instance correspondence was corroborated by executable
read-only observations. At this read-only checkpoint, formal environment
ownership/approval, backup validity, execution/stop/rollback authorization,
migration-history reconciliation, the new schema and all real write/full-path
verification were not yet authorized or verified; later authorization and backup-gate
observations are recorded below.
No DDL, DML, synthetic fixture creation, cleanup, commit, push or merge occurred.
The earlier clean verification result remains the local Java build evidence;
this follow-up changed only the read-only SQL probe, appended evidence and ignored
inspection harnesses, with no Java implementation changes.

Ignored follow-up artifacts: `task-19-dependency-catalog.log` (initial UNKNOWN),
`task-19-dependency-catalog-final.log` (392 matching), and
`task-19-complete-readonly-probe.log` (expanded tracked SQL execution), under the
same `.superpowers/sdd/2026-09-20-candidate-cross-material-authority-implementation/`
directory. Final `git diff --check` exited 0 with no whitespace diagnostics at
`2026-09-21 15:05:48 +08:00`; production and historical migration diffs remained empty.

### Task 19 controlled backup gate (2026-09-21)

The operator approved backup and migration only for the already-correlated local
integration/test `AUDITDB` instance. Immediately before export, the read-only
gate again proved 392 historical effects matching, zero drift/unknown, and all
26 P1-B effects `NOT_APPLIED`. The pinned DM8 `dexp V8` build was
`03134284604-20260707-335949-20228`. Export used its supported schema mode,
including rows, constraints, grants, indexes and triggers, with no credential in
the process arguments or output. The destination is a restricted, non-reparse
directory outside Git:

`D:\audit-agent-db-backups\phase1b-20260921\`

Two pre-DDL artifacts exist. The first (`20260921T081731Z-e4936d1e`) was
conservatively rejected by the local wrapper because its exact success-summary
matcher knew only the older `dexp` wording. The second run proved the current
build's exact no-warning success summary and exit status zero; the first file is
not used as the recovery baseline. No DDL/DML ran during these exports.

| Recovery-baseline artifact | Observed proof |
| --- | --- |
| `phase1b-pre-ddl-20260921T082400Z-9bebbb3b.dmp` | Export UTC timestamp `2026-09-21 08:24:00`; 4,236,121 bytes; `dexp` exit 0 and exact modern no-warning success summary |
| SHA-256 | `92F853E0061BF06195296A1D16708A7E0DADAAB9674944C5095F8BB23283678D` |
| Artifact safety | Regular non-reparse file; no unexpected allowed ACL identity; parent path protected and non-reparse |
| DM8 integrity check | Same-build `dimp CTRL_INFO=4`, exit 0, built-in file MD5 normal; this mode does not import |
| DM8 readability/coverage | `dimp SHOW=Y`, exit 0, one schema with 53 tables and 79 indexes; this mode does not import |

The dump manifest explicitly lists every table directly touched or referenced
by the approved migration: `CONTRACT_CANDIDATE`, `PROJECT_FILE_ROLE_DECISION`,
`PROJECT_FILE_IDENTITY_DECISION`, `PROJECT_FILE_PARSE_RESULT`, `PROJECT_FILE`,
and `SYS_USER`. It also includes `PROJECT_EXTRACTION_RESULT` and `PROJECT`.
The independent backup review marked the stated pre-DDL readability/coverage
gate **VERIFIED**. A destructive restore rehearsal has **not** been performed
and is not claimed. No artifact or raw export output was copied into Git.

Controlled migration, post-DDL catalog, real DM8 write test and browser E2E
were not yet verified at this checkpoint.

### Task 19 controlled migration and initial real-DM8 result

After a second independent safety review found no Critical/Important issue in
the one-time operator, its `Validate`, `ReadOnlyAuth` and `Preflight` modes each
exited 0. The authorized `Apply` mode then reported completion of all 14
statements from the exact approved migration (SHA-256
`CD2D1142FC23A4E84139D10C9E90B5B517A2987E45BED4CFD702BC3331E1FFD7`).
No historical or unrelated SQL file was executed; no automatic retry or restore
was attempted.

Immediate post-DDL read-only checks exited 0: the probe completed 12 SELECTs
with no P1-B effect still `NOT_APPLIED` or marked drift, and the historical
dependency comparison remained 392 matching, zero drift, zero unknown on the
same local instance. The opt-in exact-catalog DM8 test then passed 1/1, with
zero skips; it checks column definitions, named keys/checks/FKs and ordered
tuples, and five indexes rather than merely object presence.

The first full representative DM8 IT run was **not GREEN**: 6 tests executed,
4 passed, 2 failed, 0 skipped. The failing CAS-winner and injected-rollback
tests reported a sanitized MyBatis `PersistenceException` without a field-level
database cause. The exact-catalog, constraint-substitution, File/Project lock
wait and opposite-order lock tests passed. The test reported independent
connection cleanup of every generated synthetic Project fixture. Per the
approved stop/rollback rules, a structurally successful migration is not
restored solely because an application/test assertion failed; the cause is
under investigation before any production change or browser E2E. Task 19
remained `FAIL / NOT VERIFIED` until a non-skipped full real-DM8 pass.

### Task 19 representative DM8 GREEN after test-only diagnosis

The initial two failures were caused by the opt-in IT's own MyBatis setting:
it enabled argument-name constructor mapping, while the production
configuration does not. The support-row SQL column `AUTHORITY_VERSION_NO`
does not match the Java record argument name `authorityVersion`, so this
test-only override caused an `ExecutorException` during support snapshot
mapping, before replacement. The concurrent CAS test independently reached
its expected zero-row loser, but temporary diagnostic wrapping masked that
expected business exception. The RED reason was captured using only the
stage, exception type and safe reason code; no SQL, parameters or document
content was emitted.

The only correction was to remove that divergent test-only MyBatis setting;
temporary diagnostics were then removed. A fresh run on the approved local
integration `AUDITDB` executed all six opt-in tests: **6 passed, 0 failures,
0 errors, 0 skipped**. The two formerly failing tests first passed 2/2 in
isolation. The full run proved one committed Candidate CAS winner and one
expected zero-row loser without a mixed support cohort; injected failures
after delete, Candidate CAS/line replacement and support insertion restored
the prior transaction snapshot. Independently connected cleanup checks
found no remaining synthetic fixture Project. Nearby mapper/repository
regressions passed 17/17. `git diff --check` exited 0.

This is real representative DM8 verification, not an H2/mock/skip substitute.
The exact approved migration was applied once; no migration rerun, restore,
production-code change, customer-data mutation, push or merge followed.
Task 19: **PASS**. Browser Task 21 has not yet started at this checkpoint.

### Task 21 browser acceptance RED checklist

Two fresh test Projects will use the repository-authorized realistic test
documents from the named local test-material directory. The source files
remain read-only and no raw content or amounts will be copied into this
evidence. The first Project is reserved for explicit-support Promotion and
ReviewRun; the second for reverse-to-empty before Promotion.

| Browser assertion | Status |
| --- | --- |
| Explicit Project create, real file upload/parse, human role and identity confirmation | PASS |
| Primary Candidate and exact support replacement; same Candidate/primary authority, versions and authorized field states | PASS |
| Confirmation, Promotion, Contract award reference, ReviewRun and terminal F02 | PASS |
| Refresh and backend restart preserve explicit support, Contract and ReviewRun/F02 | PASS |
| Reverse Project reaches explicit mode and has an initial Confirmation | PASS |
| Reverse clear `[]` restores primary-only facts, advances versions and stales old Confirmation | PASS |
| Reverse refresh, new Confirmation, Promotion and legacy Finding evidence compatibility | PASS |
| Unassigned award material does not change Candidate business facts in either Project | PASS |
| No customer/production mutation, no source-file changes or Git runtime artifact | PASS |

### Task 21 real-content browser progress (interim)

Runtime uses this Phase 1B HEAD for Java, frontend and FastAPI, the approved
local integration DM8, Redis Session, and isolated storage/temp/profile
directories inside this worktree. The provided external environment file was
read-only. Its old demo storage/temp paths were overridden before startup;
global model read was restored to 60 seconds, contract model read to 600
seconds, cumulative contract provider budget remained 900 seconds, and the
demo monetary relaxation switch was disabled. FastAPI and backend health
returned HTTP 200. A headless real Edge browser loaded the current Vite app
and logged in with the approved test account; no password appears here.

The first local browser file-input attempt failed before reaching the server:
PowerShell-to-Node pipe encoding corrupted the non-ASCII source paths, so the
browser saw zero-byte unreadable files. A read-only byte-count check exposed
the operator error; after UTF-8 transport, browser File byte counts matched
the read-only source files and both UI uploads returned HTTP 200. No app or
source-fixture change was made for this operator issue.

The original test contract and award notice parsed and received human
`CONTRACT_BODY` / `AWARD_NOTICE` and `SAME_PROJECT` decisions. They produced
no `AWARD_*` extraction because the real test notice labels its business
amount/supplier as a *成交* result while the current deterministic extractor
recognizes the narrower *中标* labels. This is a fixture/extractor coverage
gap, not evidence that P1-B support authority failed. The latest instruction
allows an isolated synthetic derived test copy: two ignored runtime DOCX
copies retain the original contract or extracted award-notice text and add
explicitly marked machine-readable labels. Their SHA-256 identities are
`453de99f2a113d4e4b5f5e46606d81882bfea91da71bc4a6796c96add83cb04f`
and `a8fe106ea233dd69cf81d8e976502fcdcb4064eb1bf0e43b5b2b7ab8f38a46ca`.
They are **synthetic derived test fixtures**, not claims about original source
facts. Original test files were read-only throughout.

The first support Save exposed a P1-B frontend reconciliation defect despite
HTTP 200 and durable DM8 state: `ContractCandidate` deliberately hides its
internal support version with `@JsonIgnore`, while Candidate Detail projects
the same authoritative version at the top level. The frontend wrongly
required the absent nested field and retained the operation as unproven.
A minimal RED fixture using the real response shape failed 81/82; the flow
now accepts an absent nested field while still rejecting an inconsistent
field if supplied and requiring the top-level version plus exact association
set. Focused GREEN: 82/82; nearby P1-B frontend tests: 144/144. Browser
refresh replayed the original successful key once, then cleared its retained
operation without a new key or duplicate Candidate mutation.

Formal explicit Candidate evidence so far (IDs only): Project
`dd17aa0e-584b-4893-a533-0edd025a05ef`, Candidate
`04c6a0aa-8087-46b3-9598-6077f09c9c07`, primary file
`2db9932c-d949-4deb-9e10-e8e0267b274a`, exact assigned support file
`f1bd73db-16a3-49af-9317-2d9de05df43d`. The unassigned original notice
was not in that Candidate's support association. Primary-only Candidate
version 0 had a confirmed primary `AWARD_AMOUNT`; after one UI support Save,
Candidate version 1/support version 1 used the distinct assigned support
amount and `AWARD_AMOUNT` field state remained `CONFIRMED`, not conflict.
No monetary value or source excerpt is included here.

UI Confirmation returned HTTP 200 (confirmation
`54b8367f-88ff-4559-a26c-16cf035b8338`, Candidate version 1 to 2), and
Promotion returned HTTP 200 (Contract
`71904679-2f66-48cf-8590-354b2733e848`, Candidate `PROMOTED`, version 3).
The ProjectContract association and one exact support row reopened through
normal GET. The UI selected only the derived contract and assigned award
notice and sent one ReviewRun create POST (HTTP 202): run
`00314bcd-2aba-4d4f-9c16-eda123341442`, two frozen materials.

F02 is durably `COMPLETED`: `F02_AWARD_AMOUNT_MATCH=FAIL` from the intended
test conflict; `F02_AWARD_SUPPLIER_MATCH=PASS` after explicit human business
confirmation. `F02_AMOUNT_WORDS_MATCH`, `F02_LINE_ITEM_ARITHMETIC` and
`F02_LINE_SUM_MATCH` are `NOT_EVALUATED` because the current confirmed input
lacks words/line items. No verdict was fabricated. Semantic and Breach were
still running or not yet terminal at this interim checkpoint; final status belongs in the
later browser acceptance result.

### Task 21 final browser and persistence result

The final acceptance used two, and only two, new Projects in the approved
integration/test database. The source contract DOCX and award PDF were read
from the named realistic test-material directory without modifying them.
The initial documents did not yield the needed `AWARD_*` labels, so ignored
runtime DOCX derivatives retained the real document content and explicitly
marked their added machine-readable facts as **synthetic derived test
fixtures**. This verifies the full authority workflow with real-content
files; it does not assert the original notice's extractor coverage is fixed.
No raw text, monetary amount, account password, model key or JDBC address is
recorded here.

| Case | Browser/API evidence after backend restart and browser refresh |
| --- | --- |
| Explicit A | Project `dd17aa0e-584b-4893-a533-0edd025a05ef`, primary file `2db9932c-d949-4deb-9e10-e8e0267b274a`, assigned award file `f1bd73db-16a3-49af-9317-2d9de05df43d`, Candidate `04c6a0aa-8087-46b3-9598-6077f09c9c07`. The same Candidate and primary source tuple survived support Save; version 0 to 1 and support version 1 changed only the authorized derived facts. Primary and assigned support `AWARD_AMOUNT` differed, but explicit mode selected only the assigned support value and `AWARD_AMOUNT` field state was `CONFIRMED`, not `CONFLICT`. Unassigned original notice was not in the association. |
| Explicit A completion | UI Confirmation `54b8367f-88ff-4559-a26c-16cf035b8338`; UI Promotion created Contract `71904679-2f66-48cf-8590-354b2733e848` and completed `AwardNoticeReference` (presence of notice number, project, bidder, date and amount checked, values withheld). UI ReviewRun POST returned 202, run `00314bcd-2aba-4d4f-9c16-eda123341442`, two frozen materials. All five steps reached `COMPLETED`; F02 amount comparison `FAIL`, supplier comparison `PASS`, three other F02 checks `NOT_EVALUATED` for absent words/line items. Review progress for the failed award check returned `available=true`, `CROSS_FILE`, exact primary and assigned-support file IDs, not the unassigned notice. |
| Reverse B | Project `63fe93d1-12da-45e5-a147-67e167ecd673`, primary file `9d35452a-e049-4122-a6b2-4609a33a5cae`, initial assigned award file `b794348c-fb4c-4416-b6df-4503305e3082`, Candidate `7fc6a6ef-5f6a-4ca3-9076-c12b750e6b24`. Initial primary-only award field was confirmed. UI association changed Candidate version 0 to 1, support version to 1 and entered explicit mode; first Confirmation `6719a540-db22-4e77-81ce-1c191c8034c3` advanced version to 2. Before Promotion, UI clear `[]` advanced Candidate to version 3/support version 2, restored primary-only award facts and `PRIMARY_ONLY_COMPAT`, and left zero support rows. The old version-2 Confirmation's Promotion POST returned deterministic HTTP 409; no Contract was created from it. |
| Reverse B completion | After backend restart, Project GET projected `CANDIDATE_NEEDS_CONFIRMATION`, not the stale `CANDIDATE_READY`. Browser refresh/reopen offered a new Confirmation; UI confirmed version 3 to 4 and promoted to version 5, Contract `94543ce1-5611-4ddd-975f-9cb499500edc`. UI ReviewRun POST returned 202, run `53c2abc1-59a5-4dd0-bcff-8700ffb196b3`, two frozen materials; all five steps reached `COMPLETED`. F02 award amount and supplier comparisons were `PASS`; three other checks remained accurately `NOT_EVALUATED`. |
| Legacy evidence compatibility | A third **file**, not a third Project/Candidate/Contract, was uploaded through Project B's real UI: synthetic-derived award DOCX based on the same real notice content, SHA-256 `ecabd6cc769b9751a24ba08fcdc5b6cc89f20fc8ba6e3eda8309c6470647f8fc`. It has immutable classifier category `AWARD_NOTICE`, is parsed, remains unassigned and has no authoritative human role. Candidate B stayed version 5/support version 2, `PRIMARY_ONLY_COMPAT`, zero associations. Existing F02 progress API then returned `available=true`, `comparisonMode=CROSS_FILE` for a `PASS` award check, with source IDs `9d35452a-e049-4122-a6b2-4609a33a5cae` and `71a3ecfb-ab90-40ad-a971-a393b7ad6c54`. Before this matching derived file was uploaded, the same check returned `SOURCE_NOT_FOUND`. This is legacy Finding evidence projection only; no Candidate business fact authority was granted. |
| Durable reopen | After browser refresh and a second backend process restart, fresh HTTP GETs returned both exact Candidate IDs/modes/support counts and both exact `COMPLETED` ReviewRun IDs and F02 states. Project/UI reopen restored both promoted Contract associations, and Contract GETs had already returned complete `AwardNoticeReference` objects. The legacy `CROSS_FILE` projection also reopened with the same two file IDs. The UI reopened Project A's promoted Candidate with the assigned support checkbox selected and Project B with its support checkbox cleared. |

The actual UI support-replacement transport was additionally captured without
request content or idempotency-key values. In a separate still-DRAFT diagnostic
Candidate `54908dc7-c6a8-4596-b867-c10cd990d011` inside Project A, a safe
clear and restore each produced **one** POST to that Candidate's
`support-authority-replacements` route. Each JSON body had exactly the keys
`expectedCandidateVersion` and `supportFileIds`; versions were 1 then 2,
support counts 0 then 1, each action had one nonempty `Idempotency-Key`, and
the two separate actions had two distinct keys. The diagnostic Candidate
returned to explicit mode and remains DRAFT, version 3; the formal promoted
Candidate was unchanged. This extra diagnostic mutation used only test data.

Two defects found by the real browser chain received scoped RED/GREEN fixes:

1. Candidate Detail's public JSON omits nested support metadata while exposing
   authoritative top-level support metadata. The frontend reconciliation
   previously demanded the missing nested version after an HTTP 200, leaving a
   proven successful operation retained as unproven. A real-shape fixture was
   RED (1 failure of 82); the minimal fix now requires top-level version and
   exact support set, tolerates only absent nested metadata, and rejects a
   supplied inconsistent nested version. Focused GREEN was 82/82; adjacent
   frontend support tests were 144/144. Browser refresh replayed the same
   successful key once and cleared it without a duplicate Candidate version.
2. Project file progression previously treated any latest Confirmation as
   current. After support clear advanced Candidate version, Project GET offered
   Promotion while frontend correctly rejected the stale Confirmation. The new
   regression was RED (`CANDIDATE_READY` versus required
   `CANDIDATE_NEEDS_CONFIRMATION`); the read projection now fences the latest
   Confirmation's result Candidate version to current Candidate version.
   Focused GREEN was 2/2, and the real UI after backend restart offered new
   Confirmation, then completed Promotion. No Confirmation/Promotion command
   contract or migration was changed.

Final local regression after both fixes: `backend/.\mvnw.cmd clean verify`
exited 0, 1,755 tests, zero failures/errors/skips. Frontend default Node
suite exited 0, 583/583; full `--test-isolation=none` suite exited 0,
583/583. `npm.cmd run build` exited 0 (Vite chunk-size warning only).
FastAPI full `pytest -q` exited 0, 340/340 (one upstream deprecation warning).
The initial sandboxed Node/Python attempts hit Windows `EPERM`/`WinError 5`
for subprocess/temp-directory access; identical commands passed with the
necessary local execution permission. Task 19 representative DM8 remains
6/6 non-skipped PASS after the exact one-time controlled migration. Independent
read-only final code review reported no Critical/Important code blocker.

The realistic source files remained read-only; all derived fixtures, storage,
browser state and diagnostic scripts remained ignored local runtime artifacts.
Only test/integration DM8 was mutated. No customer or production database,
historical migration, push, merge or deployment was touched. Both temporary
untracked Edge profiles were stopped and removed after path/reparse checks;
the final ignored browser profile was likewise removed. These profiles are
ephemeral and not a business fact source. Backend, FastAPI and Vite test
services were stopped after acceptance; ports 8080, 8001 and 5173 were closed.

Final worktree changes are limited to the scoped frontend reconciliation and
backend progression projection fixes, their two regression-test files, this
evidence report, and the untracked opt-in real-DM8 IT plus read-only probe
prepared for Task 19. The approved controlled migration file and historical
migrations have no diff. `git diff --check` exits 0. No commit was made.

Remaining product gaps were not broadened into this task: the original notice's
unrecognized label form still needs separate extraction coverage, and F02's
words/line-item checks remain `NOT_EVALUATED` when the confirmed data lacks
those fields. These did not prevent the Phase 1B authority, durability and
compatibility acceptance above.

Phase 1B verdict: READY_FOR_REVIEW. Leave this working tree uncommitted for
the requested integration/commit decision; do not push or merge.

Browser full chain: PASS
