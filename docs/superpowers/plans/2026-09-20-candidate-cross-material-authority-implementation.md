# Candidate Cross-Material Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit, durable `AWARD_NOTICE` support authority to the existing DRAFT `ContractCandidate` lifecycle while preserving exact primary-only compatibility, Candidate identity, Confirmation/Promotion contracts, ReviewRun authority, and F02 semantics.

**Architecture:** Extend the existing Candidate aggregate with nullable support-cohort metadata and a Candidate-owned current support-association table. A short, idempotent replacement transaction locks all primary/support files in canonical UUID order before Project and Candidate, resolves exact server-side authority, rematerializes the same Candidate through the existing assembler, and commits the cohort plus Candidate CAS atomically. Empty support uses `PRIMARY_ONLY_COMPAT`; non-empty support uses `EXPLICIT_SUPPORT_AUTHORITY`. Confirmation and Promotion reuse the current authority validator, while Finding projection preserves the legacy primary-only fallback and becomes exact-association-only in explicit mode.

**Tech Stack:** Java 17, Spring Boot, MyBatis/DM8 SQL, JUnit 5, Mockito, MockMvc, React 18, TypeScript, Node test runner, Vite, Python 3.11/FastAPI pytest regression gate.

**Design:** `docs/superpowers/specs/2026-09-20-candidate-cross-material-authority-design.md`

**Design commit:** `147e5942cdbabcba5191a2ca9dea8acde86f3475`

**Baseline before design:** `9ef79de6d5d6fc53f9fe29699dd1934344b86106`

**Architecture impact:** `LOCAL EXTENSION`

## Global Constraints

- Preserve the existing chain: `ProjectFile -> ParseResult -> RoleDecision -> IdentityDecision -> ContractCandidate -> Confirmation -> Promotion -> Contract -> ReviewRun -> F02`.
- Do not introduce `CandidateV2`, `CrossMaterialCandidateEngine`, a parallel Candidate lifecycle, a new Promotion contract, a new ReviewRun source, new F02 semantics, or AI authority.
- Keep `sourceContractFileId`, `sourceParseResultId`, `sourceRoleDecisionId`, `sourceIdentityDecisionId`, `sourceAuthoritySource`, and `sourceFactFingerprint` primary-`CONTRACT_BODY` only.
- `PRIMARY_ONLY_COMPAT` means the exact current Candidate fact behavior and the exact current `FindingSourceProjectionService` legacy cross-file award-evidence fallback.
- `EXPLICIT_SUPPORT_AUTHORITY` means primary non-award facts plus only the three allowed facts from exact assigned current `AWARD_NOTICE` authority: `AWARD_AMOUNT`, `AWARD_SUPPLIER`, and `AWARD_SUPPLIER_CREDIT_CODE`.
- Never implement the modes as an `AWARD_NOTICE > CONTRACT_BODY` ranking. A non-empty support set changes the allow-list; replacing it with `[]` restores the primary-only policy.
- Every newly committed replacement, including `[] -> []`, increments Candidate version and support-authority version exactly once. Same-key replay never increments again.
- Keep the idempotency command fingerprint limited to schema token, actor, project, Candidate, expected Candidate version, and canonical support file IDs. Mutable authority belongs only in `supportAuthorityFingerprint`.
- Keep support-replacement recovery endpoint-local. Its only machine classifications are `OUTCOME_UNCERTAIN`, `REQUEST_REJECTED`, `RESULT_SUPERSEDED`, and `INTEGRITY_CONFLICT`, transported through the existing `ApiResponse.data` / `ApiError.data` seam; never infer them from English messages or alter the global error contract.
- Keep completed Confirmation replay based on its immutable confirmation tuple and canonical payload. Do not add support fields to its schema, public payload, or command fingerprint.
- Lock in the fixed order: non-lock Candidate discovery; canonical sorted union of primary/support ProjectFiles; all File locks; Project lock; Candidate lock; recheck; authority resolution.
- Do not modify V001-V022 or any existing `CONTRACT__*.sql` file. The new migration is `CONTROLLED_MANUAL`, is never run by application startup or tests, and is never named V023.
- Do not claim DM8 transaction, composite-FK, or deadlock behavior from H2, mocks, in-memory adapters, structure tests, or method names. Task 19 requires representative DM8 evidence.
- Do not log source text, excerpts, filenames, monetary values, customer names, prompts, model responses, credentials, or connection strings. Audit/log evidence is limited to IDs, counts, versions, safe enums, reason codes, and hashes.
- Execute from `D:\code\audit_agent_1D\.worktrees\candidate-cross-material-authority-20260920`. Do not push, merge, deploy, or execute a migration while implementing this plan unless a later, separate authorization explicitly grants the controlled DM8 action.

## Review Focus

- Primary-only current and historical rows remain byte-for-byte/field-for-field compatible.
- Explicit support never trusts classifier category, browser-submitted authority tuples, machine-only role proposals, stale decisions, or project-wide scans.
- Candidate/source/cohort persistence cannot be partially committed.
- Same-key replay is bounded and fail closed: current result replays; an unproven outcome retains the same operation identity; a deterministic `REQUEST_REJECTED` may guarded-clear the sent operation; and `RESULT_SUPERSEDED` may do so only after a reload proves the Candidate has advanced. Neither path posts again in the same user action.
- Finding evidence has a deliberate dual mode: legacy compatibility when support is empty, exact association when support is non-empty.
- Confirmation, Promotion, ReviewRun, and F02 public/business contracts do not change.

## Prerequisite

- [ ] Verify the approved design checkout before any implementation:

```powershell
git branch --show-current
git rev-parse HEAD
git status --short
git log -3 --oneline
```

Expected: branch `feature/candidate-cross-material-authority-20260920`, HEAD `147e5942cdbabcba5191a2ca9dea8acde86f3475`, and empty `git status --short`.

---

### Task 1: Define the controlled DM8 support-authority migration contract

**Files:**

- Create: `db/dm8/CONTRACT__contract_candidate_support_authority.sql`
- Create: `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractCandidateSupportAuthorityDm8SqlStructureTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractEvolutionMigrationOrderDocumentationTest.java`
- Modify: `db/dm8/README.md`
- Read-only references: `db/dm8/CONTRACT__project_file_parse_result.sql`, `db/dm8/CONTRACT__project_file_role_assessment.sql`, `db/dm8/CONTRACT__contract_candidate_authority.sql`, `db/dm8/CONTRACT__project_file_identity_assessment.sql`, `db/dm8/CONTRACT__contract_candidate_confirmation.sql`

**Step 1: Write the failing structure tests**

- [ ] Create `ContractCandidateSupportAuthorityDm8SqlStructureTest` before the SQL file. Assert the exact six-line header:

```text
-- MIGRATION-NAMESPACE: CONTRACT
-- MIGRATION-ID: contract-candidate-support-authority
-- DEPENDS-ON: project-file-identity-assessment,contract-candidate-authority
-- PURPOSE: bind candidate award support to exact current file parse role identity and fact authority
-- EXECUTION: CONTROLLED_MANUAL
-- IDEMPOTENCY: NOT_ASSUMED
```

- [ ] Assert additive Candidate columns `SUPPORT_AUTHORITY_VERSION_NO BIGINT` and `SUPPORT_AUTHORITY_FINGERPRINT VARCHAR(64)`, the null/null-or-complete tuple check, positive version, lowercase 64-hex fingerprint, and unique `(PROJECT_ID, ID, SUPPORT_AUTHORITY_VERSION_NO)` cohort ownership.
- [ ] Assert `CONTRACT_CANDIDATE_SUPPORT_AUTHORITY` has the exact domain columns from design section 9; candidate/cohort, project/file, exact parse/hash/version, exact role/hash/version/selected-role, exact identity/project-version/hash/version/resolution, and actor FKs; role/identity checks; positive versions; lowercase 64-hex checks; Candidate/file uniqueness; and all five indexes.
- [ ] Assert the migration adds, rather than weakens, the missing parent composite UKs needed for exact role and identity FKs. Assert existing extraction Candidate/source and Candidate/parse FKs are never dropped.
- [ ] Extend `ContractEvolutionMigrationOrderDocumentationTest` so the README lists the new file after `CONTRACT__project_file_identity_assessment.sql` and before `CONTRACT__review_session_project_file_binding.sql`, while still stating that dependency metadata—not alphabetical order—controls execution.
- [ ] Assert the SQL and README do not contain `V023`, `DROP CONSTRAINT`, data backfill, or startup migration instructions.

**Step 2: Run the exact RED command**

From `backend`:

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityDm8SqlStructureTest,ContractEvolutionMigrationOrderDocumentationTest test
```

Expected: **FAIL** because `CONTRACT__contract_candidate_support_authority.sql` does not exist and the README/order test does not yet name the migration.

**Step 3: Implement the minimal controlled migration contract**

- [ ] Create only the new SQL file. Add Candidate support metadata and these additive parent keys:

```sql
UNIQUE (DECISION_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, SELECTED_ROLE)
UNIQUE (DECISION_ID, PROJECT_ID, PROJECT_VERSION_NO, FILE_ID, FILE_SHA256, PARSE_RESULT_ID, PARSE_VERSION, RESOLUTION)
```

- [ ] Create `CONTRACT_CANDIDATE_SUPPORT_AUTHORITY` with current-cohort rows and no `ACTIVE`, row-version, or historical-cohort promise. Use `AUTHORITY_VERSION_NO` on association rows and `SUPPORT_AUTHORITY_VERSION_NO` on Candidate.
- [ ] Add indexes for `(PROJECT_ID,CANDIDATE_ID,AUTHORITY_VERSION_NO,SOURCE_FILE_ID)`, `(PROJECT_ID,SOURCE_FILE_ID,CANDIDATE_ID)`, role-decision lookup, identity-decision lookup, and `(PROJECT_ID,SOURCE_FILE_ID,PARSE_RESULT_ID)` evidence lookup.
- [ ] Update the README table and dependency diagram with an explicit branch after identity assessment. State that P1-B application writes may be enabled only after this migration is approved/applied; do not edit any existing migration header.
- [ ] Do not execute this SQL.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityDm8SqlStructureTest,ContractEvolutionMigrationOrderDocumentationTest test
```

Expected: **PASS**, proving the new controlled file and dependency documentation meet the static contract.

**Step 5: Run nearby migration regressions**

```powershell
.\mvnw.cmd -Dtest=ContractMigrationNamespacePolicyTest,ContractCandidateAuthorityDm8SqlStructureTest,ContractProjectFileIdentityDm8SqlStructureTest,ContractCandidateConfirmationDm8SqlStructureTest test
```

Expected: **PASS**; V001-V022 and all pre-existing Contract migrations remain untouched.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- db/dm8/CONTRACT__contract_candidate_support_authority.sql db/dm8/README.md backend/src/test/java/com/fuquan/audit/integration/dm8/ContractCandidateSupportAuthorityDm8SqlStructureTest.java backend/src/test/java/com/fuquan/audit/integration/dm8/ContractEvolutionMigrationOrderDocumentationTest.java
```

Expected: no whitespace errors and no migration outside the four listed files.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: define candidate support authority schema"
```

---

### Task 2: Extend `ContractCandidate` with compatible support metadata

**Files:**

