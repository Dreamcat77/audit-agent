# Contract Evidence Repair Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the real PM44 03 evidence catalog satisfiable and make the one semantic correction a server-owned typed delta that cannot rewrite valid semantics.

**Architecture:** Partition each material into exact immutable source spans, isolate deterministic-value masks, and expose citable metadata without hiding context. Normalize the first semantic result into server-owned hint identities, build one correction plan from all known defects, accept only strict missing-category and evidence-repair deltas, merge server-side, and reconstruct the unchanged public DTO.

**Tech Stack:** Python 3.11, FastAPI 0.141.1, Pydantic, httpx 0.28.1, pytest.

**Spec:** `services/audit-ai/docs/superpowers/specs/2026-09-03-evidence-repair-hardening-design.md`

## Global Constraints

- Production changes are restricted to `services/audit-ai/**`.
- Do not modify `backend/src/main/**`, `frontend/src/**`, `db/**`, F02, durable DTOs, review progress, acknowledgement, history, viewer, OCR, or project ingestion/promotion.
- Preserve public fields and `NORMAL | REVIEW | INSUFFICIENT` evidence-status semantics exactly.
- Internal `hintId`, `evidenceId`, and correction-plan fields must be excluded from public output.
- Preserve every visible business character and every current semantic category capability.
- Keep `SEMANTIC_RETRY_BUDGET=1`, `TRANSPORT_RETRY_BUDGET=1`, and the request-global `BASE_OPERATIONS+2` bound.
- Do not call a real provider or restart AI, Backend, or Frontend during code implementation.
- Use `services/audit-ai/.venv-local/Scripts/python.exe` for Python tests.
- Preserve the shared worktree's existing approved user changes; checkpoint scoped diffs instead of committing.

---

### Task 1: Exact source-span and mask-isolating evidence segmentation

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/chunker.py`
- Modify: `services/audit-ai/src/audit_ai/model/evidence.py`
- Modify: `services/audit-ai/tests/test_evidence.py`

**Interfaces:**
- Produces: `BoundedChunker.split_spans(source: str) -> tuple[tuple[int, int], ...]` with exact contiguous coverage.
- Extends: `EvidenceSegment` with immutable `source_start` and `source_end` offsets relative to its `EvidenceSource.text`.
- Preserves: `segment_evidence_sources(...) -> EvidenceCatalog` and request-wide evidence-ID ordering.

- [ ] **Step 1: Add RED source-span and mask-isolation tests.**

Add literal fixtures that assert:

```python
source = "合同金额：[确定性数值字段已移除]。逾期交付按合同金额1%/周计算，累计上限10%。"
catalog = segment_evidence_sources(
    (EvidenceSource("contract", "CONTRACT_BODY", source),),
    max_segment_chars=1000,
)
assert any(not item.citable and "[确定性数值字段已移除]" in item.exact_text for item in catalog.segments)
assert any(item.citable and "1%/周" in item.exact_text for item in catalog.segments)
```

Add independent span assertions for every segment:

```python
assert item.exact_text == source[item.source_start:item.source_end]
assert spans[0][0] == 0
assert spans[-1][1] == len(source)
assert all(left[1] == right[0] for left, right in zip(spans, spans[1:]))
```

Add the sub-1000 material, multiple-mask contract, normalized coverage, and PM44-shaped
contract/tender/bid fixtures from the spec. For each relevant material assert at least one citable
segment contains its literal breach clause.

- [ ] **Step 2: Run RED and confirm the present whole-segment poisoning.**

Run:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests/test_evidence.py -q`

Expected: new tests fail because `EvidenceSegment` has no source offsets and a mask-bearing short
material still has zero citable breach candidates.

- [ ] **Step 3: Add exact bounded spans.**

Implement `BoundedChunker.split_spans` as a contiguous offset partition that honors the current
newline/space boundary preference and `max_chunks`. Keep the existing `split` public behavior
unchanged for non-evidence consumers.

- [ ] **Step 4: Implement natural source-span segmentation.**

Build ordered boundary offsets from paragraph, line, clause punctuation, mask start/end, and source
ends. Isolate the exact mask token as non-citable; partition only overlong natural atoms with
`split_spans`. Construct every `exact_text` by slicing the original source and assign request-wide
IDs after the final span order is known.

- [ ] **Step 5: Run GREEN and focused chunker regressions.**

Run:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests/test_evidence.py services/audit-ai/tests/test_chunker.py -q`

Expected: all source-span, mask-isolation, PM44-shape, and existing chunk-bound tests pass.

### Task 2: Citable model metadata and pre-provider satisfiability gate

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`
- Modify: `services/audit-ai/src/audit_ai/logging.py`
- Modify: `services/audit-ai/tests/test_contract_model_protocol.py`
- Modify: `services/audit-ai/tests/test_safe_logging.py`

