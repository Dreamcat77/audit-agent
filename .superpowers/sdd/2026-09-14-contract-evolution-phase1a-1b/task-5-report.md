# Task 5 report — legacy parse-result materialization

## Outcome

- Added `LegacyProjectFileParseResultMaterializer` as a public Spring `REQUIRES_NEW` transaction boundary.
- It reloads `ProjectFile` by server-owned `projectId/fileId`, then uses only its durable status, SHA-256, parsed text, timestamps, filename/source type, and parse provenance.
- A valid `AVAILABLE + PARSED` legacy projection creates version 1 `LEGACY_IMPORTED`, with no superseded result and with persisted ProjectFile timestamps/provenance.
- A repeated call returns the same durable parse-result identity. Two concurrent calls that both initially observe no row generate distinct candidate IDs but converge on the one repository winner.
- A pre-existing current-pipeline or legacy result is returned only after checking project/file ownership, version/status, file SHA, canonical text/hash, and all projected provenance values.
- Missing files return `NOT_FOUND`. Unavailable/non-PARSED files, null/blank text, missing provenance, parser-method/source mismatch, and stale immutable results fail closed with fixed safe `CONFLICT` messages.

## TDD RED

Environment: Windows PowerShell, repository Maven wrapper, Java 17, local in-memory repository seams.

Command:

```powershell
cd backend
.\mvnw.cmd -Dtest=LegacyProjectFileParseResultMaterializerTest test
```

Observed before production code:

```text
COMPILATION ERROR
cannot find symbol: class LegacyProjectFileParseResultMaterializer
6 errors
BUILD FAILURE
Total time: 11.036 s
```

This was a feature-missing RED: the complete test file compiled up to references to the intentionally absent materializer; there were no unrelated test errors.

## GREEN and final focused verification

Required focused class command after implementation and concurrency-fixture self-review:

```powershell
cd backend
.\mvnw.cmd -Dtest=LegacyProjectFileParseResultMaterializerTest test
```

Observed:

```text
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 17.055 s
```

Fresh pre-commit rerun after staging the final code/test diff:

```text
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 4.305 s
```

Dedicated adversarial concurrency command:

```powershell
cd backend
.\mvnw.cmd '-Dtest=LegacyProjectFileParseResultMaterializerTest#concurrentDistinctGeneratedIdsConvergeOnExactlyOneDurableWinner' test
```

Observed:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 4.037 s
```

The concurrency seam makes both threads capture an empty initial repository result before releasing either caller. The test therefore proves two distinct generated candidate IDs and one returned/stored winner without timing sleeps, Redis, `synchronized`, or a Java monitor.

## Adversarial coverage

- Missing project/file ownership lookup is `NOT_FOUND`; no parse row is inserted.
- `UPLOAD_PENDING`, parse `FAILED`, null text, empty text, whitespace-only text, and absent provenance are rejected before insert.
- Persisted method/source mismatches are rejected for PDF/DOCX/image combinations; accepted mappings remain PDF_TEXT→pdf, DOCX_TEXT→docx, OCR→pdf/png/jpg/jpeg.
- Existing rows with wrong project, wrong file, wrong original SHA, non-PARSED status, stale canonical text/hash, or stale engine provenance are rejected rather than reused.
- The matching existing `CURRENT_PIPELINE` row is reused without attempting a legacy insert.
- The created result carries literal expected canonical SHA-256, parse version/origin, timestamps, provenance, and null supersedes identity.

## Changed files

- `backend/src/main/java/com/fuquan/audit/contract/application/project/LegacyProjectFileParseResultMaterializer.java`
- `backend/src/test/java/com/fuquan/audit/contract/application/project/LegacyProjectFileParseResultMaterializerTest.java`
- `.superpowers/sdd/2026-09-14-contract-evolution-phase1a-1b/task-5-report.md`

## Self-review and verification boundary

- `git diff --check` reported no tracked whitespace errors before staging; staged diff is reviewed before commit.
- Source scan found no Redis, FileStorage, parser/OCR invocation, `synchronized`, or Java monitor. `OCR` appears only as a persisted provenance enum/mapping.
- Full `clean verify` is deliberately deferred by the task brief; no full-suite claim is made.
- Real DM8 transaction/duplicate-key behavior was not exercised (`NOT VERIFIED`). Concurrency is verified against the existing repository contract and in-memory `ConcurrentHashMap` adapter; representative DM8 verification remains an external environment gate.
- Existing project-wide Maven deprecation/unchecked warnings remain unchanged.

## Review test-hardening round

The review identified two coverage gaps, not a production defect:

- Existing-result provenance conflict coverage now mutates `parseMethod`, `pageCount`, `durationMs`, and `confidence` independently, with all other immutable identity/provenance fields matching. Each case requires the same fixed safe conflict used for an engine mismatch.
- Unavailable/non-PARSED, null/blank text, missing provenance, and method/source mismatch cases now assert their exact fixed safe message, absence of the persisted filename, and absence of any non-blank persisted text.

No production file changed in this round. The new assertions passed against the committed materializer, so the production-only gate (“change production only after a new RED proves a defect”) did not open.

Exact focused command:

```powershell
cd backend
.\mvnw.cmd -Dtest=LegacyProjectFileParseResultMaterializerTest test
```

Observed:

```text
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 13.061 s
```

Environment and verification boundary remain unchanged: Windows PowerShell, Java 17, Maven wrapper, local repository seams; real DM8 is `NOT VERIFIED`, and full `clean verify` remains deferred by task direction.