- Create: `backend/src/test/java/com/fuquan/audit/contract/domain/project/ContractCandidateSupportMetadataTest.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ContractCandidate.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidatePersistenceRow.java`
- Modify: `backend/src/main/resources/mappers/dm8/ContractCandidateMapper.xml`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectRepositories.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ProjectContractAdapterTest.java`

**Step 1: Write the failing domain and persistence tests**

- [ ] Add tests proving historical `null/null` metadata is valid; exactly one null is rejected; version must be positive; fingerprint must match `[0-9a-f]{64}`; and explicit-clear metadata is valid even with no support rows.
- [ ] Exercise every historical constructor/factory and assert it supplies `null/null` without changing primary source fields.
- [ ] Assert `reauthorize`, `confirmed`, and both `promote` overloads preserve support metadata. Assert primary reauthorization never silently clears the cohort metadata.
- [ ] Extend the DM8 adapter test to round-trip both `null/null` and non-null metadata and require the mapper column list, insert placeholders, and row/domain mapping to include both fields.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportMetadataTest,Dm8ProjectContractAdapterTest test
```

Expected: **FAIL** at compilation because `ContractCandidate` and `ContractCandidatePersistenceRow` do not expose `supportAuthorityVersionNo` or `supportAuthorityFingerprint`.

**Step 3: Implement the minimal compatible metadata extension**

- [ ] Add nullable `Long supportAuthorityVersionNo` and `String supportAuthorityFingerprint` to the existing record, not a new Candidate type. Treat this version as an independent support-cohort counter: Confirmation/Promotion Candidate version changes preserve it and never increment it.
- [ ] Add the pair invariant to the compact constructor. Keep all existing constructors and factories as compatibility overloads that pass `null, null`.
- [ ] Thread the metadata unchanged through `reauthorize`, `confirmed`, and `promote`.
- [ ] Add `SUPPORT_AUTHORITY_VERSION_NO` and `SUPPORT_AUTHORITY_FINGERPRINT` to `ContractCandidatePersistenceRow`, `ContractCandidateMapper.xml` selects/inserts, and `Dm8ProjectRepositories.row(...)`/`candidateDomain(...)`.
- [ ] Do not change primary authority fields, Candidate Confirmation persistence, or public API in this task.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportMetadataTest,Dm8ProjectContractAdapterTest test
```

Expected: **PASS** for historical and new rows.

**Step 5: Run nearby domain/persistence regressions**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateIdentityAuthorityTest,ContractCandidateConfirmationServiceTest,ContractCandidatePromotionServiceTest,ContractProjectFileIdentityDm8SqlStructureTest test
```

Expected: **PASS** with no confirmation or promotion contract changes.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/domain/project/ContractCandidate.java backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidatePersistenceRow.java backend/src/main/resources/mappers/dm8/ContractCandidateMapper.xml backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectRepositories.java
```

Expected: only additive metadata/persistence changes.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: persist candidate support metadata"
```

---

### Task 3: Add the bounded support-authority domain and repository port

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/CandidateAuthorityMode.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ContractCandidateSupportAuthority.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ContractCandidateSupportAuthorityRepository.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/infra/memory/project/InMemoryContractCandidateSupportAuthorityRepository.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/domain/project/ContractCandidateSupportAuthorityTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/infra/memory/project/InMemoryContractCandidateSupportAuthorityRepositoryTest.java`

**Step 1: Write the failing domain and in-memory repository tests**

- [ ] Assert every durable attribute from design section 9 is required and that role is exactly `AWARD_NOTICE`, identity resolution is exactly `CONFIRM_SAME_PROJECT`, versions are positive, and hashes/fingerprints are lowercase 64-hex.
- [ ] Assert repository results are project/Candidate scoped and stable-sorted by canonical `sourceFileId` text.
- [ ] Assert one Candidate/file duplicate is rejected, while the same support file can belong to two different Candidates.
- [ ] Assert delete-plus-insert and full-set replacement produce a single deterministic current cohort, and foreign-project association insertion fails closed.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityTest,InMemoryContractCandidateSupportAuthorityRepositoryTest test
```

Expected: **FAIL** because the domain type, mode enum, port, and local adapter do not exist.

**Step 3: Implement the minimal bounded model and port**

- [ ] Define only `PRIMARY_ONLY_COMPAT` and `EXPLICIT_SUPPORT_AUTHORITY` in `CandidateAuthorityMode`.
- [ ] Implement the immutable `ContractCandidateSupportAuthority` record with exact validations.
- [ ] Define repository operations with explicit ownership parameters:

```java
List<ContractCandidateSupportAuthority> findCurrentByProjectIdAndCandidateId(UUID projectId, UUID candidateId);
List<ContractCandidateSupportAuthority> findCurrentByProjectIdAndCandidateIdForUpdate(UUID projectId, UUID candidateId);
Optional<ContractCandidateSupportAuthority> findByProjectIdAndCandidateIdAndSourceFileId(UUID projectId, UUID candidateId, UUID sourceFileId);
void replaceCurrentCohort(UUID projectId, UUID candidateId, List<ContractCandidateSupportAuthority> cohort);
void deleteCurrentByProjectIdAndCandidateId(UUID projectId, UUID candidateId);
void insertAll(List<ContractCandidateSupportAuthority> cohort);
```

- [ ] Keep authority resolution and business validation out of the repository. The in-memory adapter mirrors DM8 Candidate/file uniqueness and project ownership instead of accepting states DM8 would reject.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityTest,InMemoryContractCandidateSupportAuthorityRepositoryTest test
```

Expected: **PASS** for domain invariants and current-cohort semantics.

**Step 5: Run nearby repository/domain regressions**

```powershell
.\mvnw.cmd -Dtest=InMemoryProjectFileIdentityRepositoriesTest,InMemoryProjectFileRoleRepositoriesTest,ContractCandidateSupportMetadataTest test
```

Expected: **PASS**; no primary authority repository behavior changes.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/domain/project backend/src/main/java/com/fuquan/audit/contract/application/project/port/ContractCandidateSupportAuthorityRepository.java backend/src/main/java/com/fuquan/audit/contract/infra/memory/project/InMemoryContractCandidateSupportAuthorityRepository.java
```

Expected: one bounded domain/port/adapter, no CandidateV2 or engine.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: model candidate support authority"
```

---

### Task 4: Implement the exact DM8 support-authority repository

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateSupportAuthorityPersistenceRow.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateSupportAuthorityMapper.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ContractCandidateSupportAuthorityRepository.java`
- Create: `backend/src/main/resources/mappers/dm8/ContractCandidateSupportAuthorityMapper.xml`
- Create: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ContractCandidateSupportAuthorityRepositoryTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractCandidateSupportAuthorityMapperStructureTest.java`
- Read-only assertion target: `backend/src/main/resources/mappers/dm8/ProjectExtractionResultMapper.xml`

**Step 1: Write the failing mapper and adapter tests**

- [ ] Mock the mapper and prove row/domain conversion preserves the entire exact authority tuple.
- [ ] Require current and `FOR UPDATE` cohort queries to bind both `PROJECT_ID` and `CANDIDATE_ID`, order by `SOURCE_FILE_ID`, and never use `findByProjectId()` plus a Java category filter.
- [ ] Require exact single-association lookup to bind project, Candidate, and source file; delete to bind project and Candidate; and `insertAll` to insert each supplied immutable row without rewriting it.
- [ ] In the structure test, also assert `ProjectExtractionResultMapper.xml#findBySourceAndParse` binds `PROJECT_ID`, `SOURCE_FILE_ID`, and `PARSE_RESULT_ID`, because every later support fact/evidence read must use that exact query.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=Dm8ContractCandidateSupportAuthorityRepositoryTest,ContractCandidateSupportAuthorityMapperStructureTest test
```

Expected: **FAIL** because the mapper, XML, persistence row, and DM8 adapter are absent.

**Step 3: Implement the minimal DM8 adapter**

- [ ] Follow the existing `Dm8ContractCandidateConfirmationRepository` profile/style: `@Repository`, profiles `local-dev`, `dev29`, and `customer`, UUID/string conversion, UTC timestamps, and no HTTP/application exceptions for empty reads.
- [ ] Implement `findCurrentByProjectIdAndCandidateId`, its `FOR UPDATE` counterpart, exact Candidate/file lookup, Candidate-scoped delete, and deterministic `insertAll`.
- [ ] Keep all SQL Candidate/project scoped and keep extraction reads on existing `findBySourceAndParse`.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=Dm8ContractCandidateSupportAuthorityRepositoryTest,ContractCandidateSupportAuthorityMapperStructureTest test
```

Expected: **PASS** with stable ordering and exact scope.

**Step 5: Run nearby mapper regressions**

```powershell
.\mvnw.cmd -Dtest=Dm8ProjectContractAdapterTest,Dm8ProjectFileIdentityRepositoriesTest,Dm8RecordPersistenceMapperStructureTest test
```

Expected: **PASS**; existing mapper registrations and primary Candidate persistence remain valid.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateSupportAuthorityPersistenceRow.java backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateSupportAuthorityMapper.java backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ContractCandidateSupportAuthorityRepository.java backend/src/main/resources/mappers/dm8/ContractCandidateSupportAuthorityMapper.xml
```

Expected: no project-wide category scan and no migration execution logic.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: persist candidate support cohorts"
```

---

### Task 5: Extend the existing assembler with the two-mode fact policy

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/extraction/ContractCandidateDerivedFacts.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/extraction/ContractCandidateFactPolicy.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/extraction/ContractCandidateAssembler.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/extraction/ContractCandidateAssemblerTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/extraction/ContractCandidateFactPolicyTest.java`

**Step 1: Write the failing policy/assembler tests**

- [ ] Snapshot every field and line item produced by the current primary-only assembler, including primary `AWARD_*`, and assert `PRIMARY_ONLY_COMPAT` returns the same output.
- [ ] Assert primary `AWARD_AMOUNT=100` plus one support `AWARD_AMOUNT=98` in explicit mode yields `98` and one authoritative value; primary `100` is excluded, not compared.
- [ ] Assert two support notices with `98` and `99` yield `null`/conflict, and two normalized-equal values collapse to one Candidate value while source evidence remains separate.
- [ ] Assert support `CONTRACT_NO`, `AMOUNT_NUMERIC`, `PARTY_B`, `CREDIT_CODE`, and every `LINE_ITEM_*` fact are rejected by the support allow-list and cannot modify the Candidate.
- [ ] Assert explicit primary facts admit only the approved non-award list and that support line items never enter grouping.
- [ ] Assert deterministic sorting by role, source UUID, parse version, fact type, group key, locator, and extraction key makes reversed inputs identical.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateAssemblerTest,ContractCandidateFactPolicyTest test
```

Expected: **FAIL** because the mode-aware policy and derived-facts projection do not exist and the current assembler mixes primary award facts into every call.

**Step 3: Implement the minimal assembler extension**

