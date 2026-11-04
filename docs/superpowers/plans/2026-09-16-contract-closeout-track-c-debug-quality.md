# Contract Closeout Track C Debug and Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Deliver authorized metadata-only debug artifacts and a reproducible synthetic quality harness without exporting confidential content.

**Architecture:** A focused Java controller and application boundary read frozen ReviewRun metadata, reuse durable idempotency/audit and existing FileStoragePort, and persist only sanitized bundle metadata in DM8. Short independent transactions surround bounded artifact work; uncertain publication/commit is reconciled using the original operation identity. A repository-owned Python evaluation module accepts versioned fixtures and observations from Java/Python capability tests and emits one canonical, comparable result format.

**Tech Stack:** Java 17, Spring Boot, Jackson, MyBatis/DM8, JUnit 5, Python standard library and existing pytest/FastAPI test architecture; no new dependency.

**Spec:** `docs/superpowers/specs/2026-09-16-contract-audit-parallel-closeout-design.md` sections 8, 13–16, 20–24; `docs/superpowers/specs/2026-09-14-contract-audit-evolution-master-design.md` sections 25–26. Approved baseline: `2ed1eb9906d2c67d3c673906d5f1b27b434af3a9`.

## Global Constraints

- Worktree `.worktrees/contract-closeout-debug-quality`; branch `feature/contract-closeout-debug-quality-20260916`; initial branch/HEAD and clean status verified.
- DM8 is System of Record. UNKNOWN != FAILED. Existing FileStoragePort is the only artifact bytes boundary. No Redis business truth.
- Exclusive SQL: `db/dm8/CONTRACT__sanitized_debug_bundle.sql`, metadata/index/FK only, depends on `review-run-results`; never modify V001–V022 or previous CONTRACT files.
- Do not edit shared ReviewRun controller/service/domain/mapper/guard, security configuration, OpenAPI, frontend workspace or migration README. Document mechanical integration adaptations here.
- Default `METADATA_ONLY`; `CUSTOMER_POLICY_APPROVED_REDACTED` is modeled but always rejected while policy approval is absent. No customer originals, full/canonical/OCR/contract text, prompts, raw response, secrets, cookies, tokens, passwords, paths, stack dumps or PII in artifacts.
- Generate/read/download require AUDITOR or SYSTEM_ADMIN and exact project/contract/run ownership; actor comes from AuthContext. POST requires Session/CSRF/Idempotency-Key. GET audits access but never generates or reconciles bytes.
- Internal hash policy `internal-metadata-v1` permits exact input hashes for internal authorized diagnostics only; no customer-export approval. Hash policy interface can retain/alias/remove without mutating domain IDs.
- Only synthetic fixtures are committed. Local test thresholds are explicitly not production approval. Labels are independent and cannot be supplied by arbitrary callers to promote verification.
- Baseline: `backend/.\mvnw.cmd -q test` exit 0, 1119 tests / 0 failures / 0 errors / 0 skipped. `services/audit-ai/python -m pytest -q` failed collection (9 errors) on installed Python 3.14 due missing python-multipart; project requires 3.11. No system installation or production compatibility workaround.

## File and interface map

Java paths below are rooted at `backend/src/main/java/com/fuquan/audit/`; tests at the corresponding `backend/src/test/java/com/fuquan/audit/` path.