**Interfaces:**
- Produces: analysis headers with `citable=true|false` for every visible segment.
- Produces: safe catalog counts and an early `EVIDENCE_CATALOG_UNSATISFIABLE` rejection before `_call_contract`.
- Preserves: router rejection of invented, non-citable, and operation-invisible IDs.

- [ ] **Step 1: Add RED protocol and satisfiability tests.**

Capture provider operations and assert every evidence header includes a literal citable boolean,
non-citable text remains in `source_text`, and a PM44-shaped catalog reaches the fake gateway with
citable breach IDs. Add an explicitly unsatisfiable catalog fixture and assert zero gateway calls,
the existing safe public error envelope, and safe reason `EVIDENCE_CATALOG_UNSATISFIABLE`.

- [ ] **Step 2: Add RED safe-log tests.**

Assert allowlisted structural fields:

```text
evidence_segment_count
citable_segment_count
non_citable_segment_count
required_breach_segment_count
required_breach_citable_segment_count
semantic_defect_types
```

Also assert source text, exact text, excerpts, prompts, provider payloads, and credentials remain
rejected by the logging allowlist.

- [ ] **Step 3: Run RED.**

Run:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests/test_contract_model_protocol.py services/audit-ai/tests/test_safe_logging.py -q`

Expected: citable metadata and catalog counters are absent, and an unsatisfiable catalog still calls
the fake provider.

- [ ] **Step 4: Implement citable headers, metrics, and fail-fast gate.**

Render `citable=true|false` in `_contract_chunks`; compute counts only from the server catalog; use
the existing breach detector on individual segments; and reject before the first provider call when
grounded required-breach segments exist but none is citable. Add only the approved safe fields and
enum reasons to logging allowlists.

- [ ] **Step 5: Run GREEN plus adversarial evidence tests.**

Run Task 2 tests together with `test_contract_evidence_adapter.py`; confirm non-citable,
operation-invisible, and invented IDs still fail.

### Task 3: Server-owned hint IDs and strict correction delta schema

**Files:**
- Create: `services/audit-ai/src/audit_ai/model/contract_correction.py`
- Create: `services/audit-ai/tests/test_contract_correction.py`
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`

**Interfaces:**
- Extends internal `ContractHint` with excluded `hint_id: str` assigned only by normalization.
- Produces: frozen `EvidenceRepairTarget` and `ContractCorrectionPlan` values.
- Produces: strict Pydantic `ContractCorrectionDraft`, `MissingCategoryHintDraft`, and `EvidenceRepairDelta` models with `extra="forbid"`.
- Extends internal operation purpose with `EVIDENCE_REPAIR`, `SEMANTIC_COMPLETENESS_CORRECTION`, and `COMBINED_CORRECTION`.

- [ ] **Step 1: Add RED strict-schema and server-ID tests.**

Assert first normalization assigns deterministic `A1:H1`, `A1:H2` IDs while public JSON contains
none. Validate literal correction payloads and assert an evidence repair containing `category`,
`attention`, `title`, or `description` fails schema validation. Assert unknown and duplicate hint
targets fail merge validation.

- [ ] **Step 2: Run RED.**

Run:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests/test_contract_correction.py -q`

Expected: module collection fails because the correction types do not exist.

- [ ] **Step 3: Implement the correction types and mode selection.**

`ContractCorrectionPlan` derives exactly one mode from non-empty missing-category and repair-target
tuples. Serialize only frozen hint metadata, requested missing categories, and the already-visible
segmented source into the correction operation. Provider response types expose only the two delta
arrays from the spec.

- [ ] **Step 4: Implement server hint assignment and correction prompts.**

Assign hint IDs from request operation coordinates and accepted-hint order. Add purpose-specific
system prompts that prohibit a full result and require empty irrelevant delta arrays. Keep
`requiredCategories` and all nine allowed categories explicit. A transport replay receives the
identical purpose and correction source.

- [ ] **Step 5: Run GREEN for schema and gateway protocol.**

Run Task 3 tests plus `test_contract_model_protocol.py`; assert no public/internal field leakage.

### Task 4: Evidence-only, category-only, and combined server merge

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/contract_correction.py`
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/tests/test_contract_correction.py`
- Modify: `services/audit-ai/tests/test_contract_retry_budget.py`
- Modify: `services/audit-ai/tests/test_model_routes.py`

**Interfaces:**
- Produces: one normalized defect set containing missing required categories and exact defective hint IDs.
- Produces: server merge that preserves all existing semantic fields and accepts only truly missing category additions.
- Preserves: complete post-merge validation and existing frozen public reconstruction.

- [ ] **Step 1: Add RED evidence-only repair tests.**

Use a first result with a valid BREACH semantic hint and invalid problem ID. Return one repair delta
with a valid citable ID. Assert HTTP 200, two calls, second purpose `EVIDENCE_REPAIR`, and exact
equality of final category/attention/title/description to attempt 1. Add a repair payload that tries
to mutate category and assert `MODEL_INVALID_RESULT`.

- [ ] **Step 2: Add RED category-only and combined tests.**

For a missing BREACH result assert second purpose `SEMANTIC_COMPLETENESS_CORRECTION` and only one
new BREACH hint is merged. For simultaneous missing BREACH plus invalid evidence on another hint,
return one `missingCategoryHints` entry and one `evidenceRepairs` entry; assert purpose
`COMBINED_CORRECTION`, frozen original semantics, valid public evidence, and two total calls.

- [ ] **Step 3: Add RED budget and transport replay tests.**

Assert one semantic correction maximum for each mode. Inject one retryable transport failure into a
correction and assert the replay retains identical mode, source, hint IDs, and missing categories;
total calls equal `BASE+2`, and no path reaches `BASE+3`.

- [ ] **Step 4: Run RED and confirm the old full-result retry behavior.**

Run:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests/test_contract_correction.py services/audit-ai/tests/test_contract_retry_budget.py services/audit-ai/tests/test_model_routes.py -q -k "repair or correction or breach_completeness or shared_budget"`

