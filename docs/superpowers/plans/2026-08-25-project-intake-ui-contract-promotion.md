# Project Intake UI & Contract Promotion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the sole Project-to-Contract association and atomic candidate promotion path, then deliver the real Project list/detail/upload/evidence experience and connect promoted contracts to existing direct F02 progressive review.

**Architecture:** `PROJECT_CONTRACT` is the only relationship model and is read through a dedicated `ProjectContractQueryPort`. Promotion owns a minimal insert-only Contract creation port and commits the Contract snapshot, line/award rows, association, candidate transition, operation audit, and idempotency completion atomically. The React app adds Project state navigation and consumes Plan B facts; after promotion it calls the existing direct F02 API and Plan A progress flow.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Security session/CSRF, MyBatis/MyBatis-Plus, DM8, React 18, TypeScript, Vite 6, shared fetch client, Node built-in test runner, JUnit 5, Mockito, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-25-pre-f04-product-alignment-design.md`

## Global Constraints

- This plan starts only after Plans A and B are merged to current `main`. Re-read every latest interface; do not implement against planning-time assumptions.
- Require `git branch --show-current` = `main` and empty `git status --short` before edits.
- Use only `PROJECT_CONTRACT`; never add `PROJECT_ID` to `CONTRACT`, `Contract`, its constructor, read mapper, response, or F02 domain path.
- Keep existing `ContractRepository` read-only. Add a promotion-owned insert-only creation port; do not add generic `save`, `update`, `upsert`, or `merge`.
- Promotion is explicit after user confirmation. Intake/extraction never auto-creates a formal Contract.
- Promotion minimum validation exactly matches current `Contract` invariants: nonblank contract number/name/type/parties; non-null non-negative BigDecimal amount; valid supplied line items; nullable valid award notice; nullable amount-in-words and party-B credit code.
- The server sets Contract `status=READY_FOR_REVIEW`, `versionNo=0`, and server timestamps. Confirmed payload does not overwrite extraction facts.
- Formal business IDs remain UUID: Project, candidate, Contract, each `ContractLineItem.id`, and optional `AwardNoticeReference.id`. Actor IDs remain the current security type: `ContractCandidate.promotedBy`, `ProjectContract.createdBy`, and `OperationAuditEvent.userId` are Java `String` backed by DM8 `VARCHAR(36)`/`SYS_USER(ID)`; never model an actor as UUID.
- The promotion request contains confirmed business fields only. It cannot set Contract/line/award IDs, child `contractId` relationships, status, version, or timestamps; the server constructs all of them inside the promotion operation before persistence. The idempotency fingerprint covers only actor/project/candidate/version and canonical confirmed business fields, never server UUIDs/times.
- Contract, lines, award, association, candidate `DRAFT -> PROMOTED`, `PROJECT_CONTRACT_CANDIDATE_PROMOTED`, and idempotency success commit in one short transaction. Any failure rolls all of them back.
- Promotion scope is `PROJECT_CONTRACT_CANDIDATE_PROMOTE`. Fingerprint binds actor, project, candidate, expected candidate version, and the canonical confirmed business-field payload only. Same-key/same-fingerprint replay returns the same Contract.
- Contract-number, stale candidate, double/concurrent promotion, association, and changed-payload conflicts return HTTP 409 and never overwrite an existing Contract.
- One Project may link to many Contracts; a Contract links to only one Project; a candidate links to only one Contract.
- `FindingSourceProjection` is contract-owned and fail closed. It never changes `AUDIT_CHECK_RESULT` or fabricates evidence.
- Source is available only when association/candidate ownership is proven, Contract version is the unchanged promotion snapshot, a unique non-conflicting extraction anchor exists, and the formal value deterministically equals the candidate/extracted value. Otherwise return `source=unavailable` with a stable reason.
- Do not infer source from similar text, a single candidate, nullable coincidence, or UUID shape.
- Keep `ContractReviewService`, F02 five-rule execution, `AuditTask` lifecycle, Plan A progress ownership, and ReviewSession state/material invariants unchanged.
- Project does not create/bind ReviewSession or ReviewDocument. Direct F02 remains the only review path after promotion.
- Frontend uses existing internal `Page` state; do not add React Router or replace the shared API client.
- “+ 新建项目” immediately creates a blank record with no form. Blank detail displays the approved nullable placeholders and one primary upload action.
- Unified upload accepts direct ZIP/PDF/DOCX via Plan B. No per-category upload entrances, delete/archive/category-correction actions, red-star/priority markers, fake data, OCR/AI, workflow, approval, or formal report export.
- Plan C consumes Plan B's Project create/list/detail, metadata PATCH plus its trusted-origin CORS support, upload, explicit ProjectFile recovery, file list/content, candidate list/detail, and extraction/source projections through their application/API contracts only. It must not call a mapper, repository adapter, recovery coordinator, or storage implementation directly.
- Original file and extracted text remain distinct. Only authenticated safe preview is inline; other types download as attachment.
- All write tasks name business fact, operation-audit event, FlowDebug event, and idempotency fact. Browser state/console is never durable evidence.
- Durable OperationAudit is independently bounded from FlowDebug: allow event/module/action, String actor ID/username, semantic Project/candidate/Contract IDs, null or real task ID as applicable, result status, bounded business result/action description, Request ID, Trace ID, stable safe reason code, and safe counts/status/rule code. Prohibit full confirmed Contract payload, document/extraction/source text, bytes, paths/storage keys, idempotency keys, Cookie/Session/CSRF/Authorization, credentials/tokens, raw exceptions, or unbounded detail.
- Verification is layered: unit/transaction-structure tests prove proxy boundary, call order, failure propagation, and DM8 adapter participation; local memory tests prove semantic conflict/concurrency/idempotency/read-after-write only; atomic database commit/rollback is `VERIFIED` only after representative real-DM8 evidence. Do not add delete/revoke compensation APIs.
- Each task ends in review; all Critical/Important findings are fixed before the next task.
- Allocate the migration only at execution time using `NEXT_AVAILABLE_VERSION_AT_IMPLEMENTATION_TIME`; never edit existing migrations or reserve a fixed version.

## Current-Code Reconnaissance Baseline

Current-main symbols verified while creating this plan:

- Contract domain/read boundary: `backend/src/main/java/com/fuquan/audit/contract/domain/Contract.java`, `ContractLineItem.java`, `AwardNoticeReference.java`, `Money.java`, and `contract/application/port/ContractRepository.java` with only `findById(UUID)`.
- Local Contract baseline: `contract/infra/memory/InMemoryContractRepository.java` currently initializes its own unmodifiable map from `LocalContractFixtures`; Task 2 must replace that private ownership with the one neutral shared local store while retaining the read-only Port.
- DM8 reads: `integration/dm8/ContractMapper.java`, `Dm8ContractRepository.java`, `ContractPersistenceRow.java`, line/award rows, and `backend/src/main/resources/mappers/dm8/ContractMapper.xml` contain read-only aggregate queries.
- F02: `contract/application/ContractReviewService.java`, `ContractReviewTransactionCoordinator.java`, `contract/api/ContractController.java`, and `review/domain/AuditCheckResult.java`.
- ReviewSession regression: `contract/application/ContractReviewSessionService.java`, coordinator, controller, and their domain/integration/recovery tests.
- Common write seams: `IdempotencyPort`, `IdempotencyRequest`, `OperationAuditPort`, `OperationAuditEvent`, `FlowDebugLogger`, `AuthContext`, `ApiResponse`, and `GlobalExceptionHandler`.
- Frontend shell/client: `frontend/src/app/App.tsx` owns the `Page` union, `ContractPage`, and `pageComponents`; `frontend/src/api/client.ts` owns `apiUrl`/`request`; `frontend/src/api/userFacingError.ts` owns safe messages.
- Existing F02 UI: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`, `F02ReviewResultPanel.tsx`, and Plan A data/hook/progress components after its merge.
- Plan B contract consumed here: Project create/list/detail; metadata PATCH with Task 4 CORS coverage; upload; `POST /api/v1/projects/{projectId}/files/{fileId}/recover`; file list/content; candidate list/detail; extraction/source projections. Its underlying ownership-safe reads are `ProjectFileRepository.findByProjectIdAndId/findByProjectIdAndParentArchiveFileIdAndRelativePath/findByProjectId`, `ContractCandidateRepository.findByProjectIdAndId/findByProjectId/findBySourceContractFileId`, and `ExtractionResultRepository.findByExtractionKey/findBySourceFileId/findByCandidateId/findByProjectId`; frontend and promotion code consume only Plan B API/application/query ports, never mapper or recovery backdoors.

