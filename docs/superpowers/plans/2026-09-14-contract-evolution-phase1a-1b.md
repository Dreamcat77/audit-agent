# Contract Evolution Phase 1A + 1B Implementation Plan

> Date: 2026-09-14
>
> Baseline: `origin/main@49aa638b2224a1c58f5959b2066c48f26f1c574d`
>
> Branch/worktree: `feature/contract-evolution-phase1-20260914` / `D:\code\audit_agent_1D\.worktrees\contract-evolution-phase1`
>
> Scope: immutable ProjectFile parse results, deterministic document-role assessment, durable human role confirmation, and additive ProjectFile projection only.

## Goal and boundaries

Preserve the existing one-by-one `POST /api/v1/projects/{projectId}/files` pipeline while making every successful new parse rediscoverable as an immutable DM8 fact. Materialize one immutable `LEGACY_IMPORTED` result when an older parsed ProjectFile first enters the role flow. Add category-independent deterministic role signals, an always-human-confirmed durable decision stream, stable idempotency, concurrency control, and reopenable API projections.

This plan does not implement FastAPI `DOCUMENT_ROLE`, Qwen/model fallback, Project Identity, candidate state/schema changes, candidate re-entry, ReviewRun, async worker, finding/report persistence, debug bundle, or quality-harness production code. Frontend is stretch-only after backend `clean verify` passes.

## Architecture choices fixed by the approved design

- DM8 is durable truth; Redis is not used for parse, assessment, decision, idempotency, or concurrency correctness.
- `PROJECT_FILE.PARSED_TEXT` and provenance remain compatibility/latest projections. A `PARSED` success becomes externally visible only in the same COMPLETE transaction that inserts its immutable parse result.
- A parse result is insert-only. `(FILE_ID, PARSE_VERSION)` is the durable concurrency key; reparse will append a later version in a future task.
- `LegacyProjectFileParseResultMaterializer` uses only persisted ProjectFile text/provenance and insert-race reload. It never reopens bytes or invokes a parser/OCR.
- Historical `project-extraction-key-v1` is unchanged. New current-pipeline parse-backed extraction is re-keyed through explicit v2 input containing `parseResultId`.
- Role execution and business decision use different enums and rows. Every deterministic assessment completes with decision projection `NEEDS_CONFIRMATION`; no threshold makes it authoritative.
- Human decision stream version starts at 1. `expectedVersion=0` means no prior decision. Unique `(FILE_ID, VERSION_NO)` plus repository insert semantics gives one winner for concurrent distinct commands.
- Effective role precedence is latest human confirmation, then category as `LEGACY_EFFECTIVE` only for a parsed legacy file/no new decision, otherwise `UNKNOWN`.

## Task 1 — Immutable Parse Result domain model

**Files**

- Create `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectFileParseOrigin.java`.
- Create `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectFileParseResult.java`.
- Create `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileParseResultFactory.java`.
- Create `backend/src/test/java/com/fuquan/audit/contract/domain/project/ProjectFileParseResultTest.java`.

**RED**

Add tests for required identity/provenance fields, lower-case SHA-256, monotonic positive `parseVersion`, valid confidence/page/duration, `PARSED` canonical-text requirements, and SHA-256 derived from canonical UTF-8 text.

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectFileParseResultTest test
```

Confirm compilation/test failure is caused only by missing Phase 1A types.

**GREEN**

Implement immutable records/enums and a factory that consumes the already computed `ParsedProjectDocument`/ProjectFile projection. Permit `CURRENT_PIPELINE` and `LEGACY_IMPORTED`; expose no mutation method.

Run the same focused command and record result.

## Task 2 — CONTRACT parse-result SQL + DM8 repository

**Files**

- Create `db/dm8/CONTRACT__project_file_parse_result.sql` with the exact approved header.
- Create `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ProjectFileParseResultRepository.java`.
- Create `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectFileParseResultPersistenceRow.java`.
- Create `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectFileParseResultMapper.java`.
- Create `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectFileParseResultRepository.java`.
- Create `backend/src/main/resources/mappers/dm8/ProjectFileParseResultMapper.xml`.
- Create `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractProjectFileParseResultDm8SqlStructureTest.java`.
- Create `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ProjectFileParseResultRepositoryTest.java`.

**RED**

Add SQL-structure tests for header/dependency, table/columns, PK/FK/UK/CHECK/indexes and absence of repository/mapper update statements. Add mock-mapper tests for insert, duplicate-race reload, exact-identity validation, by-id/by-file-version/latest lookup.

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=ContractProjectFileParseResultDm8SqlStructureTest,Dm8ProjectFileParseResultRepositoryTest test
```

