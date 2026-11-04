# PM44 Regression Root-Cause Fix Plan

**Goal:** Restore the existing F02 risk acknowledgement and history surfaces, fail closed when a
quantified breach clause silently loses its semantic category, and harden the manual V016 operator
gate without editing or reapplying V016.

**Architecture:** Keep `FindingAcknowledgement` and `review-progress` as the only acknowledgement
truth, keep one AI semantic result as the owner of `BREACH_LIABILITY`, and keep DM8 migrations as an
explicit operator workflow. The UI may expose existing durable facts in the Project-backed result
panel, but it must not introduce local acknowledgement/history state. V016 remains byte-for-byte
unchanged.

**Tech stack:** React/TypeScript, Node test runner, Python/FastAPI/Pydantic, Java 17/Spring Boot,
DM8-oriented SQL documentation.

## Task 1: Lock the Project-backed acknowledgement regression

**Files:**
- Modify: `frontend/src/features/contract/reviewFindingPresentation.test.ts`
- Modify: `frontend/src/features/contract/components/F02ReviewResultPanel.tsx`
- Modify: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`

1. Add source/presentation assertions proving the Project-backed result panel receives the existing
   loading/error/callback props and renders the existing `createAcknowledgementControl` action.
2. Run the focused test and observe failure because only the hidden legacy timeline owns the action.
3. Add the three props to `F02ReviewResultPanel`, derive the action from the same durable progress
   item/replay state, and call the existing callback with the real check-result ID.
4. Wire the existing hook values from the workspace. Do not add an endpoint, store, or optimistic
   acknowledgement.
5. Re-run the focused acknowledgement, hook, and HTTP transport tests.

## Task 2: Make history availability truthful

**Files:**
- Modify: `frontend/src/features/contract/reviewHistoryViewModel.test.ts`
- Modify: `frontend/src/features/contract/reviewHistoryViewModel.ts`
- Modify: `frontend/src/features/contract/components/ContractReviewWorkspace.tsx`

1. Add failing tests for `READY`, `LOADING`, and `UNAVAILABLE` history access states.
2. Implement a small presentation function; `UNAVAILABLE` must say the history/progress cannot be
   read and must never say there was no history.
3. Keep the existing drawer when result + progress are durable; show explicit loading/error state
   when a result exists but progress is not available.
4. Re-run history, progress reconcile, hook refresh, and data-source tests proving POST then GET and
   actor/time survival.

## Task 3: Fail closed on silently omitted quantified breach semantics

**Files:**
- Modify: `services/audit-ai/tests/test_model_routes.py`
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`

1. Add a failing provider test: selected source contains a breach rate/cap clause, first provider
   result omits `BREACH_LIABILITY`, second result supplies it. Require exactly one safe retry with a
   stable reason code.
2. Add the adversarial second-omission case and require `MODEL_INVALID_RESULT`, not a successful
   empty breach section.
3. Detect only source clauses that combine breach responsibility terminology with a percentage or
   per-mille contract-amount basis. Do not weaken monetary guards or inspect/log full source text.
4. Strengthen the system/retry prompt for the same narrow completeness contract.
5. Run `tests/test_model_routes.py`; retain all monetary comparison/calculation rejection cases.

## Task 4: Record the material-insufficient boundary without guessing ZIP failure

**Files:**
- Modify: `backend/src/test/java/com/fuquan/audit/contract/api/project/ContractCandidatePromotionApiIntegrationTest.java`

1. Extend the existing complete promotion fixture to assert every F02 finding is `PASS` and no
   `NOT_EVALUATED` result appears when Contract, line-item, amount words, award amount, and supplier
   identity facts are present.
2. Run the focused integration test. This is regression evidence, not authority to fabricate missing
   production facts.
3. Keep the exact material-insufficient classification as `NOT_REPRODUCED` unless a real PM44
   runtime shows which durable structured fact is absent.

## Task 5: Harden the V016 manual operator gate

**Files:**
- Create: `db/dm8/probes/V016__f04_budget_approval_program_read_only.sql`
- Modify: `db/dm8/README.md`
- Modify: `README.md`
- Modify: `backend/src/test/java/com/fuquan/audit/integration/dm8/F04Dm8SqlStructureTest.java`

1. Add a failing structure assertion for a committed read-only V016 probe and explicit
   `DO_NOT_REAPPLY` guidance.
2. Add only read-only `USER_TABLES`, `USER_TAB_COLUMNS`, `USER_CONSTRAINTS`, and rule-count queries.
3. Document the three outcomes: complete schema + five rules = `DO_NOT_REAPPLY`; neither table =
   `NOT_APPLIED`; any partial state = `SCHEMA_DRIFT` and stop.
4. State explicitly that the application has no auto-migration/history mechanism and that an
   approved operator applies exactly one named pending migration, never `V*.sql` wholesale.
5. Do not edit V016, swallow SQL errors, or claim real DM8 verification.

## Task 6: Verification gates

1. Frontend focused tests, all `frontend/src/**/*.test.ts`, and `npm.cmd run build`.
2. Python focused module and full pytest using the repository test environment.
3. Backend focused acknowledgement/F02/clause tests, then `backend\\mvnw.cmd clean verify`.
4. `git diff --check`, scope diff, tracked artifact/secret scan, and working-tree report.
5. Attempt authenticated browser/real DM8 evidence only if services and protected configuration are
   actually available. Otherwise report those gates as `NOT VERIFIED` and keep `FIX_GATE=BLOCKED`.
