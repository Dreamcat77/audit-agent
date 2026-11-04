# Task 8 Report — Deterministic DocumentRoleResolver

## Scope

Implemented only the Task 8 deterministic role resolver and its immutable output types:

- `DocumentRoleCandidate`
- `DeterministicDocumentRoleResult`
- `DeterministicDocumentRoleResolver`
- synthetic fixture-matrix tests

No persistence, API, AI/model integration, ProjectFile category access, customer material, or auto-accept policy was added.

## TDD evidence

### RED

Command:

```powershell
cd backend
.\mvnw.cmd -Dtest=DeterministicDocumentRoleFixtureMatrixTest test
```

Observed result: `BUILD FAILURE` during test compilation with 17 expected missing-feature errors for
`DeterministicDocumentRoleResult`, `DocumentRoleCandidate`, and
`DeterministicDocumentRoleResolver`. This proved the new test exercised absent Task 8 behavior before
production implementation existed.

### GREEN

Command:

```powershell
cd backend
.\mvnw.cmd -Dtest=DeterministicDocumentRoleFixtureMatrixTest test
```

Observed result: `BUILD SUCCESS`; 8 tests run, 0 failures, 0 errors, 0 skipped.

### Task 7 + Task 8 focused regression

Command:

```powershell
cd backend
.\mvnw.cmd '-Dtest=DocumentRoleSignalExtractorTest,DeterministicDocumentRoleFixtureMatrixTest' test
```

Observed result: `BUILD SUCCESS`; 19 tests run, 0 failures, 0 errors, 0 skipped.

### Full backend verification

Command:

```powershell
cd backend
.\mvnw.cmd clean verify
```

Observed result: `BUILD SUCCESS`; 770 tests run, 0 failures, 0 errors, 0 skipped.

Environment: local Windows worktree, Java 17, Maven Wrapper, Spring `local` test profile. No DM8,
Redis, FastAPI, PaddleOCR, Qwen, 26/29, or customer environment connection was used.

## Behavior delivered

- Exact `BigDecimal` signal weights are summed once per role.
- Positive candidates are ordered by score descending, then `DocumentRole` enum order for stable ties.
- Candidate confidence is a six-decimal, half-up normalized display ratio; it is not an acceptance threshold.
- Exact competing positive maxima set `conflict=true`, retain ordered candidates/evidence, and produce
  `proposedRole=UNKNOWN`.
- No positive role signal produces an empty `UNKNOWN` result with zero display confidence.
- All deterministic outputs carry `decisionStatus=NEEDS_CONFIRMATION`, including single-candidate confidence 1.
- Candidate evidence and overall evidence are immutable copies and remain parse-bound through the Task 7 signal.
- Resolver public input is only `List<DocumentRoleSignal>`; filename and ProjectFile category cannot enter it.

## Synthetic fixture policy

The matrix uses filenames only as test labels and passes only synthetic canonical body text through the signal
extractor into the resolver:

| Test label | Expected proposal |
| --- | --- |
| `001.pdf` | `CONTRACT_BODY` |
| `扫描件.pdf` | `AWARD_NOTICE` |
| `采购资料.pdf` | `TENDER_DOCUMENT` |
| `响应材料.pdf` | `BID_RESPONSE` |
| `附件.pdf` | `UNKNOWN`, no conflict |

`附件` alone generates no `SUPPLEMENT` candidate. No customer original or derivative fixture was read or committed.

## Self-review

- No auto-accept threshold or business policy constant exists.
- `confidence` affects display only; decision status is invariant.
- Conflict uses `BigDecimal.compareTo`, so scale differences do not affect equality.
- Candidate and evidence ordering is deterministic and independent of input list order.
- No filename, path, category, parser/OCR invocation, filesystem, Redis, persistence, HTTP, or AI dependency exists.
- `git diff --check` passed after staging the Task 8 files.

## Verification classification

- UNIT/INTEGRATION VERIFIED: synthetic resolver matrix, Task 7 signal integration, and full local backend suite.
- REAL CUSTOMER MATERIAL NOT VERIFIED: intentionally not run for this slice.