| Exact files | Interface and ownership |
|---|---|
| `contract/application/debug/DebugScope.java`, `DebugContentLevel.java` | `DebugScope(UUID projectId, UUID contractId, UUID runId, UUID stepId, UUID attemptId)`; run required, optional step and nested attempt must form a complete chain. |
| `contract/application/debug/DebugHashPolicy.java`, `DebugManifestBuilder.java` | `exportHash(String): Optional<String>`; `build(UUID, DebugScope, Instant, String actorId, ContractReviewRunCreateResult): String` emits sorted UTF-8 canonical JSON, strict metadata keys/types, bounded output. No domain object serialization. |
| `contract/application/debug/SanitizedDebugBundle.java`, `SanitizedDebugBundleRepository.java` | Immutable record plus `insert`, `findById`, `findByRunId`, `transition(expected,next)` CAS; frozen manifest, storage key, byte hash/size retained before publication. |
| `contract/infra/memory/debug/InMemorySanitizedDebugBundleRepository.java` | Local/test-only concurrent-map CAS; no production lock semantics. |
| `integration/dm8/SanitizedDebugBundleMapper.java`, `SanitizedDebugBundlePersistenceRow.java`, `Dm8SanitizedDebugBundleRepository.java`, `backend/src/main/resources/mappers/dm8/SanitizedDebugBundleMapper.xml` | DM8 insert/reopen/list and status/version CAS, explicit CLOB and UTC timestamp mapping. |
| `contract/application/debug/DebugBundleTransactions.java` | `start(scope,level,key,actor,metadata): SanitizedDebugBundle`; `complete(bundleId,actor,metadata)`; `unknown(bundleId,actor,metadata)`; `auditRead(bundle,actor,metadata,action)`. REQUIRES_NEW, audit and idempotency same transaction as metadata. |
| `contract/application/debug/SanitizedDebugBundleService.java` | `generate(scope,level,key,metadata)`, `get(scope,bundleId,metadata)`, `list(scope,metadata)`, `download(scope,bundleId,metadata)`; FileStorage work outside transactions. Same-key generate reconciles uncertain identity. |
| `contract/api/project/SanitizedDebugBundleController.java` | POST/GET `/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/debug-bundles`, GET `/{bundleId}`, GET `/{bundleId}/artifact`. Strict body accepts contentLevel, optional stepId/attemptId only. |
| `db/dm8/CONTRACT__sanitized_debug_bundle.sql` | SANITIZED_DEBUG_BUNDLE only; PK, run/project FK, actor FK, storage-key unique (operation uniqueness belongs to existing durable idempotency), state/hash/bounds checks and list index. |
| `services/audit-ai/src/audit_ai/quality/manifest.py`, `metrics.py`, `harness.py`, `__init__.py`, `__main__.py` | `validate_fixture_set(dict)`, `evaluate(fixture, observations, context) -> dict`, `canonical_json(dict) -> str`; stable manifest schema and metric version, no customer/provider verification from synthetic execution. |
| `quality/fixtures/synthetic-v1.json`, `quality/README.md` | Versioned synthetic inputs/ground truth for PARSE, OCR, DOCUMENT_ROLE, PROJECT_IDENTITY, CONTRACT_CANDIDATE, F02, SEMANTIC, BREACH, TERM, EVIDENCE_GROUNDING; protocol and exact invocation. |
| `services/audit-ai/src/audit_ai/quality/schema_preflight.py` | Read-only supplied-catalog classifier: NOT_APPLIED, ALREADY_MATCHING, PARTIAL-SCHEMA_DRIFT, UNKNOWN; no database connection or execution. |

## Task 1: Allowlisted canonical metadata and scope

**Tests:** `contract/application/debug/DebugManifestBuilderTest.java`.

- [x] RED: reject incomplete scope; customer redacted level unavailable; unknown metadata keys, prompt/rawResponse/fullText/stack/filename injections rejected; malicious provider/error strings never exported. Stable sort and hashes have literal expected values.
  ```java
  assertThrows(IllegalArgumentException.class, () -> DebugManifestBuilder.validateMetadata(Map.of("prompt", "synthetic-canary")));
  assertEquals(first, builder.build(id, scope, at, actor, reordered));
  ```
- [x] Run `.\mvnw.cmd -q '-Dtest=DebugManifestBuilderTest' test`; observe missing types/behavior RED.
- [x] GREEN: typed scope and level; explicit projection of IDs, enums, integer versions/counts and approved hashes only. Unknown map keys fail closed. Actor identity is exported as a one-way internal reference, never username/displayName. Manifest verificationLabels is empty because production requests do not prove quality.
- [x] Hash contract: embedded `artifact.sha256` hashes canonical manifest excluding the `artifact` member (`digestScope=MANIFEST_WITHOUT_ARTIFACT`); the durable/API `artifactSha256` independently hashes all published bytes, avoiding a self-referential checksum. Download verifies the latter.
- [x] Rerun focused command; commit `feat: add metadata-only debug manifest policy`.

