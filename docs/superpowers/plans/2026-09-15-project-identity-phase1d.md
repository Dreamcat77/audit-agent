# Contract Evolution Phase 1D Project Identity Implementation Plan

> Date: 2026-09-15
>
> Baseline: `origin/feature/contract-evolution-phase1-20260914@6bbff9ee126fe11879ea299ccae8aa0c6b3fd1ab`
>
> Worktree: `D:\code\audit_agent_1D\.worktrees\contract-evolution-phase1`
>
> Scope: deterministic file-to-Project identity assessment, append-only human resolution, current authority projection, candidate readiness and promotion gates. This is the authorized Phase1D slice, not a new architecture specification.

## Fixed decisions and invariants

- `AUTO_SAME_PROJECT` is disabled. An assessment is an observed comparison, never ownership authority. Only a current, exact-bound human `CONFIRM_SAME_PROJECT` decision yields effective `SAME_PROJECT`.
- Compare typed `PROJECT_NO` only with `Project.projectNo` and typed `PROJECT_NAME` only with `Project.name`. Normalize NFKC, trim, collapse whitespace, normalize full-width punctuation and whitespace around allowed project-number separators while preserving letters, digits, check digits and meaningful punctuation. Any comparable mismatch is `CONFLICT`; one or more matches without mismatch is `LIKELY_SAME_PROJECT`; absent comparable values is `INSUFFICIENT`. Missing is not mismatch. No fuzzy matching, embedding, supplier or amount inference.
- Assessment and decision bind Project ID/version plus ProjectFile ID/SHA-256 and immutable parse result ID/version. Project metadata version changes make historical decisions reopenable but not current or effective.
- `PROJECT_FILE` remains the authoritative uploaded material. Assessments and confirmations reuse parse-bound typed facts; no second storage/parse subsystem and no OCR/model call in a DM8 write transaction.
- Candidate creation requires the pair of current human `CONTRACT_BODY` role decision and current human `CONFIRM_SAME_PROJECT` identity decision. A non-SAME result leaves the role decision durable and candidate materialization waiting. Identity-first and role-first converge on the same pair. A negative decision retains the uploaded file and DRAFT candidate, but blocks materialization/promotion. PROMOTED source rejects a new negative identity decision before insertion.
- A new candidate persists nullable `SOURCE_IDENTITY_DECISION_ID` along with Phase1C provenance. Historical candidate rows retain NULL. A pre-Phase1D DRAFT candidate must be explicitly reauthorized with a current SAME identity decision before promotion; no implicit human decision is created in promotion. Existing PROMOTED Contract snapshots remain untouched.
- `CONTRACT_CANDIDATE_MATERIALIZE` uses stable server key `role-decision:<roleId>:identity-decision:<identityId>` and a fingerprint over the exact role/identity pair, file/parse/project version and sorted facts. Earlier Phase1C idempotency records remain readable under their old key; they are not rewritten or reused as a new identity-authorized operation.
- Every POST uses durable DM8 idempotency and operation audit; GET reopen is read-only and does not mint an operation. All IDs and actor identity come from server-owned state and `AuthContext`. Existing Spring Security, session cookie, CSRF, RBAC, RequestId and TraceId boundaries remain.

## Execution rule

For each task, write a focused adversarial test first, run it and record its expected RED failure, make the smallest production change to GREEN, and run focused regression before moving on. Do not generate per-task reports. Do not push, merge, rebase or modify the root worktree. SQL remains controlled-manual and `REAL DM8 NOT VERIFIED` until separately executed on a representative environment.

## Task 1 — Identity domain model and normalizer

**RED:** Test NFKC/whitespace/punctuation normalization and that missing values remain distinct from conflicting values. Test all status/decision enum invariants and Project/version/file/parse binding validation.

**GREEN:** Add immutable `ProjectFileIdentityAssessment`, evidence and decision domain records; execution, comparison, decision, resolution and effective-status enums; a deterministic normalizer with bounded outputs. Preserve historical decision identity and monotonic version numbers.

## Task 2 — CONTRACT identity SQL and repositories

**RED:** Add SQL structure and namespace tests for the exact `CONTRACT__project_file_identity_assessment.sql` header, assessment/evidence/decision tables, composite ownership FKs, `DECIDED_BY VARCHAR(36)` FK to `SYS_USER(ID)`, nullable candidate identity provenance with FK/index, and no V001–V022 modifications. Add repository tests for owned lookup, latest lookup, guarded decision version insertion, and candidate identity reauthorization CAS.

**GREEN:** Create only the nonnumeric controlled-manual CONTRACT migration. Add identity repository Ports with DM8 MyBatis and local in-memory Adapters; extend candidate persistence row/mapper/repository for nullable `SOURCE_IDENTITY_DECISION_ID` and guarded DRAFT CAS. No backfill or second extraction table.

## Task 3 — Deterministic identity resolver and evidence

**RED:** Synthetic parse-bound `PROJECT_NO`/`PROJECT_NAME` matrix: match, mismatch, missing Project value, missing file value, contradictory comparable facts and unconfirmed Project metadata. Verify no supplier, award, amount or embedding creates ownership. Verify evidence references actual extraction IDs and bounded locators.

**GREEN:** Read Project metadata and exact-parse typed facts, normalize exact comparands, emit durable `MATCH/MISMATCH/MISSING_FILE_VALUE/MISSING_PROJECT_VALUE` evidence with hashes and safe bounded values. Produce `LIKELY_SAME_PROJECT`, `CONFLICT` or `INSUFFICIENT`, never automatic SAME.

