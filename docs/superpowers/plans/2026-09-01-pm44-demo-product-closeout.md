# PM44 Demo Product Closeout Plan

## Scope

This plan implements the replacement PM44 prompt on the existing Project Intake, ProjectFile,
Contract Promotion, F02, OCR and Contract semantic-analysis slices. It protects every local change
that existed at `c077bf4dee386775e687c87ca1c1740f2d8c5ef7` before this plan was created.

## Invariants

- Dashboard and task filtering read the existing durable Contract Audit Task projection. Showcase
  rows never contribute to dashboard facts.
- Project remains the intake aggregate root. Supplemental ordinary files reuse the existing
  single-file Project upload endpoint with one retained operation identity per file.
- Customer pages do not expose parser/model/adapter/request/chunk details. Safe backend
  observability remains unchanged.
- F02 remains authoritative for amount and identity findings. The two new clause reviews are
  additive deterministic read use cases and do not modify the five F02 rules.
- AI evidence file identities must belong to the selected ProjectFiles. Excerpts are normalized
  and verified against the model-visible sanitized text before a locator is declared available.
- No migration, commit, push, merge, remote deployment, or system-environment modification.

## TDD slices

1. Replace Dashboard fake values with real Contract Audit Task data and real App navigation.
2. Add truthful task filtering without presenting one page as a full-database search.
3. Convert Project intake/detail copy to business language, remove customer technical details,
   and process supplemental ordinary files independently.
4. Add material-completeness projection and lock the seven-step UI order with F02 above AI while
   keeping both branches independent.
5. Extend typed AI evidence with implicated-material status and verified excerpt locators; simplify
   INFO presentation and focus REVIEW material text in the left viewer.
6. Add independent breach-clause and term/termination deterministic read use cases and panels.
7. Run full Backend, Python 3.11, Frontend test/build, diff and secret gates; run safe local real
   E2E only when the existing protected runtime is available.

## Reference material status

The exact wisdom-maintenance and Guizhou University DOCX filenames were not found in the worktree,
`D:\code`, or the user's Downloads directory during the bounded preflight. Fixture conversion and
real-document assertions remain `REFERENCE_FILE_NOT_FOUND`; implementation and synthetic regression
fixtures continue.
