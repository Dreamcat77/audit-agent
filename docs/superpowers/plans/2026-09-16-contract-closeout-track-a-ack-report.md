# Contract Closeout Track A Acknowledgement and Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist non-F02 human acknowledgements and immutable, versioned completed-run reports with ownership-safe rediscovery and recoverable artifacts.

**Architecture:** Add focused closeout domain/application/repository/controller files. Reuse the existing verified ReviewRun output reader, frozen input loader, DM8 IdempotencyPort and OperationAuditPort; preserve F02 acknowledgement truth. Freeze a canonical report manifest in DM8 before rendering deterministic UTF-8 HTML outside write transactions, then publish through existing FileStoragePort and close with CAS.

**Tech Stack:** Java 17, Spring Boot, MyBatis/DM8, existing Jackson/JUnit, React 18/TypeScript, Node test runner, existing Session/CSRF HTTP client.

**Spec:** `docs/superpowers/specs/2026-09-16-contract-audit-parallel-closeout-design.md`; `docs/superpowers/specs/2026-09-14-contract-audit-evolution-master-design.md`; approved user Track A requirements dated 2026-09-16.

## Global Constraints

- Exact starting SHA `2ed1eb9906d2c67d3c673906d5f1b27b434af3a9`, branch `feature/contract-closeout-ack-report-20260916`, worktree `.worktrees/contract-closeout-ack-report`; initial status clean. Existing linked worktree verified; no new worktree.
- Human has approved architecture and inline execution. Use executing-plans; no subagents, approval pause, integration menu, PR, merge or deploy. Ordinary push and keep worktree.
- DM8 is System of Record; no Redis business truth, second storage, provider calls, customer fixtures or secrets. REAL_DM8_STATUS = NOT VERIFIED; never connect to DM8 in this Track.
- `UNKNOWN != FAILED`. Existing fixed five-step profile, Run/Step/Attempt/Finding/Evidence records and F02 facts stay unchanged. No shared controller/service/mapper/security/workspace/OpenAPI edits.
- Only new `db/dm8/CONTRACT__review_ack_report.sql`; namespace CONTRACT, id review-ack-report, dependencies `review-run-results,V009__review_finding_acknowledgement.sql`, CONTROLLED_MANUAL, NOT_ASSUMED. No historical migration changes or V023+.
- Ack is one append-only fact per non-F02 finding, optional trimmed handlingNote limited to 1000 Unicode code points. It means seen with handling opinion, never resolved/deleted/AI approved.
- Report formal eligibility is COMPLETED with every required step COMPLETED, self-validating frozen manifest, exact reopenable outputs and F02 provenance. No incomplete export.
- Artifact renderer is dependency-free deterministic UTF-8 HTML (`text/html; charset=utf-8`); customer PDF/template/signature acceptance is NOT VERIFIED.
- Every production behavior begins with observed RED then GREEN. Small focused commits; plan is first commit.

## Baseline evidence and file/interface map

Windows/JDK17 baseline command in backend: `.\mvnw.cmd '-Dtest=ReviewStepOutputReadServiceTest,ReviewRunManifestVerifierTest,FindingAcknowledgementServiceTest' clean test`: BUILD SUCCESS, 32 tests, 0 failures/errors/skips (manifest verifier is covered by existing run tests, no standalone matching test). Read Phase2A/2B/2C plans, current migration/reader/frozen-loader/F02-ack/storage interfaces. No approved report renderer exists in current source.

Paths below are relative to repository root. Java production prefix is `backend/src/main/java/com/fuquan/audit/`; test prefix is `backend/src/test/java/com/fuquan/audit/`. Each listed file is new.

