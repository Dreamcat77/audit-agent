# Contract Review Run Phase 2B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute Phase 2A's frozen first ReviewStep attempts and reopen all completed outputs from durable stores.

**Architecture:** ReviewRun owns immutable Contract, policy and selected parse identities. A synchronous execute command commits START, computes outside a DM8 write transaction, then commits COMPLETE or an independent FAILED/UNKNOWN closure. F02 keeps AuditTask/AuditCheckResult as physical truth; non-F02 validated findings and parse-bound evidence live under the run.

**Tech Stack:** Java 17, Spring Boot, MyBatis/DM8, Jackson, JUnit 5, existing `IdempotencyPort`, `OperationAuditPort`, F02 engine, clause analyzers and typed FastAPI semantic port.

**Spec:** `docs/superpowers/specs/2026-09-14-contract-audit-evolution-master-design.md`; approved Phase 2B Approach B is the 2026-09-16 task attachment.

## Global Constraints

- Work only in `.worktrees/contract-review-run-phase2b` on `feature/contract-review-run-phase2b-20260916`, starting at `6670060eb4b65db20f2414f4fc6e68a4fe7f7940`; baseline local backend `clean verify` observed 1021/0/0/0 and BUILD SUCCESS.
- DM8 is the business system of record. Redis/Session and React state are never ReviewRun or Finding truth; no second filesystem or file upload path.
- All review input comes from run Contract canonical JSON and exact run-material parseResultId; no executor calls current Contract, ProjectFile.parsedText or latest parse/material loaders.
- F02 reuses the existing START/COMPLETE/FAILURE engine and physically writes only AUDIT_TASK/AUDIT_CHECK_RESULT. `outputResourceId` must be its real AuditTask ID.
- Every external HTTP/CPU engine call occurs after START commit and before COMPLETE/FAILURE/UNKNOWN write transactions. UNKNOWN means outcome cannot be proven; a proven unavailable/invalid result is FAILED.
- Only first existing attemptNo=1 may execute; no retry attempts, worker, lease scan, acknowledgement, report, frontend, Python, provider routing or F09-F15.
- Only `CONTRACT__review_run_results.sql` is new; do not edit `CONTRACT__review_run.sql`, Phase 1 SQL or V001-V022. Real DM8 and real customer material stay NOT VERIFIED.
- New POST has an empty or `{}` body, safe Idempotency-Key, Session/CSRF/four-role guard, AuthContext actor and Operation Audit. GET is read-only and fails safely on wrong ownership.
- Tests use synthetic fixtures, begin RED before each production change, then focused GREEN and backend `clean verify`. Do not create RESULT.md or `.superpowers/sdd/**/task-*.md`.

## File/interface map

| Unit | Responsibility and exact shared interface |
|---|---|
| `ContractSnapshotDecoder` | `Contract decodeAndVerify(ContractReviewRun run)` hashes raw JSON first, checks schema/field types, constructs BigDecimal-backed Contract and recanonicalizes byte-for-byte. |
| `FrozenReviewInputLoader` | `FrozenReviewInput load(UUID projectId, UUID contractId, UUID runId)` obtains the verified run manifest and exact parse rows via `ProjectFileParseResultRepository.findById`; never reads latest state. |
| `FrozenReviewInput` / `FrozenReviewMaterial` | Immutable records carrying run, decoded Contract and ordered material IDs/SHA/parse/version/text/effectiveRole/decision IDs. Executors consume these only. |
| `ContractReviewExecutionPolicyResolver` | `ResolvedPolicy resolveForNewRun()` returns review profile, enabled F02 configuration fingerprint and semantic Java execution-policy version. `F02RulePolicyFingerprint` canonicalizes sorted enabled ruleCode/enabled/failLevel/blockSign/versionNo plus engine schema. |
| `FrozenEvidenceGrounder` | `GroundedEvidence ground(FrozenReviewMaterial material, String excerpt, boolean deterministic)` emits code-point offsets only for one exact match; deterministic ungroundable input conflicts; ambiguous model input has locatorAvailable=false. |
| `ContractReviewFinding` / `ContractReviewEvidence` | New `contract.domain.reviewrun` records, with source/status/attention/risk and run-step-attempt-parse ownership and bounded text. |
| Finding/Evidence repository ports | `insert` and `findByAttemptId`/`findByRunId`; DM8 and local adapters return persisted rows only. |
| Step repositories | Add `findById`, guarded `transition`/`markExternalRequested` methods that return changed versioned records or CONFLICT; DM8 uses `WHERE STATUS=? AND VERSION_NO=?`, local fake uses equivalent synchronized CAS. |
| `ReviewStepExecutor` | `stepType()` and `execute(FrozenReviewInput, ReviewExecutionContext): StepComputationResult`; executor implementations have no repository dependency. |
| `ReviewStepExecutionService` | Command orchestration: completed same-key reopen, START, frozen compute, COMPLETE or independent FAILED/UNKNOWN; one explicit executor registry. |
| `ReviewStepStart/Complete/Failure/UnknownTransactionCoordinator` | Small transaction-owning units; complete inserts non-F02 findings/evidence atomically and aggregates required step states. |
| `ReviewStepOutputReadService` | Reopens persisted step/attempt and non-F02 rows, or verifies real F02 AuditTask/check rows, for execute replay, step GET and run GET. |