Expected Plan A/B outputs must be rediscovered rather than assumed. At execution start run:

```powershell
git branch --show-current
git status --short
rg -n "interface ContractRepository|interface .*Contract.*Creat|class Dm8ContractRepository|interface ContractMapper|class ContractReviewService|review-progress|FindingSource" backend/src/main backend/src/test frontend/src
rg -n "record Project|record ProjectFile|record ContractCandidate|interface ProjectRepository|interface ContractCandidateRepository|class ProjectController|contract-candidates" backend/src/main backend/src/test
rg -n "type Page|function ContractPage|pageComponents|function apiUrl|function request|useContractReviewWorkspace" frontend/src
Get-ChildItem db/dm8/V*.sql | Sort-Object Name | Select-Object -ExpandProperty Name
```

Before the first test, reconcile exact class/method/mapper names and JSON fields with merged Plans A/B. If their reviewed names differ from those below, update this plan and the new test names consistently; do not create a duplicate port or adapter.

---

### Task 0: Allocate and verify the sole Project-Contract association migration

**Files:**

- Create: `db/dm8/NEXT_AVAILABLE_VERSION_AT_IMPLEMENTATION_TIME__project_contract_association.sql`
- Modify: `db/dm8/README.md`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/ProjectContractDm8SqlStructureTest.java`

**Interfaces:**

- Consumes: latest `PROJECT`, `CONTRACT`, `CONTRACT_CANDIDATE`, and current DM8 constraint conventions.
- Produces: `PROJECT_CONTRACT(PROJECT_ID, CONTRACT_ID, CANDIDATE_ID, CREATED_BY, CREATED_AT)` with UUID business IDs, `CREATED_BY VARCHAR(36)` String actor identity, composite PK, exact uniqueness, and database-enforced same-Project candidate ownership.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | `CONTRACT`, line/award rows, `PROJECT_CONTRACT`, candidate `PROMOTED` |
| OPERATION AUDIT EVENT | `PROJECT_CONTRACT_CANDIDATE_PROMOTED` |
| FLOWDEBUG EVENT | `PROJECT_PROMOTION_START`, `PROJECT_PROMOTION_TX_ENTER`, `PROJECT_PROMOTION_TX_COMMIT`, `PROJECT_PROMOTION_CONFLICT` |
| IDEMPOTENCY FACT | `PROJECT_CONTRACT_CANDIDATE_PROMOTE`, result resource `CONTRACT`/created Contract ID |

- [ ] Re-scan latest migration names and actual source-table keys immediately before file creation:

```powershell
Get-ChildItem db/dm8/V*.sql | Sort-Object Name | Select-Object -ExpandProperty Name
rg -n "CREATE TABLE (PROJECT|CONTRACT|CONTRACT_CANDIDATE)|PRIMARY KEY|UNIQUE" db/dm8/V*.sql
```

Replace the filename token with the next unused version and record the allocation. Do not edit any earlier migration.
- [ ] **RED:** Add structure assertions for composite primary key `(PROJECT_ID, CONTRACT_ID)`, `UNIQUE(CONTRACT_ID)`, `UNIQUE(CANDIDATE_ID)`, non-null candidate/creator/time, `CREATED_BY VARCHAR(36) -> SYS_USER(ID)`, Contract FK, Project FK, and composite `(PROJECT_ID, CANDIDATE_ID) -> CONTRACT_CANDIDATE(PROJECT_ID, ID)`. Retain the candidate table's separate PK and `UNIQUE(PROJECT_ID, ID)` from Plan B.
- [ ] Assert the new migration does not alter `CONTRACT`, add `PROJECT_ID`, create a second relationship table, add delete/soft-delete/archive fields, or use `MERGE`.
- [ ] Run and confirm failure is the missing association migration:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectContractDm8SqlStructureTest test
```

