# Track D ReviewSession ProjectFile Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** New Project-backed ReviewSessions reference authoritative ProjectFile and immutable parse identities without a second upload, parse, OCR, or ReviewDocument.

**Architecture:** Add frozen source kind and optional Project identity to the existing session aggregate, retaining its PREPARING_MATERIALS -> READY lifecycle. A focused reference service/controller and repository persist historical binding rows and validate current server-owned role/identity. Legacy endpoints reject reference-session binary mutations before storage staging; existing legacy sessions retain their behavior.

**Tech Stack:** Java 17, Spring Boot, MyBatis, DM8, local in-memory test adapters, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-16-contract-audit-parallel-closeout-design.md` sections 9, 11.4, 13–16, 20; `2026-09-14-contract-audit-evolution-master-design.md` section 27; approved SHA `2ed1eb9906d2c67d3c673906d5f1b27b434af3a9`; latest human attachment common rules and D1–D12.

## Global Constraints

- DM8 is System of Record; UNKNOWN != FAILED.
- ReviewSession != ReviewRun. READY sessions reject later changes; independent ReviewRun creation keeps its current readiness resolver and immutable snapshot.
- No historical migration changes. Owned migration only `db/dm8/CONTRACT__review_session_project_file_binding.sql`.
- No FileStorage staging/promotion, parser, OCR, upload service, or ReviewDocument creation in reference commands.
- No shared ReviewRun model/controller/service, SecurityConfig, OpenAPI root, parent POM, architecture/roadmap changes.
- Real DM8 is NOT VERIFIED; no real/customer database connection or mutation.
- Every implementation step follows observed RED then GREEN. Plan commit precedes production code.

## Schema audit and ownership

V004 CONTRACT_REVIEW_SESSION lacks both project identity and a discriminator. V005 adds only paired tombstones. Therefore add SOURCE_KIND default LEGACY_REVIEW_DOCUMENT plus PROJECT_ID; constrain their pairing. Defaults preserve all historical rows without converting materials. Source kind and project are written only at insert and retained by all domain transitions. Existing CAS SQL only updates status/version/time, never source kind.

New REVIEW_SESSION_PROJECT_FILE_BINDING stores ID, SESSION_ID, PROJECT_ID, PROJECT_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, CANONICAL_TEXT_SHA256, EFFECTIVE_ROLE, ROLE_DECISION_ID, IDENTITY_DECISION_ID, SLOT_KEY, ORDINAL_NO, ACTIVE_FLAG, BOUND_BY, BOUND_AT, SELECTION_VERSION, VERSION_NO. Append binding history; deactivate prior active rows on explicit selection replacement while preparing. Session CAS serializes replacement/confirmation. Foreign keys target existing session/project/file/parse/decision/user identities. Additive constant legacy-source columns on existing ReviewDocument/MaterialBinding tables enforce source separation through composite FKs without changing historical migrations. Migration declares namespace, unique ID, dependencies, CONTROLLED_MANUAL and NOT_ASSUMED; preflight distinguishes NOT_APPLIED, ALREADY_MATCHING, PARTIAL-SCHEMA_DRIFT, UNKNOWN.

## Exact file map and interfaces

Paths below are under `backend/src/main/java/com/fuquan/audit` unless stated otherwise.

- Modify `contract/domain/ContractReviewSession.java`; create `contract/domain/ReviewSessionSourceKind.java`: preserve old constructor/factory as legacy; add `createProjectBacked(...)` and immutable source/project fields.
- Modify `contract/application/ContractReviewSessionService.java` and `ContractReviewSessionTransactionCoordinator.java`: fail closed for reference-source legacy write paths, including replay before byte staging.
- Modify `integration/dm8/{ContractReviewSessionPersistenceRow,Dm8ContractReviewSessionRepository}.java` and `backend/src/main/resources/mappers/dm8/ContractReviewSessionMapper.xml`: insert/read discriminator and project; no mutable discriminator SQL.
- Create `contract/domain/ReviewSessionProjectFileBinding.java`: immutable authority snapshot and deactivation.
- Create `contract/application/session/ProjectSessionReadiness.java`: `resolve(UUID projectId, UUID contractId, List<UUID> fileIds)` returns server-owned material provenance, using existing Project/File locking and EffectiveDocumentRoleResolver/EffectiveProjectIdentityResolver.
- Create `contract/application/session/ProjectReviewSessionService.java`: create, replace selection, confirm and GET/list through scoped project/contract/session ownership; actor from AuthContext only.
- Create `contract/application/session/ProjectSessionBindingRepository.java`: `insert(binding)`, `findBySessionId(sessionId)`, `deactivateBySessionId(sessionId)`.
- Create `contract/infra/memory/InMemoryProjectSessionBindingRepository.java` and `integration/dm8/{ProjectSessionBindingMapper,Dm8ProjectSessionBindingRepository}.java`; owned mapper XML `backend/src/main/resources/mappers/dm8/ProjectSessionBindingMapper.xml`.
- Create `contract/api/ProjectReviewSessionController.java`: `/api/v1/projects/{projectId}/contracts/{contractId}/review-sessions` POST/GET and `/{sessionId}` GET, `/{sessionId}/materials` GET (complete selection history) / POST, `/{sessionId}/confirm-materials` POST. No DELETE or multipart route.
- Tests: `backend/src/test/java/com/fuquan/audit/contract/domain/ReviewSessionSourceKindTest.java`, `contract/application/session/ProjectReviewSessionServiceTest.java`, `contract/api/ProjectReviewSessionApiIntegrationTest.java`, `integration/dm8/ProjectSessionBindingDm8Test.java`.

## Transaction, idempotency, concurrency, security

All reference commands are short REQUIRES_NEW transactions; there is no external compute/I/O phase. Scope names: PROJECT_REVIEW_SESSION_CREATE, PROJECT_REVIEW_SESSION_BIND, PROJECT_REVIEW_SESSION_CONFIRM. Fingerprints include actor, path project/contract/session, normalized name/remark or ordered selected file IDs, and expected session version. Result identity is the real session ID. Successful replay reopens persisted binding/session facts without rerunning current readiness. Changed key content returns 409; IN_PROGRESS remains the same operation. DB claim uniqueness and session expectedVersion CAS arbitrate concurrent writers. Binding change and READY confirmation both require PREPARING_MATERIALS.

Lock files in UUID order, then project/contract association/contract in established authority order. Revalidate AVAILABLE, PARSED, exact parse/file SHA/text hash, effective role/parse identity, human SAME_PROJECT current tuple under those locks. Confirmation rechecks authority and exact selected provenance; no silent upgrade to latest parse. Core READY roles preserve existing CONTRACT_BODY and AWARD_NOTICE requirements; tender-or-bid is not a READY requirement in the approved baseline.

HTTP and application access require existing SYSTEM_ADMIN/PROCUREMENT_MANAGER/AUDITOR/LEGAL roles. Actor never comes from request body. Existing CSRF/session security applies; ownership mismatch is safe NOT_FOUND. Append OperationAuditPort events with real session identity and bounded metadata, no TASK_ID substitution or content.

## Task 1: Frozen source and legacy boundary

- [x] RED: domain constructor/factory tests assert project source survives version and READY transitions, legacy defaults remain legacy, invalid source/project pairing rejects; legacy upload to reference session rejects before FileStorage.stage.
```java
assertEquals(PROJECT_FILE_REFERENCE, session.advanceVersion(now).sourceKind());
assertThrows(BusinessException.class, () -> legacyService.upload(command));
verifyNoInteractions(storage);
```
- [x] Run `./mvnw.cmd -Dtest=ReviewSessionSourceKindTest test` from backend and capture expected missing-feature failure.
- [x] GREEN: implement additive fields/constructor, persistence mapping and pre-stage/transaction legacy guard; rerun focused tests plus existing session suite.
- [x] Commit implementation with Task 2 in `e7f6bf3 feat: bind project review sessions to immutable project materials`.

## Task 2: Durable reference selection and readiness

- [x] RED: exact ProjectFile/parse bind; wrong project/contract association; unavailable/package; parse SHA/text mismatch; unknown role; stale role/identity; no storage/OCR/parser/document call; stale version; duplicate key replay and conflict; concurrent selection versus READY; READY immutable.
```java
var result = service.create(projectId, contractId, "review", null, fileIds, key, metadata);
assertEquals(file.id(), result.bindings().get(0).projectFileId());
assertEquals(parse.parseResultId(), result.bindings().get(0).parseResultId());
```
- [x] Run `./mvnw.cmd -Dtest=ProjectReviewSessionServiceTest test`, observe expected missing implementation failure.
- [x] GREEN: reference domain, port/adapters, readiness and transaction service; owned migration with catalog/preflight guards; verify exact persisted immutable provenance, replay after authority drift, session CAS and safe ownership.
- [x] Run focused service/domain/repository tests; commit `feat: bind project review sessions to immutable project materials`.

## Task 3: API, compatibility, full verification

- [x] RED: authenticated scoped create/reopen/bind/confirm; CSRF and role enforcement; ownership 404; no public session delete; legacy read/download/READY regression; run snapshot remains unchanged when another preparing session changes selection.
- [x] GREEN: focused controller with validated bounded inputs and existing envelope/request tracing. No shared UI changes; record navigation/OpenAPI wiring as integration adaptations.
- [x] Run focused new and legacy suites, then `./mvnw.cmd clean verify` from backend. Record exact test counts and failures/skips.
- [x] Run git diff --check, historical-migration and high-conflict file gates, secret/IP and no-copy scans; inspect changed files and commit ancestry.
- [x] Commit `test: verify project session authority and legacy compatibility` after fresh verification; ordinary push only `origin HEAD:feature/contract-closeout-session-authority-20260916` (subject to destination approval; report exact clean HEAD if rejected).

## Review and result

D1–D9 map to Tasks 1–3; D10 requires no shared UI changes and lists integration wiring; D11 is the adversarial suite above; D12 is final exact result schema. No merge, PR, deployment or other-track reads. Report PLAN, HEAD_BEFORE/AFTER, MIGRATION, SOURCE_KIND, PROJECTFILE_BINDING, READINESS, NO_COPY_PROOF, LEGACY_COMPATIBILITY, TESTS, STATIC_GATES, FILES_CHANGED, COMMITS, REQUIRED_INTEGRATION_ADAPTATIONS, PUSHED_HEAD, REAL_DM8_STATUS.

## Execution evidence (2026-09-16, Windows / JDK 17.0.19)

- Baseline clean, correct branch, exact approved HEAD `2ed1eb9906d2c67d3c673906d5f1b27b434af3a9`. Plan-only commit `d32194b3a75cc2c97776cec329e921a42651febe` precedes production `e7f6bf3e906a00e705cb6dfff6ecf8316a510989`.
- Observed RED before implementation: source factory/type absent; legacy upload reached storage before rejection; reference service absent; new routes 404; DM8 discriminator not round-tripped; owned migration absent. Initial core GREEN: 19 tests. Initial complete verify: 1147/0 failures/0 errors/0 skipped.
- Independent D-only review identified CAS exception mapping, replay/current-version mixing, and missing history GET. Regression RED: 21 tests, 3 failures (wrong exception, wrong complete replay value, GET 405). Fixes: CAS maps to 409; selection replay reconstructs original session version/status/time and operation-time binding projection; history GET reopens all persisted bindings. Reviewer rechecked fixes and found no remaining blocking finding.
- A replay response is an operation-time snapshot, not the current session. GET session returns current active selection; GET materials returns complete history including inactive rows. Never combine an old selection with the current CAS version. Binding timestamps equal session update timestamps for deterministic durable replay reconstruction.
- ReviewRun regression now persists a real run, material and manifest through the existing run repository and reopens through ContractReviewRunService before/after session selection replacement; the complete frozen view remains identical. No ReviewRun production model/service was changed.
- Final focused command from backend: `.\\mvnw.cmd '-Dtest=ProjectReviewSessionApiIntegrationTest,ProjectReviewSessionServiceTest,ReviewSessionSourceKindTest,ProjectSessionLegacyGuardTest,ProjectSessionBindingDm8Test,ProjectSessionMigrationStructureTest,ContractReviewSessionApiIntegrationTest' test`: **41 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS (20:16 +08).
- Fresh final command: `.\\mvnw.cmd clean verify`: **1148 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS (20:18:30 +08). Surefire XML sum independently confirms the same totals. Full command log remains locally ignored at `backend/track-d-full-verify.log`.
- Real local JAR HTTP smoke (before review-only fixes): health GET returned 200 with `{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`; unauthenticated new Project-session collection GET returned 401 with `{"code":401,"message":"authentication required","data":null}`. Request IDs returned; response cookies excluded from evidence. Test Java process stopped. Final HTTP behavior additionally covered by Spring MockMvc tests.
- Static gates: `git diff --check` clean; exactly the owned DM8 migration changed; no historical migrations, shared security/web/OpenAPI/POM/architecture/roadmap/spec or ReviewRun production files changed. Secret/IP pattern scan of changed files found no sensitive literals. New reference service/controller have no FileStorage/OCR/parser/upload dependencies; legacy reference upload regression verifies no storage call; exact binding test verifies no ReviewDocument creation.
- Required integration adaptations only: shared navigation/material-selection UI and OpenAPI endpoint/schema wiring; controlled migration ordering and real catalog preflight/application. No shared adaptation was performed here; no PR, merge, deployment or other-track access.
- Real DM8 / Redis / OCR / AI and target domestic middleware: **NOT VERIFIED** in this task. Migration structure and mocked adapter tests are not real DM8 execution. PARTIAL/SCHEMA_DRIFT/UNKNOWN require fail-closed operator handling; do not claim ALREADY_MATCHING by object-name existence.
