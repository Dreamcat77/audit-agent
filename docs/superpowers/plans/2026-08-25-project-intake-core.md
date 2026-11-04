# Project Intake Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the contract-bounded Project Intake core: blank projects, secure durable file ingestion, deterministic archive classification, PDF/DOCX parsing and extraction, candidate generation, query APIs, and restart-safe deduplication.

**Architecture:** Project types live below the existing `contract` bounded context (`contract.domain.project`, `contract.application.project`, `contract.api.project`). Bytes continue through the existing `FileStoragePort`; DM8 remains the system of record for identities, states, extraction facts, and candidates. Upload uses a ProjectFile-owned START/external-I/O/COMPLETE-or-FAILURE protocol and never holds `Project.versionNo` as a long lease.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Security session/CSRF, MyBatis/MyBatis-Plus, DM8, existing `FileStoragePort` and filesystem adapter, PDFBox 2.0.30, Apache POI 4.1.2, and—only if the mandatory capability probe requires it—a focused Apache Commons Compress version resolved at execution from the current BOM/dependency tree, Java 17 compatibility, and approved security baseline; JUnit 5, Mockito, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-25-pre-f04-product-alignment-design.md`

## Global Constraints

- Begin only after Plan A is merged to current `main`; require `git branch --show-current` = `main` and an empty `git status --short`.
- Implement the backend/core scope only. Do not create browser application code, formal Contract rows, candidate promotion, or `PROJECT_CONTRACT`.
- `Project != Contract != ReviewSession != AuditTask`; `ProjectFile != ReviewDocument`. Do not reuse `ReviewDocumentKind`, bind files to sessions, or map an archive child to `ReviewDocument.OTHER`.
- Do not modify the invariant or allowed types of `ReviewFileValidator`; raising the Spring multipart transport ceiling must leave the ReviewSession 50MB application limit intact.
- Reuse `FileStoragePort.stage/promote/open/inspect/discard` and `FileSystemStorageAdapter` create-only/root-confinement semantics. Do not add another storage port, overwrite, expose absolute paths, or store bytes in DM8.
- Project parsing must not import or call `budget.application`, `budget.domain`, or `BudgetMaterialParser`. Reusing PDFBox, POI, `Money`, and business-neutral normalization is allowed.
- No OCR, AI, network parser, template validation, workflow engine, queue, `.doc` parser, scanned-PDF parser, recursive nested archive, delete, soft delete, archive command, or manual category correction.
- Security admission and parse support are independent. A safe unsupported file is `AVAILABLE + UNSUPPORTED`; it is not rejected and not reported as parsed.
- Direct upload allowlist is `.zip`, `.pdf`, `.docx`. Archive-child allowlist is `.pdf`, `.docx`, `.doc`, `.xls`, `.xlsx`, `.png`, `.jpg`, `.jpeg`, `.txt`, and an opaque nested `.zip`. Everything else is rejected before business child rows exist.
- Defaults: upload 100MB, extracted entry 50MB, expanded archive 500MB, 200 entries, depth 10, compression ratio 100, normalized relative path 1024 characters. Bind under `audit.project-intake.*` with environment overrides.
- A single `CONTRACT_BODY` ProjectFile creates at most one `ContractCandidate`, enforced by a DM8 unique constraint selected after inspecting the actual schema.
- Business identities `Project.id`, `ProjectFile.id`, `ContractCandidate.id`, and `ExtractionResult.id` are Java UUID/DM8 `VARCHAR(36)`. Actor identities are never converted to UUID: `Project.createdBy`, future `ContractCandidate.promotedBy`, and every `OperationAuditEvent.userId` are Java `String` backed by DM8 `VARCHAR(36)` and `SYS_USER(ID)` foreign keys where persisted in this plan. Metadata confirmation actor ownership exists only in the `PROJECT_METADATA_CONFIRMED` OperationAudit fact; `Project` and `PROJECT` have no `confirmedBy`/`CONFIRMED_BY` field.
- `ExtractionResult.extractionKey` is deterministic SHA-256 over length-delimited canonical `sourceFileId + extractionVersion + factType + nullable groupKey + sourceLocator`; random UUID, time, database order, and object identity never participate.
- DM8 enforces `UNIQUE(EXTRACTION_KEY)`. Replay uses read/insert, and unique-conflict reread/field verification; it never updates or merges a different fact into the same identity.
- Every direct PDF/DOCX or root PACKAGE ZIP has a preallocated ProjectFile UUID and deterministic persisted `StorageKey`: lowercase SHA-256 over length-prefixed UTF-8 `project-file-storage-v1`, ProjectFile ID, and staged SHA-256. Filename/path, `stagingId`, wall clock, random retry data, and idempotency key are excluded. DM8 stores the key with root hash/size/status during START, and recovery opens only that persisted key.
- UNKNOWN is distinct from FAILED, MISSING, and UNSUPPORTED. Storage `UNKNOWN` keeps an operation uncertain/in progress and fails closed.
- `Project.versionNo` is used only for metadata CAS. Upload does not require expected project version and does not advance it.
- Every write uses existing DM8 durable idempotency and operation audit. Redis/TongRDS is not a source, lock, queue, or idempotency store for this slice.
- FlowDebug is technical observability only. It must not contain document text/excerpts, bytes, original exception messages, credentials, cookie/session/CSRF values, idempotency keys, or filesystem paths. `TASK_ID` is absent unless a real `AuditTask.id` exists.
- Durable OperationAudit is separately bounded. It may contain event/module/action, String actor ID/username, semantic Project/ProjectFile/candidate resource IDs, null task ID for this slice, result status, bounded business result/action description, Request ID, Trace ID, stable safe reason code, and safe counts/statuses/categories. Its `detail` must not contain document/extracted/source text, file/ZIP bytes or entry content, absolute path/storage key, idempotency key, Cookie/Session/CSRF/Authorization, credentials/tokens, raw exception messages, or unbounded parser output.
- Transaction verification is layered: unit/structure tests prove coordinator boundary, call order, failure propagation, and DM8 adapter transaction participation; local memory tests prove business/concurrency/idempotency/read-after-write semantics only; atomic database commit/rollback is `VERIFIED` only by representative real-DM8 evidence. Do not add delete/revoke compensation APIs.
- Each task ends in review; fix Critical and Important findings before continuing.
- Allocate migrations with `NEXT_AVAILABLE_VERSION_AT_IMPLEMENTATION_TIME` only; never edit existing migrations or reserve a number in advance.

## Current-Code Reconnaissance Baseline

Re-open these exact current-main locations before implementation:

- Storage port/value types: `backend/src/main/java/com/fuquan/audit/contract/application/port/FileStoragePort.java`, `StagedFile.java`, `StoredFileContent.java`, `StoredFileInspection.java`, and `StorageKey.java` in that same package.
- Filesystem adapter/config: `integration/file/FileSystemStorageAdapter.java`, `FileStorageProperties.java`, and `FileStorageConfiguration.java`.
- Isolation guard: `contract/application/ReviewFileValidator.java` and `ReviewFilePolicy.java`; these remain unchanged.
- Ingestion/recovery reference only: `contract/application/ContractReviewSessionService.java`, `ContractReviewSessionTransactionCoordinator.java`, and `backend/src/test/java/com/fuquan/audit/contract/application/ContractReviewUploadRecoveryTest.java`.
- Durable seams: `common/application/port/IdempotencyPort.java`, `IdempotencyRequest.java`, `OperationAuditPort.java`, and `OperationAuditEvent.java` in that same package; `common/observability/FlowDebugLogger.java`; and `security/application/AuthContext.java`.
- Parsing dependencies: PDFBox use in `budget/application/PdfBudgetMaterialParser.java` is a library example only; POI/PDFBox versions are pinned in `backend/pom.xml`. The new parser must not call that budget class.
- DM8 conventions: current rows, mapper interfaces, adapters, and XML under `backend/src/main/java/com/fuquan/audit/integration/dm8/` and `backend/src/main/resources/mappers/dm8/`.
- HTTP conventions: `contract/api/ContractReviewSessionController.java`, `common/api/ApiResponse.java`, `common/exception/GlobalExceptionHandler.java`, and `security/infra/ApiSecurityConfiguration.java`.
- CORS regression seam: `backend/src/main/java/com/fuquan/audit/security/infra/ApiSecurityConfiguration.java` and `backend/src/test/java/com/fuquan/audit/security/infra/CorsSecurityIntegrationTest.java`; Task 4 adds only `PATCH` to the current allowed-method list and preserves every authentication/session/CSRF/origin rule.
- Transport config: `backend/src/main/resources/application.yml`, `application-local-dev.yml`, `application-dev29.yml`, and `application-customer.yml` as present on latest main.
- Tests: `integration/file/FileSystemStorageAdapterTest.java`, `contract/application/ReviewFileValidatorTest.java`, `contract/application/ContractReviewUploadRecoveryTest.java`, `integration/dm8/F02Dm8SqlStructureTest.java`, and `integration/LocalAdapterWiringTest.java`.

Run before edits:

```powershell
git branch --show-current
git status --short
rg -n "interface FileStoragePort|class FileSystemStorageAdapter|class ReviewFileValidator|class ContractReviewSessionTransactionCoordinator|interface IdempotencyPort|interface OperationAuditPort|class FlowDebugLogger|pdfbox|poi-ooxml|max-file-size|max-request-size" backend/src/main backend/src/test backend/pom.xml
Get-ChildItem db/dm8/V*.sql | Sort-Object Name | Select-Object -ExpandProperty Name
```

Expected: all symbols resolve on `main`. Update this inventory before implementation if a prior merge changed signatures.

---

### Task 0: ZIP runtime/library capability verification with real bytes

**Files:**

- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/archive/ArchiveCapabilityProbe.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/archive/SelectedArchiveCapabilityTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/archive/ArchiveFixtureFactory.java`
- Create: `backend/src/test/resources/archive-fixtures/README.md`
- Create: `backend/src/test/resources/archive-fixtures/encrypted.zip`
- Create: `backend/src/test/resources/archive-fixtures/truncated.zip`
- Create: `backend/src/test/resources/archive-fixtures/crc-mismatch.zip`
- Create: `backend/src/test/resources/archive-fixtures/symlink.zip`
- Create: `backend/src/test/resources/archive-fixtures/non-regular.zip`
- Create: `backend/src/test/resources/archive-fixtures/high-ratio.zip`
- Modify conditionally: `backend/pom.xml` only if the JDK branch cannot prove every critical invariant and the Commons Compress branch passes the full matrix
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/archive/SelectedArchiveCapabilityTest.java`

**Interfaces:**

- Consumes: the actual JDK 17 `java.util.zip` public API, JDK-generated ordinary ZIP bytes plus fixed edge fixtures with provenance/checksums, and—only on the fallback branch—Maven dependency management.
- Produces: a recorded capability matrix for normalized names, compressed/uncompressed accounting, CRC/truncation, encryption, Unix symlink/non-regular metadata, per-entry ratio, and archive-wide limits; one selected reader recorded as `jdk` or an exact approved Commons Compress version.

This task writes no business fact, operation-audit event, FlowDebug event, or idempotency record.

- [ ] Confirm the actual runtime and capture it in the fixture README:

```powershell
cd backend
.\mvnw.cmd -version
```

- [ ] Use the JDK-only `ArchiveFixtureFactory` for ordinary standards-compliant name/count/size/ratio fixtures and fixed checked-in bytes for metadata/corruption/encryption edges the JDK writer cannot express. Record each fixed fixture's independent generator/tool version, benign test-only password where relevant, SHA-256, expected property, and license/provenance in the README. The external fixture producer must not become a production/test dependency and Commons Compress must not be introduced merely to manufacture evidence of a JDK gap. Never hand-write a central-directory binary parser.
- [ ] **CAPABILITY RED:** Implement the test-only JDK probe against the exact real bytes and require affirmative observable proof for every invariant. A missing/unreliable public signal is a failed matrix cell; do not assume either success or failure from API names.

```powershell
cd backend
.\mvnw.cmd -Dtest=SelectedArchiveCapabilityTest -Darchive.reader=jdk test
```

- [ ] Record the observed JDK/vendor/version, command, and every PASS/FAIL/UNPROVABLE cell in `archive-fixtures/README.md`, then follow exactly one branch:
  - **All JDK cells PASS:** record `selected reader = jdk`; keep `backend/pom.xml` unchanged; retain the real-byte matrix in ordinary `clean verify`; Task 6 implements the JDK-backed selected reader.
  - **Any production-critical JDK cell FAIL/UNPROVABLE:** record the exact gap, then inspect `backend/pom.xml`, effective Spring Boot BOM, `dependency:tree`, Java 17 compatibility, and approved security baseline. Evaluate one focused Apache Commons Compress version against the identical bytes and matrix. If BOM management is both present and approved, use it; otherwise pin one explicit approved version and record why. Add it only for the Project ZIP security/parser and never replace `FileStoragePort`, PDFBox, or POI.
- [ ] **CONDITIONAL GREEN:** On the fallback branch, require the Commons reader plus streaming counters to prove every matrix cell using real bytes. Only after the full matrix passes, add that exact version at compile scope and record the dependency reason: focused Project archive metadata/security validation. If any critical cell still fails or remains unprovable, mark Plan B blocked and stop; do not lower limits/invariants, add Tika, select another broad framework, or write a central-directory parser.
- [ ] Configure `SelectedArchiveCapabilityTest` so ordinary verification exercises the single recorded selection. The JDK-success branch runs without Commons on the classpath; the fallback branch retains the JDK gap evidence and runs the selected Commons implementation. There is no predetermined default library.

- [ ] On the fallback branch only, run the focused-reader matrix below; on either successful branch, run clean verification:

```powershell
cd backend
.\mvnw.cmd -Dtest=SelectedArchiveCapabilityTest -Darchive.reader=commons-compress test
.\mvnw.cmd clean verify
```

- [ ] Refactor the fixture loader so every evaluated reader consumes identical byte arrays; keep mocks and synthetic metadata out of capability assertions.
- [ ] On the fallback branch only, run dependency inspection, record the selected Commons Compress version, and confirm it does not replace PDFBox, POI, or storage. On the JDK-success branch, run the same command and record that Commons Compress is absent:

```powershell
cd backend
.\mvnw.cmd dependency:tree -Dincludes=org.apache.commons:commons-compress,org.apache.pdfbox:pdfbox,org.apache.poi:poi-ooxml
```

- [ ] **Review checkpoint:** Require recorded JDK/vendor/version, selected-reader identity/version, fixture provenance/checksums, commands, observed results, a passing real-byte matrix, and conditional POM evidence. Reject a dependency added before a demonstrated JDK gap, hand-written central-directory parsing, mocked metadata, or a lowered invariant. Resolve all Critical/Important findings.

---

### Task 1: Allocate DM8 schema and enforce recovery identities

**Files:**

- Create: `db/dm8/NEXT_AVAILABLE_VERSION_AT_IMPLEMENTATION_TIME__project_intake_core.sql`
- Modify: `db/dm8/README.md`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/ProjectIntakeDm8SqlStructureTest.java`