Expected: the second operation still requests a full semantic result and can replace BREACH.

- [ ] **Step 5: Implement unified defect collection and delta merge.**

Normalize all first-response hints, collect every invalid-evidence hint ID, and calculate missing
required categories before selecting a mode. Consume the semantic budget once, parse the strict
delta, reject unexpected/missing/duplicate targets, merge IDs through server-owned evidence
reconstruction, append only truly missing categories, then rerun complete validation. Keep the
first summary and every pre-existing accepted semantic field unchanged.

- [ ] **Step 6: Run GREEN plus BREACH/evidence/monetary regressions.**

Run Task 4 tests and all existing tests selected by:
`-k "breach or evidence or monetary or unavailable or mask"`.

### Task 5: Public-contract equivalence tripwire

**Files:**
- Modify: `services/audit-ai/tests/test_contract_model_protocol.py`
- Modify: `services/audit-ai/tests/test_contract_evidence_adapter.py`
- Modify: `services/audit-ai/tests/test_model_routes.py`

**Interfaces:**
- Verifies: unchanged public field names, enum values, nullability, locator authority boundary, and value semantics.
- Verifies: `hintId`, `evidenceId`, and `correctionPlan` never serialize.

- [ ] **Step 1: Add RED/characterization public equivalence tests before merge implementation.**

Capture the current literal public JSON for REVIEW, INFO, visible-text INSUFFICIENT, and
absent-material INSUFFICIENT fixtures. Assert exact key sets and literal `NORMAL`, `REVIEW`, and
`INSUFFICIENT` statuses; assert `excerpt` is exact server text or `null` only under the existing
unavailable rule. Assert recursive JSON does not contain internal field names.

- [ ] **Step 2: Run the tripwire with correction tests.**

Run the three Task 5 files and confirm all fixtures pass against both first-call and corrected
results. If a public field, enum, or nullability assertion fails, fix audit-ai reconstruction rather
than adapting Backend or Frontend.

### Task 6: Full code verification and restart handoff

**Files:**
- Verify only; do not change frozen production paths.

**Interfaces:**
- Verifies: focused behavior, full audit-ai behavior, unchanged consumer compatibility, frozen scope, and restart requirements.

- [ ] **Step 1: Run focused audit-ai tests.**

Run all new segmentation, protocol, correction, retry-budget, public-equivalence, safe-log, breach,
evidence, monetary, mask, multi-chunk, and synthesis tests.

- [ ] **Step 2: Run the complete audit-ai suite.**

Run:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests -q`

- [ ] **Step 3: Run unchanged Backend consumer regressions.**

Run the existing FastAPI AI adapter, ContractTextAnalysis use-case/API, term, and breach tests. Do
not modify Backend production.

- [ ] **Step 4: Run unchanged Frontend consumer regressions and build.**

Run contract semantic presentation/hook tests and `npm.cmd run build`. Do not modify Frontend
production.

- [ ] **Step 5: Run scope and formatting tripwires.**

Run `git diff --check`, `git status --short --untracked-files=all`, and compare
`backend/src/main/**`, `frontend/src/**`, and `db/**` with the recorded pre-task dirty baseline. If
this task added a frozen production modification, report `FIX_GATE=BLOCKED` and stop.

- [ ] **Step 6: Report code gates and stop before runtime.**

Report exactly the requested evidence-catalog, mask, span, correction, contract, and frozen-scope
fields, followed by:

```text
RUNTIME_RESTART_REQUIRED:
AI=YES
BACKEND=NO
FRONTEND=NO
```

Do not restart services or call a real model. After the user restarts AI, run one synthetic
compatibility gate, then one PM44 03 semantic E2E. PM44 07 remains forbidden until PM44 03 passes.
