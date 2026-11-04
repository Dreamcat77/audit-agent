# Contract Evolution Phase 1C Candidate Authority Implementation Plan

> Date: 2026-09-15
>
> Baseline: `origin/feature/contract-evolution-phase1-20260914@570d51ab3c8e27452e6d44c182425e2dea5e4f02`
>
> Branch/worktree: `feature/contract-evolution-phase1-20260914` / `D:\code\audit_agent_1D\.worktrees\contract-evolution-phase1`
>
> Scope: remove `ProjectFile.category` as current-pipeline candidate authority, materialize/replay a parse-bound candidate only after authoritative human `CONTRACT_BODY` confirmation, and fail closed at promotion when current source authority is invalid.

## Goal and fixed boundaries

For a `CURRENT_PIPELINE` ProjectFile, the only candidate-creation authority is the latest human role decision bound to the same project, file SHA-256, immutable parse-result ID, and parse version. Initial parse extracts role-neutral potential contract facts from the in-memory `ParsedProjectDocument` once and persists those observed facts with the immutable parse identity. Confirmation reuses those facts; it never invokes PDFBox, POI, OCR, or file storage again.

Existing candidate and Contract rows remain intact. `DRAFT` and `PROMOTED` remain the only candidate states. A DRAFT candidate whose source later becomes non-contract remains readable but cannot be promoted. A PROMOTED candidate cannot receive a new non-contract role decision until a later explicit disposition workflow exists. No Project Identity, AI role fallback, FastAPI, frontend redesign, ReviewRun, async worker, report, debug bundle, or quality-harness implementation is in this slice.

## Architecture choices fixed for this slice

- `PROJECT_FILE.CATEGORY` remains classification/legacy metadata. It cannot authorize candidate creation for `CURRENT_PIPELINE` data.
- Every newly persisted extraction explicitly carries nullable `parseResultId`; current-pipeline potential facts require it, while historical v1 facts retain NULL.
- Potential extraction is an observed-fact operation, not candidate creation. `POTENTIAL_CONTRACT` mode admits contract scalar/line-item facts only outside reference/template sections and retains existing project-discovery facts.
- Candidate materialization accepts only server-owned `projectId`, `fileId`, and durable `roleDecisionId`. It validates the latest effective role as `CONTRACT_BODY/HUMAN_CONFIRMATION` and all exact parse bindings.
- One source file still has at most one candidate. Same-decision replay returns the same candidate. A later valid contract re-confirmation reauthorizes the same DRAFT candidate against the new current decision using guarded version/CAS semantics; it never creates a second candidate or edits extracted facts.
- Candidate insertion, line items, and evidence bindings execute in one short DM8 transaction. Evidence association is the only mutable extraction field and only permits NULL to the candidate for the same project/file/parse; same-candidate is replay and different-candidate is conflict.
- `CONTRACT_CANDIDATE_MATERIALIZE` uses server key `role-decision:<decisionId>` and a fingerprint over project/file/SHA/parse/text SHA/decision/extraction version/stably sorted extraction keys. A durable role decision is not rolled back when materialization fails; replay of the same role-confirmation command reopens the decision and retries the stable materialization operation.
- Promotion validates source authority in the write transaction and includes its identity in the promotion idempotency fingerprint. A changed role authority under the same client key is a 409 fingerprint conflict.
- Candidate provenance NULLs remain truthful pre-Phase1 legacy data. Read projection maps that absence to `LEGACY_COMPAT` without inventing parse or decision IDs. Legacy promotion materializes `LEGACY_IMPORTED` only when necessary and then requires `CONTRACT_BODY/LEGACY_EFFECTIVE` or current human `CONTRACT_BODY`.

## Task 1 — Candidate authority persistence additions

**Files**

- Create `db/dm8/CONTRACT__contract_candidate_authority.sql` with the required CONTRACT header and dependencies.
- Modify `ContractCandidate`, `ExtractionResult`, their persistence rows, mapper interfaces/XML, DM8 and in-memory repositories.
- Create `ContractCandidateAuthoritySource` and repository methods for exact parse lookup, guarded evidence binding, candidate source lookup/locking, and guarded DRAFT reauthorization.
- Create/extend SQL-structure, mapper/repository, identity, and migration-namespace tests.