**Interfaces:**

- Consumes: actual latest DM8 schema, UUID/FK/check conventions, `IDEMPOTENCY_RECORD`, `AUDIT_OPERATION_LOG`.
- Produces: `PROJECT`, `PROJECT_FILE`, `PROJECT_EXTRACTION_RESULT`, `CONTRACT_CANDIDATE`, `CONTRACT_CANDIDATE_LINE_ITEM` and all required keys/checks/indexes, including a persisted unique ProjectFile storage identity and database-enforced same-Project ownership for every cross-table file/candidate reference.

**Write-accountability matrix:**

| Command | BUSINESS FACT | OPERATION AUDIT EVENT | FLOWDEBUG EVENT | IDEMPOTENCY FACT |
| --- | --- | --- | --- | --- |
| blank create | `PROJECT` | `PROJECT_CREATED` | `PROJECT_CREATE_START/COMPLETE` | `PROJECT_CREATE` |
| metadata confirmation | updated `PROJECT` by CAS | `PROJECT_METADATA_CONFIRMED` | `PROJECT_METADATA_UPDATE_START/COMPLETE/CONFLICT` | `PROJECT_METADATA_CONFIRM` |
| upload START | root `PROJECT_FILE(UPLOAD_PENDING)` | `PROJECT_FILE_UPLOAD_STARTED` | `PROJECT_UPLOAD_START_TX_ENTER/COMMIT` | `PROJECT_FILE_UPLOAD` claim/result resource |
| upload COMPLETE | root/children, extraction rows, candidates | `PROJECT_FILE_UPLOAD_COMPLETED` | `PROJECT_UPLOAD_COMPLETE_TX_ENTER/COMMIT` | `PROJECT_FILE_UPLOAD` success |
| upload FAILURE | root `PROJECT_FILE(UPLOAD_FAILED)` | `PROJECT_FILE_UPLOAD_FAILED` | `PROJECT_UPLOAD_FAILURE_TX_ENTER/COMMIT` | `PROJECT_FILE_UPLOAD` failure |