- [ ] Keep all existing `assemble(...)` overloads and make them delegate to `PRIMARY_ONLY_COMPAT` so current materialization remains unchanged.
- [ ] Add one bounded mode-aware method returning `ContractCandidateDerivedFacts` from the existing Candidate ID, primary file/facts, explicit support facts, and `CandidateAuthorityMode`.
- [ ] Put only the two allow-lists and deterministic source ordering in `ContractCandidateFactPolicy`; do not create a second Candidate engine. The explicit primary allow-list is exactly `CONTRACT_NO`, `CONTRACT_NAME`, `PARTY_A`, `PARTY_B`, `CREDIT_CODE`, `AMOUNT_NUMERIC`, `AMOUNT_UPPERCASE`, `LINE_ITEM_KEY`, `LINE_ITEM_NAME`, `LINE_ITEM_QUANTITY`, `LINE_ITEM_UNIT_PRICE`, and `LINE_ITEM_DECLARED_AMOUNT`; the support allow-list is exactly the three `AWARD_*` facts.
- [ ] Reuse the current `BigDecimal.compareTo` distinct-value logic and text normalization. The derived-facts record contains only replaceable scalar fields and line items; it contains no primary authority identity.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateAssemblerTest,ContractCandidateFactPolicyTest test
```

Expected: **PASS** for exact compatibility, explicit authority, conflict, and disallowed-fact cases.

**Step 5: Run nearby materialization regressions**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateMaterializationServiceTest,ProjectFactReplayIntegrityTest,ProjectApplicationServiceTest test
```

Expected: **PASS**; current one-file Candidate materialization output does not change.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/project/extraction backend/src/test/java/com/fuquan/audit/contract/application/project/extraction
```

Expected: one small policy helper, no CandidateV2 or cross-material engine.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: apply candidate support fact policy"
```

---

### Task 6: Add atomic DRAFT derived-fact CAS persistence

**Files:**

- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ContractCandidateRepository.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/infra/memory/project/InMemoryProjectRepositories.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateMapper.java`
- Modify: `backend/src/main/resources/mappers/dm8/ContractCandidateMapper.xml`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectRepositories.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/infra/memory/project/ContractCandidateDerivedFactsCasRepositoryTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractCandidateDerivedFactsCasMapperTest.java`

**Step 1: Write the failing CAS tests**

- [ ] Specify this exact port seam:

```java
ContractCandidate replaceDraftDerivedFactsIfVersionMatches(
        UUID projectId,
        UUID candidateId,
        long expectedVersion,
        ContractCandidateDerivedFacts facts,
        long supportAuthorityVersionNo,
        String supportAuthorityFingerprint,
        Instant updatedAt);
```

- [ ] Assert success preserves Candidate ID, project, all six primary authority fields, creation time, and DRAFT state; replaces every scalar and all line items; writes support metadata; increments Candidate version exactly once; and advances `updatedAt`.
- [ ] Assert wrong version, missing Candidate, and PROMOTED Candidate reject without changing Candidate or line items.
- [ ] Assert the DM8 `UPDATE` has project, Candidate, `PROMOTION_STATE='DRAFT'`, and version predicates; does not set any primary authority column; and is followed only after one affected row by Candidate-scoped line delete and insert.
- [ ] Inject line-delete and line-insert failures in the adapter/coordinator test boundary and assert failure propagates so the surrounding transaction can roll back; do not claim real DM8 rollback until Task 19.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateDerivedFactsCasRepositoryTest,ContractCandidateDerivedFactsCasMapperTest test
```

Expected: **FAIL** because the repository and mapper have no derived-fact CAS operation.

**Step 3: Implement the minimal CAS seam**

- [ ] Add the port method and implement it in the existing local repository and `Dm8ProjectRepositories` Candidate adapter.
- [ ] Add one guarded Candidate update, one Candidate-scoped line delete, and deterministic line inserts. Treat an affected-row count other than one as `BusinessErrorCode.CONFLICT` before deleting lines.
- [ ] Return the reloaded Candidate and verify its version is exactly `expectedVersion + 1` and its support metadata equals the command result.
- [ ] Rely on the caller's Spring transaction for all-or-nothing DM8 behavior; do not introduce a second transaction or cache fact source.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateDerivedFactsCasRepositoryTest,ContractCandidateDerivedFactsCasMapperTest test
```

Expected: **PASS** for success, conflict, DRAFT fence, line replacement, and primary-field preservation.

**Step 5: Run nearby Candidate CAS regressions**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateConfirmationServiceTest,ContractCandidatePromotionConcurrencyTest,ContractCandidateMaterializationServiceTest,Dm8ProjectContractAdapterTest test
```

Expected: **PASS**; confirmation, promotion, and primary reauthorization CAS remain unchanged.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/project/port/ContractCandidateRepository.java backend/src/main/java/com/fuquan/audit/contract/infra/memory/project/InMemoryProjectRepositories.java backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateMapper.java backend/src/main/resources/mappers/dm8/ContractCandidateMapper.xml backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectRepositories.java
```

Expected: one DRAFT/version-guarded derived-fact replacement seam; no primary authority update.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: replace candidate derived facts by CAS"
```

---

### Task 7: Resolve and validate complete support authority

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/CandidateSupportAuthorityValidator.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ResolvedCandidateSupportAuthority.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ResolvedCandidateSupportAuthoritySet.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/CandidateSupportAuthorityValidatorTest.java`
- Read-only integration target: `backend/src/main/java/com/fuquan/audit/contract/application/project/CandidateSourceAuthorityValidator.java`

**Step 1: Write the failing authority-resolution tests**

- [ ] Test fresh replacement resolution for an empty set and for one/multiple requested support files. The browser supplies only IDs; the validator resolves file, latest parse, current role, current identity, exact decisions, project version, and eligible facts.
- [ ] Reject each condition independently: foreign project, unavailable file, non-PARSED file, missing/empty/stale parse, hash mismatch, parse-version mismatch, wrong role, classifier-only role, machine-only role, stale role decision, `UNKNOWN`, `CONFIRM_NOT_SAME_PROJECT`, missing/stale identity decision, project-version mismatch, and support fact tuple mismatch.
- [ ] Test current-cohort validation for historical `null/null` plus no rows, explicit-clear non-null metadata plus no rows, exact non-empty cohort, cohort version mismatch, missing/extra row, row fingerprint mismatch, and recomputed aggregate fingerprint mismatch.
- [ ] Assert per-source and aggregate fingerprints are deterministic lowercase SHA-256 and contain mutable authority/fact inputs, while no method returns or logs raw text/excerpts/values.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=CandidateSupportAuthorityValidatorTest test
```

Expected: **FAIL** because the bounded support validator and resolved snapshots do not exist.

**Step 3: Implement the minimal server-owned validator**

- [ ] Inject `ProjectRepository`, `ProjectFileRepository`, `ProjectFileParseResultRepository`, `ProjectFileRoleDecisionRepository`, `ProjectFileIdentityDecisionRepository`, `EffectiveDocumentRoleResolver`, `EffectiveProjectIdentityResolver`, `ExtractionResultRepository`, and `ContractCandidateSupportAuthorityRepository`.
- [ ] Provide two explicit operations: resolve a fresh canonical requested set under already-held locks, and validate the stored current set against Candidate support metadata.
- [ ] Use only exact `findBySourceFileIdAndParseResultId` facts; apply only `AWARD_AMOUNT`, `AWARD_SUPPLIER`, and `AWARD_SUPPLIER_CREDIT_CODE`; stable-sort before hashing; and represent explicit empty with the versioned `PRIMARY_ONLY_COMPAT` empty marker. The aggregate authority fingerprint includes mode, exact file IDs/hashes, parse IDs/versions, role decisions, identity decisions/project versions, and eligible fact identities/fingerprints.
- [ ] Leave `CandidateSourceAuthorityValidator.snapshot()` and its callers unchanged until Tasks 12-13 wire this bounded validator into first Confirmation and Promotion.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=CandidateSupportAuthorityValidatorTest test
```

Expected: **PASS** for every fail-closed authority case and deterministic fingerprint.

**Step 5: Run nearby role/identity regressions**

```powershell
.\mvnw.cmd -Dtest=CandidateSourceAuthorityValidatorTest,EffectiveDocumentRoleResolverTest,EffectiveProjectIdentityResolverTest test
```

Expected: **PASS**; existing primary `.snapshot()` semantics are not changed yet.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/project/CandidateSupportAuthorityValidator.java backend/src/main/java/com/fuquan/audit/contract/application/project/ResolvedCandidateSupportAuthority.java backend/src/main/java/com/fuquan/audit/contract/application/project/ResolvedCandidateSupportAuthoritySet.java
```

Expected: a bounded support seam, not a replacement for primary authority validation.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: validate candidate support authority"
```

---

### Task 8: Implement the short support-replacement transaction

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ReplaceContractCandidateSupportAuthorityCommand.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementResult.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementService.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementTransactionCoordinator.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementTransactionCoordinatorTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementLockOrderTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportTestSupport.java`

**Step 1: Write the failing transaction and lock-order tests**

- [ ] Cover fresh `[] -> [A]`, `[A] -> [B]`, `[A] -> []`, and `[] -> []`. Assert same Candidate ID and primary tuple, mode/facts change, Candidate and support versions increment exactly once, line items are replaced from primary only, and old confirmation version is fenced.
- [ ] Record repository calls and require: non-lock Candidate discovery; de-duplicated union of primary and supports; canonical UUID-text sorting; every `findByProjectIdAndIdForUpdate`; `ProjectRepository.findByIdForUpdate`; `ContractCandidateRepository.findByProjectIdAndIdForUpdate`; then source/version/DRAFT recheck and authority resolution. Assert no Project lock precedes a support-file lock.
- [ ] Assert browser-supplied parse/role/identity/fingerprint fields are impossible because the command contains only project, Candidate, expected version, support IDs, idempotency key, and `ProjectRequestMetadata`.
- [ ] Assert the stable command fingerprint changes for actor/project/Candidate/version/canonical support IDs and does not change when file hash, parse, decisions, project version, or facts change.
- [ ] Inject failures after support delete, Candidate CAS/line replacement, support insert, audit append, and idempotency success. Verify the coordinator throws, invokes rollback of an uncommitted local claim, and never reports success; reserve actual database rollback proof for Task 19.
- [ ] Race two different expected-version replacements and assert at most one local transaction reaches committed result and no mixed cohort is observable.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportReplacementTransactionCoordinatorTest,ContractCandidateSupportReplacementLockOrderTest test
```

Expected: **FAIL** because the command, service, coordinator, lock protocol, and replacement transaction do not exist.

**Step 3: Implement the minimal transaction**

- [ ] Authenticate in `ContractCandidateSupportReplacementService`; validate key with the existing `^[A-Za-z0-9._:-]{1,128}$` contract; canonicalize/de-duplicate support IDs; and delegate to one `@Transactional` coordinator method.
- [ ] Compute the stable command fingerprint before mutable authority resolution using version token `contract-candidate-support-replace-v1`, actor, project, Candidate, expected version, and sorted IDs only.
- [ ] Apply the fixed lock order, then re-read Candidate and reject changed primary source, non-DRAFT, or wrong version before fresh authority resolution.
- [ ] For a fresh operation, resolve exact authority, calculate `nextSupportAuthorityVersion` (`null -> 1`, otherwise `+1`), assemble mode-specific facts, and claim scope `CONTRACT_CANDIDATE_SUPPORT_REPLACE` with result resource `CONTRACT_CANDIDATE`.
- [ ] In this order inside the same transaction: delete old support rows; Candidate derived-fact/line CAS; insert the complete new non-empty cohort; append a sanitized `PROJECT_CANDIDATE_SUPPORT_REPLACED` operation audit; mark idempotency succeeded.
- [ ] The response contains Candidate ID/version, support version, authority mode, current support projections, and `replayed`; it contains no raw evidence text.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportReplacementTransactionCoordinatorTest,ContractCandidateSupportReplacementLockOrderTest test
```

Expected: **PASS** for all mode transitions, stable fingerprint, exact lock order, failure injection, and local concurrency.

**Step 5: Run nearby write-workflow regressions**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateMaterializationServiceTest,ContractCandidateConfirmationServiceTest,ContractCandidatePromotionConcurrencyTest,RoleConfirmationServiceTest,IdentityConfirmationServiceTest test
```