## Task 2: Durable bundle, migration and artifact lifecycle

**Tests:** `contract/application/debug/SanitizedDebugBundleServiceTest.java`, `integration/dm8/SanitizedDebugBundleRepositoryTest.java`, `services/audit-ai/tests/test_debug_schema_preflight.py`.

- [x] RED: same key same scope returns same bundle and frozen bytes despite changed current run; changed scope/level/actor conflicts; wrong ownership NOT_FOUND; concurrent same-key insert has one operation ID; stale COMPLETE cannot downgrade AVAILABLE; storage UNKNOWN stays UNKNOWN; publish followed by DB timeout reopens original bundle and completes from inspected hash; missing differs from unknown; GET makes no storage writes; audit failure denies download.
  ```java
  assertEquals(first.bundleId(), service.generate(scope, METADATA_ONLY, "same", metadata).bundleId());
  assertEquals(UNKNOWN, result.status());
  assertArrayEquals(originalBytes, service.download(scope, result.bundleId(), metadata));
  ```
- [x] Run Java focused command and `python -m pytest -q tests/test_debug_schema_preflight.py`; capture RED.
- [x] GREEN: START locks existing run for one coherent scope snapshot, claims `SANITIZED_DEBUG_BUNDLE_GENERATE`, freezes sanitized manifest and deterministic target/expected bytes, inserts GENERATING plus audit. Fingerprint covers actor + complete scope + level + schema/policy versions, excluding mutable current statuses. START returns durable replay before recollecting data.
- [x] Outside transaction: inspect deterministic key first; only DEFINITELY_MISSING permits create-only stage/promote. PRESENT requires exact full-byte SHA/size; UNKNOWN does not publish. COMPLETE reloads and CASes GENERATING/UNKNOWN to AVAILABLE, then marks idempotency success and audit atomically. Uncertain storage/DB outcome uses independent UNKNOWN closure, never FAILED. Concurrent identical publications are safe under existing storage create-only behavior. Missing uncertain artifacts are regenerated only by explicit original-key POST from the frozen manifest.
- [x] Implement independent DM8 metadata adapter/migration; SQL preflight input validates expected catalog columns/types/nullability/PK/FK/check/index definitions and dependency proof. Unknown catalog/dependency => UNKNOWN; partial/mismatched structure => PARTIAL-SCHEMA_DRIFT; exact matching => ALREADY_MATCHING; proven absence => NOT_APPLIED. No actual DM8 connection.
- [x] Rerun focused tests; commit `feat: persist and reconcile sanitized debug artifacts`.

## Task 3: Focused authorized API

**Tests:** `contract/api/project/SanitizedDebugBundleApiIntegrationTest.java`.

- [x] RED: authenticated four-role compatibility remains; only AUDITOR/SYSTEM_ADMIN can use debug routes; CSRF/key required on POST; malformed/extra body fields rejected; owned reopen/download audited; list supports rediscovery; GET never regenerates; safe NOT_FOUND and no storage root.
  ```java
  mvc.perform(post(path).with(user).content("{}")).andExpect(status().isForbidden());
  mvc.perform(get(path + "/" + id + "/artifact").with(user)).andExpect(status().isOk());
  ```
- [x] Run `.\mvnw.cmd -q '-Dtest=SanitizedDebugBundleApiIntegrationTest' test`; observe missing endpoint RED.
- [x] GREEN: standalone controller with strict JsonNode parsing, existing AuthContext and method guard, bounded application/json attachment, no-store response. Inherit existing security filters; no security config edit. Service also checks role for non-HTTP callers.
- [x] Rerun API/lifecycle tests; commit `feat: expose audited debug bundle generation and reopen`.

## Task 4: Quality fixtures, metrics and comparable result manifests

