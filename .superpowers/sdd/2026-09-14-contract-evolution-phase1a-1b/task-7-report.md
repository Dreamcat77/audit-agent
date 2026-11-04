# Task 7 Report — Category-independent DocumentRoleSignalExtractor

Status: `DONE_WITH_CONCERNS`

## Scope delivered

- Added the exact `DocumentRole` vocabulary.
- Added immutable, persistable `DocumentRoleSignal` values with signal type, normalized value, stable rule code,
  positive `BigDecimal` weight, parse-result identity, Unicode code-point range, parse-bound source locator,
  SHA-256 of the exact canonical span, excerpt bounded to 160 code points, and extractor version.
- Added a pure `DocumentRoleSignalExtractor` whose sole public extraction input is `ProjectFileParseResult`.
- Added deterministic synthetic Chinese title, front-matter, key-phrase, heading, and structure rules for contract,
  award notice, tender document, bid response, and supplement roles.
- Added no resolver, acceptance threshold, assessment persistence/API, AI/OCR integration, customer rule, or
  filename/category input.

## RED evidence

Environment: Windows PowerShell, repository Maven wrapper, Java 17, worktree
`D:\code\audit_agent_1D\.worktrees\contract-evolution-phase1`.

Command:

```powershell
cd backend
.\mvnw.cmd -Dtest=DocumentRoleSignalExtractorTest test
```

The first test-only run also exposed an invalid fixture reference to nonexistent `ProjectFileParseMethod.TEXT`.
That fixture was corrected to the existing `PDF_TEXT` value before accepting the RED result.

Canonical RED result: exit code 1 at test compilation, with 28 errors exclusively caused by missing
`DocumentRole`, `DocumentRoleSignal`, and `DocumentRoleSignalExtractor`. No production code existed at that point.

## GREEN evidence

Focused command:

```powershell
cd backend
.\mvnw.cmd -Dtest=DocumentRoleSignalExtractorTest test
```

Observed result after implementation: exit code 0, `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`,
`BUILD SUCCESS`.

Covered behavior includes all five positive roles, exact role vocabulary, API-shape independence from category and
filename, non-PARSED/null rejection, `附件`-alone suppression for supplement, Unicode code-point locator/hash
integrity, bounded excerpts, deterministic ordering, and repeated/overlapping phrase de-duplication.

## Full-suite concern

Command attempted:

```powershell
cd backend
.\mvnw.cmd clean verify
```

Observed result: exit code 1, `Tests run: 762, Failures: 2, Errors: 0, Skipped: 0`.
The two failures are outside Task 7 and reproduce without Task 7 tests:

- `ProjectFileRecoveryTest.deterministicExtractionIdentityConflictClosesDurableFailureWithoutDuplicateFacts`
- `ProjectIntakeRecoveryDeduplicationTest.archiveRecoveryRekeysFactsToTheAlreadyPersistedCanonicalChild`

Focused reproduction command:

```powershell
.\mvnw.cmd '-Dtest=ProjectFileRecoveryTest,ProjectIntakeRecoveryDeduplicationTest' test
```

Observed result: exit code 1, `Tests run: 10, Failures: 2`. Evidence points to the preceding extraction-key-v2
recovery seam: replay uses a different parse-result-bound key and creates four facts where two were expected.
Task 7 imports or calls no extraction/recovery code. Per parent direction, this is retained as a Task 6 concern and
is not modified here.

## Self-review

- The extractor has one public method: `extract(ProjectFileParseResult)`.
- Production files contain no `ProjectFile`, `ProjectFileCategory`, category, filename, repository, service annotation,
  model threshold, or persistence dependency.
- Every emitted locator is tied to `parseResultId` and a zero-based, start-inclusive/end-exclusive Unicode code-point
  range over immutable canonical text; every hash is computed from that exact span.
- Each rule emits at most one signal and output has an explicit stable sort, preventing repeated phrases from inflating
  the same rule.
- `附件` is absent from supplement-positive rules.
- Real customer material and external environments were not used or verified (`NOT VERIFIED`, out of scope).