- [ ] **GREEN:** Add the smallest additive DDL with explicit names and indexes required by current DM8, including the composite candidate ownership FK and String actor FK. Update README with allocation and real-DM8 status `NOT VERIFIED` pending final verification.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor only SQL-test loading helpers; keep every relationship invariant explicit.
- [ ] Run structural regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectContractDm8SqlStructureTest,ProjectIntakeDm8SqlStructureTest,F02Dm8SqlStructureTest test
```

- [ ] **Review checkpoint:** Verify refreshed numbering, sole relationship model, exact uniqueness, user/candidate/project/contract FKs, and no Contract-table change. Resolve all Critical/Important findings.

---

### Task 1: Add association domain/ports and local/DM8 read-write adapters

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ProjectContract.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ProjectContractQueryPort.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ProjectContractCommandPort.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/infra/memory/project/InMemoryProjectContractAdapter.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectContractMapper.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectContractPersistenceRow.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectContractAdapter.java`
- Create: `backend/src/main/resources/mappers/dm8/ProjectContractMapper.xml`
- Modify: None; the association uses isolated domain, port, mapper, and adapter files.
- Test: `backend/src/test/java/com/fuquan/audit/contract/domain/project/ProjectContractTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ProjectContractAdapterTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/ProjectRelationshipDependencyTest.java`

**Interfaces:**

- Consumes: Task 0 schema, Plan B Project/candidate identities, current Contract summary mapping.
- Produces: immutable `ProjectContract(UUID projectId, UUID contractId, UUID candidateId, String createdBy, Instant createdAt)`; command `insert(ProjectContract)` only; deterministic queries `findByProjectId`, `findByContractId`, `findByCandidateId`, and Project Contract summaries without injecting Project into Contract.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | insert-only `PROJECT_CONTRACT` |
| OPERATION AUDIT EVENT | coordinator emits `PROJECT_CONTRACT_CANDIDATE_PROMOTED`; adapter emits none |
| FLOWDEBUG EVENT | association repository read/write inside `PROJECT_PROMOTION_TX_*` |
| IDEMPOTENCY FACT | coordinator owns `PROJECT_CONTRACT_CANDIDATE_PROMOTE` |

- [ ] **RED:** Test UUID project/contract/candidate IDs, required String creator/time, one Project-many Contract reads, one Contract-one Project, one candidate-one Contract, insert conflicts, stable summary ordering, and same-Project candidate ownership predicates.
- [ ] Add dependency assertions that `Contract` constructor/record fields remain unchanged, `ContractRepository` remains read-only, and no review type imports project association types.
- [ ] Run and confirm missing types/mapper failures:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectContractTest,Dm8ProjectContractAdapterTest,ProjectRelationshipDependencyTest test
```

- [ ] **GREEN:** Implement immutable association, narrow command/query ports, atomic put-if-absent local adapter, explicit DM8 insert/select statements, and summary mapping. Do not expose update/delete/merge.
- [ ] Translate only proven unique/FK conflicts to business conflict; propagate uncertain database failures.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor mapper conversions to private helpers while retaining semantic-ID tests.
- [ ] Run targeted repository regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectContractTest,Dm8ProjectContractAdapterTest,Dm8ProjectRepositoriesTest,ProjectRelationshipDependencyTest test
```

- [ ] **Review checkpoint:** Verify narrow ports, sole association, stable query behavior, no Contract-domain pollution, no reverse review dependency, and conflict discrimination. Resolve all Critical/Important findings.

---

### Task 2: Add the promotion-owned insert-only Contract creation boundary

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ContractCreationPort.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/infra/memory/LocalContractStore.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/infra/memory/project/InMemoryContractCreationAdapter.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCreationMapper.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ContractCreationAdapter.java`
- Create: `backend/src/main/resources/mappers/dm8/ContractCreationMapper.xml`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/infra/memory/InMemoryContractRepository.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ContractCreationAdapterTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/infra/memory/project/InMemoryContractCreationAdapterTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/ContractReadBoundaryRegressionTest.java`

**Interfaces:**

- Consumes: existing `Contract`, `ContractLineItem`, `AwardNoticeReference`, current `CONTRACT`/line/award schema.
- Produces: `ContractCreationPort.insertCompleteSnapshot(Contract)`; insert statements for aggregate root, all line items, and optional award notice; shared local storage so a promoted Contract is immediately readable by existing direct F02; no read/update/delete method.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | insert `CONTRACT`, its line items, optional award notice |
| OPERATION AUDIT EVENT | only Task 3 coordinator emits `PROJECT_CONTRACT_CANDIDATE_PROMOTED` |
| FLOWDEBUG EVENT | insert participates in `PROJECT_PROMOTION_TX_ENTER/COMMIT` |
| IDEMPOTENCY FACT | only Task 3 owns `PROJECT_CONTRACT_CANDIDATE_PROMOTE` |

- [ ] **RED:** Test complete aggregate insert order, empty line list, optional award, BigDecimal preservation, duplicate contract number conflict, failure at root/line/award insert, and absence of partial successful return. In local profile, insert through the creation port and read the exact same aggregate through existing `ContractRepository.findById`.
- [ ] Assert via reflection/source scan that `ContractRepository` and current `ContractMapper` gain no write methods; `Contract` record components and `Dm8ContractRepository` read behavior remain unchanged.
- [ ] Run and confirm missing creation boundary fails:

```powershell
cd backend
.\mvnw.cmd -Dtest=Dm8ContractCreationAdapterTest,InMemoryContractCreationAdapterTest,ContractReadBoundaryRegressionTest test
```