**GREEN**

Implement insert-only port/adapter. Use DB unique `(FILE_ID, PARSE_VERSION)` as final race arbiter and throw safe conflict when the raced row does not have identical immutable business identity.

Run the same focused command.

## Task 3 — In-memory parse-result repository

**Files**

- Create `backend/src/main/java/com/fuquan/audit/contract/infra/memory/project/InMemoryProjectFileParseResultRepository.java`.
- Create `backend/src/test/java/com/fuquan/audit/contract/infra/memory/project/InMemoryProjectFileParseResultRepositoryTest.java`.

**RED**

Test insert/read/latest ordering, same-identity replay, conflicting same file/version rejection, and concurrent `insertIfAbsent` producing exactly one durable ID without `synchronized` correctness.

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=InMemoryProjectFileParseResultRepositoryTest test
```

**GREEN**

Implement profile-local repository with `ConcurrentMap.putIfAbsent` over file/version identity and immutable identity comparison.

Run the same focused command.

## Task 4 — Current upload creates immutable parse result

**Files**

- Modify `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionService.java`.
- Modify `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionTransactionCoordinator.java`.
- Modify focused ingestion test support/constructors as required.
- Extend `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionServiceTest.java`.
- Extend `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectUploadConcurrencyTest.java` where transaction atomicity/replay needs adversarial coverage.

**RED**

Test that a synthetic parsed upload creates one `CURRENT_PIPELINE` parse result, its canonical hash matches server text, the ProjectFile remains compatible, and one parser/OCR result feeds both facts. Add a COMPLETE-failure test proving a `PARSED` ProjectFile cannot be rediscovered without its parse row.

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectFileIngestionServiceTest,ProjectUploadConcurrencyTest test
```

**GREEN**

Carry parse results in `ProcessingOutcome`; insert/remap them in the existing COMPLETE transaction before updating ProjectFile/idempotency success. Create no result for archive container/unsupported/failed parse in this slice. Never invoke parser/OCR a second time.

Run the same focused command plus `ProjectOcrFallbackTest`.

## Task 5 — Legacy parse-result materialization

**Files**

- Create `backend/src/main/java/com/fuquan/audit/contract/application/project/LegacyProjectFileParseResultMaterializer.java`.
- Create `backend/src/main/java/com/fuquan/audit/contract/application/project/LegacyProjectFileParseResultTransactionCoordinator.java` if a dedicated short transaction is required.
- Create `backend/src/test/java/com/fuquan/audit/contract/application/project/LegacyProjectFileParseResultMaterializerTest.java`.

**RED**

Test first materialization, repeated same-ID return, existing result reuse, two-thread exactly-one result, and fail-closed behavior for non-PARSED/unavailable/missing text/missing or inconsistent provenance.

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=LegacyProjectFileParseResultMaterializerTest test
```

**GREEN**

Load ProjectFile by server-owned IDs, validate persisted hash/text/provenance, build `parseVersion=1`/`LEGACY_IMPORTED`, call insert-if-absent, and return the race winner. No file read, parser, OCR, Redis lock, or Java monitor.

Run the same focused command.

## Task 6 — ExtractionKey v2 compatibility seam

**Files**

- Create `backend/src/main/java/com/fuquan/audit/contract/application/project/ExtractionKeyFactoryV2.java`.
- Modify `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionTransactionCoordinator.java` to choose v2 only when an outcome has an immutable parse result.
- Extend `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectIdentityFactoryTest.java` or add `ExtractionKeyFactoryV2Test.java`.
- Extend `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFactReplayIntegrityTest.java`.

**RED**

Lock the existing v1 golden key, add v2 golden/difference tests whose exact component order is sourceFileId, parseResultId, extractionVersion, factType, nullable groupKey, sourceLocator, and add replay/candidate regression.

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=ExtractionKeyFactoryV2Test,ProjectFactReplayIntegrityTest test
```