---

### Task 1: FrozenReviewInput loader and Contract snapshot decoder

**Files:** Create `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/ContractSnapshotDecoder.java`, `FrozenReviewInput.java`, `FrozenReviewMaterial.java`, `FrozenReviewInputLoader.java`; test `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ContractSnapshotDecoderTest.java`, `FrozenReviewInputLoaderTest.java`.

**Interfaces:** Consume Phase2A `ContractReviewRunService.get`, `ContractSnapshotCanonicalizer`, `ProjectFileParseResultRepository.findById`; produce `FrozenReviewInputLoader.load(projectId, contractId, runId)` and immutable `FrozenReviewInput(run, frozenContract, materials)`.

- [ ] **Step 1: RED.** Write hand-derived synthetic tests: decoder returns exact Contract/line/award IDs and BigDecimal values from a canonical snapshot; a changed hash, unknown schema, malformed JSON, extra/missing field and cross-contract line/award conflict; loader supplies frozen text/role even after a newer parse/decision and rejects a mismatched parse owner, version, file SHA, text SHA or PARSED status.
  ```java
  assertEquals(new BigDecimal("100.00"), decoded.contractAmount().amount());
  assertEquals(frozenParseId, input.materials().get(0).parseResultId());
  assertThrows(BusinessException.class, () -> loader.load(projectId, contractId, corruptRunId));
  ```
- [ ] **Step 2: verify RED.** Run `backend\ .\mvnw.cmd '-Dtest=ContractSnapshotDecoderTest,FrozenReviewInputLoaderTest' test`; observe assertion failures caused by absent frozen decoder/loader, not fixture or compilation typos.
- [ ] **Step 3: minimal GREEN.** Hash raw JSON before strict Jackson tree validation; construct `Contract`, `ContractLineItem`, `AwardNoticeReference`, `Money` without current repository; recanonicalize to reject altered schema/order. Loader uses only verified run/material rows and `findById(material.parseResultId())`, compares every frozen tuple and SHA256(canonicalText), returns immutable ordinal-ordered material records.
  ```java
  var parse = parses.findById(material.parseResultId()).orElseThrow(ContractSnapshotDecoder::conflict);
  if (!parse.fileSha256().equals(material.fileSha256()) ||
      !parse.canonicalTextSha256().equals(material.canonicalTextSha256())) throw conflict();
  ```
- [ ] **Step 4: GREEN.** Rerun the focused command; include surrogate/Unicode text fixture to make later locator offset semantics observable.

### Task 2: Real execution policy and version resolver

**Files:** Create `.../reviewrun/F02RulePolicyFingerprint.java`, `ContractReviewExecutionPolicyResolver.java`; modify `.../reviewrun/ContractReviewRunCreateTransactionCoordinator.java`, `ReviewRunManifestFactory.java`; test `.../reviewrun/F02RulePolicyFingerprintTest.java`, `ContractReviewExecutionPolicyResolverTest.java`, `ContractReviewRunCreateCoordinatorTest.java`.