Expected: **PASS**; existing File-to-Project authority flows remain green.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/project/ReplaceContractCandidateSupportAuthorityCommand.java backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementResult.java backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementService.java backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementTransactionCoordinator.java
```

Expected: one short local transaction, no OCR/AI/HTTP/filesystem work, and no Project-before-File lock.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: replace candidate support authority"
```

---

### Task 9: Harden bounded idempotency replay and supersession

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementException.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementTransactionCoordinator.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementReplayTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementTransactionCoordinatorTest.java`

**Step 1: Write the failing adversarial replay tests**

- [ ] Same key/fingerprint plus SUCCEEDED, Candidate at `expected+1`, and exact internally consistent support set returns the original result with `replayed=true`, no authority revalidation, no mutation, and no new version.
- [ ] Change current role, identity, parse, Project version, file hash, or extraction facts after success while leaving the original Candidate/cohort result current. Assert old-key replay still succeeds and never reports fingerprint mismatch.
- [ ] Advance Candidate through a later successful business mutation. Assert old-key replay preserves `BusinessErrorCode.CONFLICT`, returns endpoint classification `RESULT_SUPERSEDED`, does not restore the old set, and does not allocate a new operation.
- [ ] Assert same-key `IN_PROGRESS`, database outcome not provable, or a missing/ambiguous durable result whose success/failure cannot safely be decided returns `OUTCOME_UNCERTAIN`, preserves the same key, and performs no mutation.
- [ ] Make first-execution validation deterministically reject stale expected version, already-PROMOTED Candidate, stale/wrong support role or identity, and invalid current authority while the second same-key lookup remains empty. Assert each preserves its original `BusinessErrorCode`/HTTP meaning and is classified `REQUEST_REJECTED`.
- [ ] Inject a deterministic validation failure, then expose a same-key winner on the mandatory second lookup. Assert SUCCEEDED goes through stored-result replay or `RESULT_SUPERSEDED`, IN_PROGRESS becomes `OUTCOME_UNCERTAIN`, and an identity mismatch, FAILED record, impossible state, inconsistent cohort, missing fingerprint, or result-identity corruption becomes `INTEGRITY_CONFLICT`; none is reported as `REQUEST_REJECTED`.
- [ ] Assert same key with different actor, project, Candidate, expected version, or canonical support IDs is `INTEGRITY_CONFLICT`, preserves the operation, and remains fail closed.
- [ ] Assert concurrent claim discovery follows the same four-way classification and creates no duplicate Candidate version or support row.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportReplacementReplayTest,ContractCandidateSupportReplacementTransactionCoordinatorTest test
```

Expected: **FAIL** because Task 8's happy-path replay does not yet expose the four endpoint-local recovery classifications or give a post-validation concurrent winner precedence over deterministic rejection.

**Step 3: Implement the minimal endpoint-bounded replay classifier**

- [ ] Add `ContractCandidateSupportReplacementException extends BusinessException` with exactly `OUTCOME_UNCERTAIN`, `REQUEST_REJECTED`, `RESULT_SUPERSEDED`, and `INTEGRITY_CONFLICT`. Preserve the original `BusinessErrorCode`; do not modify global exception classes or handlers.
- [ ] Validate stable record identity before examining result state. Any actor/resource/command fingerprint mismatch is `INTEGRITY_CONFLICT`, never an uncertain or retryable request rejection.
- [ ] For SUCCEEDED, use only the stored command/result tuple plus current Candidate/cohort consistency; do not call current support authority validation. The current-set schema proves only the still-current result and never promises permanent reconstruction of superseded historical cohorts.
- [ ] Return replay only at exact result version with exact canonical support IDs and consistent cohort/fingerprint. Treat a higher version or PROMOTED state as `RESULT_SUPERSEDED`; never reapply the old support set.
- [ ] Classify IN_PROGRESS and any database/result state whose success or failure cannot be proven as `OUTCOME_UNCERTAIN`; do not mark failed, clear the key, claim a new key, or apply the command. Classify FAILED and impossible/inconsistent durable state as `INTEGRITY_CONFLICT`.
- [ ] If fresh-request validation throws a deterministic `BusinessException`, re-read the same-key record before returning. A winner always takes precedence: validate its stable identity, then route SUCCEEDED through stored-result replay/supersession, IN_PROGRESS through `OUTCOME_UNCERTAIN`, and corrupt/FAILED state through `INTEGRITY_CONFLICT`. Only a still-empty second lookup may wrap the original code/message as `REQUEST_REJECTED`.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportReplacementReplayTest,ContractCandidateSupportReplacementTransactionCoordinatorTest test
```

Expected: **PASS** for stable successful replay, winner-first reconciliation, deterministic rejection, supersession, unproven fail-closed behavior, integrity mismatch, and duplicate prevention.

**Step 5: Run nearby idempotency regressions**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateConfirmationServiceTest,ContractCandidatePromotionServiceTest,Dm8IdempotencyAdapterTest,ProjectFactReplayIntegrityTest test
```

Expected: **PASS**; no global idempotency semantics change.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementException.java backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementTransactionCoordinator.java backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportReplacementReplayTest.java
```

Expected: no mutable authority field in the command fingerprint, no old-command reapplication, and no generic error-framework change.

**Step 7: Suggested commit**

```powershell
git commit -m "fix: bound candidate support replay recovery"
```

---

### Task 10: Expose the replacement command and OpenAPI contract

**Files:**

- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidateSupportAuthorityApiIntegrationTest.java`
- Create: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidateSupportAuthorityOpenApiStructureTest.java`
- Modify: `docs/openapi.yaml`

**Step 1: Write the failing API/OpenAPI tests**

- [ ] Cover authenticated/authorized success and replay for `POST /api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements` with required safe `Idempotency-Key`.
- [ ] Cover missing/invalid key, malformed JSON/UUID, negative or non-integral version, non-array/non-string IDs, foreign project/file, stale version, promoted Candidate, stale authority, fingerprint conflict, superseded result, and unproven result.
- [ ] Send extra authority fields such as `parseResultId`, `roleDecisionId`, `identityDecisionId`, `effectiveRole`, and `supportAuthorityFingerprint`; assert 400 rather than trusting or ignoring them.
- [ ] Assert request serialization is exactly `expectedCandidateVersion` plus `supportFileIds`; response contains `candidateId`, `candidateVersion`, `supportAuthorityVersion`, `authorityMode`, support projections, and `replayed`.
- [ ] Assert endpoint failures emitted by `ContractCandidateSupportReplacementException` keep their original `BusinessErrorCode` status and have exactly `data: { classification: "..." }`: stale expected version/stale authority with no winner is `REQUEST_REJECTED`; IN_PROGRESS is `OUTCOME_UNCERTAIN`; later durable mutation is `RESULT_SUPERSEDED`; identity/fingerprint/cohort corruption is `INTEGRITY_CONFLICT`.
- [ ] Assert a non-CONFLICT deterministic error such as NOT_FOUND keeps 404 while carrying `REQUEST_REJECTED`. Assert unrelated Project endpoints and the global `BusinessException` envelope do not gain this data object.
- [ ] Require OpenAPI request/response schemas, `Idempotency-Key`, 200/400/401/403/404/409 responses, a support-replacement-only classification enum with exactly four values, and no change to Confirmation/Promotion routes or schemas.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityApiIntegrationTest,ContractCandidateSupportAuthorityOpenApiStructureTest test
```

Expected: **FAIL** with 404/missing OpenAPI path because the endpoint is absent.

**Step 3: Implement the minimal endpoint**

- [ ] Inject `ContractCandidateSupportReplacementService` into `ProjectController`.
- [ ] Parse the body from `JsonNode` with an exact two-key whitelist, as the Promotion request already does for strict identity; UUID-validate, then call the service with server request metadata.
- [ ] Add controller request/response records that expose no trusted authority tuple.
- [ ] Add a `ProjectController`-local exception handler for `ContractCandidateSupportReplacementException`. Serialize the exception's unchanged HTTP status/message plus `ApiResponse.data.classification`; do not change `GlobalExceptionHandler`, `BusinessException`, or any other endpoint's error contract.
- [ ] Add the one path and additive success/error schemas to `docs/openapi.yaml`; the error data enum is exactly `OUTCOME_UNCERTAIN | REQUEST_REJECTED | RESULT_SUPERSEDED | INTEGRITY_CONFLICT`. Do not modify Confirmation, Promotion, ReviewRun, or F02 schemas.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityApiIntegrationTest,ContractCandidateSupportAuthorityOpenApiStructureTest test
```

Expected: **PASS** for strict payload, RBAC, original status preservation, endpoint-local typed classifications, success, and replay.

**Step 5: Run nearby Project API regressions**

```powershell
.\mvnw.cmd -Dtest=ProjectIntakeContinuityApiIntegrationTest,ContractCandidateConfirmationApiIntegrationTest,ContractCandidatePromotionApiIntegrationTest test
```

Expected: **PASS**; existing Project/Candidate endpoints remain unchanged.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java docs/openapi.yaml backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidateSupportAuthorityApiIntegrationTest.java backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidateSupportAuthorityOpenApiStructureTest.java
```

Expected: one additive command route plus its endpoint-local success/error schemas only; no global error-contract edit.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: expose candidate support replacement API"
```

---

### Task 11: Project exact support authority into Candidate Detail

**Files:**

- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/CandidateSupportAuthorityReadService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectApplicationService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectApplicationServiceTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidateSupportAuthorityApiIntegrationTest.java`

**Step 1: Write the failing read-projection tests**

- [ ] Historical/null metadata plus no rows returns `PRIMARY_ONLY_COMPAT`, no support associations, and the exact pre-P1-B `evidence`, `fieldStates`, and `lineItemFieldStates`.
- [ ] Explicit mode returns Candidate support version/fingerprint, stable support projections with exact file/parse/role/identity IDs, filename, current flag/safe reason, eligible award facts, and exact visible provenance evidence.
- [ ] Assert visible evidence and aggregation evidence differ: primary `AWARD_AMOUNT=100` and assigned support `98` are both inspectable, but Candidate/detail field state is `98 / CONFIRMED`, not `CONFLICT`.
- [ ] Assert support `98/99` yields `AWARD_AMOUNT=CONFLICT`; support primary-field and line-item facts are visible only if explicitly projected as provenance and never enter field/line states.
- [ ] Assert association reads never use project-wide category scan. A stale association remains safely visible for repair with `current=false`, but it cannot be labelled authoritative.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=ProjectApplicationServiceTest,ContractCandidateSupportAuthorityApiIntegrationTest test
```

Expected: **FAIL** because Candidate Detail has no authority mode, support metadata/associations, or mode-specific field-state projection.

**Step 3: Implement the minimal additive read model**

- [ ] Add a small `CandidateSupportAuthorityReadService` that reads Candidate-scoped associations, exact file/parse evidence, and safe currentness; it does not mutate or grant authority.
- [ ] Extend `ProjectApplicationService.candidate()` and its `CandidateDetail` record with `authorityMode`, support version/fingerprint, support associations, and visible evidence. Preserve the existing primary-only path exactly when the set is empty.
- [ ] In explicit mode, compute `fieldStates` from primary non-award evidence plus exact support `AWARD_*` evidence. Compute line-item states from primary evidence only. Primary award evidence may remain visible but is removed before field-state calculation.
- [ ] Extend `ProjectController.CandidateDetailResponse` additively. Do not put support IDs into `ConfirmedContractPayload`.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ProjectApplicationServiceTest,ContractCandidateSupportAuthorityApiIntegrationTest test
```