| Files | Exact interface / responsibility |
|---|---|
| `contract/domain/closeout/ContractReviewFindingAcknowledgement.java` | Immutable exact owner tuple, actor/time/note/version; `normalizeNote(String): String` rejects >1000 code points. |
| `contract/domain/closeout/ContractReviewReport.java` | Immutable row including frozen `snapshotJson`, identities, artifact expected identity and status; `Status` has PENDING/GENERATING/AVAILABLE/FAILED/UNKNOWN. |
| `contract/application/closeout/CloseoutCanonical.java` | `json(Object): String`, `sha256(String): String`; sorted JSON keys and length-safe structured hashing. |
| `contract/application/closeout/port/ContractReviewCloseoutRepository.java` | `insertAcknowledgement`, `findAcknowledgement`, `findAcknowledgementByFinding`, `findAcknowledgementsByRun`; `insertReport`, `findReport`, `findReportsByRun`, `nextReportVersion`, `compareAndSetReport(expected,next)`. Append-only acknowledgement; report updates only status/artifact closure with immutable snapshot. |
| `contract/infra/memory/closeout/InMemoryContractReviewCloseoutRepository.java` | local adapter uses ConcurrentHashMap atomic insertion/version keys/CAS, no JVM locks in command services. |
| `integration/dm8/ContractReviewCloseoutMapper.java`, `Dm8ContractReviewCloseoutRepository.java` | Focused mapper and repository for only A tables; mandatory write transactions, exact-row CAS, explicit UTC and CLOB handling. |
| `backend/src/main/resources/mappers/dm8/ContractReviewCloseoutMapper.xml` | DM8 inserts/selects/conditional updates; unique finding and (run,version); no dialect substitute. |
| `integration/dm8/ContractReviewCloseoutSchemaPreflight.java` | Pure catalog classifier `classify(expected, observed, readable)` -> NOT_APPLIED / ALREADY_MATCHING / PARTIAL-SCHEMA_DRIFT / UNKNOWN; never executes DDL. |
| `contract/application/closeout/CloseoutOwnership.java` | `finding(project,contract,run,finding): ContractReviewFinding`, `run(...)`: verified existing manifest/output; safe NOT_FOUND on wrong chain/F02. |
| `contract/application/closeout/AcknowledgementTransactionCoordinator.java`, `ContractReviewAcknowledgementService.java` | AuthContext actor; `acknowledge(project,contract,run,finding,version,note,key,metadata)` and read/list. Atomic fact+audit+idempotency; outside failed transaction winner reopen. |
| `contract/application/closeout/ReportSnapshotFactory.java` | `freeze(project,contract,run): Snapshot(json,inputIdentity,findingIdentity,acknowledgementIdentity)`; stable exact step/attempt/finding/evidence order, F02/non-F02 acknowledgement union and NONE tokens; immutable parse reopen/grounding. |
| `contract/application/closeout/port/ReportRendererPort.java`, `DeterministicHtmlReportRenderer.java` | `render(String snapshotJson): RenderedReport(bytes,mediaType)`; escaped customer text, bounded output, no nondeterministic time/IDs. |
| `contract/application/closeout/ReportTransactionCoordinator.java` | `start(...)`, `prepare(...)`, `complete(...)`, `unknown(...)`, `fail(...)`, `auditDownload(...)` use separate REQUIRES_NEW transactions and CAS. |
| `contract/application/closeout/ContractReviewReportService.java` | `generate(project,contract,run,key,metadata)`, `get`, `list`, `download`; render/storage outside tx, same-key reconcile and immutable AVAILABLE. |
| `contract/api/project/ContractReviewCloseoutController.java` | Focused ack/report POST/GET/list/artifact API; strict JSON bodies, existing four-role guard and shared session/CSRF. |
| `frontend/src/features/contract/closeout/closeoutTypes.ts`, `closeoutDataSource.ts`, `closeoutOperation.ts`, `closeoutPresentation.ts` | Typed transport, shared request wrapper, immutable actor/path/command scoped operation keys persisted before POST, Chinese customer projections. |
| `frontend/src/features/contract/closeout/ContractFindingAcknowledgement.tsx`, `ContractReviewReportPanel.tsx` | Independent controls with reopen/list/download and explicit same-operation recovery; no workspace edits. |

Test files map one-to-one to tasks: `contract/application/closeout/CloseoutAcknowledgementTest.java`, `ReportSnapshotTest.java`, `ReportArtifactTest.java`, `contract/api/project/ContractReviewCloseoutApiTest.java`, `integration/dm8/ContractReviewCloseoutPersistenceTest.java`, `integration/dm8/ContractReviewCloseoutMigrationTest.java`; supporting synthetic `contract/application/closeout/CloseoutFixtures.java`; frontend `closeoutOperation.test.ts`, `closeoutDataSource.test.ts`, `closeoutPresentation.test.ts`, `closeoutComponents.test.ts`.

## Ownership, API and transaction contract

