# Contract 01 E2E integration checkpoint

Date: 2026-09-26

State: `INTEGRATION_CHECKPOINT / NOT_FINAL_CANDIDATE`

## Source identity

- Branch: `feature/contract-final-closeout-20260923`
- Worktree: `D:\code\audit_agent_1D\.worktrees\contract-final-closeout-20260923`
- Code parent: `af9dc5da4a55d8ba72057cdce506bff083ba4ca7`
- Build ID: `20260926-001338-af9dc5da4a55-e3b0c44298fc`
- Backend JAR SHA256: `FCAB7AD89D57D6E52F5D784BA937C7519A23879B58FC0083A19285A11FFB359F`
- Frontend bundle SHA256: `46d0694be920d27d416c3dd049494f017ff6597bbc2f2529e32eaf0e2e69db89`
- FastAPI source SHA256: `97f19d51ea34b05159c585df670b06ae682c974f7aa538e438278f7341e88533`

The only pre-checkpoint untracked path was the local Superpowers progress file.
It is not product source or evidence and is excluded from this checkpoint.
Runtime files, credentials, sessions, browser profiles, logs, storage, AI temp
files, and original materials are also excluded.

## Verification state inherited by this checkpoint

The commands and observed counts are recorded in
`docs/evidence/contract-release-review-remediation.md`:

- Backend `clean verify`: 1,914 tests, 0 failures, 0 errors, 2 skipped.
- Frontend default runner: 654 passed.
- Frontend shared runner: 654 passed.
- Frontend production build: PASS, 2,409 modules transformed.
- FastAPI full suite: 363 passed; one upstream deprecation warning.
- The approved additive migration and authority constraint were verified on the
  integration DM8. No migration is run as part of this checkpoint.

Fresh pre-checkpoint checks:

- `git diff --check`: PASS.
- Tracked credential scan: PASS after inspection of one intentional negative
  test fixture containing synthetic secret-pattern text. No real credential was
  found.
- Candidate application listeners were stopped at checkpoint time. They must be
  started from the build identity above before the new 01 E2E.

## E2E boundary

This checkpoint precedes one newly authorized 01 original-material E2E. It does
not claim that the new Project, A-mini extraction, Contract, ReviewRun, eight
category view, navigation, copy, or exact-history reopen has passed. Sets 02–06
are outside the authorized execution window.
