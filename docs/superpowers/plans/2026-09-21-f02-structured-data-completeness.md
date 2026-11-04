# F02 Structured-Data Completeness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that a complete confirmed Contract survives ReviewRun freeze/reopen and makes all five F02 rules calculable, while genuine missing facts remain isolated `NOT_EVALUATED` cases.

**Architecture:** Add characterization coverage to existing ReviewRun/F02 tests. Keep the durable Contract and verified frozen snapshot as the only F02 business input. Correct evidence projection only if a focused RED test confirms its supplier-name normalization differs from the F02 rule.

**Tech Stack:** JDK 17, Spring Boot, JUnit 5, MockMvc, MyBatis/DM8, Maven wrapper.

**Spec:** `docs/superpowers/specs/2026-09-21-f02-structured-data-completeness-design.md`

## Global Constraints

- Base commit: `b16042cd6a4b52bb779dc2e1c96af5eb47d7b58e`; work only in `feature/phase1c-f02-completeness-20260921`.
- Contract is confirmed business truth; Project materials are provenance. Do not change Candidate authority, support replacement, Confirmation, Promotion, ReviewRun lifecycle, public API, or DM8 schema.
- F02 amounts use `BigDecimal`/`Money` and numerical equality (`compareTo`); no float, double, tolerance adjustment, or AI amount decision.
- Keep a real `NOT_EVALUATED` for missing amount words, empty line items, or absent award reference. One missing fact cannot suppress unrelated rules.
- Runtime configuration `D:\code\audit_agent_1D\.worktrees\ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.ps1` and original material directory `D:\code\audit-agent-demo\智慧维修系统_合同审查测试材料_20260917` are read-only. Do not migrate DM8, edit either source worktree, or expose secrets.
- Run focused tests and `backend/.\mvnw.cmd clean verify` before real integration. Do not push or merge.
- Per task: RED, minimal GREEN, nearby regression, one independent review. Fix only Critical/Important production correctness findings, at most one justified fix round.

## Review Focus

1. Full Contract with two lines and award: all five rule codes are present; line arithmetic has two distinct `itemKey` results.
2. Missing words: only amount-words is `NOT_EVALUATED`; four other rules calculate.
3. Empty lines: only arithmetic and line-sum are `NOT_EVALUATED`; the other three calculate.
4. Absent award: only award amount and supplier are `NOT_EVALUATED`; the other three calculate.
5. Full-width/half-width supplier source text: source projection follows the F02 name normalization without admitting a wrong source or changing the F02 verdict.

---

### Task 1: Complete Contract through ReviewRun freeze and F02

**Files:**
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunApiIntegrationTest.java`
- Modify if necessary: `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/ContractSnapshotDecoderTest.java`
- Production only if RED proves a gap: the exact mapper, snapshot, or executor file where a fact disappears.

**Interfaces:** Existing `promotedContract(...)`, ReviewRun POST/GET, F02 step execution, `AuditCheckResult` JSON. No new production interface.

- [ ] Add a test that creates a run from the existing incomplete fixture, asserts the two frozen line entries, amount words, award amount/supplier and six expected F02 findings (two arithmetic rows plus four single-result rules). The expected assertion must first fail for missing fixture facts; record this as fixture RED, not a production bug.
- [ ] Add a complete test-only confirmation payload: `contractAmount=100.00`, `amountInWords=人民币壹佰元整`, two lines (`ITEM-1`: `2 × 20.00 = 40.00`, `ITEM-2`: `3 × 20.00 = 60.00`), and award (`winningAmount=100.0`, `winningBidder=Supplier`, required notice number/project/date). Keep the older incomplete helper behavior for other tests.
- [ ] Reopen the run through GET, execute its frozen F02 step, assert the five rule codes, six `PASS` check rows and expected `itemKey` values. Reopen the step and assert the same task ID, result IDs/statuses and frozen snapshot hash. Compare amounts numerically to prove scale independence.
- [ ] Run `backend/.\mvnw.cmd -Dtest=ContractReviewRunApiIntegrationTest,ContractSnapshotDecoderTest test`; resolve only failures demonstrated by this test. Run nearby ReviewRun tests, then commit the task.
- [ ] Request one independent task review against this task and its diff. Record Minor findings; one fix/re-review round only for Critical/Important correctness findings.

### Task 2: Single-fact absence matrix

**Files:**
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractReviewRunApiIntegrationTest.java`
- Or, if the HTTP fixture setup makes independent cases needlessly expensive, modify: `backend/src/test/java/com/fuquan/audit/contract/application/reviewrun/executor/FrozenF02ReviewStepExecutorTest.java`
- Production only if RED demonstrates an actual prerequisite/comparison bug.