Ack POST body is exactly `findingVersionNo` plus optional `handlingNote`; actor never accepted. Scope `CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE`; fingerprint is canonical actor/project/contract/run/step/attempt/finding/version/normalized note. Safe NOT_FOUND for wrong chain or F02 ID. Unique finding loser reopens durable winner outside rolled-back transaction; same key changed fingerprint returns 409. New different command against an already acknowledged finding returns the existing immutable acknowledgement, never changes its note/actor.

Report POST body is empty/`{}`. Scope `CONTRACT_REVIEW_REPORT_GENERATE`; fingerprint binds actor, path, schema and frozen input/finding/ack identities. Completed/in-progress key lookup reopens its original snapshot and checks original command identity before any fresh snapshot; later acknowledgements never alter same-key replay. New key creates next version, unique (runId,reportVersion) conflict is retried from a fresh short transaction, bounded to five allocation attempts.

START rechecks ownership/eligibility, freezes canonical JSON and identities, claims key, allocates version and appends audit. Rendering runs after commit. PREPARE short transaction records deterministic storage key (SHA of report identity), expected media/size/hash before publication. inspect PRESENT exact -> COMPLETE; DEFINITELY_MISSING -> create-only stage/promote then inspect; UNKNOWN -> independent UNKNOWN closure, IN_PROGRESS key retained. If bytes published and COMPLETE response/commit times out, reread row/key and inspect same target; retry never allocates another report. AVAILABLE never updates. Definitive render/validation errors close FAILED; inability to prove storage/DB outcome never closes FAILED. Persisted snapshot, expected artifact identity and key support restart recovery.

API root `/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}`:

- POST/GET `/findings/{findingId}/acknowledgements`; GET `/findings/{findingId}/acknowledgements/{acknowledgementId}`.
- POST/GET `/reports`; GET `/reports/{reportId}`; GET `/reports/{reportId}/artifact`.

All endpoints require one existing role SYSTEM_ADMIN/PROCUREMENT_MANAGER/AUDITOR/LEGAL. POST requires Session, CSRF and validated Idempotency-Key. GET never generates/reconciles resources; downloads validate ownership+AVAILABLE+size/hash and append durable audit with null TASK_ID. HTML served as attachment with restrictive CSP and nosniff; no storage key/root in API DTO. Client errors map to Chinese safe copy.

---

### Task 1: Ack domain, persistence and migration

- [x] RED: Write note/immutability and atomic uniqueness tests; migration test requires exactly the two A tables and mandatory ownership FKs/unique constraints. Preflight tests simulate missing/matching/partial/unreadable catalog.
  ```java
  assertEquals("意见", normalizeNote("  意见  "));
  assertDoesNotThrow(() -> normalizeNote("😀".repeat(1000)));
  assertThrows(IllegalArgumentException.class, () -> normalizeNote("😀".repeat(1001)));
  assertEquals("UNKNOWN", preflight.classify(expected, Map.of(), false));
  ```
- [x] Observe RED with `.\mvnw.cmd '-Dtest=ContractReviewCloseoutMigrationTest,ContractReviewCloseoutPersistenceTest,CloseoutAcknowledgementTest' test`.
- [x] GREEN: Implement exact records/ports, atomic local adapter, focused DM8 row mapping and descriptive migration. Add owned FKs to finding owner tuple and run/project, contract and SYS_USER; existing F02 table untouched. Handling note uses CLOB to avoid byte-count truncation; application enforces code-point bound.
  ```java
  var normalized = note == null ? null : note.strip();
  if (normalized != null && normalized.codePointCount(0, normalized.length()) > 1000)
      throw new IllegalArgumentException("handling note too long");
  ```
- [x] Rerun focused GREEN, inspect diff, commit `feat: add closeout durable records and owned schema`.

### Task 2: Ownership-safe durable acknowledgement

- [x] RED: Exact chain passes; another project/contract/run/attempt and F02 reject safely; same key replays; changed actor/note/version conflicts; racing writers reopen one winner; audit failure must not appear as committed success.
  ```java
  assertEquals(first.acknowledgementId(), service.acknowledge(sameCommand).acknowledgementId());
  assertEquals(NOT_FOUND, wrongRun.errorCode());
  assertEquals(1, repository.findAcknowledgementsByRun(runId).size());
  ```