**GREEN**

Implement a separate v2 factory with length-prefixed SHA-256 and explicit `project-extraction-key-v2` discriminator. Do not alter `ExtractionKeyFactory#create`.

Run the same focused command plus candidate assembler/promotion regressions.

## Task 7 — Category-independent DocumentRoleSignalExtractor

**Files**

- Create `backend/src/main/java/com/fuquan/audit/contract/domain/project/DocumentRole.java`.
- Create `backend/src/main/java/com/fuquan/audit/contract/domain/project/DocumentRoleSignal.java`.
- Create `backend/src/main/java/com/fuquan/audit/contract/application/project/role/DocumentRoleSignalExtractor.java`.
- Create `backend/src/test/java/com/fuquan/audit/contract/application/project/role/DocumentRoleSignalExtractorTest.java`.
- Create synthetic text fixture builders under `backend/src/test/java/com/fuquan/audit/contract/test` if needed; do not add customer materials.

**RED**

Use generic filenames only in surrounding case metadata and pass only immutable canonical text into the extractor. Cover contract, award, tender, bid, supplement, and the rule that “附件” alone is not a high supplement signal. Verify every signal has role/type/rule/weight/locator/text hash/extractor version.

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=DocumentRoleSignalExtractorTest test
```

**GREEN**

Implement bounded, explainable title/front-matter/key-phrase/heading/structure heuristics over server canonical text. The extractor must have no ProjectFile category input or dependency.

Run the same focused command.

## Task 8 — Deterministic DocumentRoleResolver

**Files**

- Create `backend/src/main/java/com/fuquan/audit/contract/domain/project/DocumentRoleCandidate.java`.
- Create `backend/src/main/java/com/fuquan/audit/contract/domain/project/DeterministicDocumentRoleResult.java`.
- Create `backend/src/main/java/com/fuquan/audit/contract/application/project/role/DeterministicDocumentRoleResolver.java`.
- Create `backend/src/test/java/com/fuquan/audit/contract/application/project/role/DeterministicDocumentRoleFixtureMatrixTest.java`.

**RED**

Establish the required matrix: `001.pdf`, `扫描件.pdf`, `采购资料.pdf`, `响应材料.pdf`, `附件.pdf`, while role truth comes only from body text. Expect CONTRACT_BODY/AWARD_NOTICE/TENDER_DOCUMENT/BID_RESPONSE and UNKNOWN-or-conflict respectively. Assert decision status remains `NEEDS_CONFIRMATION` regardless of confidence and no auto-accept threshold exists.

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=DeterministicDocumentRoleFixtureMatrixTest test
```

**GREEN**

Aggregate exact deterministic signal weights, order candidate roles stably, derive display confidence without a business acceptance threshold, mark exact competing maxima as conflict, and return UNKNOWN when no positive role signal exists.

Run the same focused command.

## Task 9 — CONTRACT role-assessment SQL + repositories

**Files**

- Create `db/dm8/CONTRACT__project_file_role_assessment.sql` with exact header/dependency.
- Create assessment/evidence/decision records and enums under `backend/src/main/java/com/fuquan/audit/contract/domain/project`.
- Create ports under `backend/src/main/java/com/fuquan/audit/contract/application/project/port`.
- Create DM8 persistence rows/mappers/repositories and mapper XML under `backend/src/main/java/com/fuquan/audit/integration/dm8` and `backend/src/main/resources/mappers/dm8`.
- Create local in-memory repositories under `backend/src/main/java/com/fuquan/audit/contract/infra/memory/project`.
- Create `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractProjectFileRoleAssessmentDm8SqlStructureTest.java`.
- Create focused DM8/in-memory repository tests.

