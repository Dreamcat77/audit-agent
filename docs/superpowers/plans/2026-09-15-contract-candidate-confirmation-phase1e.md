# Contract Candidate Confirmation Phase 1E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Save each human-corrected contract payload as an immutable, reopenable candidate confirmation before promotion, and make promotion reference only its confirmation ID.

**Architecture:** Java validates and canonicalizes the existing `ConfirmedContractPayload`, then stores an append-only DM8 confirmation with source-authority tuple and a candidate-version CAS in one short transaction. Promotion first resolves immutable confirmation/idempotency identity, replays already-successful commands without revalidating mutable authority, and only for new commands checks current role/identity before atomically creating Contract, ProjectContract, and promotion provenance. Frontend retains two distinct operation keys and reopens latest confirmation before retrying promotion.

**Tech Stack:** Java 17, Spring Boot, MyBatis/DM8 SQL, JUnit/AssertJ/MockMvc, React/TypeScript, Vite, Node tests.

**Spec:** `docs/superpowers/specs/2026-09-14-contract-audit-evolution-master-design.md` sections 6, 11, 21, 22; human-authorized Phase 1E task dated 2026-09-15 supersedes the transitional combined promotion API described elsewhere in the master design.

## Global Constraints

- Work only in `.worktrees/contract-evolution-phase1` at `feature/contract-evolution-phase1-20260914`; baseline `3a2ccf2a20ad2d78b001a80a8d65eb6109cc31d8`, clean and remote-equal before work.
- DM8 is the system of record; Redis and frontend memory are not business truth. No client-supplied user/project/source identity may override Java server state.
- Use `CONTRACT__contract_candidate_confirmation.sql`, `CONTROLLED_MANUAL`, additive schema; no changes to V001–V022 and no numeric migration.
- Preserve machine candidate/extraction facts and historical PROMOTED candidates without invented confirmation IDs. No second write authority or legacy unsaved-payload promotion API.
- New confirmation requires DRAFT, expected candidate version, current CONTRACT_BODY human role, SAME_PROJECT human identity, exact parse/role/identity tuple; no automatic role/identity/reparse.
- Confirmation is append-only, canonical JSON is server-generated, `BigDecimal` never becomes `double`/`float`, and unreadable/unsupported/mismatched stored payload fails closed with 409.
- No OCR/model/HTTP in a business write transaction. Use candidate CAS, durable idempotency, operation audit, ownership-safe reads, and reopen/latest discovery.
- RED test must be run and its intended failure observed before each production edit; use synthetic non-sensitive fixtures only.
- No Qwen/Python/ReviewRun/async/report/frontend redesign; no push, merge, PR, or worktree removal. Finish after full verification and human code review handoff.

---

### Task 1: Confirmation domain and canonical payload/fingerprint

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/contract/domain/project/ContractCandidateConfirmation.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationPayloadCanonicalizer.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationPayloadCanonicalizerTest.java`

**Interfaces:** Produce `canonicalize(ConfirmedContractPayload)` yielding schema version, canonical JSON and normalized payload; `decodeAndVerify(ContractCandidateConfirmation)` yielding a validated `ConfirmedContractPayload`; `fingerprint(projectId, candidateId, source tuple, baseVersion, canonical JSON)`. Confirmation domain contains immutable IDs, versions, source tuple, payload/schema/fingerprint, actor/time/supersedes.

- [ ] Write tests for NFKC/collapsed text, nullable blanks, stable line order/tie rule, decimal scale normalization and ISO date; fake/unsupported stored JSON or SHA mismatch must fail 409.
- [ ] Run `backend/.\mvnw.cmd '-Dtest=ContractCandidateConfirmationPayloadCanonicalizerTest' test` and observe the expected missing-symbol/behavior RED.
- [ ] Implement only the canonicalizer and immutable domain. Use fixed serialization field order and decimal strings; no client JSON pass-through or floating-point deserialization.
- [ ] Re-run the focused test GREEN, refactor without changing behavior, and re-run.

### Task 2: CONTRACT migration and repository

**Files:**
- Create: `db/dm8/CONTRACT__contract_candidate_confirmation.sql`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ContractCandidateConfirmationRepository.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateConfirmationMapper.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateConfirmationPersistenceRow.java`
- Create: `backend/src/main/resources/mappers/dm8/ContractCandidateConfirmationMapper.xml`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/ContractCandidateMapper.java`, `backend/src/main/resources/mappers/dm8/ContractCandidateMapper.xml`, `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectRepositories.java`, candidate row/domain/repository files.
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/ContractCandidateConfirmationDm8SqlStructureTest.java`; focused repository tests.

