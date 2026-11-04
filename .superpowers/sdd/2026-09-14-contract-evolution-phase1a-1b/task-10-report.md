# Task 10 Report — durable deterministic role-assessment command/query

## Scope

Implemented the Task 10 application slice only:

- authenticated deterministic role-assessment command and durable reopen query;
- legacy parse-result materialization before assessment;
- exact server-owned input fingerprint and durable idempotency result identity;
- short START, RUNNING, COMPLETE, and independent FAILURE transaction boundaries;
- durable deterministic proposal, candidate projection, parse-bound evidence, operation audit, and replay;
- active assessment fence integration required to prevent a different idempotency key bypassing an uncertain or
  in-progress operation.

No API/controller, AI/model call, FastAPI/Python change, frontend, identity assessment, role confirmation,
candidate creation/promotion, deployment, or customer material is included.

## TDD evidence

Initial RED:

```powershell
cd backend
.\mvnw.cmd "-Dtest=RoleAssessmentServiceTest,RoleAssessmentTransactionStructureTest" test
```

Observed `BUILD FAILURE` with 17 compilation errors because the Task 10 command, result, service, and transaction
coordinator did not yet exist.

Recovery RED:

```powershell
cd backend
.\mvnw.cmd "-Dtest=RoleAssessmentServiceTest,RoleAssessmentTransactionStructureTest" test
```

After the initial implementation, 9 tests ran and one failed: same-key UNKNOWN recovery returned `UNKNOWN`
instead of resuming the same assessment to `COMPLETED`. The replay policy was changed so the same key/fingerprint
resumes `PENDING` or `UNKNOWN`, while `RUNNING` is reopened without duplicate computation.

Distinct-key bypass RED:

```powershell
cd backend
.\mvnw.cmd "-Dtest=RoleAssessmentServiceTest#differentKeyCannotBypassActiveAssessmentForSameImmutableFingerprint" test
```

Observed one test and one expected failure because a replacement key silently created a second assessment.
The broader fence RED ran 23 tests with six expected failures across PENDING/RUNNING/UNKNOWN bypass, local
concurrency, SQL structure, and mapper CAS assertions. This demonstrated why an application pre-read was
insufficient and led to the approved database-backed active fence described in the Task 9 report.

Focused GREEN:

```powershell
cd backend
.\mvnw.cmd "-Dtest=RoleAssessmentServiceTest,RoleAssessmentTransactionStructureTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest,Dm8ProjectFileRoleRepositoriesTest" test
```

Observed `BUILD SUCCESS`: 50 tests, 0 failures, 0 errors, 0 skipped.

Task 7-10 role regression:

```powershell
cd backend
.\mvnw.cmd "-Dtest=LegacyProjectFileParseResultMaterializerTest,DocumentRoleSignalExtractorTest,DeterministicDocumentRoleFixtureMatrixTest,ProjectFileRoleAssessmentTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest,Dm8ProjectFileRoleRepositoriesTest,RoleAssessmentServiceTest,RoleAssessmentTransactionStructureTest" test
```

Observed `BUILD SUCCESS`: 103 tests, 0 failures, 0 errors, 0 skipped.

Full backend verification:

```powershell
cd backend
.\mvnw.cmd clean verify
```

Observed `BUILD SUCCESS`: 826 tests, 0 failures, 0 errors, 0 skipped.

Environment: Windows worktree, Java 17, Maven Wrapper, Spring `local` test profile. No DM8, Redis, FastAPI,
PaddleOCR, Qwen, 26/29, or customer environment was contacted.

## Durable command semantics

The fingerprint is a length-prefixed SHA-256 over, in order: fingerprint schema discriminator, authenticated
actor ID, project ID, file ID, file SHA-256, parse result ID, canonical parse-text SHA-256, assessment version,
and role-policy version. Actor and file/parse identities come from `AuthContext` and server repositories, not
client assertions.

The transaction sequence is:

1. START (`REQUIRES_NEW`): claim `ROLE_ASSESSMENT`, persist `PENDING` with the real assessment ID as result
   identity, and append the start operation audit.
2. RUNNING (`REQUIRES_NEW`): CAS the same assessment to `RUNNING` and increment its attempt.
3. COMPUTE: execute the deterministic body-signal extractor/resolver outside any write transaction.
4. COMPLETE (`REQUIRES_NEW`): persist bounded parse-bound evidence, CAS the proposal to `COMPLETED`, append the
   completion audit, and mark idempotency `SUCCEEDED`.
5. FAILURE (`REQUIRES_NEW`): a proven deterministic-computation failure is closed independently as `FAILED`,
   audited, and marks idempotency `FAILED`; `ProjectFile` status is never changed.
6. RECONCILE (`REQUIRES_NEW`): a COMPLETE-path exception is never blindly converted to `FAILED`. Durable state
   is re-read: committed completion is repaired/reopened, the exact still-running attempt is closed as `UNKNOWN`,
   and an unprovable state fails closed while preserving the same durable operation identity.