Expected: **PASS** for both modes, exact evidence, and authorized field states.

**Step 5: Run nearby read-model regressions**

```powershell
.\mvnw.cmd -Dtest=ProjectIntakeContinuityApiIntegrationTest,ProjectApplicationServiceTest,ContractCandidateAssemblerTest test
```

Expected: **PASS**; current Project Detail/Candidate readers remain compatible.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/project/CandidateSupportAuthorityReadService.java backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectApplicationService.java backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java
```

Expected: additive read fields only; no confirmation, promotion, ReviewRun, or F02 contract change.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: project candidate support authority"
```

---

### Task 12: Fence first Candidate Confirmation with current support authority

**Files:**

- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationTransactionCoordinator.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationServiceTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidateConfirmationApiIntegrationTest.java`
- Read-only contract assertions: `backend/src/main/java/com/fuquan/audit/contract/application/project/ConfirmContractCandidateCommand.java`, `backend/src/main/java/com/fuquan/audit/contract/application/project/ConfirmedContractPayload.java`, `backend/src/main/java/com/fuquan/audit/contract/domain/project/ContractCandidateConfirmation.java`

**Step 1: Write the failing Confirmation authority tests**

- [ ] A first confirmation with exact current explicit support succeeds.
- [ ] A first confirmation fails closed for stale support parse, role, identity, Project version, eligible facts, cohort rows, or support fingerprint.
- [ ] A completed confirmation replay after any later support authority drift still replays its original immutable confirmation tuple/canonical payload and never invokes current support validation.
- [ ] A support replacement after confirmation makes the prior confirmation's result version stale for Promotion.
- [ ] Assert the Confirmation command fields, endpoint body, canonical schema version, canonical JSON, durable row, payload fingerprint inputs, and success response contain no support ID/version/fingerprint.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateConfirmationServiceTest,ContractCandidateConfirmationApiIntegrationTest test
```

Expected: **FAIL** because first execution validates only primary source authority and accepts a stale support cohort.

**Step 3: Implement the minimal first-execution fence**

- [ ] Inject `CandidateSupportAuthorityValidator` into `ContractCandidateConfirmationTransactionCoordinator`.
- [ ] Preserve the existing ordering where a prior completed idempotency record replays before current version/authority validation.
- [ ] On a fresh confirmation only, call `validateCurrent(candidate)` after the primary `CandidateSourceAuthorityValidator.snapshot(candidate).requirePromotable()` check and before claim/insert.
- [ ] Do not modify `commandFingerprint(...)`, `ContractCandidateConfirmation`, canonicalizer, public request, response, or repository schema.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateConfirmationServiceTest,ContractCandidateConfirmationApiIntegrationTest test
```

Expected: **PASS** for fresh support validation and drift-safe completed replay.

**Step 5: Run nearby Confirmation regressions**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateConfirmationPayloadCanonicalizerTest,ContractCandidateConfirmationDm8SqlStructureTest,ContractCandidatePromotionServiceTest test
```

Expected: **PASS**; Confirmation's immutable tuple and business payload are unchanged.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationTransactionCoordinator.java backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationServiceTest.java backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidateConfirmationApiIntegrationTest.java
```

Expected: one fresh-operation validation call; replay code and schema are unchanged.

**Step 7: Suggested commit**

```powershell
git commit -m "fix: fence confirmation with support authority"
```

---

### Task 13: Extend internal Promotion authority validation without changing Promotion

**Files:**

- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/CandidateSourceAuthorityValidator.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationTransactionCoordinator.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/CandidateSourceAuthorityValidatorTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionServiceTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidatePromotionApiIntegrationTest.java`

**Step 1: Write the failing Promotion regression tests**

- [ ] Primary-only Promotion remains identical.
- [ ] Explicit-support Candidate with current support and matching current confirmation promotes through the existing endpoint/payload/mapping.
- [ ] Stale/missing/inconsistent support blocks Promotion even if primary authority remains current.
- [ ] A confirmation created before support replacement is rejected by the existing Candidate/result-version fence.
- [ ] A PROMOTED Candidate rejects support replacement and its Contract/ReviewRun are not rewritten.
- [ ] Assert route, `PromoteContractCandidateCommand`, `PromotionRequest`, `ConfirmedContractPayload`, Contract mapping, `AwardNoticeReference`, and Promotion response are unchanged.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=CandidateSourceAuthorityValidatorTest,ContractCandidatePromotionServiceTest,ContractCandidatePromotionApiIntegrationTest test
```

Expected: **FAIL** because Promotion's existing `CandidateSourceAuthorityValidator.snapshot()` does not yet include current support authority.

**Step 3: Implement the minimal shared validator composition**

- [ ] Inject `CandidateSupportAuthorityValidator` into `CandidateSourceAuthorityValidator` and make `snapshot(candidate)` combine primary promotability with `validateCurrent(candidate)` for non-empty/metadata-bearing support state.
- [ ] Preserve the `CandidateSourceAuthority` record and `.snapshot()` caller contract. Convert support invalidity to the existing safe non-promotable/conflict path without exposing raw facts.
- [ ] Remove Task 12's direct duplicate support-validator dependency/call from `ContractCandidateConfirmationTransactionCoordinator`; its existing `.snapshot().requirePromotable()` now covers fresh support validation, while completed replay still returns before `.snapshot()`.
- [ ] Do not change any Promotion production class, public DTO, mapper, or endpoint.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=CandidateSourceAuthorityValidatorTest,ContractCandidatePromotionServiceTest,ContractCandidatePromotionApiIntegrationTest test
```

Expected: **PASS** for primary-only, explicit current, stale support, old confirmation, and promoted immutability.

**Step 5: Run nearby Confirmation/Promotion regressions**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateConfirmationServiceTest,ContractCandidateConfirmationApiIntegrationTest,ContractCandidatePromotionConcurrencyTest,ContractCandidatePromotionTransactionStructureTest test
```

Expected: **PASS**; shared validation is internal and replay/public contracts are unchanged.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/project/CandidateSourceAuthorityValidator.java backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationTransactionCoordinator.java
```

Expected: no Promotion production file changed.

**Step 7: Suggested commit**

```powershell
git commit -m "fix: validate support authority before promotion"
```

---

### Task 14: Make Finding source projection mode-specific

**Files:**

- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/FindingSourceProjectionService.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/FindingSourceProjectionServiceTest.java`

**Step 1: Write the failing dual-mode provenance tests**

- [ ] Case A: a current or historical primary-only Candidate with an existing unassigned `AWARD_NOTICE` extraction still uses the exact current legacy `findByProjectId(...)`, `candidateId == null`, value-match, and immutable `ProjectFile.category == AWARD_NOTICE` fallback.
- [ ] Assert Case A does not change Candidate facts/field states, Confirmation, Promotion, or create a support association.
- [ ] Case B: after the same Candidate enters explicit mode, award evidence comes only from its exact current association plus exact `sourceFileId + parseResultId` extraction rows.
- [ ] Case C: unrelated/unassigned award files, another Candidate's support file, classifier-only `AWARD_NOTICE`, and project-wide category matches are ignored in explicit mode.
- [ ] Assert stale explicit parse/role/identity makes projection unavailable with a safe reason and never falls back project-wide.
- [ ] Assert multiple equal assigned sources remain deterministically visible; explicit conflict/null does not invent a winner; primary contract/line evidence and F02 verdict values are unchanged.

**Step 2: Run the exact RED command**

```powershell
.\mvnw.cmd -Dtest=FindingSourceProjectionServiceTest test
```

Expected: **FAIL** because every cross-file award projection currently uses the legacy project-wide category fallback, including explicit-support Candidates.

**Step 3: Implement the minimal mode branch**

- [ ] Inject `ContractCandidateSupportAuthorityRepository` and `CandidateSupportAuthorityValidator`.
- [ ] Leave the existing primary-only branch byte-for-byte equivalent, including legacy category filtering and ambiguity behavior.
- [ ] Add a separate explicit branch that validates the current cohort, loads exact association file/parse evidence, and never calls `extractions.findByProjectId(...)` for award support.
- [ ] Keep supporting extraction rows unbound (`candidateId == null`) and prove ownership through the association. Do not change rule plans or F02 result semantics.

**Step 4: Run the focused GREEN command**

```powershell
.\mvnw.cmd -Dtest=FindingSourceProjectionServiceTest test
```

Expected: **PASS** for Cases A-C, stale explicit authority, equality/conflict, and unchanged primary sources.

**Step 5: Run nearby F02/evidence regressions**

```powershell
.\mvnw.cmd -Dtest=FindingSourceProjectionServiceTest,ContractReviewRunApiIntegrationTest,ReviewRunReadinessResolverTest,F02Dm8SqlStructureTest test
```

Expected: **PASS**; F02 verdict and ReviewRun material contracts are unchanged.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/main/java/com/fuquan/audit/contract/application/FindingSourceProjectionService.java backend/src/test/java/com/fuquan/audit/contract/application/FindingSourceProjectionServiceTest.java
```

Expected: exactly one dual-mode branch; legacy fallback remains present only for primary-only compatibility.

**Step 7: Suggested commit**

```powershell
git commit -m "fix: bind explicit award evidence to support authority"
```

---

### Task 15: Add the frontend support-authority data contract

**Files:**

- Modify: `frontend/src/features/project/types.ts`
- Modify: `frontend/src/features/project/data/projectDataSource.ts`
- Modify: `frontend/src/features/project/data/httpProjectDataSource.ts`
- Modify: `frontend/src/features/project/data/httpProjectDataSource.test.ts`
- Modify: `frontend/src/features/project/projectDurableState.test.ts`

**Step 1: Write the failing datasource/type tests**

- [ ] Define compile-time fixtures for `CandidateAuthorityMode`, Candidate support metadata, exact support association projections, visible evidence, and authorized field states.
- [ ] Add a datasource test requiring this exact POST path and `Idempotency-Key`:

```text
/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements
```

- [ ] Assert the serialized body contains exactly `expectedCandidateVersion` and `supportFileIds`, with no parse, role, identity, category, fingerprint, filename, or fact fields.
- [ ] Assert response/reload types preserve authority mode, support version, support associations, replay flag, safe currentness, and visible evidence.
- [ ] Assert 400/404/409/network errors remain the existing `ApiError`, including the already-supported readable envelope `data`, rather than introducing a second error system. Leave classification interpretation to the support-replacement flow in Task 16.