- [ ] Re-run migration allocation and inspect whether `PROJECT_FILE.ID` is globally unique:

```powershell
Get-ChildItem db/dm8/V*.sql | Sort-Object Name | Select-Object -ExpandProperty Name
rg -n "PRIMARY KEY|UNIQUE|PROJECT_FILE|VARCHAR\(36\)" db/dm8/V*.sql
```

Because the planned `PROJECT_FILE.ID` is a global primary key, retain the smaller candidate deduplication identity `UNIQUE(SOURCE_CONTRACT_FILE_ID)`. Independently add composite ownership keys/FKs below; global UUID identity does not prove that a supplied Project ID belongs to the referenced row.
- [ ] **RED:** Assert nullable blank-project fields, non-negative project/file versions, same-project composite parent FK, unique child normalized path within one parent, state/category checks, `DECIMAL(18,2)` amount columns, candidate source-file uniqueness, and `UNIQUE(EXTRACTION_KEY)`. Assert `PROJECT_FILE.STORAGE_KEY VARCHAR(64) NOT NULL` with a database UNIQUE constraint plus persisted SHA-256/size, `CREATED_AT`, and `UPDATED_AT`. Assert `PROJECT.CREATED_BY` and nullable `CONTRACT_CANDIDATE.PROMOTED_BY` are `VARCHAR(36)` user FKs while Project/file/candidate/extraction IDs remain UUID-shaped `VARCHAR(36)` business identities; assert `PROJECT.CONFIRMED_BY` does not exist.
- [ ] Assert exact ownership keys and references: `UNIQUE PROJECT_FILE(PROJECT_ID, ID)`; `UNIQUE CONTRACT_CANDIDATE(PROJECT_ID, ID)`; candidate `(PROJECT_ID, SOURCE_CONTRACT_FILE_ID) -> PROJECT_FILE(PROJECT_ID, ID)`; extraction `(PROJECT_ID, SOURCE_FILE_ID) -> PROJECT_FILE(PROJECT_ID, ID)`; and nullable extraction `(PROJECT_ID, CANDIDATE_ID) -> CONTRACT_CANDIDATE(PROJECT_ID, ID)`. Retain each table's separate PK and the global candidate-source/extraction-key UNIQUE constraints.
- [ ] Assert there is no `PROJECT_CONTRACT`, no `PROJECT_ID` added to `CONTRACT`, no BLOB file storage, no delete marker, and no mutation of historical SQL.
- [ ] Run and confirm the correct failure is the missing new migration:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectIntakeDm8SqlStructureTest test
```

- [ ] **GREEN:** Create additive DDL with the exact composite ownership keys/FKs from RED, the required root/child storage identity and timestamps, explicit in-scope String-actor `VARCHAR(36)` columns/FKs, stable timestamps, state checks, nullable `CANDIDATE_ID`, and required `SOURCE_LOCATOR`/`EXTRACTION_VERSION`. Do not add metadata confirmer state or rely only on application UUID comparisons.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor only SQL test helpers while preserving exact identity checks.
- [ ] Run DM8 structural regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectIntakeDm8SqlStructureTest,F02Dm8SqlStructureTest test
```

- [ ] **Review checkpoint:** Validate allocation, ProjectFile storage identity, candidate identity choice, extraction unique identity, every same-project composite relation, the scoped String actor boundary with no `PROJECT.CONFIRMED_BY`, and explicit absence of formal association/write schema. Leave nullable composite-FK behavior `NOT VERIFIED` until the real-DM8 smoke in Task 9 proves it. Resolve all Critical/Important findings.

---

### Task 2: Add Project domain types, policy, ports, and memory adapters

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/Project.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectFile.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectFileCategory.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectFileIngestionStatus.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectFileParseStatus.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ExtractionResult.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ContractCandidate.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ContractCandidateLineItem.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/CandidatePromotionState.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFilePolicy.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectIntakeProperties.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileStorageKeyFactory.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ExtractionKeyFactory.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ProjectRepository.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ProjectFileRepository.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ExtractionResultRepository.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ContractCandidateRepository.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/infra/memory/project/InMemoryProjectRepositories.java`
- Modify: None; this task introduces an isolated domain/port slice.
- Test: `backend/src/test/java/com/fuquan/audit/contract/domain/project/ProjectDomainTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFilePolicyTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileStorageKeyFactoryTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ExtractionKeyFactoryTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/ProjectParserDependencyTest.java`

**Interfaces:**

- Consumes: `BigDecimal`, `Money`, UUID business identities, String actor identities, immutable time types, typed configuration values.
- Produces: exact aggregate/value types; repository create/list/CAS/insert-if-absent operations; policy decisions `DIRECT_UPLOAD_ALLOWED`, `ARCHIVE_CHILD_ALLOWED`, parse support, and pending-file recovery age; deterministic root storage and extraction keys; the ownership-safe read contract consumed by candidate APIs and Plan C.

Exact read surfaces produced here (do not rename or add mapper backdoors later):

- `ProjectFileRepository.findByProjectIdAndId(UUID projectId, UUID id): Optional<ProjectFile>`, `findByProjectIdAndParentArchiveFileIdAndRelativePath(UUID projectId, UUID parentArchiveFileId, String normalizedRelativePath): Optional<ProjectFile>`, and deterministic `findByProjectId(UUID projectId): List<ProjectFile>`.
- `ContractCandidateRepository.findByProjectIdAndId(UUID projectId, UUID id): Optional<ContractCandidate>`, `findByProjectId(UUID projectId): List<ContractCandidate>`, and `findBySourceContractFileId(UUID projectId, UUID sourceFileId): Optional<ContractCandidate>`.
- `ExtractionResultRepository.findByExtractionKey(String extractionKey): Optional<ExtractionResult>`, `findBySourceFileId(UUID projectId, UUID sourceFileId): List<ExtractionResult>`, `findByCandidateId(UUID projectId, UUID candidateId): List<ExtractionResult>`, and project-level fact query `findByProjectId(UUID projectId): List<ExtractionResult>`.

Every list uses explicit deterministic ordering (`sourceFileId`, nullable `candidateId`, `factType`, nullable `groupKey`, `sourceLocator`, then result ID as applicable), applies Project ownership in the repository query, performs no fuzzy source inference, and has no dependency on formal `Contract`.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | domain types for `PROJECT`, `PROJECT_FILE`, `PROJECT_EXTRACTION_RESULT`, `CONTRACT_CANDIDATE`, candidate lines |
| OPERATION AUDIT EVENT | emitted by application transactions, never domain/repository constructors |
| FLOWDEBUG EVENT | emitted by application/storage/parser coordinators, never used as state |
| IDEMPOTENCY FACT | `PROJECT_CREATE`, `PROJECT_METADATA_CONFIRM`, `PROJECT_FILE_UPLOAD` consumed by later tasks |

- [ ] **RED:** Test blank nullable metadata, `Project.createdBy: String`, server time, metadata CAS, absence of `Project.confirmedBy`, no aggregate upload lease, complete category/status enums, BigDecimal-only money, candidate `DRAFT -> PROMOTED` one-way state seam with nullable `promotedBy: String` but without implementing promotion, and immutable extraction identity fields. Assert Project/file/candidate/extraction IDs remain UUID.
- [ ] Test `ProjectFileStorageKeyFactory` fixed vectors. Its exact canonical byte sequence is `LP("project-file-storage-v1") || LP(projectFileId.toString()) || LP(stagedSha256)`, where `LP` is a four-byte big-endian unsigned UTF-8 byte length followed by those bytes. Assert one preallocated ID + one staged hash produces the same lowercase 64-hex `StorageKey` across instances/restarts; changing the ID or hash changes the key; filename/path, `stagingId`, clock, and random UUID are not inputs; invalid hashes and any output violating the existing `StorageKey` invariant fail closed.
- [ ] Test `ProjectFile` persists `storageKey`, SHA-256, size, `createdAt`, and `updatedAt`; its pending stale decision uses `updatedAt` (falling back to `createdAt` only if the model deliberately permits a missing legacy update timestamp) plus a strictly positive `ProjectFilePolicy.pendingRecoveryAge` wired from the existing `FileStorageProperties.pendingRecoveryAge`, not a scheduler or Project-version lease.
- [ ] Test extraction-key fixed vectors with null/non-null group keys, length boundaries, Unicode, repeated values at different occurrence locators, and prove UUID/time/candidate ID do not affect the key.
- [ ] Test policy extension + MIME + magic/container decisions, direct/archive-child differences, nested archive opaque handling, parser size limit, and executable/script/JAR/shortcut rejection.
- [ ] Strengthen `ProjectParserDependencyTest` to scan `contract.application.project` and reject budget imports/calls. Add a regression that `ReviewFileValidator` still accepts/rejects its original PDF/PNG/JPEG set and 50MB limit.
- [ ] Run and confirm missing types fail:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectDomainTest,ProjectFilePolicyTest,ProjectFileStorageKeyFactoryTest,ExtractionKeyFactoryTest,ProjectParserDependencyTest,ReviewFileValidatorTest test
```

