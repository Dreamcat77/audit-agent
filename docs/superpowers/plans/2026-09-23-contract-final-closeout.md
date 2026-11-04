# Contract Final Closeout Implementation Plan

**Execution mode:** serial shared DTO/domain/schema integration. Every production change starts with a focused failing test. One independent review is requested after the final candidate; only Critical/Important correctness findings trigger a fix round.

## Task 1: Freeze compatibility characterizations

**Tests:** Contract promotion/canonical payload, Contract snapshot decoder, F02 rules, old finding/read API.

1. Add v1 snapshot and confirmation fixtures that must remain byte-compatible/readable.
2. Add RED amount-only fixtures proving the current non-null breakpoints.
3. Add RED tests proving a complete amount-only Contract can freeze/reopen and F02 isolates NOT_EVALUATED to line arithmetic.
4. Run focused RED and retain exact failures.

## Task 2: Implement B amount-only Contract domain and persistence

**Production:** `ContractLineItem`, `ConfirmedContractPayload`, canonicalizer, promotion mapper, API DTOs, DM8 rows/mappers/repositories.

1. Add `ContractLineItemBasis` and basis invariants.
2. Version Confirmation canonical payload to v2; read schema v1 and v2.
3. Propagate basis through Promotion and Contract response.
4. Create `db/dm8/CONTRACT__contract_final_closeout.sql` with controlled header and the approved B changes; update README/order tests without editing old migrations.
5. Run focused GREEN and promotion/repository/API regression.

## Task 3: Snapshot/F02/evidence for B

1. Write RED v1/v2 round-trip, reopen and amount-only evidence tests.
2. Write snapshot v2 and dual-version decoder.
3. Update `LineItemArithmeticRule` to return per-line NOT_EVALUATED for AMOUNT_ONLY; keep line sum over declared amounts.
4. Update FindingSourceProjection and frozen F02 mapping to omit nonexistent q/u sources and preserve declared-amount locator.
5. Run F02 focused GREEN, ReviewRun reopen/recovery and old-run compatibility suites.

## Task 4: A FastAPI typed fact candidate protocol

1. Add RED Pydantic/request/router tests for the exact v1 allow-list, strict JSON, typed values, stable segment references, provider provenance, timeout and invalid provider output.
2. Add `ContractFactOperation` to `ModelGatewayPort` and a dedicated prompt/operation; do not route through contract review analysis.
3. Add `/ai/v1/agent/contract-fact-candidates` and full unit tests.
4. Run the focused FastAPI suite, then full FastAPI regression.

## Task 5: A Java validation, durable attempt and publication

1. Add RED adapter tests for exact configured provenance and strict response validation.
2. Add RED validator tests for file/SHA/parse/canonical/segment/locator/excerpt/typed/context checks and wrong-project/final-award fences.
3. Add domain ports and short-transaction coordinator: durable claim -> remote call outside transaction -> validate/publish -> durable completion.
4. Add the A tables to the controlled migration only after schema execution authorization is confirmed; add DM8 adapters and representative tests.
5. Integrate the command into explicit preparation progression. Upload/GET must not silently trigger repeated model calls; one server-authorized action performs extraction.
6. Prove deterministic facts survive AI failure, conflicts remain visible for human confirmation, and Candidate assembly still consumes only published `ExtractionResult`s.

## Task 6: C typed bilateral domain and deterministic analyzers

1. Add RED breach/term scenarios for two KNOWN sides, one missing side, NOT_APPLICABLE, CONFLICT and mismatched scenario keys.
2. Add typed value/status/result records; update analyzers without legal conclusions.
3. Keep existing v1 summaries for compatibility but create v2 only when typed comparison validation succeeds.
4. Run clause focused GREEN and existing analyzer regression.

## Task 7: C persistence, recovery and API

1. Add RED mapper/repository tests and interrupted completion recovery tests.
2. Add C tables to the controlled migration only after schema execution authorization is confirmed.
3. Persist findings, typed comparisons, values and evidence in the same COMPLETE transaction; recovery uses the same mapping without rerunning the analyzer/model.
4. Read v1 and v2; reject incomplete v2. Expose optional typed comparison DTO.
5. Verify DM8 persisted GET, new session, backend restart and old/new run coexistence.

## Task 8: Categories 2, 5, 6, 7 and 8

1. Add projection RED tests for template absence, WAITING_EXTERNAL, attachment references/matches, separate effectiveness/copies and no invented legal citation.
2. Implement the minimal deterministic attachment-reference/match output over frozen materials.
3. Add copy-count fact display without an invented authoritative threshold.
4. Keep page skeleton and exact-run projection unchanged.
5. Run eight-category, navigation, report and permission regressions.

## Task 9: Thin real integration loop before full 01—06

