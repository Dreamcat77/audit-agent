# Track C: sanitized diagnostics and local quality evaluation

This directory contains synthetic fixtures only. It is not a customer-material export,
provider benchmark, production threshold approval, or real DM8 verification.

## Debug artifact contract

The focused API is rooted at
`/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/debug-bundles`.
POST accepts `{}` (METADATA_ONLY), or `contentLevel`, optional `stepId`, and nested
`attemptId`. Session authentication, AUDITOR/SYSTEM_ADMIN, CSRF and a stable
`Idempotency-Key` are required. GET lists/reopens metadata; `/{bundleId}/artifact`
downloads canonical JSON with `Cache-Control: no-store`. All successful accesses
are durably audited; failed auditing denies access. IDs must belong to the URL scope.
There is no new customer-facing UI or unauthenticated entry.

`CUSTOMER_POLICY_APPROVED_REDACTED` is modeled but always forbidden. Internal policy
`internal-metadata-v1` permits exact SHA-256 for authorized internal correlation,
not approved customer export. `DebugHashPolicy` can retain, replace with a salted
64-hex alias, or remove input hashes without changing domain IDs. Actor identity is
one-way hashed in the artifact. Arbitrary strings, filenames, document/canonical/OCR
text, prompts, raw responses, PII, credentials, paths and stack traces are absent.
Only typed IDs, enumerated statuses, integer counts/versions and approved hashes are
projected. Bundle `applicationBuild` identifies this debug schema implementation;
it is not an asserted deployment Git SHA. Production bundle verificationLabels is
empty: a request to export metadata proves no quality capability.

START commits the frozen sanitized manifest, deterministic target key, expected byte
hash/size, durable idempotency claim and operation audit. FileStorage inspection and
create-only publication occur outside any caller transaction. COMPLETE or UNKNOWN
uses a new short transaction and optimistic CAS. UNKNOWN is never treated as missing
or failed. An explicit POST with the **original key** reconciles the same frozen
artifact after a timeout; GET never publishes, regenerates or reconciles. AVAILABLE
cannot be downgraded. Embedded `artifact.sha256` hashes the manifest **without the
artifact member**; API/DM8 `artifactSha256` hashes all UTF-8 artifact bytes. This avoids
a self-referential checksum. Download checks the complete artifact hash and size.

Only `db/dm8/CONTRACT__sanitized_debug_bundle.sql` is new. It depends on
`CONTRACT__review_run_results.sql` and transitively the existing run schema. The
`quality.schema_preflight.classify` function consumes a supplied read-only catalog
snapshot: proven absence -> NOT_APPLIED, exact normalized match -> ALREADY_MATCHING,
partial/mismatching table -> PARTIAL-SCHEMA_DRIFT, unreadable/unproven dependency ->
UNKNOWN. It never connects to or alters a database. A deployment operator must obtain
the real DM8 catalog and verify it; passing fixture tests is not platform validation.

## Versioned fixture and result protocol

`fixtures/synthetic-v1.json` is fixture schema 1 / ground-truth schema 1. Each case
contains a stable ID, capability, bounded synthetic input, exact UTF-8 input SHA-256,
and typed truth (text, bounded label/claim codes, decimal amount strings). Changes to
inputs, labels or annotation decisions require a new fixture/ground-truth version;
do not rewrite v1 to hide a measured regression. Provenance records reviewer, reason
and creation date. PUBLIC sources additionally require license/source metadata;
CUSTOMER_APPROVED_SANITIZED requires approval identity/redaction version. Validation
is not itself permission to commit customer data. This repository includes neither.

The evaluation module accepts one observation per fixture and emits canonical sorted
JSON with code SHA, fixture/ground-truth identity and hashes, metric implementation
version, policy/schema/prompt versions, threshold profile, provider/model/revision,
per-capability raw aggregate counts, metrics and pass/fail. Comparison identity
excludes provider/model so runs on identical inputs and policies can be compared.
Runtime observations and timing will differ; canonical serialization of identical
inputs remains stable. Free-text error messages cannot become labels or claim codes.

Only `LOCAL_SYNTHETIC` execution is attested by this runner. `OFFLINE_OBSERVATIONS`
also measures approved-source manifests, but emits no verification label. It cannot issue
REAL_PROVIDER_VERIFIED or REAL_CUSTOMER_MATERIAL_VERIFIED. Its label
LOCAL_SYNTHETIC_VERIFIED means the **local fixture/adapter/metric evaluation ran**,
not that every capability passed. Acceptance is always MEASURED_NOT_ACCEPTED.
`synthetic-regression-v1` requires no schema/binding/support failure, failed/timeout
case, incorrect classification, text edit, amount mismatch or claim FP/FN. It is a
test threshold, not a customer or production acceptance threshold.

## Capability exercise and metric definitions

