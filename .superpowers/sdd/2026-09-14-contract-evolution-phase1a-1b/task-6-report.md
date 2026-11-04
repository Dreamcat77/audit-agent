# Task 6 — ExtractionKey v2 compatibility seam

## Scope delivered

- Added `ExtractionKeyFactoryV2`: SHA-256 over length-prefixed UTF-8 components in this exact order: discriminator `project-extraction-key-v2`, durable `sourceFileId`, durable `parseResultId`, extraction version, fact type, nullable group key (`-1` length sentinel), then source locator.
- Preserved `ExtractionKeyFactory#create` unchanged and locked its v1 golden value in test.
- `ProjectFileIngestionTransactionCoordinator` records each `insertIfAbsent` return keyed by the outcome's proposed source file ID. Any extraction accompanied by a stored immutable parse result uses the returned durable file/parse-result identities for v2; outcomes without such a result explicitly retain v1.
- No `PROJECT_EXTRACTION_RESULT` schema change and no role/access work.

## TDD evidence

- RED: `backend\\.\\mvnw.cmd "-Dtest=ExtractionKeyFactoryV2Test,ProjectFactReplayIntegrityTest" test` exited 1 before production implementation. `ExtractionKeyFactoryV2Test` failed to compile because `ExtractionKeyFactoryV2` did not exist (three `cannot find symbol` errors).
- GREEN focused regression: `backend\\.\\mvnw.cmd "-Dtest=ExtractionKeyFactoryV2Test,ProjectIdentityFactoryTest,ProjectFactReplayIntegrityTest,ProjectUploadConcurrencyTest,ProjectFileIngestionServiceTest,ContractCandidateAssemblerTest,ContractCandidatePromotionServiceTest,ContractCandidatePromotionConcurrencyTest" test` exited 0: 25 tests, 0 failures, 0 errors.
- Full verification: `backend\\.\\mvnw.cmd clean verify` exited 0 in the existing `local` test environment.

## Regression coverage

- v1 golden key remains `1c1c34710e4ae9e8466dc7d496154d012ac4134c0b353841f0ddc85378b7b0e2`.
- v2 golden and every distinguishing component, including null versus empty group key, are covered.
- Replay coexistence uses the real v2 factory.
- Child canonical-ID remap plus parse-result repository race asserts facts key from the durable child ID and durable winning parse-result ID.
- Legacy/non-parse-backed outcome asserts explicit v1 fallback.

## Review fix evidence — presence-based v2 binding

- Review finding: the former `CURRENT_PIPELINE` origin gate incorrectly sent a newly processed `LEGACY_IMPORTED` immutable parse-result outcome through v1.
- RED: after adding `legacyImportedImmutableParseResultUsesTheV2ExtractionKey` with a valid PDF parse provenance, `backend\\.\\mvnw.cmd "-Dtest=ProjectUploadConcurrencyTest" test` exited 1. The stored key was v1 (`a2668388930479730d085f45be30349e95ea5a03c2e02dffacdf48d5793d2fbf`) rather than the expected v2 key (`08e73db9d71c78600ff769a96d85adbbd1fcdd13e06881b9145610365fb9d6cc`).
- GREEN: removed only the origin predicate; v1/v2 factories and their algorithms are unchanged. `backend\\.\\mvnw.cmd "-Dtest=ExtractionKeyFactoryV2Test,ProjectIdentityFactoryTest,ProjectFactReplayIntegrityTest,ProjectUploadConcurrencyTest" test` exited 0: 14 tests, 0 failures, 0 errors.
- Self-review: v2 now selects solely on the presence of the durable parse-result winner; v1 remains solely for paths with no parse result. Historical persisted rows are not rewritten by this coordinator path.

## Recovery-fixture regression fix — f66e06d baseline

- Root cause: two recovery fixtures directly persisted `processPersisted()` intermediate facts with their provisional v1 keys, omitting the immutable parse-result winner and the coordinator's authoritative COMPLETE-time v2 normalization. That state cannot arise inside the real COMPLETE transaction, but remains a useful adversarial partial-row fixture only when it is internally parse/v2-consistent.
- RED: `backend\\.\\mvnw.cmd "-Dtest=ProjectFileRecoveryTest#deterministicExtractionIdentityConflictClosesDurableFailureWithoutDuplicateFacts,ProjectIntakeRecoveryDeduplicationTest#archiveRecoveryRekeysFactsToTheAlreadyPersistedCanonicalChild" test` exited 1 at f66e06d: the conflict test did not throw and the archive test persisted four facts instead of two.
- Fixture repair (test-only): the conflict fixture persists its parse winner and seeds its conflicting fact with `ExtractionKeyFactoryV2` over that winner; the recovery parse boundary returns the captured authoritative outcome so elapsed parse-observability values cannot mask the intended deterministic extraction conflict.
- Fixture repair (test-only): the archive fixture persists canonical children, parse winners, candidates, and v2 facts. Its replay proposal has a distinct parse-result ID but identical immutable parse metadata; repository insert returns the existing winner, and the assertion proves every durable fact key binds that winner's parse-result ID.
- GREEN recovery command above exited 0: 2 tests, 0 failures, 0 errors. Task 6 focused suite exited 0: 26 tests, 0 failures, 0 errors. `backend\\.\\mvnw.cmd clean verify` exited 0 in the existing `local` test environment.
- No production source, algorithms, schemas, or historical-v1 test paths changed in this fix.