**Interfaces:** Repository `insert`, `findByProjectAndCandidateAndId`, `findLatestByProjectAndCandidate`, `findById` with ownership check; candidate repository `confirmDraftIfVersionMatches` and promotion CAS with nullable `promotedConfirmationId` compatibility. One `(candidateId, resultVersion)` unique constraint establishes latest, with composite FKs where existing owner keys allow.

- [ ] Write SQL/mapper structure and repository behavior tests for header, CLOB, ownership/actor/role/identity/supersedes FKs, append-only read APIs, candidate CAS and PROMOTED provenance; run RED and record the intended missing schema/column failure.
- [ ] Add the migration and minimal DM8/in-memory repository changes, keeping old PROMOTED rows readable with `promotionProvenanceSource=LEGACY` and no backfill.
- [ ] Run focused tests GREEN. Do not claim real DM8 verified from a structure test.

### Task 3: Confirmation command, durable idempotency, CAS and recovery

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ConfirmContractCandidateCommand.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationService.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationTransactionCoordinator.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidateConfirmationServiceTest.java`

**Interfaces:** `confirm(command)` returns confirmation plus `replayed`; command takes project/candidate/expectedVersion/server-validated payload/key/request metadata, actor from `AuthContext`. Scope `CONTRACT_CANDIDATE_CONFIRM`; immutable fingerprint binds actor, project, candidate, expectedVersion, source tuple, payload fingerprint. Coordinator locks file→project→candidate, checks an existing same-key operation using its saved confirmation tuple before mutable readiness, and only on NEW operation checks authoritative role/identity, claims, inserts, CASes version, audits and marks succeeded in the same transaction.

- [ ] Write tests for success/version+1, same-key replay without second version change, different payload/version/candidate 409, corrected C2 superseding C1, two distinct commands racing, stale role/identity, PROMOTED rejection, and uncertain-result retry reopening C1. Run focused RED and observe each representative failure.
- [ ] Implement short local transaction with no OCR/model calls. Distinguish a committed confirmation recovered by existing idempotency result from an unproven IN_PROGRESS result; never create a second confirmation or bump again.
- [ ] Run focused GREEN and adversarial concurrency/recovery regression.

### Task 4: Confirmation HTTP API and reopen/latest discovery

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidateConfirmationApiIntegrationTest.java`
- Modify: `docs/openapi.yaml`

**Interfaces:** POST `/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations` requires `Idempotency-Key`; GET `/confirmations/{confirmationId}` and `/confirmations/latest` are read-only, require no key, validate project/candidate ownership, and return validated persisted canonical payload, source IDs, versions, actor/time/supersedes, fingerprint/schema/replayed.

- [ ] Write MockMvc tests for POST→GET exact canonical payload, GET latest, replay flag, wrong project/candidate NOT_FOUND, and no write/audit from GET; run RED.
- [ ] Implement additive controller DTO and read service, project controller guards/CSRF/RBAC as existing. Update OpenAPI confirmation paths/schema only when test has failed.
- [ ] Run focused API test GREEN and confirm a refresh can rediscover C1 without a remembered confirmationId.

### Task 5: Promotion accepts only confirmationId and persisted payload

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/PromoteContractCandidateCommand.java`, `ContractCandidatePromotionTransactionCoordinator.java`, `ProjectController.java`, candidate repository/mapper.
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidatePromotionApiIntegrationTest.java`; `ContractCandidatePromotionServiceTest.java`.
- Modify: `docs/openapi.yaml` promotion request.

**Interfaces:** POST promote body has only `expectedVersion` and `confirmationId` (unknown old payload fields rejected); transaction loads/decodes/validates confirmation and uses its persisted `ConfirmedContractPayload` to create Contract. NEW promotion requires C.resultVersion == candidate.version == expectedVersion and exact current source tuple; promoted candidate stores `promotedConfirmationId`.

- [ ] Write RED tests proving old direct-payload body cannot create a Contract, C1 content overrides machine proposal, stale C1 after C2 rejects, and Contract+ProjectContract+candidate confirmation provenance are atomic.
- [ ] Implement command/controller/schema switch and formal Contract creation solely from decoded durable C. No parallel legacy payload write endpoint.
- [ ] Run focused GREEN and failure-before-commit test.

### Task 6: Promotion replay ordering and immutable command identity

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionService.java`, `ContractCandidatePromotionTransactionCoordinator.java`.
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionServiceTest.java`, `ContractCandidatePromotionConcurrencyTest.java`.