1. Re-read the reference env without outputting secrets. Record CODE_ROOT, isolated storage/temp/log, session namespace and non-sensitive DB/Redis/provider identity.
2. Recheck provider host/port/API shape/exact model, worker claim predicates, old pending/expired tasks and connected worker processes.
3. Apply only the explicitly authorized controlled migration to the integration DM8 after read-only catalog preflight and backup/rollback commands are recorded. Never rerun previous migrations.
4. Start backend/FastAPI/frontend from this worktree with PID files. Override the exact model only in the current PowerShell session.
5. Run one original thin flow: login -> isolated project -> upload -> parse/OCR -> deterministic/AI candidates -> confirmation/promotion -> ReviewRun -> typed/F02 -> locator -> copy -> history reopen.

## Task 10: Full automated gates and final candidate

1. Backend focused suites, then `backend\.\mvnw.cmd clean verify`.
2. Frontend focused, default full, shared full and production build.
3. FastAPI focused and full.
4. `git diff --check`, secret scan and architecture invariants.
5. Commit local final candidate and record SHA/build hashes. No push/merge.
6. Request one independent review. Fix only Critical/Important production correctness; if fixed, create a new candidate SHA and rerun affected gates.

## Task 11: Original 01—06 browser E2E on one candidate SHA

For each original set record only non-sensitive evidence: original hashes, deterministic facts, AI candidates/call count, conflict/human corrections, Candidate/Confirmation/Contract, exact run, F02, typed clause, eight categories, locator, refresh/new browser/backend restart/history/permissions.

Required cases:

- 01: amount-only 28000 + 16000 + 9000 + 14000 = 67000.
- 02: contract 69000 vs award 67000 and both locators.
- 03: service/performance differences.
- 04: breach typed differences.
- 05: wrong-project notice never becomes authority.
- 06: OCR/missing facts remain honestly uncertain.

## Task 12: Evidence and handoff

Create/update:

- `docs/evidence/contract-review-self-verification-guide-20260924.md`
- `docs/evidence/contract-eight-category-acceptance-20260925.md`
- `docs/evidence/contract-evidence-navigation-regression-20260925.md`
- `docs/evidence/contract-review-release-ledger-20260922.md`
- `docs/evidence/contract-review-unified-closeout-problem-matrix-20260923.md`

Report CODE, PREPARATION, AI_FACT_EXTRACTION, AMOUNT_ONLY, TYPED_CLAUSE, EIGHT_CATEGORIES, SOURCE_NAVIGATION, HISTORY, SECURITY, ORIGINAL_01_06, PROVIDER, OA and EXTERNAL_QUALIFICATION separately.

## 2026-09-25 remediation execution addendum

The following tasks extend this plan without opening another phase.

### R1 — Offline correctness blockers

1. Characterize and fix false-normal finding projection and copied opinions.
2. Inject the inner-success/outer-in-progress recovery break and recover by the
   original durable operation identity without a second provider call.
3. Add negative, percent, wrong-label, scale, repeated-excerpt, partial bilateral
   evidence, duplicate-proof, and late-publication tests.
4. Tighten template boundaries, template order, attachment readability/ambiguity,
   and copy-count coverage while retaining the fixed page skeleton.
5. Preserve existing FastAPI semantic error codes through the Java adapter,
   executor, asynchronous worker, and durable attempt/step reason fields.
6. Run focused backend and frontend regression before any environment write.

### R2 — Protocol v2 and durable diagnostics (approved; implementation in progress)

1. Add the v2 server-owned evidence-ID endpoint while retaining v1 compatibility.
2. Add partial-success/rejection/call-metric protocol records to Python and Java.
3. Add the two-column precision widening and two additive audit tables described
   in spec section 8.3. Do not edit A/B/C migrations.
4. Persist accepted candidates, rejected row summaries, and call audits in the
   existing short COMPLETE/FAILURE transaction boundaries.
5. Add DM8 round-trip, crash recovery, old/v2 coexistence, and no-secret tests.

### R3 — Thin integration and final verification

1. Re-read the existing handoff and reference environment without printing
   secrets. Confirm DB/Redis/storage locality and private-provider reachability.
2. After R2 focused/full tests, backup, catalog/authority/worker preflight, apply
   only the authorized new migration to the verified integration DM8. Do not
   rerun A/B/C. Stop on changed or partial preflight state.
3. Run one new original-material browser chain and verify API/mapper/persistence,
   source navigation, copied opinions, and restart recovery.
4. Run all backend/frontend/FastAPI gates, review the candidate, commit and
   non-force push the feature branch, then run a fresh exact-SHA 01—06 matrix.
5. Create one non-owner identity through the existing normal application admin
   flow, using the least existing Contract-capable role, then run owner/non-owner
   checks. Anonymous denial is supplementary and does not replace cross-user
   verification. Do not change user tables, historical owners, or role semantics.

The user approved R2 implementation, the exact additive migration on the verified
integration DM8, and second-user creation through the existing application admin
flow. Customer/production DDL, old migration reruns, direct user-table writes,
force push, merge, and main remain outside scope.