**RED**

Test separate execution/decision statuses, parse-bound evidence fields, append-only completed assessments, PENDING→RUNNING→COMPLETED/FAILED CAS, immutable evidence, decision stream unique file/version behavior, latest/read-by-ID, duplicate-race identity, exact SQL header/FK/UK/CHECK/indexes, and no machine-row overwrite by human confirmation.

Run focused SQL/repository tests by class name.

**GREEN**

Implement three tables: assessment, evidence, decision. Store structured candidate-role projection durably, bind assessment/evidence/decision to the same project/file/SHA/parse identity, and use update statements only for assessment execution transitions before terminal completion. Decisions/evidence are insert-only.

Run the same focused commands.

## Task 10 — Durable role-assessment command/query

**Files**

- Create `RoleAssessmentCommand`, result/read model, service and transaction coordinator under `backend/src/main/java/com/fuquan/audit/contract/application/project/role`.
- Create focused application tests under the parallel test package.

**RED**

Test legacy materialization before assessment, exact fingerprint inputs, same-key/same-fingerprint replay of the same assessment ID, same-key/changed file/parse fingerprint 409, durable PENDING/RUNNING/COMPLETED flow, reopen query, deterministic evidence persistence, failure closure, and ProjectFile status remaining unchanged on role failure.

Run the focused service/coordinator tests.

**GREEN**

Use short START transaction (idempotency claim + PENDING), short RUNNING CAS, deterministic compute outside write transactions, short COMPLETE (terminal assessment + evidence + audit + idempotency success), and independent failure closure. Result resource type is the real assessment ID.

Run focused tests.

## Task 11 — Durable human role confirmation

**Files**

- Create `RoleConfirmationCommand`, service/coordinator under `backend/src/main/java/com/fuquan/audit/contract/application/project/role`.
- Add focused idempotency/concurrency/application tests.

**RED**

Test actor exclusively from AuthContext, assessment/file/SHA/parse ownership validation, completed assessment requirement, normalized reason fingerprint, same-key replay, same-key/different-role 409, expectedVersion conflict, reload, and two distinct conflicting concurrent confirmations yielding exactly one version winner/current decision.

Run focused tests.

**GREEN**

In one short transaction claim idempotency, validate authoritative records/current stream version, append `CONFIRMED` decision version `expectedVersion+1`, append operation audit, and mark success. Let DB unique file/version arbitrate concurrent distinct keys; map loser to 409 without Redis or Java monitor.

Run focused tests.

## Task 12 — EffectiveDocumentRoleResolver

**Files**

- Create `backend/src/main/java/com/fuquan/audit/contract/application/project/role/EffectiveDocumentRoleResolver.java` and result/source enum.
- Create `backend/src/test/java/com/fuquan/audit/contract/application/project/role/EffectiveDocumentRoleResolverTest.java`.

**RED**

Test latest human decision precedence, superseding decision version, `LEGACY_EFFECTIVE` from category only for parsed legacy/no-new-decision, UNKNOWN for current-pipeline proposal without confirmation, and PACKAGE/UNKNOWN exclusions.

Run focused test.

**GREEN**

Implement exactly the approved three-rule precedence. Never update or reinterpret `PROJECT_FILE.CATEGORY`; never use proposal confidence as authority.

Run focused test.

## Task 13 — Role APIs and ProjectFile additive projection

**Files**

- Modify `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`.
- Add a read/projection service under `backend/src/main/java/com/fuquan/audit/contract/application/project/role`.
- Extend `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectApiIntegrationTest.java` or create `ProjectFileRoleApiIntegrationTest.java`.

**RED**

Test authenticated/CSRF Project API style, required Idempotency-Key, assessment POST response, confirmation POST body and role validation, no client actor field, exact four existing RBAC roles/no new role, additive file fields, same results after GET/reload, idempotency conflicts as 409, and generic filename matrix through the API where practical.

Run focused API integration test.

**GREEN**

Add the two approved POST routes. Extend existing ProjectFile response without removing fields with parse-result identity, suggested role/confidence, execution status, decision status, effective role/source. Read everything from DM8-backed ports, not browser/runtime state.