- [ ] **GREEN:** Implement minimum validated records/enums, the exact root storage-key encoding above, extraction length-delimited canonical encoding with explicit null token, SHA-256 lowercase hex, narrow ports, atomic memory inserts, and typed configuration defaults. Reuse the existing `StorageKey` value type and the existing file-storage pending recovery-age setting; do not create a second storage port or use the upload fingerprint as a storage key.
- [ ] Keep repository commands insert-only for extraction/candidate identity facts; implement every exact read method above for conflict-read-verify recovery and downstream ownership-safe projection. Memory adapters must mirror the same Project predicates and deterministic ordering.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor shared validation into domain-owned helpers without creating a top-level module or generic parsing framework.
- [ ] Run targeted domain/isolation regression:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectDomainTest,ProjectFilePolicyTest,ProjectFileStorageKeyFactoryTest,ExtractionKeyFactoryTest,ProjectParserDependencyTest,ReviewFileValidatorTest test
```
- [ ] **Review checkpoint:** Verify business UUID versus String actor types, no metadata confirmer field, every exact B-to-C read signature including parent/path recovery lookup, deterministic ordering, ownership predicates, money types, root-storage/extraction deterministic identity inputs, policy separation, module placement, and absence of ReviewDocument/Contract coupling. Resolve all Critical/Important findings.

---

### Task 3: Implement DM8 repositories and deterministic conflict verification

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectMapper.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectFileMapper.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectExtractionResultMapper.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateMapper.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectRepositories.java`
- Create: `backend/src/main/resources/mappers/dm8/ProjectMapper.xml`
- Create: `backend/src/main/resources/mappers/dm8/ProjectFileMapper.xml`
- Create: `backend/src/main/resources/mappers/dm8/ProjectExtractionResultMapper.xml`
- Create: `backend/src/main/resources/mappers/dm8/ContractCandidateMapper.xml`
- Modify: None; current DM8 adapters remain untouched.
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ProjectRepositoriesTest.java`

**Interfaces:**

- Consumes: Task 2 repository ports and Task 1 schema.
- Produces: DM8 insert/read/page/CAS methods implementing every exact Task 2 read signature, including the Project-scoped parent/path recovery lookup; persisted ProjectFile storage key/hash/size/timestamps; candidate source identity and extraction-key conflict-read-verify behavior; stable `(CREATED_AT DESC, ID DESC)` project paging and deterministic file/candidate/extraction ordering.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | mapper inserts/CAS for Task 1 tables |
| OPERATION AUDIT EVENT | application coordinators append the exact events; mappers do not |
| FLOWDEBUG EVENT | safe repository read/write events under `PROJECT_*` operations |
| IDEMPOTENCY FACT | existing DM8 idempotency adapter remains the only durable command identity store |

- [ ] **RED:** Mock each mapper and test exact row/domain mapping, String creator/promoter actor fields versus UUID business IDs, absence of a metadata confirmer field, ProjectFile storage key/hash/size/timestamps, null fields, BigDecimal scale-independent equality, page tie-breaker, CAS zero-row conflict, and Project ownership filtering for every exact Task 2 read method.
- [ ] Test candidate insert unique conflict: reread by source file, return existing only when every immutable identity/fact matches, otherwise raise integrity conflict. Repeat for extraction-key insert and all identity inputs/value facts.
- [ ] Add XML assertions for explicit deterministic `ORDER BY`, no `MERGE`, no overwrite update for candidate/extraction facts, and Project predicates on file-by-id, file-by-parent/path, candidate-by-id/source/list, and extraction-by-source/candidate/project queries. Reassert the Task 1 composite keys/FKs and storage-key UNIQUE so a mapper cannot compensate for missing database enforcement.
- [ ] Run and confirm missing adapters/statements fail:

```powershell
cd backend
.\mvnw.cmd -Dtest=Dm8ProjectRepositoriesTest,ProjectIntakeDm8SqlStructureTest test
```

- [ ] **GREEN:** Implement explicit mapper statements and conversion methods. Use database uniqueness as final arbitration; application pre-check is an optimization only.
- [ ] Distinguish proven unique conflicts from connection/timeout/unknown failures. Unknown database state must propagate as unknown/integrity failure, never as successful replay.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor persistence rows only where constructor-order mistakes remain test-covered.
- [ ] Run DM8 adapter regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=Dm8ProjectRepositoriesTest,ProjectIntakeDm8SqlStructureTest,Dm8IdempotencyAdapterTest,Dm8AuditCheckResultRepositoryTest test
```

- [ ] **Review checkpoint:** Inspect every SQL statement, unique-conflict branch, stable ordering, ownership predicate, and unknown-state behavior. Resolve all Critical/Important findings.

---

### Task 4: Implement blank create, list/detail, and metadata confirmation APIs

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectApplicationService.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectCommandTransactionCoordinator.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Modify: `backend/src/main/java/com/fuquan/audit/security/infra/ApiSecurityConfiguration.java`
- Modify: `backend/src/test/java/com/fuquan/audit/security/infra/CorsSecurityIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectApplicationServiceTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectCommandTransactionStructureTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectApiIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/security/infra/CorsSecurityIntegrationTest.java`

**Interfaces:**

- Consumes: Project repository, `IdempotencyPort`, `OperationAuditPort`, `FlowDebugLogger`, `AuthContext.currentUser().userId: String`, `Clock`, Request/Trace IDs, `ApiResponse`.
- Produces: `POST /api/v1/projects` with no required body; `GET /api/v1/projects?page&size&keyword`; `GET /api/v1/projects/{projectId}`; `PATCH /api/v1/projects/{projectId}` with `expectedVersion` and nullable `name`, `projectNo`, `category`.

**Write-accountability matrix:**

| Command | BUSINESS FACT | OPERATION AUDIT EVENT | FLOWDEBUG EVENT | IDEMPOTENCY FACT |
| --- | --- | --- | --- | --- |
| POST blank | new `PROJECT` | `PROJECT_CREATED` | `PROJECT_CREATE_START/COMPLETE` | `PROJECT_CREATE` |
| PATCH metadata | CAS update of `PROJECT` | `PROJECT_METADATA_CONFIRMED` | `PROJECT_METADATA_UPDATE_START/COMPLETE/CONFLICT` | `PROJECT_METADATA_CONFIRM` |
| GETs | none | none | safe read diagnostics only | none |

- [ ] **RED:** Test blank creation with exact String `createdBy`, UUID Project identity, same-key replay, fingerprint conflict, list stability, detail derived counts, nullable fields, PATCH CAS success/stale 409, safe 404, and no business-number uniqueness assumption. Assert the metadata command/audit obtains the actor from `AuthContext`, but neither the Project domain nor API/DM8 payload has `confirmedBy`.
- [ ] Capture create/metadata `OperationAuditEvent` separately from FlowDebug. Assert String `userId`, semantic Project resource ID, null task ID, bounded status/count/reason metadata, and absence of metadata payload values, document/source text, path/storage key, idempotency key, security headers/tokens/credentials, raw exception message, or unbounded detail.
- [ ] In transaction-structure tests prove proxy boundary, call order, failure propagation, and DM8 adapter participation. Do not assert a local in-memory map was rolled back by Spring; local tests prove semantics/read-after-write only, while real atomic audit/idempotency rollback remains a Task 9 DM8 smoke claim.
- [ ] In the exact current `CorsSecurityIntegrationTest`, add a trusted-origin PATCH preflight asserting `PATCH` is allowed, `X-CSRF-TOKEN` and `Idempotency-Key` remain allowed headers, credentials remain enabled for configured origins, and wildcard configuration still fails closed. Add an actual trusted-origin PATCH without authentication assertion proving preflight admission did not make the endpoint public. In `ProjectApiIntegrationTest`, keep authenticated session + CSRF success and missing-auth/missing-CSRF 401/403 cases. Do not change authentication, authorization, session, CSRF repositories/handlers, allowed origins, credential policy, or any security matcher.
- [ ] Test page bounds, common response envelope, Request ID, and absence of delete routes.
- [ ] Run and confirm missing service/controller failures:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectApplicationServiceTest,ProjectCommandTransactionStructureTest,ProjectApiIntegrationTest,CorsSecurityIntegrationTest test
```

- [ ] **GREEN:** Implement short transactional commands and read-only queries. Fingerprints bind actor plus normalized command fields; successful replay returns the same project ID/version.
- [ ] In `ApiSecurityConfiguration.corsConfigurationSource`, change only the current allowed-method list from `GET, POST, PUT, DELETE, OPTIONS` to `GET, POST, PUT, PATCH, DELETE, OPTIONS`. Preserve the current allowed headers (`Content-Type`, `Idempotency-Key`, `X-CSRF-TOKEN`, `X-Request-Id`), `allowCredentials=true` behavior for configured origins, and wildcard rejection.
- [ ] Derive detail status/counts from files/candidates: no available file = awaiting upload; any available = uploaded; any parseable parsed = parsed; candidate count > 0 = candidates available; unsupported/failed counts remain separate.
- [ ] Use existing error envelope and role set. Do not add delete, category correction, approval, or overall status storage.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor response mapping into project API records while preserving nullable fields.
- [ ] Run targeted API/audit regression:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectApplicationServiceTest,ProjectCommandTransactionStructureTest,ProjectApiIntegrationTest,CorsSecurityIntegrationTest,TraceContextIntegrationTest,GlobalExceptionHandlerTest test
```