- [x] Observe RED with `.\mvnw.cmd '-Dtest=CloseoutAcknowledgementTest' test`.
- [x] GREEN: Add ownership/service/coordinator. Read server-owned exact finding/step/attempt and manifest; fingerprint immutable command; one transaction claims/inserts/audits/completes; classify duplicate outside rollback and validate winner. No synchronized service.
  ```java
  String fingerprint = CloseoutCanonical.sha256(CloseoutCanonical.json(commandFacts));
  var claim = idempotency.claim(request);
  if (!claim.acquired()) return verifyAndReopen(claim.record(), fingerprint);
  ```
- [x] GREEN plus existing `FindingAcknowledgementServiceTest`; commit `feat: acknowledge exact contract review findings`.

### Task 3: Completed-only canonical snapshot and renderer

- [x] RED: Reject every non-COMPLETED run and incomplete required step; reject exact output/provenance/evidence mismatch. Shuffle findings/evidence/acks and retain identical hashes; NONE tokens cover both sources; adding F02/non-F02 ack changes only ack snapshot; later authority never read. Renderer escapes script/HTML and is byte deterministic.
  ```java
  assertEquals(first.findingIdentity(), shuffled.findingIdentity());
  assertTrue(first.json().contains("NONE"));
  assertFalse(new String(renderer.render(snapshot).bytes(), UTF_8).contains("<script>"));
  ```
- [x] Observe RED with `.\mvnw.cmd '-Dtest=ReportSnapshotTest' test`.
- [x] GREEN: Existing output reader + frozen input loader prove manifest/parse and F02 task; select exact current completed attempts; snapshot uses ordered records and canonical JSON. F02 acknowledgements come only from existing read repository. Renderer produces bounded UTF-8 HTML with escaped summaries/recommendations/notes, Chinese source/status copy and no raw technical identifiers.
  ```java
  if (run.status() != COMPLETED || requiredSteps.stream().anyMatch(s -> s.status() != COMPLETED))
      throw conflict();
  ```
- [x] GREEN plus existing output-reader/frozen-loader regressions; commit `feat: freeze complete review report snapshots`.

### Task 4: Versioned artifact generation and same-operation recovery

- [x] RED: Version race yields unique increasing versions; same key never duplicates; AVAILABLE immutable; render/storage outside tx; publish success; missing versus unknown; published bytes plus DB timeout recovers same ID/hash; ACK added after START cannot change bytes.
  ```java
  assertEquals(first.reportId(), recovered.reportId());
  assertEquals(AVAILABLE, recovered.status());
  assertEquals(UNKNOWN, unavailable.status());
  assertArrayEquals(versionOneBytes, download(versionOne));
  ```
- [x] Observe RED with `.\mvnw.cmd '-Dtest=ReportArtifactTest' test`.
- [x] GREEN: START/prepare/closure coordinators REQUIRES_NEW; bounded allocation retry; deterministic key and expected hash persisted before create-only publication; exact inspect check and no current snapshot on replay. Keep idempotency IN_PROGRESS for UNKNOWN. Audit download uses real report resource ID, null task ID.
  ```java
  if (inspection.status() == UNKNOWN) return transactions.unknown(reportId);
  if (inspection.status() == PRESENT) return transactions.complete(verifyExact(inspection));
  storage.promote(storage.stage(new ByteArrayInputStream(bytes), limit), storageKey);
  ```
- [x] GREEN on real temporary local FileStorage adapter where practical; commit `feat: generate and recover immutable report artifacts`.

### Task 5: Focused API/security and rediscovery

- [x] RED: Authenticated role/CSRF/key matrix, strict bodies reject actor/extra fields, wrong owner 404, response envelope/request ID, collection rediscovery, GET calls no generator; artifact download audited with safe headers.
  ```java
  mvc.perform(post(path).with(user).with(csrf()).header("Idempotency-Key", "ack-1")
      .contentType(APPLICATION_JSON).content("{\"findingVersionNo\":0,\"acknowledgedBy\":\"forged\"}"))
      .andExpect(status().isBadRequest());
  ```