**Interfaces:** Consume `ContractRuleConfigRepository.findEnabledForF02`; produce `ResolvedPolicy(reviewProfileVersion, ruleVersion, modelPolicyVersion, enabledF02Configurations)` and `F02RulePolicyFingerprint.fingerprint(list)`. New run creation freezes versions; successful old create replay calls no policy resolver.

- [ ] **Step 1: RED.** Test equivalent shuffled config rows have identical fingerprint; a change to enabled/failLevel/blockSign/versionNo changes it; description/time do not. Test new key policy drift changes run inputSnapshotIdentity while old successful key replays without resolving current configs. Test F02 first execution policy mismatch leaves its step PENDING.
  ```java
  assertEquals(fingerprint.of(shuffled), fingerprint.of(sorted));
  assertNotEquals(fingerprint.of(before), fingerprint.of(changedBlockSign));
  ```
- [ ] **Step 2: verify RED.** Run `backend\ .\mvnw.cmd '-Dtest=F02RulePolicyFingerprintTest,ContractReviewExecutionPolicyResolverTest,ContractReviewRunCreateCoordinatorTest' test`; verify expected failed assertions.
- [ ] **Step 3: minimal GREEN.** Canonical JSON includes `enginePolicySchemaVersion=1` and enabled configurations sorted by ruleCode with exact five execution fields; hash to a ≤100-char `ruleVersion`. `modelPolicyVersion` names Java's current typed schema/redaction/grounding contract, not guessed provider/revision. Call resolver only after create's completed-key replay check, freeze values into run identity and per-type `stepPolicyVersion` (F02 rule, semantic model, clause analyzer policy), leaving old stored runs untouched.
  ```java
  var policy = policies.resolveForNewRun();
  String identity = identities.identity(projectId, contractId, version, hash,
      candidateId, confirmationId, materials, policy.reviewProfileVersion(),
      policy.ruleVersion(), policy.modelPolicyVersion());
  ```
- [ ] **Step 4: GREEN.** Rerun focused tests; verify old run replay survives changed rule config and new run is distinct.

### Task 3: Finding/Evidence domain and controlled CONTRACT migration

**Files:** Create `db/dm8/CONTRACT__review_run_results.sql`, `.../domain/reviewrun/ContractReviewFinding.java`, `ContractReviewEvidence.java`, `ReviewFindingSource.java`, `.../reviewrun/FrozenEvidenceGrounder.java`; create DM8 SQL/domain/grounding tests under `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractReviewRunResultsDm8SqlStructureTest.java` and `.../application/reviewrun/FrozenEvidenceGrounderTest.java`.

**Interfaces:** Produce bounded finding/evidence rows with `(runId,stepId,attemptId)` and exact `(runId,fileId,fileSha,parseResultId,parseVersion,canonicalTextSha)`; `FrozenEvidenceGrounder.ground(material, excerpt, deterministic)` returns authoritative code-point offsets only for one exact raw match.

- [ ] **Step 1: RED.** Tests require only two new tables, namespace header/dependency, existing attempt/finding composite ownership FKs and material tuple FK, hash/text bounds, source/status/ordinal/version constraints. Grounding tests: unique raw excerpt (including surrogate prefix) has literal code-point offsets/hash; repeated/normalized-only model excerpt has no locator; ungroundable deterministic excerpt throws conflict.
  ```java
  assertEquals(3, grounded.startOffset());
  assertFalse(grounder.ground(material, "重复", false).locatorAvailable());
  ```
- [ ] **Step 2: verify RED.** Run `backend\ .\mvnw.cmd '-Dtest=ContractReviewRunResultsDm8SqlStructureTest,FrozenEvidenceGrounderTest' test`; observe missing migration/grounder failures.
- [ ] **Step 3: minimal GREEN.** Add only finding/evidence tables and additive composite unique ownership constraints in new `CONTRACT__review_run_results.sql`. Finding preserves semantic ATTENTION_CODE and analyzer STATUS_CODE without fabricated legal risk; summary ≤2000, recommendation ≤4000, category/type/status ≤128. Evidence stores immutable parse tuple, optional page/paragraph/table/row/cell/segment, code-point offsets, SHA and bounded excerpt ≤1000; locatorUnavailable has null offsets. Reject overlong typed text rather than silently truncate. Never save raw model body/prompt.
- [ ] **Step 4: GREEN.** Rerun focused and existing namespace structure tests; no earlier SQL edits.