**Tests:** `services/audit-ai/tests/test_quality_harness.py`, `backend/src/test/java/com/fuquan/audit/contract/application/debug/JavaQualityHarnessTest.java`.

- [x] RED: unknown source, missing public license/source or sanitized approval, invalid fixture/ground-truth versions, changed input hash, confidential canary pattern, duplicate IDs and missing observations rejected. Same fixture/provider comparison has identical comparison identity. Result canonical ordering stable; synthetic observations cannot yield real-provider/customer labels.
  ```python
  assert edit_distance("abc", "adc") == 1
  assert result["verificationLabels"] == ["LOCAL_SYNTHETIC_VERIFIED"]
  assert result["acceptanceStatus"] == "MEASURED_NOT_ACCEPTED"
  ```
- [x] Run `python -m pytest -q tests/test_quality_harness.py`; observe RED. Java quality adapter test invokes existing Java parsing/role/identity/candidate/F02/clause/grounding seams with fixture-selected inputs and writes bounded observation metadata to target (never repository originals).
- [x] GREEN: versioned fixtures and shared observation schema; metrics include role accuracy/macro-F1/confusion, OCR CER/WER/page coverage/empty rate, typed exact/normalized/decimal numeric match, evidence hit/unsupported/hash binding, identity precision/recall/conflict FN/false ownership, semantic/breach/term schema/unsupported/retry/precision/recall, performance p50/p95/failure/timeout counts. Zero denominators return null, not fabricated success; all raw counts retained. Monetary values remain decimal strings; Java uses BigDecimal and Python decimal only for evaluation, never business decisions.
- [x] Result context records code SHA, fixture/version/groundTruth, capability, provider/model/revision, policy/schema/prompt, metric version, threshold profile, raw counts, metrics, pass/fail and execution evidence. Synthetic test thresholds are named `synthetic-regression-v1`, never production approval; customer thresholds absent => MEASURED_NOT_ACCEPTED. No fixture source automatically implies verification.
- [x] Rerun Python quality/preflight and available existing pure Python tests; attempt relevant full FastAPI suite with existing supported environment if discoverable. Record unsupported Python 3.14/missing dependency separately rather than weaken repository dependencies.
- [x] Run Java focused quality test and full `backend/.\mvnw.cmd clean verify`; preserve unrelated budget baseline if encountered. No frontend build (no frontend change).
- [x] Run `git diff --check`, status/stat, historical-migration diff and fixture/secret/scope scan. Commit `test: add versioned synthetic quality harness and metrics`.

## Final verification and handoff

- [x] Review all C1–C11 requirements against artifacts/tests, capture Java/Python counts and exact environment/commands. LOCAL_SYNTHETIC_VERIFIED applies only to executed local synthetic tests; REAL_PROVIDER_STATUS, REAL_CUSTOMER_MATERIAL_STATUS, REAL_DM8_STATUS remain NOT VERIFIED.
- [x] Read-only real HTTP smoke via local profile if a server can safely run; report observed curl status/envelope and explicitly distinguish HTTP fixture integration from real DM8/provider validation.
- [ ] `git push origin feature/contract-closeout-debug-quality-20260916`; no force, PR, merge, deploy or integration branch. Keep worktree.
- [ ] Return all C12 fields, exact commits and pushed SHA. Integration adaptations: add focused debug routes/schemas to shared OpenAPI; register new SQL in controlled manual migration ordering documentation; optional authorized workspace entry (no frontend component needed for API delivery). Production provider approval remains blocked pending separately reviewed Track C evidence and customer thresholds.

## Self-review

Execution refinement: current SQL proves the `(RUN_ID, STEP_ID, ATTEMPT_ID)` unique parent key is introduced by `CONTRACT__review_run_results.sql`, so the new migration depends on `review-run-results` (transitively `review-run`), and uses exact existing composite-key order. No historical table is altered.

