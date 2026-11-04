# Contract Closeout Track E — Capability Framework and Provider Seam

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. No subagents or cross-Track implementation access.

**Goal:** Deliver 12A descriptive capability/profile contracts and 12B a typed, fail-closed provider seam without activating a provider or changing the fixed ReviewRun execution manifest.

**Architecture:** Java remains the business identity, evidence-validation and durable-result authority. An immutable Java catalog describes the existing five required steps and seven non-executable extension slots. Python routes only compiled Qwen-compatible semantic and Paddle OCR adapters through an independently reviewed quality-result/approval read port. Existing HTTP routes remain compatibility paths, byte-for-byte unchanged. The new gate is default-deny and checked on every adapter invocation; configuration is never approval.

**Tech Stack:** Java 17/Spring Boot/JUnit, Python dataclasses/Protocols/pytest, existing FastAPI/Qwen-compatible gateway/Paddle ports; no new runtime dependency.

**Approved inputs:** `docs/superpowers/specs/2026-09-14-contract-audit-evolution-master-design.md`, `docs/superpowers/specs/2026-09-16-contract-audit-parallel-closeout-design.md`, Phase2A/Phase2B plans and latest user attachment common rules/E1–E13. The coordinator confirmed the initially named alternate master-spec/plan paths were typos; no replacement specification is created.

## Baseline and ownership

- Worktree: `.worktrees/contract-closeout-capability-provider`; branch: `feature/contract-closeout-capability-provider-20260916`.
- HEAD_BEFORE: `2ed1eb9906d2c67d3c673906d5f1b27b434af3a9`; initial status clean, exact branch/HEAD checked before actions.
- Observed Java baseline: `backend/ .\mvnw.cmd -Dtest=ReviewRunManifestFactoryTest,ReviewRunStatusAggregatorTest,ContractReviewExecutionPolicyResolverTest test`: 6 tests, zero failures/errors/skips, BUILD SUCCESS.
- Initial Python baseline: `services/audit-ai/ python -m pytest -q`: collection blocked by missing `python-multipart` (9 collection errors); host Python 3.14, target 3.11 unavailable. Resolve only with a worktree-local ignored venv and existing pinned dependencies, never system installation. Report target Python 3.11 NOT VERIFIED separately.
- Existing `ContractReviewStepType` is exactly MATERIAL_VALIDATION/F02_DETERMINISTIC/SEMANTIC_AI/BREACH/TERM. `ReviewRunManifestFactory` creates five steps/four attempts; `ReviewRunStatusAggregator` requires precisely those five. `ContractReviewExecutionPolicyResolver.REVIEW_PROFILE_VERSION` is `review-profile-five-required-v1`.
- Existing Python `Settings` and `main.create_app` select the current gateway/OCR ports; model router performs typed schema/evidence safety normalization. Neither business service nor HTTP contract is replaced.

## Exact files and interfaces

All Java additions under `backend/src/main/java/com/fuquan/audit/contract/application/capability/`:

- `CapabilityAvailability.java`: ENABLED/NOT_CONFIGURED/DISABLED_BY_POLICY.
- `ReviewCapabilityDescriptor.java`: immutable code/display/kind/input/output/policy requirement, optional fixed step binding, availability. Nested enums supply closed capability codes and execution kinds; extension descriptors have no execution/input/output binding.
- `ReviewProfileDescriptor.java`: immutable profile version, stable required/optional capability lists; duplicate/overlap rejection.
- `CustomerRuleCapabilityRegistry.java`: immutable stable catalog; `currentProfile()`, `descriptors()`, `find(code)`; no runtime rule/executor loader, no mutation or business writes.
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/capability/CustomerRuleCapabilityRegistryTest.java`.

All Python additions under `services/audit-ai/src/audit_ai/capabilities/`:

- `__init__.py`: package boundary documentation only.
- `contracts.py`: frozen `Capability`, `Provider`, `RouteBinding`, `QualityResultManifest`, `ProductionApproval`, `ReviewedQualityResult`, `ApprovalState` and explicit verification level. Versioned route identity binds capability/provider/model/revision/fixtureVersion/metricVersion/thresholdProfile plus schema/prompt/policy versions. Safe bounded identifier validation; no credentials or business identity fields.
- `approval.py`: `ReviewedQualityResultPort.find_reviewed_result(binding)` read-only protocol, default empty source, `ApprovalGate.require_approved(binding)`; absent/unknown/unapproved/version mismatch/provider mismatch/capability mismatch all deny with a generic safe exception. No approval-write API.
- `config.py`: frozen stable `ProviderRoutingConfiguration`, explicit route list, duplicate/unknown-provider rejection; compiled `build_provider_router(settings, configuration, reviewed_results)` factory creates only existing gateway/Paddle adapters, checks configured model and supported capabilities. Empty configuration/default source cannot activate a route.
- `router.py`: `CapabilityProviderRouter.model_gateway()` and `.ocr_port()` return existing typed port-compatible wrappers. Each invocation rechecks a server-owned route and approval, dispatches only to its supported compiled adapter, rejects returned model mismatch. No raw generic operation or business persistence entry point. Existing result normalization and Java validation remain mandatory downstream.
- Tests: `services/audit-ai/tests/test_capability_contracts.py`, `test_capability_approval.py`, `test_capability_provider_router.py` (only synthetic quality/approval/provider fixtures).

API ownership: no new public HTTP endpoint, root OpenAPI/security/ReviewRun/controller/model records untouched. The seam can be supplied through existing `create_app(model_gateway=..., ocr_port=...)` injection, but legacy default wiring is deliberately unchanged. Integration may consume the Java catalog and inject an authoritative reviewed-quality read adapter after Track C evidence and approval review; no parallel branch details are assumed.

## Invariants and migration decision

- MIGRATION_NECESSITY = NO MIGRATION. This slice stores no approval or review facts: descriptors are compiled metadata, approvals are read through a default-deny port. No DB/cache/queue table, no DM8 connection, no historical migration edit. A future durable approval adapter requires an explicitly reviewed schema proposal; do not fabricate an in-memory production approval authority.
- No transactions, idempotent commands, worker claims, or aggregate status transitions added. Repeated reads are side-effect-free; immutable collections and per-call approval checks prevent mutable configuration and cached approval from silently enabling a route. Approval revocation denies the next invocation; an already-started external request cannot be retroactively cancelled by this seam.
- Provider execution remains outside Java transactions. The seam does not allocate project/file/task/run/evidence identities, persist results, or replace evidence/schema checks. No dynamic import, scripts, SQL, RAG/vector/general-agent capability or user-supplied executable rules.
- F09–F15: only `待配置审核能力 Fxx`, NOT_CONFIGURED, customer-policy requirement; no required material, output, step, executor, threshold/risk/blockSign/legal semantics.
- Gate matching includes quality schema version 1, accepted quality, REAL_PROVIDER_VERIFIED attestation from the trusted read port, exact immutable binding and quality-result reference, approval identity/reviewer and APPROVED_FOR_PRODUCTION_ROUTE. Unit fixtures simulate these values only; no real provider is approved or contacted.
- Approval failures contain no provider payload, credential, endpoint, approval identity or body text. Runtime binding/provenance metadata is not a verified deployment claim.

## Task 1 — Descriptive Java catalog (commit `feat: describe fixed review capabilities and unconfigured extension slots`)

- [x] RED: add tests for exact five-code/profile order, enum unchanged, all seven safe Chinese NOT_CONFIGURED slots, immutable collections and invalid descriptor/profile combinations; run the focused new test and observe missing implementation failure.
- [x] GREEN: implement four focused Java files; reuse the existing profile-version constant, never create a ReviewStep or change manifest generation.
- [x] Verify catalog initialization leaves factory output unchanged (five steps/four attempts) and all completed required steps aggregate COMPLETED despite the registered slots. Run focused new and existing manifest/aggregator/policy tests; self-review then commit.

## Task 2 — Typed quality/approval contract (commit with Task 3)

- [x] RED: add Python contract/gate tests: immutability, unsafe/blank identifiers, unknown enums, missing/false approval, no quality evidence, unsupported manifest version, every binding dimension mismatch, wrong quality reference, unverified evidence, source error, source revocation and default denial. Observe import/assertion failure before implementation.
- [x] GREEN: implement frozen contracts and read-only default-deny gate. Synthetic accepted records are confined to tests, not config or factory defaults.
- [x] Run `python -m pytest -q tests/test_capability_contracts.py tests/test_capability_approval.py` through the verified local runtime.

## Task 3 — Compiled provider router/config (commit `feat: add fail-closed capability provider approval seam`)

- [x] RED: add tests for unknown providers, unsupported capabilities (including handwriting), duplicate routes, settings/model mismatch, configured-but-unapproved denial before provider invocation, approved synthetic model/OCR dispatch, per-call revocation, returned-model mismatch, unchanged request/result forwarding, and port injection into existing HTTP validation.
- [x] GREEN: implement the factory/router using the existing Settings/model/OCR ports only. No existing model/OCR/router/main modifications, no new actual provider adapter, no production approval records.
- [x] Verify request-provided identity/extra authority cannot bypass existing schema/evidence validation when a synthetic approved gateway is injected; validate legacy Qwen/Paddle tests remain green; self-review and commit.

## Task 4 — Fresh verification and handoff (commit `docs: record capability provider verification evidence`)

- [x] Run focused Java tests above, then `backend/ .\mvnw.cmd clean verify`; report exact test counts/exit/environment. Do not fix unrelated budget baseline issues.
- [x] Run all three new Python modules, existing gateway/Paddle/config/model-route/OCR-route tests and full `python -m pytest -q`; report actual runtime and target-runtime limitation. No customer material, live provider, real DM8, or external deployment.
- [x] Run `git diff --check`, `git status --short`, `git diff --stat`, compare changed paths to ownership; scan added files for secrets, IP-specific values, raw fixtures, migrations, executable loading, caches/queues, fake extension rules, new dependencies and forbidden architecture.
- [ ] Record observed RED/GREEN/full test evidence in this plan. Verify plan commit precedes all production commits; final status clean.
- [ ] Ordinary push only `origin feature/contract-closeout-capability-provider-20260916`; no force/merge/PR/deploy. If rejected by safety approval, do not retry via a different path: report exact clean HEAD/PUSHED_HEAD NOT PUSHED.
- [ ] Return E13 exact result sections with `PRODUCTION_PROVIDER_ACTIVATION = NOT PERFORMED`, required future integration adapters, real environment limitations, and stop.

## Plan self-review

Approved design already passed brainstorming; no reapproval requested. Scope stays additive and executable tests cover every E12 minimum. All existing shared files and five-step lifecycle remain untouched. The default-deny read seam avoids a new approval system of record, and synthetic approval fixtures cannot enter production configuration. No unavailable Track C API is guessed.

## Observed execution and verification — 2026-09-16

- Plan-first commit `03216a527c59340c4046665359412412daa970e3` precedes Java commit `ef694a4` and Python commit `569f016`.
- Java RED: new catalog test failed for missing catalog/descriptor classes. Initial fixture accessor typos were corrected to the inspected record's `runId/stepPolicyVersion/currentAttemptNo`; RED rerun still failed solely for the missing implementation. GREEN: 6 new catalog tests plus 6 existing profile/manifest/aggregator tests, 12 total, zero failures/errors/skips.
- Java final production tree: `backend/ .\mvnw.cmd clean verify`, Windows/JDK 17, 1,125 tests, zero failures/errors/skips, BUILD SUCCESS (2026-09-16 19:57 local). DM8-backed tests use existing fakes/mocks; REAL_DM8_STATUS = NOT VERIFIED / NOT CONNECTED.
- Python setup used only ignored `services/audit-ai/.venv-track-e`, host Python 3.14. Existing pinned requirements were installed with approved `python -m pip --python .venv-track-e/Scripts/python.exe install -r requirements-test.txt`; no dependency file or system package changed. Sandbox-only temporary-directory permissions required approved full-suite execution. The unchanged baseline then passed 229 tests.
- Python RED: contracts/approval import failed before package creation (2 collection errors); minimal GREEN passed 51 tests. Router RED: missing new config module (1 collection error). First router GREEN exposed a synthetic fixture emitting only one category and then the wrong correction schema; logs proved the existing completeness gate rejected it. Corrected only the synthetic fixture using the existing complete fixture helper, leaving production normalization unchanged.
- Final focused Python command: `.\.venv-track-e\Scripts\python.exe -m pytest -q tests/test_capability_contracts.py tests/test_capability_approval.py tests/test_capability_provider_router.py --basetemp=.test-runtime/pytest-e-final-focused`: 72 passed. Final full command after tightening the malicious-provider assertion: `.\.venv-track-e\Scripts\python.exe -m pytest -q --basetemp=.test-runtime/pytest-e-final-full`: 301 passed, 2 existing FastAPI/Starlette deprecation warnings, exit 0. The full run covers the final exact test assertion and all existing Qwen/Paddle compatibility tests. Target Python 3.11 and actual Paddle/model inference remain NOT VERIFIED here.
- Adversarial HTTP proof: a synthetically approved gateway attempting invented task/actor/file/evidence metadata receives HTTP 502 / `MODEL_INVALID_RESULT` through unchanged normalization; no invented values escape. Client-supplied approval fields are rejected before provider calls. Legitimate synthetic input still returns HTTP 200. Java remains the business/evidence/durable authority; these are local contract tests, not business acceptance.
- Real local curl smoke against the unchanged app, started briefly with Uvicorn and stopped afterward: `curl.exe -i http://127.0.0.1:18976/health` returned `HTTP/1.1 200 OK`, `x-request-id`, and `{"status":"UP","service":"audit-ai"}`. No provider request or deployment was performed.
- Static gates: baseline-to-HEAD diff check passes; all 14 changed paths are additions in the declared Java/Python packages, their four test files, and this plan. No existing source, manifest/enum/aggregate/controller/security/OpenAPI, frontend, dependency, migration or shared documentation changed. Production scans found no executable loading, secrets, network literals, Redis/queue/DB dependency or forbidden agent/vector framework. Test scan found only the deliberately rejected `https://unsafe.invalid` synthetic identifier; no real endpoint/secret/customer material.
- Self-review completed; bounded review context sent to coordinator without spawning a prohibited subagent. No correctness blocker identified.

## Integration and explicit non-claims

REQUIRED_INTEGRATION_ADAPTATIONS: none needed to compile/test this additive seam. Future activation requires an authoritative `ReviewedQualityResultPort` adapter mapping reviewed Track C manifest version 1 into one consistent current quality/approval read, with immutable result IDs and revocation; any durable approval schema requires the separate approved proposal. Integration may inject the returned typed gateway/OCR wrappers through existing `create_app` parameters and may consume the Java catalog without changing the fixed manifest. Do not wire the default-deny router over legacy routes before approved evidence exists. Configured model/revision/schema/prompt/policy metadata alone is not remote-runtime attestation or persisted Java provenance.

MIGRATION_STATUS = NO MIGRATION. PRODUCTION_PROVIDER_ACTIVATION = NOT PERFORMED. Real provider/DM8/customer/target Python 3.11/国产 middleware verification is NOT VERIFIED in this local contract-only Track. Push outcome and exact final clean HEAD are reported in the final E13 handoff; no merge, PR, deployment, or worktree removal is authorized.