### Task 4: Repository ports/adapters and guarded execution state transitions

**Files:** Create finding/evidence repository ports in `.../reviewrun/port`, local adapter `.../infra/memory/reviewrun/InMemoryContractReviewResultsRepositories.java`, DM8 mapper/rows/XML/adapters under `integration/dm8` and `resources/mappers/dm8`; modify existing ReviewRun/Step/Attempt ports and both Phase2A adapters. Tests `.../infra/memory/reviewrun/ReviewStepTransitionRepositoryTest.java`, `.../integration/dm8/Dm8ReviewStepTransitionRepositoryTest.java`, `Dm8ReviewFindingEvidenceRepositoryTest.java`.

**Interfaces:** Guarded updates accept `expectedStatus`, `expectedVersionNo`, exact IDs, and a complete next immutable row; return next row only when one row changed. Finding/evidence `insert` and `findByAttemptId`/`findByRunId` return durable results; DB writes never use blind update.

- [ ] **Step 1: RED.** Test one PENDING→RUNNING writer succeeds, stale/concurrent writer conflicts, version increments, no partial finding/evidence binding, and memory/DM8 row projections agree on same ordering/ownership. Mapper tests verify `WHERE STATUS`/`VERSION_NO` plus one-row effect.
  ```java
  var claimed = attempts.transition(pending, RUNNING, pending.versionNo());
  assertThrows(BusinessException.class,
      () -> attempts.transition(pending, RUNNING, pending.versionNo()));
  ```
- [ ] **Step 2: verify RED.** Run the three focused repository tests; expect missing guarded method/mapper failures.
- [ ] **Step 3: minimal GREEN.** Extend ports and adapters with exact-ID reopen and CAS. DM8 mapper guarded UPDATE returns affected row count 1 or CONFLICT; local fake uses synchronized compare-and-replace. Results adapters use append-only inserts and persisted read methods. Match the new migration's composite FKs and uniqueness.
- [ ] **Step 4: GREEN.** Rerun repository tests plus Phase2A memory/DM8 mapper tests.

### Task 5: START, COMPLETE, FAILED, UNKNOWN coordinators and run aggregation

**Files:** Create `.../reviewrun/ReviewStepStartTransactionCoordinator.java`, `ReviewStepCompleteTransactionCoordinator.java`, `ReviewStepFailureTransactionCoordinator.java`, `ReviewStepUnknownTransactionCoordinator.java`, `ReviewRunStatusAggregator.java`, `ReviewExecutionContext.java`, `StepComputationResult.java`, `ReviewStepExecutor.java`; tests `ReviewStepLifecycleCoordinatorTest.java`, `ReviewRunStatusAggregatorTest.java`, `ReviewStepTransactionStructureTest.java`.

**Interfaces:** `start(command, actor): StartedExecution`, `complete(started, result): durable step projection`, `fail(started, safeReason): void`, `unknown(started, safeReason): void`. Executors return typed findings/evidence and output identity but never write repositories. Run aggregation uses all five required steps.

- [ ] **Step 1: RED.** Tests require atomic idempotency claim and PENDING→RUNNING attempt/step/run, exactly one concurrent winner, no engine inside write transactions; COMPLETE inserts non-F02 rows and statuses in one boundary; failure closes with REQUIRES_NEW; uncertain outcome stays UNKNOWN/IN_PROGRESS identity; all-five aggregation matches UNKNOWN > all-complete > all-terminal-with-failed > RUNNING.
  ```java
  assertEquals(RUNNING, started.attempt().status());
  assertEquals(UNKNOWN, aggregator.status(stepsWithUnknown));
  ```