C1–C6/C10 map to Tasks 1–3; C7–C9 map to Task 4; C11 maps to all adversarial tests. New domain types do not replace common ReviewRun authority. The only new table stores allowlisted metadata and artifact identity. Canonical hash self-reference is explicitly resolved, GET is read-only except access audit, exact operation identity survives uncertainty, and no customer policy or production verification is invented.

## Executed evidence and review (2026-09-16)

Implementation code SHA: `1029780551205f1ba3abfaa67cf54919ee16f0ff`. The final evidence-only commit changes no executable source. Checkboxes above record completed task work/verification attempts, not external-platform approval. Initial literal-example assertions were implemented using canonical repeatability, typed projection and full-byte hash validation; no deployment build SHA is inferred from the schema build identifier.

- Task 1: missing-class RED followed by 17 passing allowlist/scope/hash/canonical tests.
- Task 2: missing durable types RED followed by lifecycle/repository tests and four-state preflight tests. Further observed REDs exposed empty-list auditing and local orphan-claim handling; both fixed. Final lifecycle suite has 13 passing tests, including same-key concurrent recovery and Spring proxy proof of START/COMPLETE transactions with suspended caller transaction during storage inspection.
- Task 3: missing-route RED followed by 2 passing Spring MockMvc local API tests. Real local JAR HTTP smoke returned health 200 (`code=200`, `status=UP`) and unauthenticated debug-list 401 (`authentication required`), preserving supplied Request IDs. The temporary loopback-only server was stopped. No session cookie value is retained in committed evidence.
- Task 4: missing quality module and semantic adapter REDs, malformed label/provenance tests, and a candidate fixture-adapter mapping regression were observed and resolved. Fixture ground truth was not changed to hide capability failures. Final selected Python command in README: 58 passed, Python 3.14.0, including 17 quality tests and 4 preflight tests.
- Full backend `./mvnw.cmd clean verify`: BUILD SUCCESS; 1154 tests, 0 failures, 0 errors, 0 skipped; Java 17.0.19, Windows local profile, 2026-09-16. Baseline was 1119 tests. No frontend code changed, so no frontend build was run.
- Full `python -m pytest -q`: exit 2, 9 collection errors for absent python-multipart, matching the clean baseline. Supported Python >=3.11,<3.12 full FastAPI verification remains NOT VERIFIED; no dependency installation/upgrade or production accommodation was made.
- Post-commit JavaQualityHarnessTest: 1 passed. Post-commit selected Python suite: 58 passed. Exact executable code SHA above; observed canonical result is `quality/results/2026-09-16-local-synthetic-v1.json`.
- Result: 9/10 local capability gates pass; TERM has one false negative for the existing analyzer's unsupported date wording. Overall passed=false and acceptanceStatus=MEASURED_NOT_ACCEPTED. LOCAL_SYNTHETIC_VERIFIED attests only local adapter/fixture/metric execution, not universal quality success. OCR is synthetic DTO plumbing; semantic is evidence segmentation, not model inference.
- REAL_PROVIDER_STATUS=NOT VERIFIED; REAL_CUSTOMER_MATERIAL_STATUS=NOT VERIFIED; REAL_DM8_STATUS=NOT VERIFIED. No customer originals, external provider or real database was used.
- Static gates: git diff --check; explicit new-file/ownership review; only the assigned CONTRACT migration changed; historical V migrations, shared OpenAPI/security/ReviewRun files and frontend untouched; fixture credential/confidential/URL scan found no matches. Preflight catalog definitions are tested against the exact owned SQL.

Commits completed: `95f4939` plan; `c58fdf6` manifest; `b467e69` durable storage; `5f4a11c` API; `1f59923` list/claim hardening; `1029780` quality harness. Final evidence/documentation commit follows. User-selected ordinary push and preserved worktree remain the handoff action; no PR, merge or deployment.

Exact REQUIRED_INTEGRATION_ADAPTATIONS are documented in `quality/README.md`: additive OpenAPI registration, manual CONTRACT migration order after review-run-results plus real catalog preflight, optional authorized UI integration preserving the original key, and separately authorized supported-Python/provider/customer validation and thresholds. No cross-track source mutation was needed.