**Step 2: Run the exact RED command**

From `frontend`:

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/data/httpProjectDataSource.test.ts src/features/project/projectDurableState.test.ts
```

Expected: **FAIL** because the support types and `replaceCandidateSupportAuthority(...)` datasource method do not exist.

**Step 3: Implement the minimal frontend data contract**

- [ ] Add `CandidateAuthorityMode`, `CandidateSupportAssociation`, support metadata, and support projection fields to `ContractCandidate`/`CandidateDetail` additively.
- [ ] Add `CandidateSupportAuthorityReplacementRequest` and `CandidateSupportAuthorityReplacementResponse` to `projectDataSource.ts`.
- [ ] Add `replaceCandidateSupportAuthority(projectId, candidateId, payload, options)` to the interface and HTTP implementation using the existing cookie/CSRF client and exact retained key.
- [ ] Do not send trusted authority fields and do not alter `ApiError`, Confirmation, Promotion, or ReviewRun datasource contracts.

**Step 4: Run the focused GREEN command**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/data/httpProjectDataSource.test.ts src/features/project/projectDurableState.test.ts
```

Expected: **PASS** for exact serialization, response typing, reload, replay, and errors.

**Step 5: Run nearby frontend data regressions**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/contract/data/httpContractDataSource.test.ts src/features/project/candidateConfirmationFlow.test.ts src/features/project/promotionReconciliation.test.ts
```

Expected: **PASS**; shared HTTP and existing Candidate commands are unchanged.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- frontend/src/features/project/types.ts frontend/src/features/project/data/projectDataSource.ts frontend/src/features/project/data/httpProjectDataSource.ts frontend/src/features/project/data/httpProjectDataSource.test.ts frontend/src/features/project/projectDurableState.test.ts
```

Expected: additive types and one datasource method only.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: add candidate support authority client"
```

---

### Task 16: Reconcile one frontend replacement operation with typed recovery

**Files:**

- Create: `frontend/src/features/project/candidateSupportAuthorityOperation.ts`
- Create: `frontend/src/features/project/candidateSupportAuthorityOperation.test.ts`
- Create: `frontend/src/features/project/candidateSupportAuthorityFlow.ts`
- Create: `frontend/src/features/project/candidateSupportAuthorityFlow.test.ts`
- Read-only reference: `frontend/src/features/contract/reviewRunOperation.ts`
- Read-only reference: `frontend/src/api/client.ts`

**Step 1: Write the failing operation/reconciliation tests**

- [ ] Persist one operation containing `idempotencyKey` and canonical command identity `[expectedCandidateVersion, sortedUniqueSupportFileIds]`; reload returns the exact same key/command and allocates no replacement.
- [ ] Assert network error, timeout/abort, 5xx, aborted durable proof, malformed success response, and Candidate reload mismatch retain the exact old key and command, make no second POST, and allocate no replacement key.
- [ ] Assert proven success requires reloaded Candidate ID, `expected+1` version, response support version, mode, and exact canonical support IDs to match before guarded clear.
- [ ] Parse classification only when the thrown value is the existing `ApiError` and `ApiError.data` is an object whose `classification` is exactly one of `OUTCOME_UNCERTAIN`, `REQUEST_REJECTED`, `RESULT_SUPERSEDED`, or `INTEGRITY_CONFLICT`. Assert arbitrary strings, missing/malformed data, and an old-backend unclassified 409 all remain unclassified and fail closed; status/message text never supplies a classification.
- [ ] Assert `OUTCOME_UNCERTAIN` preserves the exact stored operation/key and makes no second POST. Reproduce lost response followed by same-key IN_PROGRESS and prove the original key remains.
- [ ] Assert `INTEGRITY_CONFLICT`, including same-key command identity mismatch, preserves the old operation, fails closed, and never fresh-submits.
- [ ] For `REQUEST_REJECTED`, cover stale expected version and deterministic stale support authority. Verify the stored operation still exactly equals the sent operation, guarded-clear that exact entry, surface the deterministic error, and make no second POST in the same user action. After the UI has current Candidate state, the next explicit Save may allocate exactly one fresh key and make exactly one POST.
- [ ] For `RESULT_SUPERSEDED`, reload the exact Candidate and require durable proof that its version has moved beyond the old command's result version (`current.version > expectedCandidateVersion + 1`) before guarded-clearing the exact old operation. Surface a safe “state changed; reload and review” error, never roll back/reapply the old set, and make no second POST. If reload fails, returns the wrong Candidate, or cannot prove advancement, retain the operation and fail closed.
- [ ] Assert guarded clear refuses to delete a newer concurrently stored operation while handling either `REQUEST_REJECTED` or `RESULT_SUPERSEDED`; the newer operation remains untouched and no automatic create follows.
- [ ] Assert malformed stored operations fail closed; reload alone never allocates a key; no English message substring controls recovery.

**Step 2: Run the exact RED command**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/candidateSupportAuthorityOperation.test.ts src/features/project/candidateSupportAuthorityFlow.test.ts
```

Expected: **FAIL** because the persisted operation and durable reconciliation helpers do not exist.

**Step 3: Implement the minimal operation-specific helpers**

- [ ] Follow the bounded `reviewRunOperation.ts` pattern without generalizing the ReviewRun helper. Store only safe IDs/version/key in session storage under a project/Candidate-specific key.
- [ ] Export load/create/load-existing/guarded-clear functions. Canonicalize UUID strings by trim, de-duplicate, and lexical sort; validate safe key syntax.
- [ ] Add a support-replacement-local classification parser that reads only `ApiError.data.classification`, accepts the exact four-value allow-list, and returns no classification for every other shape/value. Do not modify `ApiError`, add a generic error framework, or inspect message substrings.
- [ ] Implement `replaceCandidateSupportAndReconcile(...)` with at most one POST per invocation. After 200, GET Candidate, prove the exact durable result, then guarded-clear. Network/timeout/5xx, unknown classification, `OUTCOME_UNCERTAIN`, `INTEGRITY_CONFLICT`, and any unproven result retain the exact operation and surface an error.
- [ ] On `REQUEST_REJECTED`, first re-load and compare the complete stored operation to the operation sent, then guarded-clear it and rethrow/surface the deterministic error. Do not POST again; a new key is permitted only on a later explicit Save using then-current Candidate state.
- [ ] On `RESULT_SUPERSEDED`, GET the exact Candidate and prove a newer durable version before guarded-clear. Surface only the safe state-changed recovery error; if proof or guarded clear fails, preserve the current stored operation and fail closed. Never reapply the old support set.
- [ ] Never use browser state as authority and never allocate a new key merely because the Candidate was reloaded.

**Step 4: Run the focused GREEN command**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/candidateSupportAuthorityOperation.test.ts src/features/project/candidateSupportAuthorityFlow.test.ts
```

Expected: **PASS** for successful proof, four-way classification, deterministic guarded clear, proven supersession recovery, retained uncertainty/integrity/unclassified failures, concurrent-operation protection, and one-POST-per-action behavior.

**Step 5: Run nearby operation regressions**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/projectOperationGuard.test.ts src/features/project/promotionReconciliation.test.ts src/features/contract/reviewRunOperation.test.ts
```

Expected: **PASS**; existing operation identities are unchanged.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- frontend/src/features/project/candidateSupportAuthorityOperation.ts frontend/src/features/project/candidateSupportAuthorityOperation.test.ts frontend/src/features/project/candidateSupportAuthorityFlow.ts frontend/src/features/project/candidateSupportAuthorityFlow.test.ts
```

Expected: one bounded Candidate-support operation helper, strict `ApiError.data` parsing, and no `ApiError` or ReviewRun helper refactor.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: reconcile candidate support replacements"
```

---

### Task 17: Add the minimal Candidate support-association UI

**Files:**

- Create: `frontend/src/features/project/candidateSupportAuthorityPresentation.ts`
- Create: `frontend/src/features/project/candidateSupportAuthorityPresentation.test.ts`
- Create: `frontend/src/features/project/components/CandidateSupportAuthorityPanel.tsx`
- Create: `frontend/src/features/project/candidateSupportAuthorityPanel.test.ts`
- Modify: `frontend/src/features/project/useProjectWorkspace.ts`
- Modify: `frontend/src/features/project/components/ProjectDetailPage.tsx`
- Modify: `frontend/src/features/project/projectDetailViewModel.test.ts`

**Step 1: Write the failing presentation/hook/static UI tests**

- [ ] UX eligibility admits only files that are `AVAILABLE`, `PARSED`, effective `AWARD_NOTICE`, `HUMAN_CONFIRMATION`, current human `SAME_PROJECT`, `identityCurrent=true`, and not excluded. Each negative dimension removes the file.
- [ ] The panel displays “关联中标材料”, filename, effective role, award amount, supplier, current/conflict status, selected full set, save, clear-all, and reload.
- [ ] Save submits the complete canonical set and current Candidate version exactly once through Task 16. Success reloads server state. `OUTCOME_UNCERTAIN`, `INTEGRITY_CONFLICT`, unclassified old-backend 409, network/timeout/5xx, and unproven supersession retain the operation and show a safe fail-closed error.
- [ ] A stale expected version or stale-authority `REQUEST_REJECTED` guarded-clears only the exact sent operation, shows the deterministic error, and performs no automatic second POST. After reload/review provides current Candidate state, the next explicit Save allocates one fresh key and performs one fresh POST.
- [ ] A proven `RESULT_SUPERSEDED` reloads authoritative Candidate state, guarded-clears only the exact old operation, shows “state changed; reload and review”, and never rolls back or reapplies the old support set. If the reload cannot prove advancement, the old operation remains.
- [ ] While either rejection is being handled, replace the persisted entry with a newer operation and assert guarded clear refuses to delete it; the UI fails closed without posting.
- [ ] A PROMOTED Candidate disables editing/saving/clearing. An unchanged selection disables save so the UI does not create needless `[] -> []` changes, although the backend still safely supports them.
- [ ] Primary-only UI outside the new section retains existing behavior; the panel never converts classifier category into authority and never emits parse/decision/fingerprint fields.
- [ ] Refresh reconstructs assigned selection from server Candidate Detail, not stale component state.

**Step 2: Run the exact RED command**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/candidateSupportAuthorityPresentation.test.ts src/features/project/candidateSupportAuthorityPanel.test.ts src/features/project/projectDetailViewModel.test.ts
```

Expected: **FAIL** because the eligibility projection, panel, and workspace action do not exist.

**Step 3: Implement the minimal UI integration**

- [ ] Implement pure eligibility/presentation functions first; use server-projected effective role/identity/currentness only.
- [ ] Add `CandidateSupportAuthorityPanel` adjacent to Candidate detail/confirmation in `ProjectDetailPage`.
- [ ] Add one single-flight save action to `useProjectWorkspace` that uses session storage persistence, Task 16 reconciliation, `userFacingError`, and existing `reload(candidateId)`. It never loops or automatically calls POST after any failure; after deterministic guarded clear, only a later explicit Save may create another operation.
- [ ] Pass current Project files and server Candidate Detail into the panel. Do not add automatic assignment, background mutation, or support editing after Promotion.

**Step 4: Run the focused GREEN command**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/candidateSupportAuthorityPresentation.test.ts src/features/project/candidateSupportAuthorityPanel.test.ts src/features/project/projectDetailViewModel.test.ts
```