- [x] Observe RED with `.\mvnw.cmd '-Dtest=ContractReviewCloseoutApiTest' test`.
- [x] GREEN: New focused controller, DTO hides snapshot/storage fields; strict JSON parsing, AuthContext identity, GET read-only except required download audit. No shared security/OpenAPI/controller edits.
- [x] GREEN plus existing run/F02 API regressions; commit `feat: expose focused acknowledgement and report APIs`.

### Task 6: Isolated frontend controls and operation identities

- [x] RED: Transport sends exact paths/body/key with shared request, GET key-free; refresh/network failure retains original key and command; changed note blocked until known resolution; report new-version key only on explicit new-generation action. Rendered controls/panel never expose UUID/hash/enum/policy/backend exception; unknown shows 结果状态待确认.
  ```typescript
  assert.equal(reopened.begin(scope, fingerprint), firstKey);
  assert.throws(() => reopened.begin(scope, changedFingerprint));
  assert.doesNotMatch(rendered, /UNKNOWN|GENERATING|policyVersion|[a-f0-9]{64}/);
  ```
- [x] Observe RED with `node --test --experimental-strip-types "src/features/contract/closeout/*.test.ts"`.
- [x] GREEN: Focused types/data source/operation/presentation and React controls; persistence stores only keys/fingerprints/pointers, never notes/findings/results; GET list after mount/reload; explicit recovery replays original command. No workspace edits or dependency upgrade.
- [x] GREEN focused Node tests and `npm.cmd run build`; commit `feat: add isolated contract acknowledgement and report controls`.

### Task 7: Final verification and ordinary push

- [x] Self-review A1-A12 against implementation. Add RED for any uncovered invariant before corrections; no unrelated budget static-contract fix.
- [x] Run backend `.\mvnw.cmd test` and `.\mvnw.cmd clean verify`; record XML tests/failures/errors/skips. Frontend focused Node tests and build. HTTP MockMvc is local contract evidence; live HTTP/DM8 not exercised must be marked NOT VERIFIED.
- [x] Run `git diff --check`, `git status --short`, `git diff --stat 2ed1eb9`; verify historical migrations/shared files untouched and no secrets/customer data/IP config/second storage/Redis truth/fake rules/RAG/vector/general agent.
- [x] Prepare verified handoff and final evidence commit `test: verify contract closeout ack and report invariants`. Ordinary push `git push origin HEAD:feature/contract-closeout-ack-report-20260916` and remote SHA outcome are reported in final A13 output; keep worktree, no PR/merge/deploy.

## Required integration adaptations

Integration branch wires `ContractFindingAcknowledgement` and `ContractReviewReportPanel` into the existing `ContractReviewWorkspace` using authenticated actor/project/contract/run and non-F02 finding IDs. Preserve F02 existing acknowledgement UI/API. Add focused paths/DTO to shared OpenAPI and migration ordering/runbook after review; execute schema preflight and real DM8 round-trip/concurrency/storage verification in an authorized environment. No semantic change to common model is planned; if one proves necessary, stop Track A with CROSS_TRACK_CONTRACT_BLOCKER.

## Self-review

Coverage: A1-A5 Tasks 1/2/5; A6-A9 Tasks 1/3/4; A10 Task 5; A11 Task 6; A12 Tasks 1-7; A13 Task 7. Snapshot persistence explicitly closes later-ack/restart drift. New files own all mutation; existing readers own established F02 provenance. Plan contains exact interfaces/ownership/commands, bounded concurrency recovery and no pending design choices. Real DM8, renderer customer acceptance and browser visual verification are distinct from local tests.

## Execution evidence (2026-09-16, Windows / JDK 17)

