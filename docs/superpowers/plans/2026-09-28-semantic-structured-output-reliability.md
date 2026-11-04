# Semantic Structured Output Reliability Closeout Plan

**Baseline:** `c3420cb51afb8a0848ecb4dbc6c0f4a65b652cde`

**Scope:** Backend diagnostics, FastAPI Semantic machine contract and structured transport, aggregate-only no-write probes, regression gates, then one fresh 01 golden E2E. Frontend remains unchanged.

## Task 1: Safe read-only diagnostics

**Files:**
- Add `backend/src/main/java/com/fuquan/audit/contract/application/reviewrun/ContractReviewStepDiagnosticsService.java`
- Modify `backend/src/main/java/com/fuquan/audit/contract/api/project/ContractReviewRunController.java`
- Add focused application/controller tests under matching backend test packages
- Update the checked-in OpenAPI contract only where required by the existing API workflow

1. Write RED tests for exact run/step/attempt ownership, finite safe-reason classification, call ordering, absence of sensitive fields, owner hiding, and SYSTEM_ADMIN/AUDITOR-only access.
2. Implement a read-only service that reuses `ReviewStepOutputReadService` and `ContractModelCallAuditRepository`.
3. Derive `failureStage` only from finite safe reason-code maps. Unknown codes map to `UNKNOWN`; no exception-message parsing.
4. Add the narrow diagnostics GET endpoint and retain existing exact-run ownership checks.
5. Run focused backend tests.

## Task 2: Strict production Semantic machine contract

**Files:**
- Modify `services/audit-ai/src/audit_ai/model/contract_draft.py`
- Modify `services/audit-ai/src/audit_ai/model/router.py`
- Add/modify focused FastAPI protocol and validation tests

1. Write RED tests for schema generation, strict required fields/enums, extra-property rejection, unknown/non-citable evidence, and safe validation reasons.
2. Replace the permissive primary provider draft boundary with one strict Pydantic model.
3. Generate the provider JSON Schema from that same model with aliases.
4. Keep server-owned evidence reconstruction and all current completeness, evidence, grounding, business, frozen-reference, and Java validators.
5. Run focused FastAPI tests.

## Task 3: Aggregate-only protocol probe

**Files:**
- Add a repository probe module/script only if it can stay fixture-independent and credential-free
- Add probe unit tests
- Store actual outputs only under ignored runtime/quality paths

1. Write RED tests that verify no raw prompt, document text, response body, credential, or evidence excerpt is persisted.
2. Implement L1-L4 schemas, where L4 comes directly from the production Pydantic model.
3. Support `PROMPT_ONLY`, `OPENAI_COMPAT_STRUCTURED`, and `OLLAMA_NATIVE_STRUCTURED` with identical model, fixture, temperature, token policy, and schema.
4. Record only aggregate counts/rates, safe failure stage/reason, usage, repair count, average latency, and p95 latency.
5. Verify provider version and exact model identity before sending the authorized non-sensitive 01 fixture.
6. Retain the existing capability-probe evidence. Per the 2026-09-28 execution-strategy revision, do not run the former L2-L4 / 20-repetition matrix before the golden 01 E2E and do not claim statistical stability.

## Task 4: Structured-output-first production path

**Files:**
- Modify `services/audit-ai/src/audit_ai/model/gateway.py`
- Modify `services/audit-ai/src/audit_ai/model/router.py` and related application flow only as demonstrated necessary by RED tests
- Modify focused gateway/retry/safe-logging tests

1. Write RED tests for selected transport request construction and response normalization.
2. Enforce strict Pydantic validation before any compatibility normalization.
3. Keep at most one controlled retry after the initial structured request; fail closed after exhaustion.
4. Persist safe call audit metadata for each ordinal and validation stage without raw content.
5. Run focused and nearby FastAPI/backend adapter tests.

## Task 5: Historical readback and full gates

1. Start the exact local integration build without creating business data.
2. Read the existing failed 01 run through the diagnostics API and report only PROVEN facts; mark absent historical facts UNKNOWN.
3. Run backend `clean verify`, FastAPI full suite, `git diff --check`, sensitive-data scan, and verify `git diff -- frontend` is empty.
4. Confirm the completed no-write probe did not write business tables, and record that the former 20x qualification gate was explicitly waived rather than passed.
5. Commit the integration checkpoint and record the exact SHA.

## Task 6: Single final 01 golden E2E

1. From the exact committed SHA, use the normal UI and authorized 01 originals only.
2. Exercise login, upload, parse/OCR, roles/identity/support, deterministic and AI candidates, evidence validation, confirmation, promotion, ReviewRun, five steps, source navigation, copy, ACK/report, refresh, relogin, backend restart, and exact history reopen.
3. Record provider-call audit and the five-step/eight-category matrices without secrets or original text.
4. If this one run fails, stop new business writes and report the exact boundary. Do not automatically create a second run.