**RED**

First add tests proving the migration is additive, V010 is untouched, provenance columns are nullable with FK/index/check constraints, extraction `PARSE_RESULT_ID` is explicit, historical NULL rows remain readable, exact parse query works, and evidence binding has NULL/same/different-candidate semantics.

Run the focused SQL/repository tests and observe failures caused by the absent schema/domain/ports.

**GREEN**

Implement only the nullable provenance/parse fields and guarded repository operations. Preserve v1 extraction/candidate constructors through deliberate compatibility factories or overloads rather than guessed backfill.

## Task 2 — Parse-aware potential contract extraction

**Files**

- Modify `ProjectFactExtractor`, `ProjectFactContextGuard`, `ExtractionKeyFactoryV2` call sites, and focused tests.
- Add `ProjectFactExtractionMode` (or an equivalent explicit mode) distinguishing project discovery from potential-contract observation.

**RED**

Add synthetic parsed-document tests with generic names (`001.pdf`, `扫描件.pdf`, `材料2.pdf`) proving contract facts are extracted independent of filename/category, all new facts bind the exact parse result, project facts retain existing behavior, and fields/line items under `参考合同/合同模板/合同范本/示范文本/仅供参考` are excluded. Add a parser invocation counter proving extraction consumes the already-created `ParsedProjectDocument` only once.

**GREEN**

Pass the preallocated immutable parse ID into extraction, use v2 keys directly, and gate potential contract facts by mode plus context rather than `ProjectFile.category`. Do not create candidates in the extractor.

## Task 3 — Candidate materialization service/coordinator

**Files**

- Create `ContractCandidateMaterializationService`, `ContractCandidateMaterializationTransactionCoordinator`, result/status/fingerprint types, and focused tests.
- Modify `ContractCandidateAssembler` to accept a preallocated candidate ID while retaining only necessary compatibility overloads.

**RED**

Test exact current parse/decision binding, `CONTRACT_BODY/HUMAN_CONFIRMATION` authority, server-side materialization idempotency, no client facts, one candidate per source, same-decision replay, conflicting facts, exact-parse evidence, and atomic failure behavior.

**GREEN**

Load only durable server state, assemble from exact-parse observed facts, preallocate the candidate identity, insert/reuse or guardedly reauthorize the DRAFT candidate, bind all evidence, append operation audit, and complete idempotency in one short transaction. No external compute occurs in the transaction.

Code-review gate: lock the stable `PROJECT_FILE` row before reading mutable latest role authority; separate completed-operation idempotent reopen from a new materialization so an earlier successful command remains replayable after later role decisions. A `LEGACY_IMPORTED` file with an existing legacy candidate reuses the historical candidate without fabricating parse-bound facts.

## Task 4 — Role confirmation to candidate re-entry

**Files**

- Modify `RoleConfirmationService`, `RoleConfirmationTransactionCoordinator`, `RoleConfirmationResult`, `ProjectController`, and API/concurrency tests.

**RED**

Test: generic-body confirm creates one candidate; award confirmation creates none; same and concurrent confirmation replay returns the same decision/candidate; distinct commands preserve role-decision CAS and candidate follows the winner; post-decision materialization failure leaves the decision durable and replay retries; GET decision is read-only; PROMOTED source rejects non-contract before a decision is appended.

**GREEN**

After confirmation/reconciliation returns the durable decision, invoke materialization only for `CONTRACT_BODY`. Add nullable `candidateId` and `candidateMaterializationStatus` (`NOT_APPLICABLE`, `MATERIALIZED`, `REPLAYED`) to the response. GET may project existing candidate state but must never invoke materialization.

Code-review gate: confirmation acquires the same `PROJECT_FILE` transaction fence before appending a new decision; promoted candidate may accept another confirmed `CONTRACT_BODY` without rewriting the promoted snapshot, and same-key retry reopens the same candidate.

## Task 5 — Promotion authority gate

