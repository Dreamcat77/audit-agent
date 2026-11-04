# Contract release review remediation ledger

Date: 2026-09-26
Branch: `feature/contract-final-closeout-20260923`
Review baseline: `6fea0feafa207e65755d82afbd4e26fdade2e8df`
State: `CANDIDATE_CODE_GATES_GREEN / FRESH_EXACT_SHA_01_06_PENDING`

This ledger records fresh remediation evidence for the fixed review. It does not
replace the historical site handoff, old 01–06 runs, or customer acceptance.
The approved additive migrations are now applied on the verified integration
DM8. A new Project 01 thin browser chain proved A-mini v2 persistence, promotion,
ReviewRun completion, F02 navigation, copied opinions, refresh, a new browser
login, and backend restart recovery. That thin chain is integration evidence;
it is not the still-pending final exact-SHA 01–06 matrix.

## RR01–RR08 status

| ID | Current status | Fresh evidence | Remaining gate |
| --- | --- | --- | --- |
| RR01 false normal | `FIXED_FOCUSED_VERIFIED` | Persisted `ATTENTION`, review aliases, and unknown non-null states fail closed in the eight-category projection; copied overall opinion retains the issue. Frontend focused tests: 14 passed. | Browser rendering and copy flow on the final SHA. |
| RR02 recovery dead point | `FIXED_FOCUSED_VERIFIED` | An inner A-mini `SUCCEEDED` attempt is recovered by exact actor, operation key, input fingerprint, file/SHA/parse/role/identity, and provenance. Recovery completes the missing outer stage without a second provider call; wrong identity fails closed. | DM8 crash-boundary injection and restart recovery after the approved schema work is integrated. |
| RR03 A-mini evidence protocol | `IMPLEMENTED_FOCUSED_VERIFIED` | The v2 server-owned evidence-ID endpoint retains v1 compatibility, separates CORE/LINE_ITEMS/AWARD, validates server-owned evidence and exact ranges, preserves accepted rows in partial batches, and returns durable-safe rejection and per-call audit records. FastAPI focused route tests: 18 passed. Java adapter/coordinator focused tests are green. | Controlled integration migration, DM8 round-trip, real provider quality, and final browser flow. |
| RR04 numeric grounding | `FIXED_FOCUSED_VERIFIED` | Tests reject omitted negative signs, percent/per-mille values, quantity with currency units, and budget values presented as contract amount. Currency keeps two meaningful decimals; quantity/unit price allow four. Java candidate assembly and frozen snapshot preserve `100.0000` and `2.3456`. | DM8 four-decimal round trip requires the approved precision ALTERs. |
| RR05 frozen source navigation | `FIXED_FOCUSED_VERIFIED` | Exact canonical locators are verified against frozen text; repeated excerpts keep an exact locator. A missing comparison side no longer deletes the reliable side. Same-span equivalent proof is deduplicated, distinct spans remain ambiguous, and post-run publication cannot change an older run. | Persisted GET, restart, and browser L01–L12 on the final SHA. |
| RR06 category coverage | `FIXED_FOCUSED_VERIFIED` | Template comparison requires explicit bounded template material and checks heading order. Title-only, one-line, or ambiguous attachments do not prove completeness. A parsed copy count remains insufficient without an authoritative requirement. | Final browser category/copy-opinion regression. External qualification remains an external dependency. |
| RR07 semantic diagnosis | `IMPLEMENTED_FOCUSED_VERIFIED` | FastAPI records each real semantic transport/repair call with ordinal, phase, outcome, safe reason, duration, finish reason, and provider usage when supplied. Java validates and binds those rows to the exact ReviewStep attempt/fingerprint in the same COMPLETE or proven-FAILURE transaction. Compatible old responses remain readable. | Controlled integration persistence/restart verification and new 03/04 runs against the exact private provider; old failed runs remain unchanged. |
| RR08 release identity | `IN_PROGRESS` | This ledger separates review baseline, fresh offline evidence, historical runs, and pending environment gates. | Create the final verification index only after protocol/schema integration, all gates, final SHA, and fresh 01–06 E2E. |

## Focused verification completed

Backend correctness and semantic-diagnosis bundle:

```text
.\mvnw.cmd '-Dtest=ProjectFileProgressionRecoveryServiceTest,InMemoryContractFactExtractionRepositoryTest,ContractFactCandidateValidatorTest,FindingSourceProjectionFrozenTest,FrozenEvidenceGrounderTest,FrozenF02ResultMapperTest,ReviewStepOutputReadServiceTest,ContractTermClauseAnalyzerTest,FrozenTermReviewStepExecutorTest,ContractCandidateAssemblerTest,ContractSnapshotCanonicalizerTest,ContractSnapshotDecoderTest,FastApiAiReviewAdapterTest,FrozenSemanticReviewStepExecutorTest,ContractReviewWorkerTest' test
125 tests, 0 failures, 0 errors, 0 skipped
```

Frontend projection/opinion/panel bundle:

```text
node --test --experimental-strip-types --test-isolation=none src/features/contract/eightCategoryReviewProjection.test.ts src/features/contract/contractReviewOpinion.test.ts src/features/contract/eightCategoryReviewPanel.test.ts
14 tests passed, 0 failed
```

Current FastAPI A-mini v1/v2 characterization with the previously validated local
Python environment:

```text
<validated-python-3.11> -m pytest tests/test_contract_fact_candidate_route.py -q
18 passed, 1 upstream deprecation warning
```

The machine-default Python 3.14 installation was also tried first and failed
during collection because it lacks `python-multipart`; that run is an invalid
test environment and is not counted as a product failure or a passing gate.

Fresh candidate-gate results on 2026-09-26:

```text
Backend .\mvnw.cmd clean verify: 1914 tests, 0 failures, 0 errors, 2 skipped
Frontend default runner: 654 passed, 0 failed/skipped
Frontend shared runner: 654 passed, 0 failed/skipped
Frontend production build: PASS, 2409 modules transformed
FastAPI full suite: 363 passed, 1 upstream deprecation warning
DM8/Redis/provider preflight: PASS; review-worker claimable/expired/running/validLease = 0/0/0/0
```

The final exact-SHA 01–06 browser matrix and normal-flow second-user isolation
remain open gates. The repository has no implemented application user
administration API; no direct user-table write will be used as a substitute.

## Minimal concentrated approval boundary

### Internal protocol implementation

Retain `/ai/v1/agent/contract-fact-candidates` v1 and add a versioned v2
Java-to-FastAPI protocol. The server owns bounded evidence IDs and exact source
slices; the model returns an evidence ID and typed value. The response separates
accepted candidates, rejected-row summaries, batch completeness, scope coverage,
and safe per-call metrics. Candidate/Confirmation/Promotion authority and public
business APIs do not change.

### One new controlled Contract migration

The proposed migration contains only:

1. `PROJECT_EXTRACTION_RESULT.VALUE_AMOUNT DECIMAL(18,2) -> DECIMAL(20,4)`;
2. `CONTRACT_CANDIDATE_LINE_ITEM.UNIT_PRICE DECIMAL(18,2) -> DECIMAL(20,4)`;
3. new `PROJECT_FACT_EXTRACTION_REJECTION`, owned by an existing A-mini attempt;
4. new `CONTRACT_MODEL_CALL_AUDIT`, owned by exactly one A-mini attempt or one
   ReviewStep attempt.

It does not edit or rerun A/B/C migrations, rewrite old runs, change owners,
delete data, or add a second business fact authority. Migration execution is
limited to the existing integration DM8 and must wait for a fresh catalog and
worker-risk preflight.

### Security input

The user authorized creation of one non-owner test identity through the existing
normal application administration flow with the least existing Contract-capable
role. Real Project, Contract, exact-run, ACK, report, and original-file isolation
is still pending execution. Anonymous denial remains supplementary.

## Environment and historical boundaries

- Existing A/B/C integration migrations and preserved 01–06 records are
  historical evidence only; they are not rerun or altered.
- The private provider identity and exact configured model remain fixed. A
  different provider/model cannot substitute for the required verification.
- The original 03/04 semantic failures remain durable history. New diagnostics
  will use new runs and will not rewrite those outcomes.
- Private-network unavailability blocks only real provider and integration
  verification. It does not invalidate the focused offline evidence above.