Expected: **PASS** for eligibility, full replacement, clear, typed recovery presentation, no same-action retry, safe next-explicit-Save recovery, reload, conflict display, and promoted disablement.

**Step 5: Run nearby Project workspace regressions**

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/candidateConfirmation.test.ts src/features/project/candidateConfirmationFlow.test.ts src/features/project/projectDurableState.test.ts src/features/project/projectIntakeCompletion.test.ts src/features/project/promotionReconciliation.test.ts
npm.cmd run build
```

Expected: **PASS** and Vite build succeeds; existing primary Candidate/confirmation UI is unchanged.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- frontend/src/features/project/candidateSupportAuthorityPresentation.ts frontend/src/features/project/components/CandidateSupportAuthorityPanel.tsx frontend/src/features/project/useProjectWorkspace.ts frontend/src/features/project/components/ProjectDetailPage.tsx
```

Expected: one local panel and workspace action, no contract-review/ReviewRun UI change.

**Step 7: Suggested commit**

```powershell
git commit -m "feat: associate award support with candidates"
```

---

### Task 18: Lock historical migration/read compatibility

**Execution note:** Author and run Step 1 immediately after Task 1, before Task 2, to capture a real compatibility RED. Keep the failing tests uncommitted or in a dedicated work-in-progress commit while Tasks 2-17 supply the implementation. Return here after Task 17 for GREEN and the compatibility-only commit. This ordering prevents a late test from merely confirming code that was never driven by a historical-row fixture.

**Files:**

- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportMigrationCompatibilityTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ProjectContractAdapterTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectApplicationServiceTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ReviewRunReadinessResolverTest.java`
- Modify: `frontend/src/features/project/projectDurableState.test.ts`
- Read-only startup assertions: `backend/pom.xml`, `backend/src/main/resources/application.yml`, `backend/src/main/resources/application-local-dev.yml`, `backend/src/main/resources/application-dev29.yml`, `backend/src/main/resources/application-customer.yml`

**Step 1: Write the failing historical compatibility tests before Task 2**

- [ ] Hydrate a historical Candidate persistence row with `SUPPORT_AUTHORITY_VERSION_NO=NULL`, `SUPPORT_AUTHORITY_FINGERPRINT=NULL`, and no support rows. Assert it is readable as `PRIMARY_ONLY_COMPAT` with existing primary award facts.
- [ ] Reopen existing DRAFT and PROMOTED Candidates, their ProjectContract/Contract, and an existing ReviewRun fixture. Assert no support backfill is required and no ID/version/primary authority/Contract/ReviewRun fact changes.
- [ ] Assert historical/current primary-only Finding evidence still uses the legacy category fallback.
- [ ] Assert backend profiles and POM contain no Flyway/Liquibase/startup execution of the new controlled SQL.
- [ ] Assert a frontend Candidate Detail fixture without new optional fields still renders the existing primary-only path.

**Step 2: Run the exact RED commands before Task 2**

From `backend`:

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportMigrationCompatibilityTest,Dm8ProjectContractAdapterTest,ProjectApplicationServiceTest,ReviewRunReadinessResolverTest test
```

From `frontend`:

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/projectDurableState.test.ts
```

Expected: **FAIL** on the pre-Task-2 tree because the Candidate domain/DM8 row/read model do not yet understand support metadata/mode; record the precise failure before proceeding.

**Step 3: Apply only the compatibility implementation already scoped in Tasks 2-17**

- [ ] Do not add a backfill, startup migrator, CandidateV2, fallback support row, or special historical Promotion path.
- [ ] Use Task 2's constructor/row compatibility, Task 11's null/no-row interpretation, Task 14's primary-only evidence branch, and Task 15's additive optional frontend fields. If this RED exposes a missed compatibility defect, fix it only in the already listed P1-B files that own that behavior and add the exact case to this test.

**Step 4: Run the focused GREEN commands after Task 17**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportMigrationCompatibilityTest,Dm8ProjectContractAdapterTest,ProjectApplicationServiceTest,ReviewRunReadinessResolverTest test
```

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/projectDurableState.test.ts
```

Expected: **PASS** for null/null rows, no support rows, promoted Contract, ReviewRun reopen, no startup migration, and old frontend payloads.

**Step 5: Run nearby historical regressions**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateIdentityAuthorityTest,ContractCandidatePromotionServiceTest,FindingSourceProjectionServiceTest,ContractReviewRunApiIntegrationTest test
```

Expected: **PASS** with no backfill or lifecycle fork.

**Step 6: Check whitespace and scope**

```powershell
git diff --check
git diff -- backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateSupportMigrationCompatibilityTest.java backend/src/test/java/com/fuquan/audit/integration/dm8/Dm8ProjectContractAdapterTest.java backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectApplicationServiceTest.java backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ReviewRunReadinessResolverTest.java frontend/src/features/project/projectDurableState.test.ts
```

Expected: compatibility tests plus only any narrowly evidenced fix in an already approved P1-B file; no migration execution/backfill.

**Step 7: Suggested commit**

```powershell
git commit -m "test: preserve candidate support compatibility"
```

---

### Task 19: Prove schema and concurrency invariants on representative DM8

**Execution note:** Build the opt-in test/probe after Task 8, but perform the real GREEN run only after Task 20 is green and only in an approved representative DM8 environment where the controlled migration has separately passed preflight and been applied by an authorized operator. A skip, mock, H2 run, or in-memory run is `NOT VERIFIED`, never PASS.

**Files:**

- Create: `db/dm8/probes/CONTRACT__contract_candidate_support_authority_read_only.sql`
- Create: `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractCandidateSupportAuthorityRepresentativeDm8IT.java`
- Create: `docs/evidence/candidate-cross-material-authority-verification-20260920.md`

**Step 1: Write the failing opt-in DM8 verification**

- [ ] Add read-only catalog queries for the Candidate columns, support table, column types/nullability, PK/UK/FK/check constraints, indexes, and exact parent composite keys. The probe prints object names/status only and never connection credentials or customer data.
- [ ] Write an opt-in integration test that refuses to run unless `AUDIT_P1B_DM8_VERIFY=true` and the normal secret environment variables are supplied out of band. Do not embed or echo `AUDIT_DB_URL`, username, or password.
- [ ] Cover real constraint behavior inside rolled-back test transactions: historical Candidate null/null; invalid one-null tuple; cross-project file; substituted parse/hash/version; substituted role/selected role; substituted identity/project version/resolution; duplicate Candidate/file; and the same support file under two Candidates.
- [ ] Cover real transaction/locking behavior:
  - authority confirmation holds support File then requests Project while replacement starts; replacement must wait for File, never hold Project first, and finish without deadlock after release;
  - replacements for `[A,B]` and `[B,A]` acquire the same canonical File order and do not deadlock;
  - two replacements at the same Candidate version produce at most one committed CAS winner and no mixed cohort;
  - injected failure after old-row delete, Candidate CAS/line replacement, and new-row insert rolls the complete transaction back.
- [ ] Start the evidence document with environment identifier/version, commands, sanitized IDs, and every DM8 item marked `PENDING / NOT VERIFIED`.

**Step 2: Run the exact RED command before controlled deployment**

From `backend`, in a shell with the approved external environment loaded but before applying the new migration:

```powershell
$env:AUDIT_P1B_DM8_VERIFY='true'
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityRepresentativeDm8IT test
```

Expected: **FAIL** because the representative database does not yet expose the approved support-authority schema. If the environment is absent or the test skips, record `NOT VERIFIED`; do not convert that to RED or PASS evidence.

**Step 3: Perform the separately authorized controlled database preparation**

- [ ] Have the approved DM8 operator run the read-only probe interactively with the dedicated non-system application account and classify every effect as `NOT_APPLIED`, `ALREADY_MATCHING`, `PARTIAL/SCHEMA_DRIFT`, or `UNKNOWN`.
- [ ] Stop on `ALREADY_MATCHING` pending history reconciliation, and stop on `PARTIAL/SCHEMA_DRIFT` or `UNKNOWN` pending a separate repair decision.
- [ ] Only if every effect is `NOT_APPLIED` and backup/execution/stop/rollback authorization exists, have the operator apply `db/dm8/CONTRACT__contract_candidate_support_authority.sql` manually. The application and integration test must never execute DDL.
- [ ] Verify catalog state again, then run the opt-in test. Record DM8 version, sanitized transaction observations, lock wait/no-deadlock outcome, winning Candidate version/cohort, and rollback facts in the evidence document.

**Step 4: Run the focused real-DM8 GREEN command**

```powershell
$env:AUDIT_P1B_DM8_VERIFY='true'
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityRepresentativeDm8IT test
```

Expected: **PASS on representative DM8** with all constraint, lock-order, CAS, and rollback assertions executed. A skipped test is not PASS.

**Step 5: Run nearby automated repository/structure regressions**

```powershell
.\mvnw.cmd -Dtest=ContractCandidateSupportAuthorityDm8SqlStructureTest,ContractCandidateSupportAuthorityMapperStructureTest,Dm8ContractCandidateSupportAuthorityRepositoryTest,ContractCandidateSupportReplacementLockOrderTest test
```

Expected: **PASS**; automated coverage and representative-platform evidence agree.

**Step 6: Check whitespace, evidence safety, and scope**

```powershell
git diff --check
git diff -- db/dm8/probes/CONTRACT__contract_candidate_support_authority_read_only.sql backend/src/test/java/com/fuquan/audit/integration/dm8/ContractCandidateSupportAuthorityRepresentativeDm8IT.java docs/evidence/candidate-cross-material-authority-verification-20260920.md
```

Expected: no credentials, full connection strings, source text, filenames, amounts, or customer data; no DDL in the probe/test.

**Step 7: Suggested commit**

```powershell
git commit -m "test: verify candidate support authority on DM8"
```

---

### Task 20: Run the complete regression and architecture gate

**Execution note:** Create and run the architecture guard at the start of implementation, before Task 1, to capture RED. Complete the full gate after Tasks 1-18 and before the real GREEN portion of Task 19.

**Files:**

- Create: `backend/src/test/java/com/fuquan/audit/contract/application/project/CandidateCrossMaterialAuthorityArchitectureGuardTest.java`
- Modify: `docs/evidence/candidate-cross-material-authority-verification-20260920.md`

**Step 1: Write the failing cross-scope architecture guard before Task 1**

- [ ] Require the bounded support domain/port/coordinator/endpoint and both authority modes.
- [ ] Require the support-replacement exception/API/OpenAPI contract to expose exactly `OUTCOME_UNCERTAIN`, `REQUEST_REJECTED`, `RESULT_SUPERSEDED`, and `INTEGRITY_CONFLICT` through endpoint-local `data.classification`. Assert `GlobalExceptionHandler`, `BusinessException`, and `frontend/src/api/client.ts` are unchanged by P1-B, and reject any support recovery branch that inspects English message text.
- [ ] Read source/OpenAPI structures and assert there is no `CandidateV2`, `CrossMaterialCandidateEngine`, support field in `ConfirmedContractPayload`/Confirmation canonical schema, support field in Promotion request, support dependency in `ReviewRunReadinessResolver`/`ContractReviewRunMaterial`/`FrozenReviewInput`, new F02 rule/tolerance, or startup migration runner.
- [ ] Assert the primary-only legacy Finding fallback and explicit exact-association path are both present.
- [ ] Add a `Full regression: PENDING` line to the evidence document.