**Files**

- Create `CandidateSourceAuthorityValidator` and an immutable promotion-authority snapshot/fingerprint input.
- Modify promotion service/coordinator, candidate repository lock/CAS seams, and promotion unit/API/concurrency tests.

**RED**

Test current human role/parse equality for new candidates, rejection after DRAFT source changes to OTHER, safe re-entry after changing back, legacy contract acceptance, legacy UNKNOWN rejection, and same promotion key conflicting after authority changes.

**GREEN**

Lock/read the candidate source, validate current effective role and parse identity before any Contract write, include file/SHA/parse/effective role/source/current decision in the idempotency fingerprint, and revalidate under the same short transaction. Preserve existing atomic Contract/children/ProjectContract/candidate promotion behavior.

Code-review gate: acquire the source `PROJECT_FILE` fence before candidate-row `FOR UPDATE` to serialize both `OTHER` and renewed `CONTRACT_BODY` decisions with promotion, preserving one global file-then-candidate lock order.

## Task 6 — Candidate/evidence projection compatibility

**Files**

- Modify `ProjectApplicationService.CandidateDetail`, `ProjectController.CandidateDetailResponse`, and projection tests.

**RED**

Test non-empty exact-parse evidence for a generic confirmed candidate and additive provenance output: exact source parse ID, exact source decision ID, `HUMAN_CONFIRMATION`, and source fact fingerprint.

**GREEN**

Expose durable provenance without returning raw model data or inventing legacy IDs. Preserve existing candidate fields and evidence/field-state projections.

## Task 7 — Legacy compatibility

**Files**

- Extend legacy parse materializer/authority validator tests and legacy candidate fixture support only where required.

**RED**

Test existing DRAFT candidate read/evidence/promotion with `LEGACY_IMPORTED + category CONTRACT_BODY`, fail-closed promotion for category UNKNOWN, unchanged PROMOTED Contract/association, no historical extraction recalculation, and no `WITHDRAWN` state.

**GREEN**

Interpret nullable candidate provenance as `LEGACY_COMPAT` on read. Materialize only the immutable legacy parse projection when absent; never reparse, rebuild a candidate/Contract, mutate category, or fake a role decision.

## Task 8 — Full regression and static gates

Run focused suites after each RED/GREEN cycle, then:

```powershell
cd backend
.\mvnw.cmd clean verify
```

Record tests, failures, errors, skipped, and build result. Then run `git diff --check`, `git status --short`, and `git diff --stat`; prove `db/dm8/V001` through `V022` have zero changes; inspect `WITHDRAWN`, `FastAPI`, `Qwen`, `ProjectIdentity`, and `ContractReviewRun` searches for scope creep.

The completion statement must distinguish `LOCAL UNIT/INTEGRATION VERIFIED` from `REAL DM8 NOT VERIFIED` and `REAL CUSTOMER MATERIAL NOT VERIFIED`. Do not push; stop for human code review.

## Representative focused commands

```powershell
cd backend
.\mvnw.cmd '-Dtest=ContractCandidateAuthorityDm8SqlStructureTest,ContractMigrationNamespacePolicyTest,Dm8ProjectRepositoriesTest,InMemoryProjectRepositoriesTest,ProjectFactReplayIntegrityTest' test
.\mvnw.cmd '-Dtest=ProjectFactExtractorTest,ProjectFactContextGuardTest,ProjectFileIngestionServiceTest,ProjectUploadConcurrencyTest,ProjectFileRecoveryTest' test
.\mvnw.cmd '-Dtest=ContractCandidateMaterializationServiceTest,ContractCandidateMaterializationConcurrencyTest,ContractCandidateAssemblerTest' test
.\mvnw.cmd '-Dtest=RoleConfirmationServiceTest,ProjectFileRoleApiIntegrationTest,ContractCandidatePromotionServiceTest,ContractCandidatePromotionConcurrencyTest,ContractCandidatePromotionApiIntegrationTest' test
```

Exact test class names may follow existing repository conventions, but each required invariant above must have executable evidence before its production change is accepted.