- [ ] **Review checkpoint:** Verify blank semantics, no `Project.confirmedBy`, String actor/UUID business identity boundary, CAS boundary, paging, idempotency fingerprint, the one-method CORS delta, unchanged authentication/session/CSRF/origin/credential behavior, bounded OperationAudit versus FlowDebug, correctly scoped transaction evidence, and authorization. Resolve all Critical/Important findings.

---

### Task 5: Implement PDF/DOCX parsing, DOCX container guard, extraction, and candidate assembly

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ProjectDocumentParser.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ProjectPdfTextParser.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ProjectDocxTextParser.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ProjectDocxContainerGuard.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/parse/ParsedProjectDocument.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/extraction/ProjectFactExtractor.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/extraction/ContractCandidateAssembler.java`
- Create: `backend/src/test/resources/docx-fixtures/README.md`
- Create: `backend/src/test/resources/docx-fixtures/entry-count-limit.docx`
- Create: `backend/src/test/resources/docx-fixtures/expanded-bytes-limit.docx`
- Create: `backend/src/test/resources/docx-fixtures/compression-ratio-limit.docx`
- Create: `backend/src/test/resources/docx-fixtures/encrypted.docx`
- Create: `backend/src/test/resources/docx-fixtures/truncated.docx`
- Create: `backend/src/test/resources/docx-fixtures/non-regular-entry.docx`
- Modify: None; Task 7 consumes these completed interfaces, so this task does not depend on a future ingestion service.
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/parse/ProjectPdfTextParserTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/parse/ProjectDocxTextParserTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/parse/ProjectDocxContainerGuardTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/extraction/ProjectFactExtractorTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/extraction/ContractCandidateAssemblerTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/ProjectParserDependencyTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/budget/application/ExcelBudgetMaterialParserTest.java`

**Interfaces:**

- Consumes: Task 0's final selected archive reader/version, Task 2 limits and deterministic identity policy, authenticated stored-file streams supplied by a later caller, PDFBox/POI, deterministic traversal positions, `Money`/BigDecimal, Chinese uppercase parser, and ProjectFile category.
- Produces: `ProjectDocumentParser.parse(ProjectFile, InputStream): ParsedProjectDocument`; guarded PDF/DOCX parsers; parsed page/block segments; deterministic `ExtractionResult` facts; `ContractCandidateAssembler.assemble(UUID projectId, ProjectFile sourceFile, List<ExtractionResult>): ContractCandidate`; at most one candidate per `CONTRACT_BODY` source file. These are real production capabilities ready for Task 7—there is no temporary parser.

**Write-accountability matrix:**

| Phase | BUSINESS FACT | OPERATION AUDIT EVENT | FLOWDEBUG EVENT | IDEMPOTENCY FACT |
| --- | --- | --- | --- | --- |
| parse/extract outside transaction | immutable in-memory outcome only | none | `PROJECT_PARSE_START/COMPLETE/UNSUPPORTED/FAILED`, safe counts/reason codes | later Task 7 upload remains in progress |
| persistence | none in this task | none | none | none |

- [ ] **RED:** Test PDF page numbers, DOCX paragraph/table traversal locators, empty/scanned PDF UNSUPPORTED, `.doc`/oversized parseable file/other formats UNSUPPORTED, corrupt PDF/DOCX FAILED with stable reason code, and no network access.
- [ ] Drive every real adversarial DOCX fixture through `ProjectDocxContainerGuard` before constructing `XWPFDocument`. Prove rejection for internal entry count, total expanded bytes, per-entry/container compression ratio, encrypted/unsupported archive state, malformed/truncated container, non-regular/suspicious metadata, and configured size limits. Assert rejected bytes never reach an XWPF parser seam and return bounded stable parse reason codes.
- [ ] Generate/document fixtures with the Task 0 archive library/tool and record checksums; tests inspect real bytes and actual library metadata, never mocked entries. Reuse Task 0's final reader/version rather than a second ZIP implementation. Source-scan production/tests to prohibit a hand-written central-directory parser.
- [ ] Assert Project code does not call `ZipSecureFile.setMinInflateRatio`, `setMaxEntrySize`, or `setMaxTextSize`. The independent guard must not mutate JVM-global POI settings; run `ExcelBudgetMaterialParserTest` to prove existing F01 XLSX behavior remains unchanged.
- [ ] Test exact supported fact types from the Spec, label/header/adjacent-text-only extraction, BigDecimal money, uppercase check, nullable missing facts, preservation of conflicting normalized values, bounded source excerpts, and no invented empty/default values.
- [ ] Test extraction keys bind source file/version/fact/group/locator, source locators include deterministic occurrence ordinals, candidate ID is excluded, traversal returns identical keys after replay, and candidate assembly consumes the ownership-safe Task 2 ports without fuzzy source matching.
- [ ] Test each successful CONTRACT_BODY source yields one candidate; two source files yield two candidates; supporting facts remain project-level when association cannot be proven; candidate detail uses `findByProjectIdAndId`, `findBySourceContractFileId`, `findByCandidateId`, and `findByProjectId` without mapper access.
- [ ] Run and confirm missing guard/parsers/extractor/assembler failures:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectPdfTextParserTest,ProjectDocxTextParserTest,ProjectDocxContainerGuardTest,ProjectFactExtractorTest,ContractCandidateAssemblerTest,ProjectParserDependencyTest,ExcelBudgetMaterialParserTest test
```

- [ ] **GREEN:** Implement Project-owned parsers directly with PDFBox and POI. `ProjectDocxTextParser` must call the independent streaming container guard first and construct `XWPFDocument` only for an accepted result. Persist real extracted text in the parsed outcome; never fabricate preview text.
- [ ] Implement the guard using Task 0's selected archive reader and streaming counters. Keep it scoped to Project DOCX/ZIP security, preserve POI global settings, and return stable reason codes without raw library exceptions.
- [ ] Assemble nullable/conflicting candidates without creating formal Contracts. Expose deterministic outcome objects that Task 7 can insert through Task 2 repositories; parser unsupported/failure remains distinct from storage/archive failure.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor common parser segment types without importing budget code or adding a broad parser dependency.
- [ ] Run parser/container/domain regression:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectPdfTextParserTest,ProjectDocxTextParserTest,ProjectDocxContainerGuardTest,ProjectFactExtractorTest,ContractCandidateAssemblerTest,ProjectParserDependencyTest,ReviewFileValidatorTest,ExcelBudgetMaterialParserTest test
```

- [ ] **Review checkpoint:** Verify independent parser module, DOCX guard before XWPF, real-byte adversarial coverage, no POI global mutation/F01 regression, deterministic locators/keys, fact non-fabrication, amount types, bounded sources, exact B-to-C ports, and unsupported-vs-failed semantics. Resolve all Critical/Important findings.

---

### Task 6: Implement secure archive scan and deterministic classification

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/archive/ProjectArchiveReader.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/archive/SelectedProjectArchiveReader.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/archive/ProjectArchiveStorageKeyFactory.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileClassifier.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectArchiveProcessingService.java`
- Modify: None; Task 7 consumes the staged archive outcome, so this task compiles without a future ingestion service.
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/archive/ProjectArchiveSecurityTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileClassifierTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectArchiveStagingAtomicityTest.java`

**Interfaces:**

- Consumes: the single reader/API branch selected and recorded by Task 0, policy/config, PACKAGE storage stream, `FileStoragePort.stage/promote/discard`.
- Produces: fully validated staged child descriptors; deterministic SHA-256 storage keys bound to root ProjectFile ID + normalized child path + child hash; normalized NFC paths; fixed-version classifications; no child business fact until the entire archive passes.

**Write-accountability matrix:**

| Phase | BUSINESS FACT | OPERATION AUDIT EVENT | FLOWDEBUG EVENT | IDEMPOTENCY FACT |
| --- | --- | --- | --- | --- |
| scan/stage | no child rows yet | none | `PROJECT_ARCHIVE_SCAN_START/COMPLETE/REJECTED` | existing `PROJECT_FILE_UPLOAD` remains in progress |
| accepted handoff | immutable staged-child outcome only; Task 7 later creates child facts | none | `PROJECT_ARCHIVE_SCAN_COMPLETE` | later Task 7 upload remains in progress |
| rejection handoff | bounded rejection outcome only; Task 7 later closes upload failure | none | `PROJECT_ARCHIVE_SCAN_REJECTED` | later Task 7 upload remains in progress |

- [ ] **RED:** Drive real library bytes for traversal, absolute/drive/UNC paths, NUL/control segments, empty/dot/dot-dot, Unicode NFC/case-fold collisions, duplicate normalized paths, depth/path/count limits, single/total expanded bytes, live per-entry ratio, CRC mismatch, truncation, encryption, symlink, and non-regular mode.
- [ ] Test a late malicious entry returns no accepted child descriptors and discards every unpromoted staging handle. This service has no repository dependency, so it cannot create partial database facts. Test entry bytes are never materialized using archive paths.
- [ ] Test the same root file/path/content yields the same child storage key after process restart, while a different root/path/hash does not. This gives already-promoted child bytes a rediscovery target after a crash without exposing a filesystem path.
- [ ] Test classification priority/version: top package; award notice; bid/response; tender/procurement; contract/agreement; supplemental/attachment/other; unknown. Higher priority wins independent of entry iteration order.
- [ ] Test nested ZIP is safe opaque OTHER/UNKNOWN + UNSUPPORTED and is never opened recursively; disallowed executable/script/JAR/shortcut/special entries reject the whole package.
- [ ] Run and confirm missing reader/classifier failures:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectArchiveSecurityTest,ProjectFileClassifierTest,ProjectArchiveStagingAtomicityTest test
```

- [ ] **GREEN:** Implement `SelectedProjectArchiveReader` with exactly the branch recorded by Task 0: JDK public API when and only when all JDK cells passed, otherwise the one fully evidenced Commons Compress version. Normalize separators/NFC, use `Locale.ROOT` collision keys, count every entry, and enforce byte/ratio limits while reading rather than trusting headers.
- [ ] Stage child bytes during scan; promote only after the entire package validates. Address each child with the deterministic storage key and pass immutable child descriptors to COMPLETE; do not write child rows from the scanner.
- [ ] Emit only entry counts, sizes, durations, category counts, and stable safe reason codes to FlowDebug.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor limit counters and path normalization into package-private pure types, retaining real-reader end-to-end tests.
- [ ] Run archive/storage regression:

```powershell
cd backend
.\mvnw.cmd -Dtest=SelectedArchiveCapabilityTest,ProjectArchiveSecurityTest,ProjectFileClassifierTest,ProjectArchiveStagingAtomicityTest,FileSystemStorageAdapterTest test
```

- [ ] **Review checkpoint:** Reconcile every security invariant with a real-byte assertion and the Task 0 branch record; verify the production reader matches that record, any dependency is focused/versioned/reasoned, and there is no home-grown central-directory parser, mock metadata, partial child fact, or recursive archive. Resolve all Critical/Important findings.

---

### Task 7: Integrate parser/archive capabilities into ProjectFile ingestion and authenticated reads

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionService.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionTransactionCoordinator.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-local-dev.yml`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionServiceTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionTransactionStructureTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectFileApiIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectMultipartConfigurationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/ReviewFileValidatorTest.java`

**Interfaces:**

- Consumes: `MultipartFile` stream, Task 2 policy/ownership-safe repositories and `ProjectFileStorageKeyFactory`, existing `FileStoragePort`, Task 5's real `ProjectDocumentParser`/extractor/candidate assembler, Task 6's real `ProjectArchiveProcessingService`, durable idempotency/audit, and `StoredFileInspection` tri-state.
- Produces: `POST /api/v1/projects/{projectId}/files`, deterministic stage -> root-ID/key allocation -> START -> promote -> Task 5/6 processing -> COMPLETE/FAILURE orchestration, `GET /api/v1/projects/{projectId}/files`, ownership-safe candidate detail, and authenticated `GET /api/v1/projects/{projectId}/files/{fileId}/content` with safe disposition. No production fake parser or temporary UNSUPPORTED path exists.

**Write-accountability matrix:**

| Phase | BUSINESS FACT | OPERATION AUDIT EVENT | FLOWDEBUG EVENT | IDEMPOTENCY FACT |
| --- | --- | --- | --- | --- |
| START | root `PROJECT_FILE(UPLOAD_PENDING)` with ID/storage key/hash/size/timestamps | `PROJECT_FILE_UPLOAD_STARTED` | `PROJECT_UPLOAD_STAGE_*`, `PROJECT_UPLOAD_START_TX_ENTER/COMMIT` | claim `PROJECT_FILE_UPLOAD`, result resource fixed to the same root file ID in that transaction |
| processing outside tx | immutable parser/archive outcome | none | Task 5/6 safe parse/archive events | same record remains in progress |
| COMPLETE | root/children `AVAILABLE`, parse states, extraction rows, candidates | `PROJECT_FILE_UPLOAD_COMPLETED` | `PROJECT_UPLOAD_COMPLETE_TX_ENTER/COMMIT` | same record succeeded |
| FAILURE | root `UPLOAD_FAILED` | `PROJECT_FILE_UPLOAD_FAILED` | `PROJECT_UPLOAD_FAILURE_TX_ENTER/COMMIT` | same record failed |
| GET/content | none | none | safe storage read/inspect only | none |

- [ ] **RED:** Test stage before START; preallocate the root ProjectFile UUID after stage metadata is known; derive the Task 2 key from that ID + staged SHA-256; atomically persist root ID, storage key, SHA-256, size, `UPLOAD_PENDING`, timestamps, idempotency claim, and result-resource reference in START; then call `storage.promote(staged, new StorageKey(persistedProjectFile.storageKey()))` outside the transaction. Assert Task 5 parser selection for direct PDF/DOCX and Task 6 archive processing for root PACKAGE ZIP, completion/failure short transactions, same-key replay, changed fingerprint, outcome-unknown storage states, no expected Project version, and two independent uploads.
- [ ] Prove direct text PDF/DOCX reaches the real parser and cannot be temporarily marked UNSUPPORTED; guarded DOCX rejection maps to its stable parse reason; accepted archive children are parsed/classified before COMPLETE. A late archive rejection writes no child/extraction/candidate facts.
- [ ] Test only direct ZIP/PDF/DOCX admission, 100MB app limit, MIME/magic mismatch 400, size 413, filename sanitization, safe attachment default, PDF inline only when verified, `findByProjectIdAndId` ownership-safe 404, and no filesystem path response.
- [ ] Test candidate detail obtains candidate/source facts only via `ContractCandidateRepository.findByProjectIdAndId`, `ProjectFileRepository.findByProjectIdAndId`, and the exact extraction read ports. Prove deterministic ordering, cross-Project denial, no mapper backdoor, and no formal Contract dependency.
- [ ] Capture upload OperationAudit independently from FlowDebug (Task 4 already covers create/metadata). Allow only String actor, semantic Project/ProjectFile IDs, null task ID, bounded status/count/category/reason; prohibit document/extraction/source text, entry content, paths/storage keys, idempotency key, security headers/tokens/credentials, raw exceptions, or unbounded detail.
- [ ] The current `application.yml` reuses `AUDIT_UPLOAD_MAX_FILE_SIZE` for Spring multipart and `audit.file-storage.max-file-size`. Add a configuration regression, then split transport to `AUDIT_MULTIPART_MAX_FILE_SIZE` (default 100MB) and `AUDIT_MULTIPART_MAX_REQUEST_SIZE` (default at least 102MB). Keep `audit.file-storage.max-file-size=${AUDIT_UPLOAD_MAX_FILE_SIZE:50MB}` unchanged so ReviewSession stays 50MB. Update only the `local-dev` multipart override; `dev29`/`customer` inherit environment-driven base values.
- [ ] Run and confirm missing ingestion/controller integration:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectFileIngestionServiceTest,ProjectFileIngestionTransactionStructureTest,ProjectFileApiIntegrationTest,ProjectMultipartConfigurationTest,ProjectPdfTextParserTest,ProjectDocxContainerGuardTest,ProjectArchiveSecurityTest,ReviewFileValidatorTest test
```