- [ ] **GREEN:** Implement the one-purpose port and separate DM8 mapper/adapter. Under `@Profile("local")`, define exactly one neutral `contract.infra.memory.LocalContractStore` bean, seed it once from `LocalContractFixtures`, and inject that same instance into both existing `InMemoryContractRepository` and project-owned `InMemoryContractCreationAdapter`. `ContractRepository` remains read-only; neither it nor the neutral store exposes a public business insert/update/delete API.
- [ ] The local creation adapter atomically publishes one complete immutable Contract aggregate to the shared map so promoted Contracts are immediately readable by existing `ContractRepository.findById`; this proves local read-after-write and prevents partial aggregate publication only. Insert explicit DM8 columns and use server-generated UUID/timestamps supplied by the application. Do not claim that Spring rolls back this store or coordinates it atomically with other local maps; real cross-fact rollback belongs to the DM8 smoke.
- [ ] Map the verified contract-number unique conflict to 409-capable conflict without converting connectivity/timeout errors.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor shared persistence-row conversion only if it does not add write methods to existing read abstractions.
- [ ] Run contract read/F02 mapper regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=Dm8ContractCreationAdapterTest,InMemoryContractCreationAdapterTest,ContractReadBoundaryRegressionTest,Dm8ContractRepositoryTest,ContractReviewServiceTest test
```

- [ ] **Review checkpoint:** Verify neutral store ownership/path, exactly one local bean seeded from existing fixtures, both adapters sharing it, existing and promoted Contract reads, read-only `ContractRepository`, insert-only creation surface, BigDecimal handling, and correctly scoped local transaction claims. Resolve all Critical/Important findings.

---

### Task 3: Implement atomic idempotent candidate promotion and API

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/PromoteContractCandidateCommand.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ConfirmedContractPayload.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionService.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionTransactionCoordinator.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ContractCandidateRepository.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionServiceTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionTransactionStructureTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionConcurrencyTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidatePromotionApiIntegrationTest.java`

**Interfaces:**

- Consumes: Plan B `ContractCandidateRepository.findByProjectIdAndId` plus candidate CAS, `ContractCreationPort`, Project association ports, existing `ContractRepository`, `IdempotencyPort`, `OperationAuditPort`, `FlowDebugLogger`, `AuthContext.currentUser().userId: String`, Clock, Request/Trace IDs.
- Produces: `POST /api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote`; a request DTO containing only `expectedVersion` and confirmed business fields; server-owned Contract/line/award identities and control fields; `ContractCandidateRepository.promoteIfVersionMatches(UUID projectId, UUID candidateId, long expectedVersion, UUID contractId, String promotedBy, Instant promotedAt): ContractCandidate`; replay response with the same Contract ID.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | server-identified complete Contract snapshot + lines/award, `PROJECT_CONTRACT` with String creator, candidate `PROMOTED` with String promoter |
| OPERATION AUDIT EVENT | `PROJECT_CONTRACT_CANDIDATE_PROMOTED`; module `CONTRACT`; resource type/id `CONTRACT_CANDIDATE`/candidate ID; task ID null |
| FLOWDEBUG EVENT | `PROJECT_PROMOTION_START`, `PROJECT_PROMOTION_TX_ENTER`, `PROJECT_PROMOTION_TX_COMMIT`, `PROJECT_PROMOTION_CONFLICT` |
| IDEMPOTENCY FACT | `PROJECT_CONTRACT_CANDIDATE_PROMOTE`; fingerprint actor/project/candidate/version/confirmed business fields only; result `CONTRACT`/Contract ID |

- [ ] **RED:** Test all required/nullable confirmed business fields, non-negative BigDecimal amounts, optional line/award business fields, source candidate unchanged, and no extraction mutation. The request DTO must expose no `Contract.id/status/versionNo/createdAt/updatedAt`, no `ContractLineItem.id/contractId`, and no `AwardNoticeReference.id/contractId`.
- [ ] Prove the server generates one Contract UUID, one UUID for every line, and an award UUID when present; assigns every child `contractId` to the generated Contract; sets `READY_FOR_REVIEW`, version 0, server timestamps, String `promotedBy`, and String association `createdBy` before persistence. Assert no client value can influence these identities/relationships/control fields.
- [ ] Use explicit DTO `@JsonIgnoreProperties` entries for all prohibited identity/control member names so the current global JSON policy is not relied upon. Add MockMvc requests containing malicious extra IDs/status/version/times/child contract IDs; prove they are explicitly ignored and the response/persisted aggregate contains only server-generated values.
- [ ] Test parent/candidate mismatch 404, stale expected version 409, candidate already promoted 409, contract-number 409, association 409, same-key replay, changed payload/key conflict, two users/keys racing, and immutable winner.
- [ ] Inject failure after every step: claim, Contract root, line, award, association, candidate CAS, operation audit, idempotency success. Structure tests assert propagation/order and no false successful response; local tests assert only adapter-specific atomic publication/read-after-write guarantees; the no-partial-state rollback assertion is reserved for the real-DM8 smoke.
- [ ] Test FlowDebug fields contain only project/candidate/contract IDs, versions, outcome, duration, safe reason code; no confirmed text payload, key, tokens, source excerpts, or task-ID substitution.
- [ ] Independently capture `PROJECT_CONTRACT_CANDIDATE_PROMOTED` OperationAudit. Assert String user ID, semantic candidate resource and null task ID; allow only bounded Contract/result status/count/safe reason. Prohibit the full confirmed payload, document/extraction/source text, idempotency key, paths/storage keys, security headers/tokens/credentials, raw exceptions, or unbounded detail.
- [ ] Add MockMvc cases for Session/CSRF, roles, 401/403, safe 404 IDOR, validation 400/422 according to current handler, conflicts 409, response envelope, Request ID, and absence of automatic F02 call in the backend transaction.
- [ ] Run and confirm missing service/coordinator/route failures:

```powershell
cd backend
.\mvnw.cmd -Dtest=ContractCandidatePromotionServiceTest,ContractCandidatePromotionTransactionStructureTest,ContractCandidatePromotionConcurrencyTest,ContractCandidatePromotionApiIntegrationTest test
```