- [ ] **Step 2: verify RED.** Run lifecycle/aggregation/transaction focused tests; verify expected failures.
- [ ] **Step 3: minimal GREEN.** START checks exact run/step/attemptNo=1, PENDING/no output, claims `REVIEW_STEP_EXECUTE` with actor/run/step/attempt/input identity/step policy fingerprint, CAS updates and audit in short tx. COMPLETE verifies same owned RUNNING attempt/version, inserts non-F02 finding/evidence atomically, links real output resource, aggregates run, marks idempotency SUCCEEDED and audits. Proven failure uses independent `REQUIRES_NEW`; UNKNOWN uses independent short tx and retains immutable key/attempt identity. MATERIAL_VALIDATION execute conflicts.
- [ ] **Step 4: GREEN.** Rerun focused and existing F02 transaction structure tests.

### Task 6: Execute/read API shell and common executor registry

**Files:** Create `.../reviewrun/ReviewStepExecutionService.java`, `ReviewStepOutputReadService.java`; modify `.../api/project/ContractReviewRunController.java`, `.../reviewrun/ContractReviewRunService.java`; tests `.../api/project/ContractReviewStepApiIntegrationTest.java`, `.../reviewrun/ReviewStepExecutionServiceTest.java`.

**Interfaces:** `execute(projectId,contractId,runId,stepId,key,metadata)` returns persisted `StepExecutionResult` with replayed flag; `getStep` reopens only durable rows; ReviewRun GET adds nested non-F02 output without removing Phase2A fields.

- [ ] **Step 1: RED.** API tests for strict empty/`{}` POST, CSRF/key/four roles, cross-resource safe NOT_FOUND, GET no write, same-key completed replay without executor, different-key conflict, RUNNING conflict and no attemptNo=2. A fake executor may compute a typed result, but assertions target persisted step/output projection.
  ```java
  assertEquals(first.stepId(), replay.stepId());
  assertTrue(replay.replayed());
  assertEquals(1, persistedAttempts.size());
  ```
- [ ] **Step 2: verify RED.** Run API/service focused tests; confirm missing endpoint/orchestrator failures.
- [ ] **Step 3: minimal GREEN.** Register exactly F02/SEMANTIC_AI/BREACH/TERM executors by enum (temporary test executors only until tracks land). Use AuthContext identity. Pre-claim successful-key replay verifies stored fingerprint against frozen run/step command before any frozen compute/current policy; START→outside-tx loader+executor→COMPLETE; proven validation/unavailable error→FAILED; genuinely uncertain escaped transport→UNKNOWN. Read service verifies F02 AuditTask/contract/result ownership and non-F02 finding/evidence tuples; GET does no materialization.
- [ ] **Step 4: GREEN.** Rerun focused API/service and Phase2A API tests. Commit common foundation only after Tasks 1–6 and Task 7's aggregation/output projection are focused GREEN; record COMMON_SHA.

### Task 6A: Frozen F02 executor (isolated track A from COMMON_SHA)

**Files:** Create `.../reviewrun/executor/FrozenF02ReviewStepExecutor.java`; minimally modify `.../application/ContractReviewService.java` to expose `executeFrozen(Contract,List<ContractRuleConfiguration>,CurrentUser,ContractReviewCommand)` while preserving direct `review`; tests `.../reviewrun/executor/FrozenF02ReviewStepExecutorTest.java`, existing F02 service/transaction regressions.

**Interfaces:** Consume frozen Contract and exact immutable sorted config list matching run.ruleVersion; return `AUDIT_TASK`/real taskId and no new findings/evidence. Internal key `reviewrun-f02:<runId>:<stepId>:<attemptId>` (safe length/charset) is server-owned.