**Interfaces:** Fingerprint v4 binds actor/project/candidate/expectedVersion/confirmation ID, payload fingerprint and saved source tuple, never mutable project/role/identity snapshot. Existing SUCCEEDED claim reopens Contract+ProjectContract+promoted candidate before current authority validation; only NEW claim checks current authority, DRAFT/version and confirmation freshness. Changed key input 409.

- [ ] Write RED tests for same-key replay after role/identity/project drift, K1+C2 conflict, new key after PROMOTED conflict, and corrupt/missing durable association fail closed.
- [ ] Reorder idempotency read/claim and promotion readiness minimally; ensure legacy v3 SUCCEEDED records are replayed only if old command equivalence can be proven from durable facts, otherwise fail closed and report NOT VERIFIED—never reintroduce old unsaved-payload POST.
- [ ] Run focused GREEN and concurrent regression.

### Task 7: Frontend confirm→promote and refresh-safe retry

**Files:**
- Modify: `frontend/src/features/project/data/projectDataSource.ts`, `data/httpProjectDataSource.ts`, `promotionReconciliation.ts`, `useProjectWorkspace.ts`, `components/CandidateConfirmationPanel.tsx`, `types.ts` where required.
- Test: `frontend/src/features/project/data/httpProjectDataSource.test.ts`, `promotionReconciliation.test.ts`, `candidateConfirmation.test.ts`; add focused two-stage workflow test.

**Interfaces:** Data source `confirmCandidate`, `getConfirmation`, `getLatestConfirmation`, and `promote({expectedVersion, confirmationId})`; UI retains separate confirmation and promotion keys. After refresh it can display the latest current-version confirmation; an uncertain POST result is proven only by retrying the exact same key and body, never by adopting a content-matching latest confirmation. Retrying a failed promotion never reconfirms.

- [ ] Write Node tests for confirm→promote ordering/two distinct keys, confirmation-succeeded/promotion-failed/latest rediscovery/retry-no-second-confirm, and distinct saved-vs-promoted UX; run targeted RED.
- [ ] Implement minimal operation state retention and latest GET reconciliation. Only show “合同确认已保存，可重试生成正式合同。” when confirmation is durable; never fabricate promotion runtime status.
- [ ] Run focused GREEN and verify API client sends only confirmationId+expectedVersion on promote.

### Task 8: Backward compatibility and regression

**Files:**
- Test/Modify: existing candidate domain, DM8 structure, promotion/API, project intake, identity, F02 tests as affected.

**Interfaces:** Historical DRAFT candidate can get first confirmation; old PROMOTED candidate/Contract/ProjectContract read with LEGACY provenance; old SUCCEEDED promotion idempotency can replay only if old fingerprint/command equivalence is provable, otherwise return safe 409 without a second write authority.

- [ ] Write RED tests for legacy PROMOTED read, historical DRAFT first confirmation, stale authority after confirmation, tampered stored CLOB/fingerprint 409, and no rollback of saved C after promotion failure.
- [ ] Apply only compatibility fixes required by observed RED; update old tests to the intentional two-command contract rather than weakening assertions.
- [ ] Run focused candidate/identity/F02 tests GREEN and `git diff --check`.

### Task 9: Full verification and handoff

**Files:** No production files unless verification reveals a new RED that is addressed by its own test-first cycle.

**Interfaces:** Report executable command, environment and observed result; distinguish LOCAL VERIFIED from REAL DM8 / REAL CUSTOMER MATERIAL NOT VERIFIED. Local in-memory repositories are not transaction-aware, so they cannot prove rollback after a partial insert/CAS; the DM8 transaction and migration require a controlled real-platform adversarial check before deployment.

- [ ] Run `backend/.\mvnw.cmd clean verify`; record Tests run/Failures/Errors/Skipped/BUILD.
- [ ] Run focused Node tests, then `frontend/npm.cmd ci` and `frontend/npm.cmd run build`; record observed results without dependency upgrades.
- [ ] Run `git diff --check`, `git status --short`, `git diff --stat`; verify V001–V022 unchanged, no numeric migration, no old HTTP promotion payload authority, no Qwen/Python/ReviewRun scope creep.
- [ ] Review transaction/replay/ownership/BigDecimal/refresh invariants; make one or a few local GREEN commits only if permitted by current sandbox; do not push.
- [ ] Produce only the terminal `PHASE1E_CANDIDATE_CONFIRMATION_RESULT` summary and stop for human code review.