All completed results can be reconstructed through the assessment-ID query from assessment and evidence
repositories after service reconstruction/process restart. No runtime-only result state is returned.

## Replay, recovery, and concurrency

- Same key + same fingerprint + `SUCCEEDED/COMPLETED`: replay the same assessment ID and durable evidence.
- Same key + same fingerprint + `FAILED/FAILED`: replay the same failed assessment ID without computation or a
  second operation-audit event. A new key may retry after the proven failure.
- Same key + same fingerprint + `IN_PROGRESS/RUNNING`: reopen the same live assessment without computation.
  Only when `startedAt + audit.role-assessment.running-recovery-age <= now` is durably provable does recovery
  fence the prior attempt as `UNKNOWN` and reuse the same ID through `UNKNOWN -> RUNNING`.
- Same key + same fingerprint + `IN_PROGRESS/PENDING`: resume the same ID through RUNNING.
- Same key + same fingerprint + `IN_PROGRESS/UNKNOWN`: reuse the same ID, transition
  `UNKNOWN -> RUNNING`, increment `attemptNo`, and complete the new attempt.
- Same key + changed actor/file/SHA/parse/text/policy identity: return conflict.
- Different key + same fingerprint while the assessment is PENDING/RUNNING/UNKNOWN: the database/local active
  fence selects one winner; the losing newly claimed key is rolled back and the command returns conflict.
- Proven FAILED releases the active fence and permits an explicit new-key assessment with a new assessment ID.

Tests prove `PENDING -> RUNNING -> COMPLETED`, `PENDING -> RUNNING -> FAILED`, and
`PENDING -> RUNNING -> UNKNOWN -> RUNNING -> COMPLETED`; sequential and concurrent distinct-key attempts cannot
produce two active assessments.

## Verification classification and risks

- UNIT/INTEGRATION VERIFIED: deterministic synthetic parsed-text fixture, legacy materialization, exact golden
  fingerprint, durable state sequence/evidence/reopen, same-key replay/recovery, active-fence concurrency,
  failure closure, audit identity, and ProjectFile non-mutation.
- REAL DM8 NOT VERIFIED: SQL uniqueness, CHECK evaluation, duplicate wait/reload, mapper round-trip, and transaction
  rollback behavior were not executed on a DM8 instance.
- REAL CUSTOMER MATERIAL NOT VERIFIED: no customer contract or sensitive material was read, copied, sanitized,
  or added to Git.

Residual risk: real DM8 must verify that concurrent unique-key violations block/reload as expected and that the
CAS update changing the fence key has the tested constraint behavior. This slice intentionally has no public API,
so HTTP/RBAC/CSRF response behavior belongs to the later API task.

## Independent review fix wave

Independent requirements and code reviews found four recovery/transaction gaps and all were addressed:

- a durable `FAILED` assessment now replays the same assessment ID for the same key/fingerprint; a new key is
  required for an explicitly new attempt after proven failure;
- expired same-key `RUNNING` recovery performs a CAS-fenced `RUNNING -> UNKNOWN -> RUNNING` takeover on the same
  ID, while a non-expired live computation is only reopened and is never preempted;
- deterministic-compute failures alone close as proven `FAILED`; COMPLETE/commit exceptions enter a
  `REQUIRES_NEW` reconciler that either proves durable success, repairs `COMPLETED + IN_PROGRESS` with a
  reconciliation audit, or records `UNKNOWN` while leaving idempotency `IN_PROGRESS`;
- local START compensation removes only the exact uncommitted PENDING row/fence and claim after an audit
  failure, while local evidence batch validation publishes all-or-none and provisional evidence remains hidden
  until the parent assessment is COMPLETED;
- COMPLETE builds its result directly and no longer self-invokes the separately transactional reopen method.

The review-fix RED suite reproduced FAILED replay rejection, stranded RUNNING recovery, uncertain COMPLETE being
misclassified, orphan PENDING after START audit failure, non-atomic evidence batching, and transactional reopen
self-invocation. The first full-suite GREEN attempt then exposed Spring constructor ambiguity caused by the
package-private computation test seam; the production constructor was explicitly selected with `@Autowired`.

Final review-wave verification:

```powershell
cd backend
.\mvnw.cmd "-Dtest=RoleAssessmentServiceTest,RoleAssessmentTransactionStructureTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest,Dm8ProjectFileRoleRepositoriesTest" test
```

Observed: `BUILD SUCCESS`; 50 tests, 0 failures, 0 errors, 0 skipped.

The Task 7-10 regression observed 103 tests, 0 failures, 0 errors, 0 skipped. A fresh
`.\mvnw.cmd clean verify` observed `BUILD SUCCESS`; 826 tests, 0 failures, 0 errors, 0 skipped. These remain
UNIT/INTEGRATION VERIFIED only; REAL DM8 and REAL CUSTOMER MATERIAL remain NOT VERIFIED.

## Review-blocker closure

A final TDD review wave closed transaction-outcome and local-adapter failure windows:

- Same-key replay of a proven `FAILED` assessment now returns the original ID without recomputation or new audit;
  a distinct key can create a new attempt only after the failed assessment released its active fence.
- A stale `RUNNING` assessment is recoverable with the same operation identity by an audited
  `RUNNING -> UNKNOWN -> RUNNING` CAS sequence; no Redis state or process-local timer is involved.
- START audit failure compensates only the uncommitted local PENDING insert/fence and idempotency claim. The
  repository seam is deliberately a DM8 no-op because the real adapter relies on the surrounding transaction.
- Local evidence uses an atomic immutable-state batch publication. A later item conflict publishes none; evidence
  already durably present while the parent is RUNNING/UNKNOWN is validated but hidden until the parent completes.
- COMPLETE no longer invokes a transactional reopen method on `this`. It returns the built durable result.
- COMPLETE exceptions trigger durable reconciliation. A committed assessment/idempotency outcome reopens;
  completed-plus-in-progress is audited then repairs idempotency; exact RUNNING becomes UNKNOWN; missing, drifted,
  or unreadable state fails closed without replacing the assessment or operation identity.

The review-wave RED evidence included missing compute-injection/batch repository contracts, an UNKNOWN result that
incorrectly exposed provisional evidence, and a committed-completion reconciliation path missing its required
operation audit. The first full-suite run also exposed Spring constructor ambiguity after adding the test seam;
explicit constructor injection fixed it, and `AuditApplicationTests` then passed 4/4 before the clean rerun.

Final verification after all review fixes:

```powershell
cd backend
.\mvnw.cmd "-Dtest=RoleAssessmentServiceTest,RoleAssessmentTransactionStructureTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest,Dm8ProjectFileRoleRepositoriesTest" test
```

Observed `BUILD SUCCESS`: 50 tests, 0 failures, 0 errors, 0 skipped.

```powershell
cd backend
.\mvnw.cmd "-Dtest=LegacyProjectFileParseResultMaterializerTest,DocumentRoleSignalExtractorTest,DeterministicDocumentRoleFixtureMatrixTest,ProjectFileRoleAssessmentTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileParseResultDm8SqlStructureTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest,Dm8ProjectFileRoleRepositoriesTest,RoleAssessmentServiceTest,RoleAssessmentTransactionStructureTest" test
```

Observed `BUILD SUCCESS`: 103 tests, 0 failures, 0 errors, 0 skipped.

```powershell
cd backend
.\mvnw.cmd clean verify
```

Observed `BUILD SUCCESS`: 826 tests, 0 failures, 0 errors, 0 skipped.

UNIT/INTEGRATION VERIFIED. REAL DM8 NOT VERIFIED. REAL CUSTOMER MATERIAL NOT VERIFIED.

## Operational lease and local START isolation follow-up

A second strict-TDD review wave closed two remaining interleavings and made the recovery boundary explicit:

- `audit.role-assessment.running-recovery-age` is a typed, positive operational policy with a 15-minute default
  and the environment seam `AUDIT_ROLE_ASSESSMENT_RUNNING_RECOVERY_AGE`. It is not a business decision threshold.
  A same-key live RUNNING request reopens the same ID without compute; takeover is permitted exactly when
  `startedAt + runningRecoveryAge <= now`. Tests cover a latched live computation, the exact expiry boundary, and
  process reconstruction with an advanced clock.
- Evidence `createdAt` is derived from the stable assessment operation creation time, not the current attempt's
  completion clock. Retrying after provisional evidence publication therefore converges on the same insert-only
  signal facts even after the process clock advances.
- The local assessment repository stages PENDING and its active fence in one immutable CAS state. While START
  audit is uncommitted, execution CAS is refused. After audit succeeds, the coordinator publishes the staged row;
  on audit failure it removes only that exact staged row and claim. DM8 stage/commit defaults remain no-ops because
  transaction isolation and rollback provide the production boundary.

Second-wave RED was observed as eight compilation failures for the absent recovery policy and stage/commit port
seams. The first implementation run then exposed that the test tracking repository had not delegated the new
staging methods: one staged row was not compensated and one first caller lost its execution CAS. Delegating the
seams made both adversarial tests exercise the actual local adapter.

Final second-wave verification:

```powershell
cd backend
.\mvnw.cmd "-Dtest=RoleAssessmentServiceTest,RoleAssessmentTransactionStructureTest,InMemoryProjectFileRoleRepositoriesTest,ContractProjectFileRoleAssessmentDm8SqlStructureTest,Dm8ProjectFileRoleRepositoriesTest" test
```

Observed `BUILD SUCCESS`: 56 tests, 0 failures, 0 errors, 0 skipped.

The Task 7-10 regression plus Spring configuration binding run passed 113 tests (109 role regression and four
`AuditApplicationTests`). A fresh `.\mvnw.cmd clean verify` passed 832 tests with zero failures, errors, or skips.

UNIT/INTEGRATION VERIFIED. REAL DM8 NOT VERIFIED. REAL CUSTOMER MATERIAL NOT VERIFIED.