- [ ] **Step 1: RED.** Current Contract differs from frozen yet F02 checks frozen amount; real AuditTask ID and check rows persist only in F02 tables; same internal key reopens task after interrupted ReviewStep COMPLETE; changed rule policy before first execution returns 409 with step PENDING; zero new finding rows.
- [ ] **Step 2: verify RED.** Run frozen F02 and existing F02 focused tests; see missing seam failures.
- [ ] **Step 3: minimal GREEN.** Refactor direct `review` to resolve current Contract/config then delegate to a common private F02 execution path; frozen seam skips those latest reads and uses given immutable config list. Keep current F02 START/COMPLETE/FAILURE coordinators, AuditTask/AuditCheckResult and original fingerprint behavior for direct endpoint.
- [ ] **Step 4: GREEN and commit.** Rerun track focused tests, commit `feat: execute frozen F02 review step`; return HEAD/tests/files/issues. Do not edit common interfaces/schema/clause/semantic.

### Task 6B: Frozen BREACH and TERM executors (isolated track B from COMMON_SHA)

**Files:** Create `.../reviewrun/executor/FrozenClauseMaterialAdapter.java`, `FrozenBreachReviewStepExecutor.java`, `FrozenTermReviewStepExecutor.java`, focused mapper helpers/tests in that executor package. Do not modify existing analyzers.

**Interfaces:** Construct `ContractClauseMaterial(fileId,safeLabel,ProjectFileCategory.valueOf(frozenRole.name()),canonicalText)` and call current `BreachClauseAnalyzer`/`ContractTermClauseAnalyzer` directly. Return one deterministic finding per analyzer item/comparison, one evidence per declared source (more for comparisons), `REVIEW_FINDING_SET`/attemptId.

- [ ] **Step 1: RED.** Later ProjectFile text/category drift has no effect; frozen CONTRACT_BODY filters authority; breach/term status and BigDecimal values preserved; DateItem/ConditionItem/comparison evidence exact-parse bound; repeated/ungroundable deterministic excerpt fails step closed.
- [ ] **Step 2: verify RED.** Run frozen clause executor tests and existing direct clause tests; observe missing executor failures.
- [ ] **Step 3: minimal GREEN.** Map real statuses into STATUS_CODE, meaningful bounded summaries (scenario/rate/frequency/cap/calculation or date/type/condition), no invented risk. Resolve declared source file only within frozen materials, ground its raw excerpt; no current material loader.
- [ ] **Step 4: GREEN and commit.** Run track tests; commit `feat: persist frozen clause review findings`; return HEAD/tests/files/issues. Do not edit common schema/F02/semantic.

### Task 6C: Frozen SEMANTIC_AI executor (isolated track C from COMMON_SHA)

**Files:** Create `.../reviewrun/executor/FrozenSemanticReviewStepExecutor.java`, `FrozenSemanticRequestBuilder.java`; if unavoidable, minimal typed exception/provenance seam in existing FastAPI Java adapter only, preserving legacy direct endpoint. Tests `FrozenSemanticReviewStepExecutorTest.java` and existing semantic direct endpoint/adapter tests.

**Interfaces:** Build `ContractTextAnalysisPort.ContractTextAnalysisRequest` with frozen run projectId and frozen `fileId/effectiveRole/redactor.redact(canonicalText)`; return MODEL_ASSISTED hint findings with ATTENTION_CODE and optional grounded evidence, `REVIEW_FINDING_SET`/attemptId.

- [ ] **Step 1: RED.** Request stays frozen/redacted after current file drift; outside-run evidence fails closed; unique raw excerpt has locator; ambiguous/unmatched model excerpt has no fabricated offset; available=false is proven FAILED; typed schema failure is FAILED; uncertain escaped transport is UNKNOWN; same completed key calls model once; model name proven, provider/revision null unless typed response proves them; no raw response/prompt persisted.
- [ ] **Step 2: verify RED.** Run semantic executor and direct endpoint focused tests; observe missing executor failures.
- [ ] **Step 3: minimal GREEN.** Before HTTP, commit REQUESTED and a SHA256 of run input/step/attempt/model policy/redaction policy/sorted frozen material tuple. After typed result, validate all evidenceRef IDs belong to run, ground only unique exact excerpts against original frozen text, preserve category/attention/title/description. Do not infer provider from result.model(). Classify definitive unavailable/schema errors FAILED; only unprovable transport outcome UNKNOWN.
- [ ] **Step 4: GREEN and commit.** Rerun track tests; commit `feat: persist semantic review findings`; return HEAD/tests/files/issues. Do not edit common schema/F02/clause/Python.