- [ ] **GREEN:** Implement stage -> preallocated root ID/deterministic key -> atomic START -> promote to the exact persisted key -> real parser/archive delegation -> COMPLETE, with `REQUIRES_NEW` failure closure for ordinary failure. Treat inspection `UNKNOWN` as uncertain/in-progress and never as missing/success. Use repository read/insert/verify semantics from Task 3 during COMPLETE.
- [ ] Mark parser UNSUPPORTED/FAILED as durable upload success with `AVAILABLE`; only archive/storage/integrity failures fail the upload. Bind the idempotency fingerprint to String actor, Project UUID, sanitized filename, normalized MIME, staged hash, and size; keep that client operation key immutable across retry. It is distinct from the deterministic storage key, which is persisted once and is the only key later recovery may use.
- [ ] Stream content from `FileStoragePort.open`; set `nosniff`, safe content type, sanitized filename, and attachment unless the verified inline rule applies.
- [ ] Treat transaction-structure tests as proxy/order/failure-propagation evidence only; local memory tests prove semantics/concurrency/read-after-write, not Spring rollback of maps. Reserve atomic COMPLETE/FAILURE audit/idempotency rollback verification for Task 9's real DM8 smoke.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor phase outcomes into explicit value types; do not collapse stage/promote/parse exceptions to a raw message.
- [ ] Run storage/parser/archive/session regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectFileIngestionServiceTest,ProjectFileIngestionTransactionStructureTest,ProjectFileApiIntegrationTest,ProjectMultipartConfigurationTest,ProjectPdfTextParserTest,ProjectDocxContainerGuardTest,ProjectArchiveSecurityTest,FileSystemStorageAdapterTest,ContractReviewUploadRecoveryTest,ReviewFileValidatorTest test
```

- [ ] **Review checkpoint:** Inspect consumption of completed Task 5/6 interfaces, exact root storage-key inputs, atomic START persistence, promote-to-persisted-key order, transaction boundaries, immutable result-resource identity, actor type, ownership-safe API reads, OperationAudit payload bounds, transport/business limits, content headers, and UNKNOWN handling. Resolve all Critical/Important findings.

---

### Task 8: Prove durable recovery and duplicate-free unknown-outcome replay

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileRecoveryService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionTransactionCoordinator.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectFileRecoveryTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectIntakeRecoveryDeduplicationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectUploadConcurrencyTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectFileRecoveryApiIntegrationTest.java`

**Interfaces:**

- Consumes: authenticated Project/file IDs; original durable upload idempotency record located by its result resource; ownership-safe `ProjectFileRepository.findByProjectIdAndId` and `findByProjectIdAndParentArchiveFileIdAndRelativePath`; persisted ProjectFile storage key/hash/size/status/timestamps; `FileStoragePort.inspect/open`; deterministic Task 5/6 processing; `ContractCandidateRepository.findBySourceContractFileId`; and `ExtractionResultRepository.findByExtractionKey` unique identities.
- Produces: explicit `POST /api/v1/projects/{projectId}/files/{fileId}/recover`; restart recovery for pending roots; committed-outcome rediscovery; exactly-once candidate/extraction content under replay and concurrency. This endpoint accepts no upload bytes, creates no ProjectFile, requires/creates no new idempotency key, and is never invoked as a GET side effect.

**Write-accountability matrix:**

| Recovery outcome | BUSINESS FACT | OPERATION AUDIT EVENT | FLOWDEBUG EVENT | IDEMPOTENCY FACT |
| --- | --- | --- | --- | --- |
| resume COMPLETE | reuse/insert only missing rows, root/children AVAILABLE | `PROJECT_FILE_UPLOAD_COMPLETED` only if original COMPLETE was not already committed | `PROJECT_UPLOAD_RECOVERY_*`, complete tx events | same immutable `PROJECT_FILE_UPLOAD` record succeeds |
| proven ordinary failure | root UPLOAD_FAILED | `PROJECT_FILE_UPLOAD_FAILED` once | recovery + failure tx events | same record fails |
| uncertain storage/DB | no guessed terminal mutation | none | safe recovery UNKNOWN reason | remains in progress/unknown |
| original record already SUCCEEDED | none | none | bounded recovery replay event | replay its final resource/result |
| original record already FAILED | none | none | bounded recovery conflict event | stable conflict; never claim a new record |

- [ ] **RED:** At the API boundary, require authenticated session/CSRF and Project-scoped ownership; call `POST /api/v1/projects/{projectId}/files/{fileId}/recover` with no multipart body and no `Idempotency-Key`. Assert it never stages bytes, allocates a new ProjectFile UUID/storage key, or calls idempotency claim. GET detail/file/content remains read-only and never triggers recovery.
- [ ] Make the coordinator read the original `PROJECT_FILE_UPLOAD` record with `IdempotencyPort.findByResultResource("PROJECT_FILE", fileId.toString())`, then cross-check its result resource, operation, actor/ownership rules, and persisted ProjectFile. Missing/mismatched/unknown durable state fails closed; no client-provided key substitutes for the original record.
- [ ] Test the exact status dispatch: original SUCCEEDED replays the final response without parsing/audit duplication; original FAILED returns a stable conflict; in-progress + non-`UPLOAD_PENDING` mismatch fails closed; only original in-progress + root `UPLOAD_PENDING` enters storage reconciliation.
- [ ] Inject crashes before/after START, after PACKAGE promote, during scan, after child promote, before COMPLETE, and after COMPLETE commit with response loss. Recreate services/repositories to simulate restart.
- [ ] Required deduplication scenario: first COMPLETE outcome unknown -> restart -> reopen the same source -> deterministic reparse. Assert candidate count, candidate IDs, extraction count, extraction keys, and immutable contents do not grow or change when the first commit succeeded; when it did not commit, assert exactly one missing set is inserted.
- [ ] Add concurrent recovery tests where two workers race on the candidate source unique constraint and each extraction key. The loser must reread and field-verify; a same key with different immutable input/value fails closed and never overwrites.
- [ ] Prove recovery constructs `StorageKey` only from the persisted `ProjectFile.storageKey`; it never recomputes from path, staging ID, current clock, request headers, or a new random identity. Test PRESENT with matching size/hash reopens and deterministically parses/scans toward COMPLETE; PRESENT with wrong size/hash fails closed as an integrity conflict; DEFINITELY_MISSING remains 409/in-progress before the stale threshold and closes through the ordinary FAILURE transaction only when stale; UNKNOWN always remains uncertain/409 and non-terminal.
- [ ] Define stale exactly from `ProjectFile.updatedAt + ProjectFilePolicy.pendingRecoveryAge` (or `createdAt` only for an explicitly supported legacy-null `updatedAt`), using the server clock. Reuse the existing file-storage pending recovery-age configuration. Recovery is synchronous on this POST, has no scheduler/background queue, never uses/holds/advances `Project.versionNo`, and does not block a different upload.
- [ ] Run and confirm current recovery behavior is absent:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectFileRecoveryTest,ProjectIntakeRecoveryDeduplicationTest,ProjectUploadConcurrencyTest,ProjectFileRecoveryApiIntegrationTest test
```

- [ ] **GREEN:** Rediscover only through the route's Project/file IDs and the original idempotency record's result-resource root ID; inspect/open the persisted storage key, validate hash/size, and rerun deterministic work. Complete/fail only when durable/storage facts prove the outcome.
- [ ] In COMPLETE, lookup/reuse candidate through `findBySourceContractFileId(projectId, sourceFileId)` first, then lookup/reuse every extraction through `findByExtractionKey`; insert missing facts; on unique conflicts reread and compare Project ownership plus all immutable identity/value fields.
- [ ] For archive children, first call the exact `ProjectFileRepository.findByProjectIdAndParentArchiveFileIdAndRelativePath(projectId, parentArchiveFileId, normalizedRelativePath)` and reuse its ProjectFile ID. Its memory implementation, DM8 mapper/XML/adapter, and Task 3 tests must enforce all three predicates. If the first COMPLETE did not commit, create one child row while reusing the deterministic promoted storage key; never generate a second committed child identity for the same parent/path.
- [ ] Suppress duplicate COMPLETE audit only when durable state proves it was already committed; never use FlowDebug presence as proof.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor crash injection points into test seams without changing transaction boundaries.
- [ ] Run recovery/storage/idempotency regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectFileRecoveryTest,ProjectIntakeRecoveryDeduplicationTest,ProjectUploadConcurrencyTest,ProjectFileRecoveryApiIntegrationTest,ContractReviewUploadRecoveryTest,FileSystemStorageAdapterTest,Dm8IdempotencyAdapterTest test
```