**Step 2: Run the exact RED command before Task 1**

```powershell
.\mvnw.cmd -Dtest=CandidateCrossMaterialAuthorityArchitectureGuardTest test
```

Expected: **FAIL** because the required bounded support domain/coordinator/endpoint do not exist on the design-only baseline.

**Step 3: Complete only Tasks 1-18; do not add scope to satisfy the guard**

- [ ] If the guard detects a forbidden contract, remove the out-of-scope change rather than weakening the assertion.
- [ ] Record the recovery matrix in the evidence document: deterministic stale-version/stale-authority rejection clears only the exact old operation with no same-action POST; IN_PROGRESS preserves its exact key; proven supersession reloads before guarded clear and never reapplies; integrity/unclassified failures remain retained and fail closed.
- [ ] Update the evidence document with exact commands, runtime versions, observed counts/results, and any failure. Do not mark a suite PASS until its final command exits zero.

**Step 4: Run the focused P1-B GREEN gate**

From `backend`:

```powershell
.\mvnw.cmd -Dtest=CandidateCrossMaterialAuthorityArchitectureGuardTest,ContractCandidateSupportAuthorityDm8SqlStructureTest,ContractCandidateSupportAuthorityTest,ContractCandidateFactPolicyTest,ContractCandidateDerivedFactsCasRepositoryTest,CandidateSupportAuthorityValidatorTest,ContractCandidateSupportReplacementTransactionCoordinatorTest,ContractCandidateSupportReplacementReplayTest,ContractCandidateSupportAuthorityApiIntegrationTest,ProjectApplicationServiceTest,FindingSourceProjectionServiceTest test
```

From `frontend`:

```powershell
node --test --experimental-strip-types --test-isolation=none src/features/project/data/httpProjectDataSource.test.ts src/features/project/candidateSupportAuthorityOperation.test.ts src/features/project/candidateSupportAuthorityFlow.test.ts src/features/project/candidateSupportAuthorityPresentation.test.ts src/features/project/candidateSupportAuthorityPanel.test.ts
```

Expected: **PASS** with zero failures/errors and all four endpoint-local classifications, winner-first backend reconciliation, `ApiError.data`-only parsing, deterministic guarded clear, proven supersession recovery, and no automatic second POST covered.

**Step 5: Run every full regression**

Backend from `backend`:

```powershell
.\mvnw.cmd clean verify
```

Frontend from `frontend`:

```powershell
node --test --experimental-strip-types --test-isolation=none "src/**/*.test.ts"
npm.cmd run build
```

FastAPI from `services/audit-ai` using the repository-approved Python 3.11 interpreter:

```powershell
python -m pytest -q
```

Expected: backend verify has 0 failures/0 errors; the full isolated Node suite has 0 failures; Vite build succeeds; FastAPI remains **340 passed** because P1-B changes no Python production code or tests.

Then from repository root:

```powershell
git diff --check
git status --short
git diff --stat
```

Expected: no whitespace errors; only scoped P1-B files and verification documentation are changed.

**Step 6: Mark the regression evidence GREEN**

```powershell
Select-String -LiteralPath docs\evidence\candidate-cross-material-authority-verification-20260920.md -Pattern '^Full regression: PASS$'
```

Expected: **PASS** only after all Step 5 commands and actual observed counts have been written. Before that update, this command is the deliberate documentation-gate RED.

**Step 7: Suggested commit**

```powershell
git commit -m "test: verify candidate support authority regressions"
```

---

### Task 21: Complete browser full-chain and reverse-mode acceptance

**Files:**

- Modify: `docs/evidence/candidate-cross-material-authority-verification-20260920.md`
- No application, test, migration, fixture, or customer-data file is created or modified in this task.

**Step 1: Add a failing browser acceptance gate**

- [ ] Add sanitized checklist rows for upload, parse, human role, human identity, primary Candidate, support replacement, Candidate rematerialization, confirmation, promotion, Contract award reference, ReviewRun, F02, refresh, backend restart, reverse clear, stale confirmation, primary fact restoration, legacy Finding projection, and no production/customer mutation. Mark every row `PENDING`.
- [ ] Use exactly two new safe synthetic Projects: one for the immutable explicit-mode full chain and one for the reverse-to-empty compatibility chain. Do not attempt to clear support after a Candidate is PROMOTED.

**Step 2: Run the exact RED evidence gate**

```powershell
$report = Get-Content -LiteralPath 'docs\evidence\candidate-cross-material-authority-verification-20260920.md' -Raw
if ($report -notmatch '(?m)^Browser full chain: PASS$' -or $report -match '\bPENDING\b') { throw 'browser acceptance is not complete' }
```

Expected: **FAIL** while any browser row is PENDING.

**Step 3: Execute the safe explicit-support full chain**

- [ ] In a new Project, upload one synthetic contract body and one synthetic award notice. The primary contract should contain compatible primary award facts distinct from the support fixture so authority switching is observable without customer data.
- [ ] Through the real UI: parse both files; human-confirm `CONTRACT_BODY` and `AWARD_NOTICE`; human-confirm both as `SAME_PROJECT`; materialize the primary Candidate; save the full support set.
- [ ] Capture sanitized request evidence proving the POST body contains only expected Candidate version and support file IDs and that exactly one retained key is used.
- [ ] Reload Candidate Detail and prove: same Candidate ID, unchanged primary file/parse/role/identity/fingerprint, explicit mode, incremented Candidate/support versions, support award facts only, exact support evidence, and expected `CONFIRMED`/`CONFLICT` states.
- [ ] Enter the required human award notice number, project name, and date, confirm the Candidate, promote through the unchanged UI, and prove the Contract's `AwardNoticeReference` is complete.
- [ ] Start an unchanged ReviewRun and run F02 to terminal. Prove award checks are evaluated from the promoted Contract and are not `NOT_EVALUATED` merely because upstream Candidate award data is absent. Record the actual PASS/FAIL business result without changing rule semantics.
- [ ] Refresh the browser and restart the backend. Reopen Candidate/Contract/ReviewRun and prove support rows, exact evidence, promoted Contract, and F02 result remain durable.

**Step 4: Execute the safe reverse-mode chain before Promotion**

- [ ] In the second fresh Project/Candidate, reach explicit mode and save a Candidate Confirmation.
- [ ] Replace support with `[]` while the Candidate is still DRAFT. Prove same Candidate ID, unchanged primary authority, Candidate/support version increments, restored primary award facts, `PRIMARY_ONLY_COMPAT`, and the old Confirmation is rejected as stale.
- [ ] Reload/refresh and prove the empty set is rediscovered from DM8, not browser state.
- [ ] Create a new confirmation and promote the primary-only Candidate; run a safe F02 case with award evidence and prove the legacy `FindingSourceProjectionService` category fallback still returns the current compatible evidence behavior. It remains evidence compatibility, not Candidate fact authority.
- [ ] Prove unrelated/unassigned award material never changed Candidate facts in either Project.

**Step 5: Re-run acceptance-adjacent regression checks**

```powershell
git status --short
git diff --check
```

Expected: the runtime exercise created no tracked artifact, uploaded source, screenshot, secret, or customer data; only the sanitized evidence document changed in this task.

**Step 6: Mark and verify browser evidence GREEN**

- [ ] Replace every PENDING row with observed PASS/FAIL, include sanitized Project/Candidate/Contract/ReviewRun IDs and HTTP/status facts only, and add `Browser full chain: PASS` only when both explicit and reverse chains satisfy every required assertion.

```powershell
$report = Get-Content -LiteralPath 'docs\evidence\candidate-cross-material-authority-verification-20260920.md' -Raw
if ($report -notmatch '(?m)^Browser full chain: PASS$' -or $report -match '\bPENDING\b') { throw 'browser acceptance is not complete' }
git diff --check
```

Expected: **PASS**. Otherwise final implementation verdict remains NOT READY.

**Step 7: Suggested commit**

```powershell
git commit -m "test: verify candidate support full chain"
```

---

## Rollout and Rollback Gate

- Rollout order is catalog preflight, approved controlled migration, backend read compatibility, backend writes/validation, frontend UI, full regression, representative DM8 verification, then browser acceptance.
- Safe application rollback disables the replacement endpoint/UI while preserving read compatibility for already persisted rows.
- Never drop the new table/columns, delete support rows, clear Candidate support metadata, rewrite promoted Contracts, or invalidate ReviewRuns as an application rollback.
- Any schema/data repair is a separately approved task with backup, stop conditions, and recovery evidence.

## Final Plan Self-Review

- [ ] Exactly 21 tasks are present, and every production change is preceded by a named RED test and exact command.
- [ ] Every task lists exact create/modify/test paths, focused GREEN, nearby regression, `git diff --check`, and a suggested commit.
- [ ] The migration is new, additive, `CONTROLLED_MANUAL`, explicitly after identity assessment, absent from startup execution, and never named V023.
- [ ] No existing migration is edited; no migration command is executed by application/test automation.
- [ ] No `CandidateV2`, cross-material engine, parallel lifecycle, new Promotion contract, new ReviewRun source, new F02 semantics, or AI authority is introduced.
- [ ] `PRIMARY_ONLY_COMPAT` preserves primary award facts and the legacy Finding category fallback.
- [ ] `EXPLICIT_SUPPORT_AUTHORITY` excludes primary award facts and uses only exact assigned support award facts/evidence.
- [ ] Stable command fingerprint and mutable authority fingerprint are separate and tested.
- [ ] Completed Confirmation replay remains its original immutable tuple/canonical payload.
- [ ] File -> Project -> Candidate lock order and both adversarial lock cases are explicit.
- [ ] Successful replay, `OUTCOME_UNCERTAIN`, `REQUEST_REJECTED`, `RESULT_SUPERSEDED`, and `INTEGRITY_CONFLICT` are distinct; a durable winner always outranks deterministic rejection.
- [ ] Frontend classification comes only from the exact `ApiError.data.classification` allow-list; deterministic rejection and proven supersession guarded-clear only the sent operation, while uncertainty/integrity/unclassified failure retains it, and no failure causes an automatic second POST.
- [ ] Historical null/null/no-row Candidates, promoted Contracts, and ReviewRuns reopen without backfill.
- [ ] Representative DM8 proof cannot be satisfied by H2, in-memory tests, mocks, skipped tests, or source inspection.
- [ ] Browser acceptance covers explicit full chain, reverse-to-empty behavior, refresh/restart durability, and legacy evidence compatibility without violating PROMOTED immutability.
- [ ] Full backend, frontend, build, FastAPI 340-test regression, whitespace, status, and diff-stat gates are recorded.
- [ ] The implementation evidence contains no secrets, raw contract text, excerpts, monetary/customer data, or committed runtime artifacts.

## Definition of Done

Implementation is ready for review only when Tasks 1-18 and Task 20 are green, Task 19 has non-skipped representative DM8 PASS evidence, Task 21 has explicit and reverse browser PASS evidence, and the final working tree contains only reviewed scoped files. Any `NOT VERIFIED`, `PENDING`, unexpected regression, schema drift, or unproven durable outcome keeps the result fail closed.