- Plan committed first: `4706c2f`; schema/repositories `fb01e9f`; acknowledgement `ffff47a`; frozen snapshots/HTML `c98ca84`; artifact recovery `6d08f99`; API `f653313`; isolated frontend `4aad28b`; acknowledgement read/winner ownership hardening `b2284c7`.
- Observed RED before implementation: missing closeout types/schema; missing acknowledgement coordinator/services; missing snapshot/renderer; missing artifact coordinator/service. API RED: 5 failures because new routes returned 404. Frontend RED: missing production modules, then missing React components. Regression RED: AVAILABLE overwrite accepted; then empty-list ownership and corrupt winner accepted (2 failures). Each was followed by focused GREEN; no existing assertion was weakened.
- Latest combined backend focused gate: `.\mvnw.cmd '-Dtest=CloseoutAcknowledgementTest,ReportSnapshotTest,ReportArtifactTest,ContractReviewCloseoutPersistenceTest,ContractReviewCloseoutMigrationTest,ContractReviewCloseoutApiTest,FindingAcknowledgementServiceTest,ReviewStepOutputReadServiceTest,FrozenReviewInputLoaderTest,ContractReviewRunApiIntegrationTest' test` — 79 tests, 0 failures/errors/skips, BUILD SUCCESS. New closeout tests contribute 37, including parameterized non-COMPLETED eligibility; existing F02 acknowledgement contributes 21.
- Full `.\mvnw.cmd test` — 1156 tests, 0 failures/errors/skips, BUILD SUCCESS. No unrelated budget static-contract changes were made.
- Fresh `.\mvnw.cmd clean verify` — 1156 tests, 0 failures/errors/skips, BUILD SUCCESS (2:06). Executable JAR packaging completed; this is not deployment evidence.
- Frontend `node --test --experimental-strip-types --test-isolation=none "src/features/contract/closeout/*.test.ts"` — 7 tests, 0 failures/skips. Actual React server rendering checks Chinese text and hidden internal fields. `npm.cmd run build` — PASS, 2391 modules transformed; existing chunk-size warning remains. Locked `npm.cmd ci --ignore-scripts --offline=false` was required because offline cache lacked a package; it reported 3 high advisories, without changing the lockfile or dependencies.
- Transaction evidence: Spring TransactionInterceptor around coordinator observes START commit before rendering; renderer and real create-only local publication observe no active transaction; PREPARE and COMPLETE commit separately. This is local transaction-boundary evidence, not a real DM8 commit test.
- Artifact evidence: real temporary Windows FileSystemStorageAdapter create-only hard links, byte/hash reopen, immutable old version after new acknowledgement; UNKNOWN does not publish until DEFINITELY_MISSING; published bytes plus simulated COMPLETE DB timeout recover same report without rerender.
- Migration guard and synthetic complete-signature classifier cover NOT_APPLIED, ALREADY_MATCHING, PARTIAL-SCHEMA_DRIFT and UNKNOWN. Mapper XML/CLOB bindings and row conversion are tested without a database. REAL_DM8_STATUS = NOT VERIFIED; no live DM8/customer connection was attempted.
- HTTP evidence is local Spring MockMvc Session/CSRF/role/key/body/download-header behavior. LIVE_HTTP/CURL = NOT VERIFIED; browser visual and LAN/customer E2E = NOT VERIFIED. No deployment or PDF/template/signature acceptance is claimed.
- Static scope review: only new Track A files; exactly one new owned SQL migration. No historical migration, shared controller/service/domain/mapper/security, OpenAPI, workspace, migration README or dependency lockfile changed. `git diff --check` passed. Focused forbidden-feature scan found no Redis truth, JVM service lock, floating-point business amount, provider URL, RAG or vector dependency.

### Exact integration handoff

1. In the integration branch only, import the two new components into existing `ContractReviewWorkspace.tsx`. Supply `context = {actorId, projectId, contractId, runId}` from authenticated/durable state. For non-F02 findings supply `findingId` and `findingVersionNo`; retain existing F02 acknowledgement components. Supply authoritative `runStatus` to the report panel. Optional `dataSource`/`operations` props are focused test/integration seams, not a second state truth.
2. Add the seven focused POST/GET/list/artifact paths and DTO to shared OpenAPI. POST acknowledgement body has only findingVersionNo and optional handlingNote; report POST is empty. Report DTO deliberately omits frozen JSON, hashes and storage key. No shared security change is required.
3. Add `CONTRACT__review_ack_report.sql` after `review-run-results` and actual `V009__review_finding_acknowledgement.sql` to the controlled migration runbook. Before authorized execution, obtain complete scoped DM8 catalog signatures and feed the classifier; unreadable/partial/drift must stop. Real DM8 SQL/CLOB/FK/unique/CAS/race verification remains an integration gate, not completed here.
4. Verify authenticated browser download/session routing and browser secure-context Web Crypto availability (HTTPS or trustworthy loopback) before exposing these controls; persisted browser operation keys/fingerprints are not business facts. The controls fail closed if persistence/cryptography cannot be used. No raw handling notes/results are stored in browser storage.