**Interfaces:** The Task 1 complete Contract fixture and existing frozen F02 execution. No new production interface.

- [ ] Add distinct cases derived from Task 1 facts: omit only `amountInWords`; omit only `lineItems`; omit only `awardNotice`. Assert all unaffected rules remain calculable. For the empty-line case, assert both line rules are `NOT_EVALUATED`; for absent award, both award rules; for missing words, only amount-words.
- [ ] Watch each new case fail against the deliberately incomplete fixture or a test-only negative control before supplying its intended single-fact fixture. Record whether RED is a fixture failure or a source defect; do not change production for a fixture failure.
- [ ] Run `backend/.\mvnw.cmd -Dtest=ContractReviewRunApiIntegrationTest,FrozenF02ReviewStepExecutorTest test`, then nearby F02 rule tests. Commit the task.
- [ ] Request one independent task review. Record Minor findings; one justified fix/re-review round only for Critical/Important correctness findings.

### Task 3: Supplier evidence projection normalization

**Files:**
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/FindingSourceProjectionServiceTest.java`
- Modify only after expected RED: `backend/src/main/java/com/fuquan/audit/contract/application/FindingSourceProjectionService.java`

**Interfaces:** Existing `FindingSourceProjectionService.project(Contract, AuditCheckResult)` and F02 `AwardSupplierMatchRule` normalization semantics.

- [ ] Build a no-credit-code fixture whose confirmed Contract and Candidate use a full-width supplier name while the unique, correctly owned extraction has the equivalent half-width name. Assert the F02 supplier rule calculates `PASS` and the projection returns the two correctly owned file IDs. Keep an adversarial extraction with a genuinely different name unavailable.
- [ ] Run `backend/.\mvnw.cmd -Dtest=FindingSourceProjectionServiceTest test` and record the expected `SOURCE_NOT_FOUND` RED for the equivalent-name case; if it is already GREEN, make no production change.
- [ ] If RED identifies only normalization, apply NFKC to the projection's `NAME` comparison while preserving existing whitespace/case normalization and authority/source filters. Run focused GREEN and neighboring source-projection tests. Commit the task.
- [ ] Request one independent task review. Record Minor findings; one justified fix/re-review round only for Critical/Important correctness findings.

### Task 4: Full gate and real integration evidence

**Files:**
- Create: `docs/evidence/phase1c-f02-structured-data-completeness-20260921.md`
- Modify no production file unless a new RED test or real runtime evidence first isolates its defect.

**Interfaces:** Existing Phase 1B validated integration environment, existing ReviewRun/F02 HTTP APIs, existing migrated DM8.

- [ ] Run focused F02 and ReviewRun tests, `backend/.\mvnw.cmd clean verify`, relevant frontend tests, full frontend tests and build. Run FastAPI suite only if Phase 1C touches or relies on changed AI behavior.
- [ ] Read the integration environment configuration without editing it. Use application code from this P1-C worktree. Confirm target DB is the already migrated Phase 1B integration DM8; do not apply migration. Inspect prior safe Projects/Contracts for unambiguous reuse or create new synthetic test records only.
- [ ] Via authenticated HTTP, verify durable Contract facts, ReviewRun frozen JSON/hash, reopen after fresh read/restart when practical, then the five F02 rule statuses, reasons, and evidence. Record sanitized presence/value summaries; never write secrets or raw original material to Git. If the original materials lack a required fact, use an explicitly labeled safe synthetic test fixture, not a claim that OCR extracted it.
- [ ] Verify the P1-B explicit-support and primary-only compatibility cases still retain their award amount/supplier behavior. Record all business-data `NOT_EVALUATED` separately from technical gaps.
- [ ] Run `git diff --check`, inspect `git diff` and `git status`, request one final independent review, and report command, environment, observed result and unverified limits. Do not push or merge.