Run focused API and legacy Project API tests.

## Task 14 — Full regression, policy checks, and optional frontend gate

**Files**

- Update `db/dm8/README.md` with Contract namespace/dependency/preflight semantics.
- Add/extend structure regression ensuring V001–V022 unchanged and no numeric V023/V024/V025.
- Only after backend full GREEN, optionally add the minimal existing Project file-card fields/actions and frontend tests; otherwise record `STRETCH_FRONTEND=NOT_STARTED`.

**RED/GREEN policy regression**

Before README/structure changes, add a test that fails because Contract namespace/header/order documentation is absent; then make it green with only the required docs/SQL declarations.

Run focused migration policy test, then:

```powershell
cd backend
.\mvnw.cmd clean verify
```

Required regression focus includes current `合同.pdf` candidate behavior, candidate promotion API/state, F02 deterministic suite, OCR fallback, upload/recovery/replay, role failure not mutating upload state, and process-reload rediscovery through repositories/API.

Final read-only checks from worktree root:

```powershell
git diff --check
git status --short
git diff --stat
git diff -- db/dm8/V001__audit_task_baseline.sql db/dm8/V002__local_account_auth_observability.sql db/dm8/V003__f02_contract_review.sql db/dm8/V004__contract_review_session.sql db/dm8/V005__contract_review_session_soft_delete.sql db/dm8/V006__f01_budget_approval.sql db/dm8/V007__f01_material_review.sql db/dm8/V008__f01_material_check_fields.sql db/dm8/V009__review_finding_acknowledgement.sql db/dm8/V010__project_intake_core.sql db/dm8/V011__project_contract_association.sql db/dm8/V012__f01_validation_risk_fields.sql db/dm8/V013__f01_validation_confirm_log.sql db/dm8/V014__f01_project_and_year_validation_config.sql db/dm8/V015__f01_category_rules.sql db/dm8/V016__f04_budget_approval_program.sql db/dm8/V017__project_file_parse_provenance.sql db/dm8/V018__f03_blacklist.sql db/dm8/V019__f03_blacklist_operation_log.sql db/dm8/V020__f01_budget_semantic_analysis.sql db/dm8/V021__f01_ai_provenance.sql db/dm8/V022__f01_ai_node_results.sql
rg -n "PROJECT_MANAGER|V023__|V024__|V025__|DOCUMENT_ROLE|ContractReviewRun|SAME_PROJECT|LIKELY_SAME_PROJECT" backend db/dm8
```

Interpret forbidden-term search manually so approved architecture documentation or negative assertions are not mistaken for production implementation.

## Fixture and verification policy

- Use synthetic parsed-document strings first. Generic fixture filenames must not disclose the correct role.
- Existing repository-owned non-sensitive PDF/DOCX/image fixtures may be used only where parser integration is specifically under test.
- Do not connect to 26/29/customer environments. Do not copy customer originals, real names, amounts, credit codes, IPs, passwords, tokens, or API keys into Git.
- The optional D-drive contract package is read-only reference at most; this implementation does not need it and will not commit any derivative unless it is demonstrably synthetic and non-reidentifiable.
- Final evidence must separate `UNIT/INTEGRATION VERIFIED` from `REAL CUSTOMER MATERIAL NOT VERIFIED`.

## Plan self-review

- Exactly Tasks 1–14 are present and map to the requested slices.
- Each production behavior starts with a focused missing-feature RED and ends with focused GREEN.
- There is no batch-completeness assumption; every file has independent parse/assessment/confirmation identity.
- No second upload/storage system, Redis business truth, AI role route, Project Identity, candidate state change, ReviewRun, async worker, report, debug bundle, or quality-harness implementation is planned.
- New external computation is absent; parser/OCR remains exactly once in the existing upload path and outside the COMPLETE write transaction.
- Immutable parse/result/evidence and append-only role decisions all have repository/API rediscovery paths.
- Candidate and F02 compatibility are explicit acceptance gates.
- No unresolved implementation choice remains inside this authorized slice.