| Capability | Actual local seam and limits |
|---|---|
| PARSE | Existing Java DOCX parser, using an in-memory synthetic POI document |
| OCR | Synthetic OcrPort DTO output; no image recognizer/Paddle call |
| DOCUMENT_ROLE | Existing Java signal extractor and deterministic role resolver |
| PROJECT_IDENTITY | Existing Java identity resolver with conflicting synthetic project number |
| CONTRACT_CANDIDATE | Existing Java candidate assembler with immutable typed extraction facts |
| F02 | Existing amount-match rule using BigDecimal/Money; no AI monetary decisions |
| SEMANTIC | Existing Python evidence segmentation/citation binding; no model inference |
| BREACH | Existing Java breach analyzer; frequency and BigDecimal amount |
| TERM | Existing Java term analyzer; literal annotated normalized date |
| EVIDENCE_GROUNDING | Existing Java frozen Unicode evidence grounding and excerpt hash |

Text metrics are exact match, NFKC/whitespace-normalized match, Levenshtein CER
(edits/reference Unicode characters), and WER (edits/whitespace-delimited reference
words). Each text case represents one evaluation page: coverage is nonempty output
pages / pages, and empty-page rate is the complement. These tiny samples are not
statistically meaningful OCR quality estimates. Amount match compares finite Decimal
values numerically, so 100.0 and 100.00 match. Decimal use in Python is evaluation only.

Classification records confusion counts, accuracy, per-label precision/recall/F1 and
macro-F1 over the union of observed/reference labels. Conflict false-negative rate is
missed CONFLICT / reference CONFLICT; false ownership is predicted SAME_PROJECT for
a non-owned case / reference non-owned cases. Claim precision/recall use set TP/FP/FN.
Locator hit, hash/parse binding, schema validity, unsupported and retry rates use
explicit observation flags and aggregate counts. For adapters without an evidence
locator, binding/locator flags refer only to synthetic fixture binding, not a provider
grounding claim. Retry rate counts cases with at least one retry; total retries are
also retained. P50/P95 use nearest-rank measured adapter duration. Failure/timeout
counts remain visible. Undefined zero-denominator rates are null, not success.

## Reproduction

From `backend` (Java 17):

```powershell
.\mvnw.cmd -q '-Dtest=JavaQualityHarnessTest' test
```

This writes `backend/target/quality-java-observations.json` using eight actual Java
capability seams. Then from `services/audit-ai`:

```powershell
$env:PYTHONPATH = 'src'
$qualityCodeSha = git rev-parse HEAD
python -m audit_ai.quality.synthetic --fixtures ../../quality/fixtures/synthetic-v1.json --java-observations ../../backend/target/quality-java-observations.json --code-sha $qualityCodeSha
python -m pytest -q tests/test_quality_harness.py tests/test_debug_schema_preflight.py tests/test_evidence.py tests/test_chunker.py tests/test_config.py tests/test_safe_logging.py
```

Use a clean committed source tree when recording evidence against its code SHA. The
runner combines the Java observations with Python OCR DTO/semantic segmentation and
prints the result manifest. Generic offline observation evaluation is available as
`python -m audit_ai.quality --fixtures ... --observations ... --context ...`; supplied
observations are not proof of external provider execution.

The historical [2026-09-16 measurement](results/2026-09-16-local-synthetic-v1.json)
is intentionally retained: 9/10 capability checks passed and TERM recorded one false
negative. The corrected [2026-09-17 measurement](results/2026-09-17-local-synthetic-v1.json)
also remains immutable audit history: measured code SHA
`b4f2ddddfbb93c05a5433e686b26d4ac30dbce4b`, TERM recall `1.0`, and 10/10 synthetic
capability checks passed. Both manifests keep `MEASURED_NOT_ACCEPTED`; the corrected
result's `LOCAL_SYNTHETIC_VERIFIED` label proves only local synthetic execution and does
not approve a provider, customer material, or a production threshold.

Installed Python is 3.14, while this service requires >=3.11,<3.12. The independent
quality/pure-Python tests execute in the available interpreter, but full FastAPI pytest
collection is blocked by missing python-multipart. No dependency was upgraded or
installed and no production compatibility claim is made. Supported-Python full
FastAPI verification, real provider/OCR, customer material and real DM8 remain
NOT VERIFIED. Java profile-local HTTP integration is separate from these claims.

## Required integration adaptations

1. Add these focused routes, strict request/metadata response and binary JSON artifact
   schemas to shared `docs/openapi.yaml`; no shared file was edited by Track C.
2. Register the new CONTRACT migration after review-run-results in the controlled
   manual migration order and apply the supplied-catalog preflight on real DM8.
3. If a workspace entry is desired, an authorized UI can call the focused API and
   preserve the original operation key during uncertainty. No frontend edit is
   required for this API-only delivery.
4. Run the relevant suite on the approved Python 3.11 environment and obtain separately
   authorized provider/customer quality evidence and customer thresholds before any
   production approval. The local result does not authorize a provider switch.