### Task 7: Run aggregate and durable output projection

**Files:** Complete `ReviewRunStatusAggregator`, `ReviewStepOutputReadService` and additive `ContractReviewRunController.ReviewRunResponse`; tests `ReviewRunStatusAggregatorTest.java`, `ReviewStepOutputReadServiceTest.java`, `ContractReviewRunApiIntegrationTest.java`.

**Interfaces:** Run/step GET projects stored attempts/findings/evidence; F02 output points only to verified AuditTask and its existing check results.

- [ ] **Step 1: RED.** Shuffled repository rows reconstruct enum-order five steps and parent-order attempts; after each engine step COMPLETE the run remains RUNNING until all four done, then COMPLETED; any required UNKNOWN dominates; all-terminal FAILED yields FAILED; GET after local repository restart fixture reconstructs results and does not call engines.
- [ ] **Step 2: verify RED.** Run focused aggregation/output/Phase2A projection tests.
- [ ] **Step 3: minimal GREEN.** Sort in common Java projection, filter exact step attempt ownership, validate F02 task contractId/rows and finding/evidence tuple; keep Phase2A response fields stable and add nested durable output. No React runtime state.
- [ ] **Step 4: GREEN.** Rerun focused; the common foundation checkpoint uses this task before tracks.

### Task 8: Parallel-track integration, OpenAPI and adversarial API tests

**Files:** Modify `docs/openapi.yaml`; extend `ContractReviewStepApiIntegrationTest.java`, `ContractReviewRunOpenApiStructureTest.java`, repository SQL tests. Integration branch only.

**Interfaces:** Cherry-pick A, B, C from exact COMMON_SHA one at a time, preserving common executor interface; do not merge Phase2A/main or push temporary tracks.

- [ ] **Step 1: integration RED.** For each cherry-pick, run common+track focused tests and observe any required registration/projection failure before integration adjustment; test POST/GET all four step types, CSRF/roles/ownership, same-key replay, no second attempt, process interruption after durable F02 task, parser/model drift and no raw response.
- [ ] **Step 2: minimal GREEN.** Wire four executors, stable policy and output readers; update OpenAPI only for step execute/read and durable run outputs, not retries/workers/ack/report.
- [ ] **Step 3: focused GREEN.** Run common, F02, clause, semantic, API, SQL/OpenAPI and existing direct endpoint suites. Commit integration changes as `feat: integrate durable review step outputs`.

### Task 9: Full regression, static gates and review handoff

**Files:** No additional production file expected; only corrections supported by new RED tests. No task reports.

- [ ] **Step 1: verify.** In backend run `.\mvnw.cmd clean verify`, aggregate surefire XML counts and record tests/failures/errors/skipped/BUILD. Run `git diff --check`, `git status --short`, `git diff --stat` and byte-level checks showing V001–V022, Phase1 SQL and `CONTRACT__review_run.sql` unchanged.
- [ ] **Step 2: authority/scope review.** Search ReviewRun executor package for `SelectedProjectMaterialReadService`, `findLatestByFileId`, `ProjectFile.parsedText` and current Contract rereads; assert none are execution-authority uses. Review UNKNOWN/FAILED classification, stable same-key result reopen, F02 real task identity and migration composite FKs.
- [ ] **Step 3: independent review.** Review combined branch diff and fix only actionable issues with failing regression tests; rerun focused/full gates after fixes.
- [ ] **Step 4: ordinary push.** After full verify, push only `feature/contract-review-run-phase2b-20260916` to origin, report pushed HEAD and stop for human code review. No merge, PR, deployment or Phase2C.

## Rollback and compatibility

The controlled migration is additive and never rewrites prior schema/history. Disabling new step execute commands leaves Phase2A run GET plus existing direct F02/semantic/breach/term routes intact; already persisted attempts/findings/evidence remain reopenable. A FAILED/UNKNOWN first attempt is terminal in this slice—no new key or automatic worker may fabricate attemptNo=2. All local evidence is UNIT/INTEGRATION VERIFIED only; real DM8 execution and real customer material are NOT VERIFIED.