## Task 4 — Identity assessment command, idempotency and reopen

**RED:** Same-key replay returns the same assessment/evidence; changed Project version, file SHA, parse, normalized metadata or sorted fact identities with the same key returns 409. Cross-project/file GET yields safe NOT_FOUND. GET creates no assessment/decision/idempotency/audit command result.

**GREEN:** Add short transaction coordinator and service using `PROJECT_IDENTITY_ASSESSMENT` scope, actor from `AuthContext`, versioned fingerprint, owned latest parse/facts and operation audit. Persist assessment/evidence atomically; GET reopens the durable resource without recalculation or new command.

## Task 5 — Human identity confirmation and effective resolver

**RED:** Human SAME and NOT_SAME are append-only; same-key replay has one decision; changed resolution/reason/expectedVersion or current Project version conflicts. Concurrent commands with the same expected decision version yield one winner. A previous Project-version decision reopens but is not current. PROMOTED source rejects NOT_SAME before any new decision. Negative resolution never deletes, moves or reparses a file.

**GREEN:** Add `PROJECT_IDENTITY_CONFIRMATION` scope and short confirmation transaction with project/file authority fence, exact assessment/file/parse/Project version validation, decision-stream CAS, `SYS_USER` actor ID and audit. Add read-only effective resolver: current SAME only from exact-bound human positive decision; current NOT_SAME excludes; outdated/missing decision is not authoritative.

## Task 6 — ProjectFile projection and additive HTTP APIs

**RED:** API POST assessment→GET same ID/evidence, POST decision→GET same ID/actor/version, ownership NOT_FOUND, CSRF/RBAC/idempotency behavior, and read-only reopen. Projection tests distinguish latest assessment execution, decision status, effective identity status, current decision flag and excluded flag without fake runtime state.

**GREEN:** Add only four identity POST/GET routes to existing `ProjectController` and additive identity fields to existing file/confirmation responses. Require the same four ProjectController roles. IDs are server-validated; no client-declared user/project ownership trusted. No frontend changes.

## Task 7 — Candidate materialization identity gate

**RED:** Generic contract role-first waits without candidate; identity-first waits until role confirmation; positive resolution converges to exactly one candidate and parse-bound evidence without reparse. Same command replay retries materialization after a durable decision and transient failure. Pair key/fingerprint replay, new provenance, DRAFT identity reauthorization, negative decision retaining DRAFT, file A/B independence and PROMOTED no-rewrite behavior.

**GREEN:** Have role and identity confirmation services trigger the existing materializer only after their own decision commits. Materializer independently reloads the latest exact-bound role/identity pair, Project metadata version, parse and facts, then performs candidate insert/replay or guarded DRAFT reauthorization plus evidence binding and audit in one short transaction. Return `WAITING_FOR_PROJECT_IDENTITY` for a durable role confirmation without SAME; GET remains read-only.

## Task 8 — Promotion identity authority

**RED:** Reject a DRAFT candidate without current SAME or without matching `SOURCE_IDENTITY_DECISION_ID`; reject after NOT_SAME or Project-version change; same promotion key with changed identity authority returns 409. Existing PROMOTED Contract remains immutable, and F02 promotion regressions pass.

**GREEN:** Extend server-derived promotion authority snapshot and idempotency fingerprint with Project version/effective identity/current identity decision. Validate under source-file lock in the existing short promotion transaction before any Contract/ProjectContract write. Do not auto-create assessment or decision during promotion.

## Task 9 — Project-version invalidation and compatibility

**RED:** Metadata confirmation increments version and invalidates old SAME for each file independently; old assessments/decisions remain owned GET-reopenable but not effective. Old Phase1C materialization records and pre-Phase1D candidate provenance remain readable. Existing PROMOTED contracts are unchanged; old DRAFT candidates need explicit SAME reauthorization before promotion.

**GREEN:** Make effective identity and candidate gates compare current `Project.versionNo` on every command/projection. Preserve nullable legacy provenance and previous role/candidate states without guessed backfill or migration rewrite.

## Task 10 — Full regression and static gates

Run focused identity, candidate materialization, role API and promotion tests after each GREEN. Then run `backend\.\mvnw.cmd clean verify`, record aggregate Tests run/Failures/Errors/Skipped and BUILD result. Run `git diff --check`, `git status --short`, `git diff --stat`, verify V001–V022 zero changes, no numeric migration, no Python/frontend/FastAPI/Qwen/ReviewRun/async scope expansion. Self-review no batch assumption, no AI authority, no candidate/identity authority hole, no hidden reparsing, no partial candidate/evidence transaction, and an owned GET path for known durable IDs. Final terminal result distinguishes `LOCAL UNIT/INTEGRATION VERIFIED`, `REAL DM8 NOT VERIFIED` and `REAL CUSTOMER MATERIAL NOT VERIFIED`; stop for human code review without push.

## Known recovery boundary for human review

This slice exposes the latest owned assessment ID/status in the file projection and owned GET-by-ID for every stored assessment. It does not expose a full assessment-history list or a key-based read-only operation lookup. If A1's POST response/ID is lost, Project metadata changes, and A2 later supersedes it, A1 remains durable but is no longer discoverable from the latest file projection; the original key correctly returns 409 for changed input. This is a residual historical-assessment rediscovery gap, not authority for a new decision. A separate approved recovery/history API design is needed before claiming lossless rediscovery of every superseded assessment after an unknown client outcome.