- [ ] **GREEN:** Validate and canonicalize confirmed business fields; compute the fingerprint from actor/project/candidate/expected version plus length-delimited business payload only; emit START; enter one short transaction; claim; generate all UUIDs/times/relationships/status/version server-side; construct Contract; insert snapshot; insert association; CAS candidate; append audit; mark success. Never include generated UUIDs/timestamps in the fingerprint.
- [ ] On same-fingerprint replay, use the idempotency result Contract ID plus association/candidate facts to prove and return the original aggregate. Any mismatch fails closed.
- [ ] Use insert/CAS only. Never retry by generating another Contract ID after outcome becomes uncertain and never overwrite an existing Contract.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor payload fingerprint encoding into a deterministic tested helper with explicit null/list/item boundaries and fixed list order; assert two commands with identical business payload but different mocked UUID/Clock outputs share the same fingerprint.
- [ ] Transaction-structure tests prove the proxy boundary, order, failure propagation, and DM8 adapter participation. Local memory tests prove conflict/concurrency/idempotency/read-after-write only; they do not prove cross-map rollback. Mark Contract + lines/award + ProjectContract + candidate + OperationAudit + idempotency atomic rollback `VERIFIED` only after representative DM8 evidence.
- [ ] Run promotion/idempotency/contract regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=ContractCandidatePromotionServiceTest,ContractCandidatePromotionTransactionStructureTest,ContractCandidatePromotionConcurrencyTest,ContractCandidatePromotionApiIntegrationTest,Dm8IdempotencyAdapterTest,ContractReviewServiceTest test
```

- [ ] **Review checkpoint:** Inspect server ownership of every formal identity/control field, business-only fingerprint, String actor types, actual transaction proxy boundary, scoped rollback evidence, result-resource replay proof, OperationAudit payload safety, conflict semantics, and safe FlowDebug. Resolve all Critical/Important findings.

---

### Task 4: Add fail-closed finding source projection without changing result persistence

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/FindingSourceProjection.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/FindingSourceProjectionService.java`
- Modify: Plan A's merged `backend/src/main/java/com/fuquan/audit/contract/application/F02ReviewProgressItem.java`
- Modify: Plan A's merged `backend/src/main/java/com/fuquan/audit/contract/application/F02ReviewProgressService.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/FindingSourceProjectionServiceTest.java`
- Test: Plan A's merged `backend/src/test/java/com/fuquan/audit/contract/application/F02ReviewProgressServiceTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/F02Dm8SqlStructureTest.java`

**Interfaces:**

- Consumes: result rule code/item key, real task source Contract, `ProjectContractQueryPort`, Plan B `ContractCandidateRepository.findByProjectIdAndId`, `ProjectFileRepository.findByProjectIdAndId`, `ExtractionResultRepository.findByCandidateId/findBySourceFileId/findByProjectId`, and current Contract snapshot.
- Produces: nullable progress-item `source` with `available`, stable unavailable reason, project/file/page/paragraph/locator/excerpt when proven.

This is read-only. It creates no business fact, operation-audit event, FlowDebug business surrogate, or idempotency record.

- [ ] **RED:** Cover amount uppercase/numeric, line quantity/unit price/declared amount, award amount, party B/credit code, and award supplier/credit code mappings. Use exact rule/item-key semantics, not fuzzy text.
- [ ] Prove available only for promoted association, candidate/contract identity match, unchanged Contract `versionNo=0`, a unique non-conflicting extraction result, and scale-independent BigDecimal or exact normalized-text equality.
- [ ] Prove unavailable for legacy Contract, missing relation, wrong candidate, modified confirmed value, conflict, ambiguous support association, missing locator, later Contract version, and any unrecognized result. Return a safe reason rather than a guessed anchor.
- [ ] Assert source preparation has no source and result persistence/XML/migration contains no new evidence/source columns or updates.
- [ ] Run and confirm missing projection fails:

```powershell
cd backend
.\mvnw.cmd -Dtest=FindingSourceProjectionServiceTest,F02ReviewProgressServiceTest,F02Dm8SqlStructureTest test
```

- [ ] **GREEN:** Implement a contract-owned mapping table and strict equality strategies. Query only through the named association/candidate/file/extraction ports; never add a mapper/SQL helper in Plan C. Return unavailable at the first unproven link and attach source only to real result items.
- [ ] Keep source excerpt bounded and never send full document text through progress. Do not add a review-module dependency on project types.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor equality/mapping to pure package-private strategies while keeping fail-closed defaults.
- [ ] Run F02/review dependency regressions:

```powershell
cd backend
.\mvnw.cmd -Dtest=FindingSourceProjectionServiceTest,F02ReviewProgressServiceTest,ContractReviewApiIntegrationTest,ContractReviewServiceTest,ReviewModuleDependencyTest,F02Dm8SqlStructureTest test
```

- [ ] **Review checkpoint:** Verify every source chain link, later-version rule, bounded response, no fuzzy inference, no `AUDIT_CHECK_RESULT` mutation, and preserved module direction. Resolve all Critical/Important findings.

---

### Task 5: Add typed Project client, list, blank creation, and app navigation

**Files:**

- Create: `frontend/src/features/project/types.ts`
- Create: `frontend/src/features/project/data/projectDataSource.ts`
- Create: `frontend/src/features/project/data/httpProjectDataSource.ts`
- Create: `frontend/src/features/project/projectOperation.ts`
- Create: `frontend/src/features/project/useProjectList.ts`
- Create: `frontend/src/features/project/components/ProjectListPage.tsx`
- Create: `frontend/src/features/project/components/ProjectDetailPage.tsx`
- Create: `frontend/src/features/project/projectListViewModel.test.ts`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectApplicationService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Modify: `frontend/src/app/App.tsx`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectApiIntegrationTest.java`
- Test: `frontend/src/features/project/projectListViewModel.test.ts`

**Interfaces:**

- Consumes: Plan B create/list/detail/PATCH APIs, `ProjectContractQueryPort`, shared `apiUrl/request`, `userFacingError`, internal `Page` navigation.
- Produces: associated Contract summaries in Project detail; Project API types/data source; list paging/loading/error; `+ 新建项目`; `Page` member plus `activeProjectId`; a minimal durable `ProjectDetailPage`; immediate navigation to that blank detail.

**Write-accountability matrix:**

| Layer | Exact fact/event/scope |
| --- | --- |
| BUSINESS FACT | create button requests a new blank `PROJECT` |
| OPERATION AUDIT EVENT | server commits `PROJECT_CREATED`; client does not synthesize it |
| FLOWDEBUG EVENT | server uses `PROJECT_CREATE_START/COMPLETE`; browser console is not evidence |
| IDEMPOTENCY FACT | UI uses immutable `PROJECT_CREATE` key until create outcome is proven |

- [ ] **RED:** Test nullable project fields, stable list mapping, page=1/size<=100, loading/empty/error, placeholders `待识别/待提供`, no fabricated phone/risk/category, and no demo/showcase hydration.
- [ ] Extend `ProjectApiIntegrationTest` first: a Project with zero associations returns an empty Contract-summary list; one Project returns multiple associated summaries in stable order; a Contract associated to another Project is never exposed. Run it and confirm the correct failure is the absent association projection.
- [ ] Test create sends no business body, preserves one key through uncertain retry, navigates only after returned Project ID is proven, and does not open a form/dialog or silently generate a new key on refresh.
- [ ] In `projectListViewModel.test.ts`, cover the minimal `ProjectDetailPage` contract: loading/error states, navigation identity bound to the returned Project UUID, visible Project ID, exact nullable copy `项目名称：待识别`, `项目编号：待识别`, `项目类目：待提供`, and one primary CTA `上传项目资料`. It has no upload implementation yet and is the same component Task 6 will extend, not a throwaway placeholder.
- [ ] Run and confirm missing feature/types fail:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectApiIntegrationTest test
cd ..\frontend
node --experimental-strip-types --test src/features/project/projectListViewModel.test.ts
```

