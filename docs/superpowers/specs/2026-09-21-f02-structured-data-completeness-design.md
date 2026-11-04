# F02 Structured-Data Completeness Design

## Intent and evidence

Phase 1C proves that a complete confirmed Contract remains complete through Promotion, DM8 persistence, ReviewRun freeze/reopen, deterministic F02 execution, and the result read model. Phase 1B final baseline is `b16042cd6a4b52bb779dc2e1c96af5eb47d7b58e`. Its representative integration E2E produced five durable F02 results. The three `NOT_EVALUATED` results had absent amount words or line items in the confirmed Contract; award amount and supplier were calculable. These are business-data gaps, not demonstrated mapping defects.

The desired result is a characterization proof, plus only source-proven corrections. A complete Contract must make all five rules calculable. Removing one business fact may make only dependent rules `NOT_EVALUATED`. Existing zero-tolerance BigDecimal and supplier comparison semantics stay fixed.

## Authority and data flow

Confirmation and Promotion alone choose business truth. F02 consumes the durable Contract through the canonical ReviewRun Contract snapshot. `FrozenReviewInputLoader` verifies and decodes that snapshot; `FrozenF02ReviewStepExecutor` passes the decoded Contract to the existing `ContractReviewService`. No F02 path reads Candidate or Project-wide award materials to replace Contract facts.

The schema already stores Contract root facts, all line fields, the award reference, and the immutable ReviewRun snapshot CLOB/hash. No migration or public API change is designed. `noticeNo`, `projectName`, `winningDate`, and `sourceRef` remain frozen for fidelity but do not become prerequisites for the five rules.

## Local design

1. Extend existing characterization tests to prove a complete confirmed Contract is frozen, reopened, and evaluated through the real F02 ReviewRun step. Assert each rule code and status, every line item result, and unchanged IDs/amounts after reopen. Also assert a scale variant (`100.0` versus `100.00`) without changing tolerance.
2. Add a fact-absence matrix. Missing words affects only amount-words; no lines affects only arithmetic and line-sum; no award affects only award amount and supplier. Both award and primary-only compatibility cases remain covered. Invalid but present Chinese amount words remain `NOT_EVALUATED` unless a separate reproducible parser defect is demonstrated.
3. Test the known source-projection hypothesis separately. F02 supplier names use NFKC normalization. A source projection that compares visually equivalent full-width/half-width names should use the same normalization while retaining all existing file, authority, parse, uniqueness, and value checks. This is an evidence-display correction, never an F02 business-truth change. First prove it RED with a targeted test; change production code only if the failure is the expected normalization gap.
4. After focused and full automated tests pass, use the Phase 1B validated integration environment as read-only configuration. Reuse its migrated DM8 without migration. Use existing safe Contract/Project only if its state is unambiguous; otherwise create new synthetic test data. Verify Contract → freeze → reopen → all five durable F02 results and source behavior, recording sanitized input presence, status, reason, and evidence. Existing original test materials remain read-only.

## Completion boundary

No Candidate authority, support replacement, Confirmation, Promotion, ReviewRun lifecycle, rule engine, AI amount decision, DM8 schema, or public API redesign. No production changes for a green characterization test. A real failure is classified at the earliest boundary: Contract mapping, snapshot, frozen input, prerequisite, comparison, or evidence projection. Persisted F02 status and mutable evidence projection must be reported separately. No push or merge.