- [ ] **Review checkpoint:** Require the exact no-bytes/no-new-key POST contract, original result-resource lookup, status dispatch, persisted-key-only reopen, explicit row-count evidence for both unknown-outcome branches, concurrent unique races, immutable-key collision handling, storage tri-state/stale policy, read-only GETs, and absence of a scheduler or long aggregate lease. Resolve all Critical/Important findings.

---

### Task 9: Complete API projections, audit/log safety, and release verification

**Files:**

- Create: None; this task completes and verifies the existing core slice.
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectApplicationService.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectApiIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectFileApiIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectAuditAndFlowSafetyTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/LocalAdapterWiringTest.java`

**Interfaces:**

- Consumes: all core services and the exact Task 2 read ports: `ProjectFileRepository.findByProjectIdAndId/findByProjectIdAndParentArchiveFileIdAndRelativePath/findByProjectId`, `ContractCandidateRepository.findByProjectIdAndId/findByProjectId/findBySourceContractFileId`, and `ExtractionResultRepository.findByExtractionKey/findBySourceFileId/findByCandidateId/findByProjectId`; current security/error/trace conventions.
- Produces: detail projections for files, parsed text, extraction facts/source locators, candidate conflicts/missing values; the Task 8 recovery command; `GET /api/v1/projects/{projectId}/contract-candidates`; `GET /api/v1/projects/{projectId}/contract-candidates/{candidateId}`; consistent safe errors; complete local/DM8 wiring.

**Write-accountability matrix:**

| Command | BUSINESS FACT | OPERATION AUDIT EVENT | FLOWDEBUG EVENT | IDEMPOTENCY FACT |
| --- | --- | --- | --- | --- |
| create | `PROJECT` | `PROJECT_CREATED` | `PROJECT_CREATE_START/COMPLETE` | `PROJECT_CREATE` |
| metadata | project CAS | `PROJECT_METADATA_CONFIRMED` | update start/complete/conflict | `PROJECT_METADATA_CONFIRM` |
| upload | root/child/files/extraction/candidate facts | START/COMPLETED/FAILED upload events | stage/tx/scan/parse/recovery exact events | `PROJECT_FILE_UPLOAD` |
| reads | none | none | safe read diagnostics | none |

- [ ] **RED:** Add full API cases for list/detail/file/category/other/unknown, original content, real parsed text, extraction locators, candidate evidence/conflicts/missing values, deterministic ordering, explicit pending-file recovery POST, IDOR-safe nested 404/cross-Project denial, 400/413/409 semantics, 401/403, CSRF, and absence of GET side effects, mapper backdoors, formal-contract/association/delete/category-correction routes.
- [ ] Capture durable OperationAudit independently from FlowDebug. Assert exact String actor ID, semantic Project/ProjectFile resource IDs, null task ID, bounded business result/action description/safe reason/count/status/category, and prohibit full metadata/document/extracted/source/ZIP content, paths/storage keys, idempotency key, Cookie/Session/CSRF/Authorization, credentials/tokens, raw exception messages, and unbounded parser output. Separately assert FlowDebug event names and its stricter prohibited-content rules.
- [ ] Transaction-structure failure injection proves coordinator order/failure propagation and DM8 adapter participation. Local memory assertions prove no successful publication/read-after-write after a failed command only where the memory adapter explicitly publishes atomically; they do not claim Spring database rollback. Actual business + OperationAudit + idempotency atomic commit/rollback is verified only in the real-DM8 smoke below.
- [ ] Run and confirm missing projections/wiring fail:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectApiIntegrationTest,ProjectFileApiIntegrationTest,ProjectAuditAndFlowSafetyTest,LocalAdapterWiringTest test
```

- [ ] **GREEN:** Complete response mapping and Spring local/DM8 wiring through the named ports only. Use safe stable reason codes and existing `ApiResponse`/exceptions; never return source absolute path or raw parser/storage exception.
- [ ] For extracted-text reads, reopen the proven `AVAILABLE + PARSED` file through `FileStoragePort`, verify storage inspection/hash, and rerun the same deterministic parser on demand. Return only real parser output; if storage integrity is unknown, fail closed instead of returning mock/stale text. This read creates no business, audit, or idempotency fact.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor response records and fixture builders only if all ownership/error cases remain explicit.
- [ ] Run full backend verification:

```powershell
cd backend
.\mvnw.cmd clean verify
```

- [ ] Run repository checks:

```powershell
cd ..
git diff --check
git status --short --untracked-files=all
```

- [ ] Real DM8 smoke evidence: on a representative DM8 version, apply the allocated migration; inspect all PK/FK/UNIQUE/CHECK/index definitions, including `UNIQUE(PROJECT_FILE.STORAGE_KEY)`, both `(PROJECT_ID, ID)` keys, candidate/source/extraction nullable composite FKs, and the absence of `PROJECT.CONFIRMED_BY`; explicitly insert null/non-null candidate references to verify actual DM8 nullable composite-FK behavior. Run blank create, CAS conflict, direct upload, archive upload, rollback injections around START storage identity + business + OperationAudit + idempotency, explicit recovery POST, concurrent recovery, and COMPLETE-outcome-unknown replay; record sanitized SQL, version, row counts, and observed results. Unit/mock/SQL-string tests do not make real DM8 `VERIFIED`.
- [ ] Target filesystem evidence: with the deployment-like storage root, stage/promote/reopen/inspect after process restart, test create-only conflict and hash mismatch, and confirm no archive path escapes the root. Record OS/filesystem and sanitized observations; otherwise mark target filesystem `NOT VERIFIED`.
- [ ] Redis/session impact: confirm no new Redis dependency, keys, locks, or business facts. With a real session profile, verify authenticated/CSRF upload behavior and that Redis loss causes only re-login while DM8 facts persist. Record evidence or mark real Redis behavior `NOT VERIFIED`.
- [ ] Browser/API manual E2E: use an authenticated session and CSRF token to create blank, patch metadata, upload direct PDF/DOCX and safe archive, explicitly recover a pending root through `POST /api/v1/projects/{projectId}/files/{fileId}/recover` without bytes/new key, list/download files, inspect real parsed text/candidates through API responses, replay the original upload key, and verify 400/413/409/401/403 behavior. There is no new browser screen in this plan.
- [ ] **Review checkpoint:** Review the entire plan against every spec invariant, dependency scope, migration allocation, recovery evidence, audit/diagnostic separation, F02/ReviewSession/F01/F03 regressions, and verification labels. Resolve all Critical/Important findings.

## Completion Evidence Checklist

- [ ] The conditionally selected archive reader passed every real-byte capability/security test. If JDK passed, Commons Compress is absent; if JDK had a recorded critical gap, the exact focused dependency/version/reason and its passing matrix are recorded.
- [ ] One source contract file cannot create a second candidate, and one deterministic extraction identity cannot create a second result, including after unknown COMPLETE outcome and concurrent recovery.
- [ ] Same-Project ownership is enforced by the planned composite keys/FKs, and candidate/detail/source consumers use only the exact ownership-safe repository ports with deterministic ordering.
- [ ] Project/ProjectFile/candidate/extraction IDs remain UUID; creator/promoter/audit user IDs remain String/DM8 `VARCHAR(36)`; metadata confirmer is only the OperationAudit actor and no `Project.confirmedBy`/`PROJECT.CONFIRMED_BY` exists.
- [ ] ProjectFile and ReviewDocument remain separate; Project parser and budget parser remain independent.
- [ ] File ingestion does not hold a project-version lease; storage UNKNOWN never becomes guessed success/failure.
- [ ] Every root ProjectFile START atomically persists its deterministic storage key/hash/size/result resource; explicit recovery uses that key and the original idempotency record, creates no new upload/file identity, and no GET performs recovery.
- [ ] No formal Contract or association row is created, and no browser application source is changed.
- [ ] Business facts, operation audit, FlowDebug, and idempotency have distinct tested responsibilities.
- [ ] `backend\.\mvnw.cmd clean verify`, repository checks, and applicable smoke evidence are recorded; real integrations remain `NOT VERIFIED` without direct evidence.