- [ ] **GREEN:** Inject `ProjectContractQueryPort` into the Project detail query and map associated Contract summaries without adding Project fields to `Contract`. Implement frontend types and HTTP data source using the shared cookie/CSRF client. Add the list/hook, minimal detail component, and internal app-page state. Keep Contract and ReviewSession pages intact.
- [ ] Display only actual nullable metadata and derived intake counts. Use button copy `+ 新建项目`; after success set `activeProjectId` and render the created `ProjectDetailPage` with its ID, three nullable labels, loading/error handling, and single upload CTA. Do not create a modal or a component that Task 6 deletes/replaces.
- [ ] Run both RED commands and confirm they pass.
- [ ] Refactor request identity handling into `projectOperation.ts`; retain the key until server outcome is known.
- [ ] Run targeted backend/frontend regression and build:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProjectApiIntegrationTest,Dm8ProjectContractAdapterTest test
cd ..\frontend
node --experimental-strip-types --test src/features/project/projectListViewModel.test.ts
npm.cmd run build
```

- [ ] **Review checkpoint:** Verify Task 5 builds independently with the real minimal detail route/component, no create form, fake data, throwaway placeholder, router replacement, key churn, Contract page regression, or unhandled nullable value. Resolve all Critical/Important findings.

---

### Task 6: Build blank detail, unified upload, categorized files, original/text views, and structured evidence

**Files:**

- Create: `frontend/src/features/project/useProjectWorkspace.ts`
- Modify: `frontend/src/features/project/components/ProjectDetailPage.tsx`
- Create: `frontend/src/features/project/components/ProjectUploadPanel.tsx`
- Create: `frontend/src/features/project/components/ProjectFileBrowser.tsx`
- Create: `frontend/src/features/project/components/ProjectEvidencePanel.tsx`
- Create: `frontend/src/features/project/projectDetailViewModel.ts`
- Create: `frontend/src/features/project/projectDetailViewModel.test.ts`
- Create: `frontend/src/features/project/projectFileRecovery.ts`
- Create: `frontend/src/features/project/projectFileRecovery.test.ts`
- Modify: `frontend/src/features/project/data/projectDataSource.ts`
- Modify: `frontend/src/features/project/data/httpProjectDataSource.ts`
- Modify: `frontend/src/app/App.tsx`
- Test: `frontend/src/features/project/projectDetailViewModel.test.ts`
- Test: `frontend/src/features/project/projectFileRecovery.test.ts`

**Interfaces:**

- Consumes: Task 5's existing minimal `ProjectDetailPage`; Plan B detail/PATCH + CORS, upload, `POST /api/v1/projects/{projectId}/files/{fileId}/recover`, file/content, candidate list/detail, and extraction/source APIs; multipart/recovery conflict semantics; authenticated blob access; real parsed text/extraction/source facts.
- Produces: an in-place extension of the existing blank header; metadata confirmation; one ZIP/PDF/DOCX upload control; explicit pending-file recovery; five approved category groups plus UNKNOWN/unsupported/failed counts; separate original/extracted/structured views.

**Write-accountability matrix:**

| UI command | BUSINESS FACT | OPERATION AUDIT EVENT | FLOWDEBUG EVENT | IDEMPOTENCY FACT |
| --- | --- | --- | --- | --- |
| metadata confirm | Project CAS | `PROJECT_METADATA_CONFIRMED` | metadata update start/complete/conflict | `PROJECT_METADATA_CONFIRM` immutable key |
| upload | root/child ProjectFile, extraction, candidate facts | `PROJECT_FILE_UPLOAD_STARTED/COMPLETED/FAILED` | stage/tx/scan/parse/recovery events | `PROJECT_FILE_UPLOAD` immutable key |
| recover pending file | no new upload/file fact; server resumes or closes original operation | only the original upload's terminal event when newly committed | `PROJECT_UPLOAD_RECOVERY_*` | original server-located `PROJECT_FILE_UPLOAD`; client sends/creates no key |
| reads/previews | none | none | server safe read diagnostics | none |

- [ ] **RED:** Test exact blank header `项目名称：待识别`, `项目编号：待识别`, `项目类目：待提供`, and sole primary CTA `上传项目资料`.
- [ ] Test one chooser accepts only `.zip,.pdf,.docx`; category sections display `合同正文/招标文件/投标文件/中标通知书/其他材料`; UNKNOWN, UNSUPPORTED, FAILED, and MISSING remain distinct; no red stars/dots/priority labels or per-category upload.
- [ ] Test metadata expectedVersion/key retention and 409 refresh path. Test upload progress/replay/key retention, 400/413/401/403 messages through `userFacingError`, and refresh reconstruction from server facts rather than local completion.
- [ ] In `projectDetailViewModel.test.ts`, prove a root `UPLOAD_PENDING` file renders one explicit `继续处理` action; `UPLOAD_FAILED` renders the stable failure and no recovery-as-success; an UNKNOWN/recovery-conflict state remains processing/unknown. The UI never guesses AVAILABLE/FAILED, never auto-recovers during GET/render, and never hides the original file behind a new upload.
- [ ] In `projectFileRecovery.test.ts`, prove `recoverProjectFileAndReload(projectId, fileId)` sends exactly `POST /api/v1/projects/{projectId}/files/{fileId}/recover` with no multipart bytes and no `Idempotency-Key`, then performs GET Project detail and GET Project files before publishing refreshed state. A 409/UNKNOWN keeps the file pending/unknown and exposes retry-safe messaging. A successful replay/recovery publishes only reloaded server facts.
- [ ] Test that an explicit later `上传项目资料` choice is a separate new upload operation with a new upload key only after the user chooses a new file; recovery never rotates, copies, or fabricates that key.
- [ ] Test original vs extracted text tabs, PDF/image authenticated preview, attachment-only Office/unknown, selectable real text, source locator page/paragraph/excerpt, structured groups, `¥128,000.00`, raw Chinese uppercase preservation, nullable/unavailable placeholders, and no fake evidence.
- [ ] Run and confirm missing view model/components fail:

```powershell
cd frontend
node --experimental-strip-types --test src/features/project/projectDetailViewModel.test.ts src/features/project/projectFileRecovery.test.ts
```

- [ ] **GREEN:** Extend the Task 5 `ProjectDetailPage` in place and implement the workspace/subcomponents. Preserve its route, navigation identity, loading/error behavior, ID, nullable header copy, and CTA while wiring the CTA to upload. Reuse shared API/error/authenticated-blob behavior where compatible; keep feature state below `features/project`.
- [ ] Add typed `recoverProjectFile(projectId, fileId)` to `projectDataSource`/`httpProjectDataSource`. It uses the shared authenticated CSRF request path, has no idempotency-key parameter/header, and calls only Plan B's recovery API. `projectFileRecovery.ts` invokes it only from the explicit `继续处理` action, then reloads Project detail and Project files; refresh candidate/extraction/source views from their public endpoints when the returned file facts make those panels relevant.
- [ ] On uncertain uploads, retain the original upload operation key and reload server state. On uncertain recovery, keep the server file `UPLOAD_PENDING`/unknown and offer the same explicit action; do not submit upload bytes or invent a client recovery key. Do not treat client upload/recovery response receipt as durable completion. Keep no delete/archive/category-correction controls.
- [ ] Render exact factual groups: 项目信息、合同基本信息、合同金额、合同明细、中标信息、供应商信息. Render source unavailable explicitly.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor pure format/group/status functions into `projectDetailViewModel.ts`; do not coerce unknown/missing/failed/unsupported together.
- [ ] Run targeted tests and build:

```powershell
cd frontend
node --experimental-strip-types --test src/features/project/projectListViewModel.test.ts src/features/project/projectDetailViewModel.test.ts src/features/project/projectFileRecovery.test.ts
npm.cmd run build
```

- [ ] **Review checkpoint:** Verify factual/nullable presentation, separate upload versus recovery identities, exact POST-then-GET recovery flow, pending/failed/unknown state handling, no mapper/coordinator/storage backdoor, secure preview/download behavior, category semantics, no fabricated conclusions, and accessibility. Resolve all Critical/Important findings.

---

### Task 7: Add candidate confirmation, promotion, direct F02, and Plan A progressive review

**Files:**

- Create: `frontend/src/features/project/components/ContractCandidateReviewPanel.tsx`
- Create: `frontend/src/features/project/components/ProjectContractReviewPanel.tsx`
- Create: `frontend/src/features/project/candidateConfirmation.ts`
- Create: `frontend/src/features/project/candidateConfirmation.test.ts`
- Modify: `frontend/src/features/project/useProjectWorkspace.ts`
- Modify: `frontend/src/features/project/data/projectDataSource.ts`
- Modify: `frontend/src/features/project/data/httpProjectDataSource.ts`
- Modify: `frontend/src/features/project/components/ProjectDetailPage.tsx`
- Test: `frontend/src/features/project/candidateConfirmation.test.ts`
- Test: Plan A's merged `frontend/src/features/contract/reviewProgressState.test.ts`

**Interfaces:**

- Consumes: Plan B candidate detail/evidence APIs backed by the exact ownership-safe ports, Task 3's business-fields-only promotion API, existing `ContractDataSource.reviewContract`, and Plan A `getReviewProgress`, `acknowledgeFinding`, `acknowledgeFindingAndReloadProgress`, and `deriveProgressiveReplayState` contracts.
- Produces: explicit confirmed payload editor; promotion operation; Project-linked Contract summary; direct F02 trigger; progressive review panel with nullable source anchors.

**Write-accountability matrix:**

| UI command | BUSINESS FACT | OPERATION AUDIT EVENT | FLOWDEBUG EVENT | IDEMPOTENCY FACT |
| --- | --- | --- | --- | --- |
| promote | Contract/lines/award + association + candidate PROMOTED | `PROJECT_CONTRACT_CANDIDATE_PROMOTED` | `PROJECT_PROMOTION_START/TX_ENTER/TX_COMMIT/CONFLICT` | `PROJECT_CONTRACT_CANDIDATE_PROMOTE` immutable key |
| direct F02 | existing `AuditTask` + `AuditCheckResult` facts | existing F02 operation audit events unchanged | existing F02 diagnostics unchanged | existing `CONTRACT_F02_REVIEW` key |
| acknowledge | Plan A acknowledgement fact | `REVIEW_FINDING_ACKNOWLEDGED` | `REVIEW_ACK_*` | `REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE` |

- [ ] **RED:** Test candidate fields grouped with MISSING/CONFLICT and exact sources, required confirmed business fields, BigDecimal amount validation, optional line/award business fields, and confirmed payload changes not mutating displayed extraction facts. Assert the TypeScript promotion request type cannot contain Contract/line/award IDs, child contract relationships, status, version, or timestamps.
- [ ] Test promotion key remains stable through unknown/in-progress outcome; same replay returns same Contract; 409 refreshes candidate/associations and never sends a new promotion automatically; button cannot double-submit.
- [ ] Test successful promotion adds the returned Contract summary then requires an explicit direct F02 action. Verify F02 uses the returned real Contract ID, its own independent immutable key, existing synchronous five-rule call, then Plan A progress GET/acknowledgement behavior.
- [ ] Test source anchors navigate to the right file/page/paragraph/excerpt only when `available`; unavailable displays `暂无可靠来源定位`. Test refresh reconstruction and no ReviewSession creation/binding.
- [ ] Run and confirm missing confirmation/promotion integration fails:

```powershell
cd frontend
node --experimental-strip-types --test src/features/project/candidateConfirmation.test.ts src/features/contract/reviewProgressState.test.ts
```

- [ ] **GREEN:** Implement candidate confirmation and promotion with canonical confirmed business fields plus `expectedVersion` only. Never synthesize or send formal IDs/status/version/timestamps. Reload project/candidate/association state after success or replay before enabling F02.
- [ ] Embed or compose the existing F02 workspace/data source rather than duplicating rule execution/progress logic. Preserve Plan A POST-then-GET acknowledgement rule and drawer.
- [ ] Render only `审核完成` plus factual risk summary; no overall approval/pass/fail language. Keep source anchors nullable and server-driven.
- [ ] Run the RED command and confirm it passes.
- [ ] Refactor separate client operation states for promotion, F02 review, and acknowledgement so one key can never substitute for another.
- [ ] Run all relevant frontend tests and build:

```powershell
cd frontend
node --experimental-strip-types --test src/features/project/projectListViewModel.test.ts src/features/project/projectDetailViewModel.test.ts src/features/project/projectFileRecovery.test.ts src/features/project/candidateConfirmation.test.ts src/features/contract/reviewProgressState.test.ts src/features/contract/reviewHistoryViewModel.test.ts src/features/contract/reviewFindingPresentation.test.ts
npm.cmd run build
```

- [ ] **Review checkpoint:** Verify explicit promotion, immutable operation identities, no automatic Contract/review creation, direct F02 reuse, Plan A ownership, source fail-closed behavior, and copy/accessibility. Resolve all Critical/Important findings.

---

## Final Verification and Evidence

- [ ] Re-run the targeted promotion, source, association, F02, and ReviewSession suites. Any failure blocks the gate; diagnose it and return to the owning Task's RED step before changing production code.
- [ ] Run complete backend verification:

```powershell
cd backend
.\mvnw.cmd clean verify
```

- [ ] Run frontend production verification:

```powershell
cd ..\frontend
node --experimental-strip-types --test src/features/project/projectListViewModel.test.ts src/features/project/projectDetailViewModel.test.ts src/features/project/candidateConfirmation.test.ts src/features/contract/reviewProgressState.test.ts src/features/contract/reviewHistoryViewModel.test.ts src/features/contract/reviewFindingPresentation.test.ts
npm.cmd run build
```

- [ ] Run repository hygiene checks:

```powershell
cd ..
git diff --check
git status --short --untracked-files=all
```

- [ ] Real DM8 smoke evidence: apply the dynamically allocated association migration to a representative DM8; inspect PK/FK/UNIQUE constraints including `(PROJECT_ID, CANDIDATE_ID) -> CONTRACT_CANDIDATE(PROJECT_ID, ID)` and String `CREATED_BY -> SYS_USER(ID)`; attempt a cross-Project candidate association and prove rejection. Promote happy path; inject rollback at Contract/line/award/association/candidate/OperationAudit/idempotency steps; run same-key replay and concurrent different-key promotion; prove one Contract/association/audit result and no orphans. Record DM8 version, sanitized commands/results, and row counts. Unit/structure/local-memory tests never make atomic rollback `VERIFIED`.
- [ ] Real filesystem evidence: reuse Plan B evidence for persisted-key-only original/parsed source reopen and explicit ProjectFile recovery after restart, then verify source-link content remains authenticated and path-free. Record platform and observations or mark target filesystem `NOT VERIFIED`.
- [ ] Redis/session impact: no new Redis business facts, queues, locks, or idempotency keys. With the actual session profile, verify login/CSRF across create/upload/promote/F02/ack; simulate session loss and prove it causes re-login while DM8 Project/Contract/review facts remain. Record evidence or mark real Redis behavior `NOT VERIFIED`.
- [ ] Browser manual E2E: list -> `+ 新建项目` -> immediate blank detail -> unified ZIP/PDF/DOCX upload -> simulate/observe `UPLOAD_PENDING` -> explicit `继续处理` POST with no new key/bytes -> Project/File GET refresh -> categories/other/status counts -> original vs real extracted text -> structured facts/source anchors -> candidate confirmation -> promotion -> explicit direct F02 -> progressive pause/ack/GET -> drawer -> refresh/restart reconstruction. Also verify `UPLOAD_FAILED`, recovery UNKNOWN/409 remains pending, explicit new upload is a separate operation, 401/403, IDOR-safe 404, multipart 413, 409 replay/conflict, narrow viewport, keyboard/focus, and `userFacingError` messages.
- [ ] Verify copy/data prohibitions manually: no create modal, fake phone/blacklist/collusion, fake priority markers, approval/pass/fail conclusion, delete/archive/category-correction action, Project-to-ReviewSession binding, or mock content.
- [ ] **Review checkpoint:** Review the combined diff for sole same-Project association, server-owned identities, String actor types, neutral local store, insert-only write boundary, correctly scoped transaction claims, upload/recovery identity separation, public API/application-only Plan B consumption, OperationAudit payload safety/FlowDebug separation, source fail-closed behavior, unchanged Contract/F02/ReviewSession paths, frontend factual UX, and evidence labels. Resolve all Critical/Important findings before completion.

## Completion Evidence Checklist

- [ ] Plans A and B were merged first and their actual interfaces were reread from latest `main`.
- [ ] `PROJECT_CONTRACT` is the sole relation; no Contract `PROJECT_ID` exists.
- [ ] The creation boundary is insert-only and the existing Contract read aggregate is unchanged.
- [ ] Promotion replay/concurrency/rollback prove one atomic result with no overwritten or orphan facts/audit.
- [ ] The atomic rollback claim above is labeled `VERIFIED` only with representative DM8 evidence; local profile proves semantic read-after-write/concurrency behavior, not rollback across memory adapters.
- [ ] Source projection is unavailable whenever ownership, version, value, conflict state, or locator is unproven; result persistence is unchanged.
- [ ] Project create/list/detail, metadata PATCH+CORS, upload, explicit pending-file recovery, content, evidence/source, candidate list/detail, promotion, direct-F02, and progressive-review experience uses public Plan B/application contracts, real server facts, and approved nullable copy; no mapper/recovery backdoor exists.
- [ ] Recovery sends no bytes/new key, POSTs explicitly, then reloads Project/File facts; UPLOAD_FAILED and UNKNOWN/409 remain factual and a new upload is a separate user operation.
- [ ] Full backend verification, frontend production build, repository checks, and manual E2E are recorded.
- [ ] Real DM8/filesystem/session claims remain `NOT VERIFIED` unless the stated direct evidence was collected.
