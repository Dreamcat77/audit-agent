# Audit AI Provider Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make contract semantic calls reliable through explicit mandatory coverage, request-wide
server evidence IDs, public-contract-equivalent reconstruction, and independent bounded semantic
and transport retries.

**Architecture:** Segment every existing redacted material into a request-wide immutable evidence
catalog, ask the provider for internal evidence IDs, and reconstruct the unchanged public hint DTO
inside audit-ai. Coordinate all analysis chunks and synthesis through one request-level retry budget
with one semantic correction, one transport retry, and an exact BASE+2 call ceiling.

**Tech Stack:** Python 3.11, FastAPI 0.141.1, Pydantic, httpx 0.28.1, pytest.

**Spec:** `services/audit-ai/docs/superpowers/specs/2026-09-02-provider-reliability-design.md`

## Global Constraints

- Production changes are restricted to `services/audit-ai/**`.
- Do not modify Backend, Frontend, DB, F02, durable DTO, ReviewProgress, acknowledgement, history,
  ProjectMaterialsViewer, promotion, or OCR-ingestion production code.
- Preserve the current audit-ai response envelope and public hint/evidence field names and enum
  semantics; `evidenceId` is internal only.
- Preserve all existing visible business text and all nine category capabilities.
- `SEMANTIC_RETRY_BUDGET=1`, `TRANSPORT_RETRY_BUDGET=1`, and calls never exceed
  `BASE_OPERATIONS+2` across all chunks and synthesis.
- Do not restart the user-managed AI, Backend, or Frontend processes.
- Use `services/audit-ai/.venv-local/Scripts/python.exe` for Python tests.
- The shared worktree already contains approved uncommitted PM44 changes; do not commit or overwrite
  unrelated user changes. Record focused diffs at each checkpoint instead.

---

### Task 1: Request-wide evidence segmentation

**Files:**
- Create: `services/audit-ai/src/audit_ai/model/evidence.py`
- Create: `services/audit-ai/tests/test_evidence.py`

**Interfaces:**
- Produces: immutable `EvidenceSource`, `EvidenceSegment`, and `EvidenceCatalog` values.
- Produces: `segment_sources(sources, max_segment_chars)` with request-wide stable IDs.
- Produces: `normalize_visible_text(value)` for coverage assertions.
- Preserves: every normalized business character from every source material.

- [ ] **Step 1: Add RED tests for stable unique IDs and exact resolution.**

```python
def test_evidence_ids_are_request_wide_unique_and_resolve_once():
    catalog = segment_sources([
        EvidenceSource("contract", "CONTRACT_BODY", "合同服务条款"),
        EvidenceSource("bid", "BID_RESPONSE", "投标服务条款"),
        EvidenceSource("tender", "TENDER_DOCUMENT", "招标服务条款"),
    ], max_segment_chars=100)
    assert [item.evidence_id for item in catalog.segments] == [
        "C:S1", "B:S2", "T:S3",
    ]
    assert len(catalog.by_id) == len(catalog.segments)
    assert catalog.by_id["B:S2"].exact_text == "投标服务条款"
```

- [ ] **Step 2: Add RED text-equivalence and long-source tests.**

```python
def test_segmentation_preserves_normalized_visible_business_text_per_material():
    original = "第一条  服务内容\n第二条：交付要求\n第三条：质保要求"
    catalog = segment_sources([
        EvidenceSource("contract", "CONTRACT_BODY", original),
    ], max_segment_chars=12)
    rebuilt = "".join(item.exact_text for item in catalog.segments)
    assert normalize_visible_text(rebuilt) == normalize_visible_text(original)
```

- [ ] **Step 3: Run RED.**

Run:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests/test_evidence.py -q`

Expected: collection fails because `audit_ai.model.evidence` does not exist.

- [ ] **Step 4: Implement immutable catalog and bounded exact segmentation.**

Implement frozen dataclasses, deterministic category prefixes, one request-wide segment counter,
line-aware bounded splitting, an immutable mapping, citable-mask detection, and normalized coverage.
Do not import router DTOs into this module.

- [ ] **Step 5: Run GREEN and checkpoint the scoped diff.**

Run the Task 1 pytest command, then:
`git diff -- services/audit-ai/src/audit_ai/model/evidence.py services/audit-ai/tests/test_evidence.py`

Expected: all Task 1 tests pass and only the two scoped files appear.

### Task 2: Internal drafts and public-contract reconstruction

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/tests/test_model_routes.py`

**Interfaces:**
- Produces: internal `ModelHintDraft` and `ContractChunkDraft` Pydantic models.
- Produces: reconstruction from valid IDs to the existing `ContractHint` and
  `ContractEvidenceItem` response models.
- Consumes: `EvidenceCatalog.by_id` and operation-visible evidence-ID sets from Task 1.
- Preserves: public category/attention/title/description/evidenceRefs/evidenceItems fields and
  NORMAL/REVIEW/INSUFFICIENT evidence status semantics.

- [ ] **Step 1: Add a RED public-equivalence test.**

Create three server segments and return this provider draft:

```python
{
  "summary": "服务责任存在差异",
  "hints": [{
    "category": "SERVICE_WARRANTY",
    "attention": "REVIEW",
    "title": "服务响应差异",
    "description": "建议结合三份材料复核。",
    "problemEvidenceIds": ["C:S1"],
    "comparisonEvidenceIds": ["B:S2", "T:S3"]
  }]
}
```

Assert the current public fields, three file IDs in `evidenceRefs`, one REVIEW problem item, two
NORMAL comparison items, server-owned exact excerpts, and absence of any `evidenceId` field.

- [ ] **Step 2: Add RED adversarial tests.**

Cover multiple problem IDs from the same file, an invented problem ID, a non-citable mask segment,
truthful absent-material INSUFFICIENT evidence, improper unavailable category, and OTHER_TEXT.
Invalid problem evidence must use `MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE`; it may not be guessed or
fuzzy-matched.

- [ ] **Step 3: Run RED and verify failures are caused by the old provider DTO.**

Run the named new tests with:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests/test_model_routes.py -q -k "evidence_id or public_contract_equivalence"`

Expected: assertions fail because the router still expects `evidenceRefs/evidenceItems` from the
provider.

- [ ] **Step 4: Implement internal normalization and reconstruction.**

Parse only the internal ID fields from provider hints, apply the existing category, attention,
strong-verdict, monetary, and unavailable-material checks, then reconstruct the unchanged public
models from the server catalog. Keep `ContractEvidenceItem` and `ContractHint` public aliases and
enum literals unchanged.

- [ ] **Step 5: Run GREEN plus current evidence and monetary regressions.**

Run the new tests and the existing evidence, unavailable, monetary, mask-token, and schema-drift
tests. Inspect the diff to confirm no public field was removed or renamed.

### Task 3: Explicit requiredCategories and independent retry state

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/src/audit_ai/logging.py`
- Modify: `services/audit-ai/tests/test_model_routes.py`
- Modify: `services/audit-ai/tests/test_safe_logging.py`

**Interfaces:**
- Extends internal `ContractTextOperation` with `required_categories`, `semantic_attempt`,
  `transport_attempt`, `semantic_retry_reason`, and `transport_retry_reason` while retaining safe
  default construction for tests.
- Produces: request-scoped `ContractRetryBudget` with `take_semantic_retry()` and
  `take_transport_retry()` returning false after one use.
- Preserves: the semantic correction reason across a transport retry.

- [ ] **Step 1: Add a RED first-call requiredCategories gateway test.**

Capture the OpenAI-compatible request and assert the first user message contains explicit JSON-like
operation metadata with all nine allowed categories and
`requiredCategories=["BREACH_LIABILITY"]`. Assert a non-required OTHER_TEXT hint remains accepted.

- [ ] **Step 2: Add the real PM44 03 RED sequence.**

Use a gateway sequence of missing BREACH result, retryable CONNECT_ERROR, then a valid breach draft.
Assert operation coordinates and reasons are exactly:

```python
[(1, 1, None, None),
 (2, 1, "MISSING_REQUIRED_BREACH_CATEGORY", None),
 (2, 2, "MISSING_REQUIRED_BREACH_CATEGORY", "CONNECT_ERROR")]
```

Assert HTTP 200, one semantic retry, one transport retry, and three total calls.

- [ ] **Step 3: Add RED exhaustion and ordering tests.**

Cover semantic failure twice (`MODEL_INVALID_RESULT`, two calls), transport failure twice
(`MODEL_GATEWAY_ERROR`, two calls), transport-then-semantic success, semantic-then-transport
exhaustion, and a non-retryable 4xx. Assert the last failure class controls the public error.

- [ ] **Step 4: Run RED.**

Run:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests/test_model_routes.py services/audit-ai/tests/test_safe_logging.py -q -k "required_categories or independent_retry or pm44_03 or retry_budget"`

Expected: failures show the current single shared `retry_available` flag cannot produce the approved
state sequence.

- [ ] **Step 5: Implement the request-scoped retry coordinator and prompt metadata.**

Compute required categories before each first provider call, pass them through
`ContractTextOperation`, and render them without restricting allowed categories. Replace the shared
boolean with separate one-use semantic and transport budgets. A transport retry reuses the same
semantic attempt and correction reason. Add allowlisted counters/reasons only; never log content.

- [ ] **Step 6: Run GREEN and prove BASE+2.**

Run focused tests and assert, for N analysis chunks plus optional synthesis:

```python
len(provider_operations) <= base_operations + 2
semantic_retry_count <= 1
transport_retry_count <= 1
```

No parametrization may observe BASE+3.

### Task 4: Segmented multi-chunk analysis and synthesis

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/tests/test_model_routes.py`
- Modify: `services/audit-ai/tests/test_evidence.py`

**Interfaces:**
- Produces: analysis operations whose source contains all segment text plus request-wide IDs.
- Produces: synthesis input containing normalized internal drafts and the bounded catalog for all
  cited IDs.
- Preserves: all safe partial hints through existing synthesis fallback.

- [ ] **Step 1: Add RED all-material and category-coverage tests.**

Use contract, award, bid, and tender sources containing all nine category signals. Assert every
normalized source text is recoverable from analysis operation input and that requiring BREACH does
not suppress the other eight valid categories.

- [ ] **Step 2: Add RED cross-chunk synthesis tests.**

Return problem ID `C:S1` from one chunk and comparison IDs `B:S2` and `T:S3` from other chunks.
Assert synthesis sees request-wide non-colliding IDs, can return all three, and reconstructs the
same public files/statuses/excerpts.

- [ ] **Step 3: Add RED multi-chunk shared-budget tests.**

Force one semantic correction in analysis and one transport retry in synthesis. Assert all later
operations have no extra budget and total calls equal BASE+2. Add the inverse order and assert the
same bound.

- [ ] **Step 4: Run RED, then implement segmented chunk packing and ID-based synthesis.**

Pack complete segment text without filtering materials. Synthesis may use only IDs exposed by safe
partials. Retain the current bounded fallback when compact synthesis input cannot fit.

- [ ] **Step 5: Run GREEN with existing chunk/synthesis/breach tests.**

Run all tests whose names contain `chunk`, `synthesis`, `breach`, `evidence`, or `monetary`, then
inspect operation counts and reconstructed public response values.

### Task 5: Full code verification and restart handoff

**Files:**
- Verify only. A new production file requires a failing test and must remain under
  `services/audit-ai/**`.

**Interfaces:**
- Verifies: complete Python behavior, frozen consumer compatibility, scope, formatting, and runtime
  handoff.

- [ ] **Step 1: Run all audit-ai tests.**

Run:
`services/audit-ai/.venv-local/Scripts/python.exe -m pytest services/audit-ai/tests -q`

- [ ] **Step 2: Run unchanged consumer regressions.**

Run the existing Backend AI adapter/use-case locator tests and Frontend contract semantic
presentation tests without modifying their production sources. These tests prove the old public
response remains consumable; they are not real PM44 runtime evidence.

- [ ] **Step 3: Verify formatting and scope.**

Run `git diff --check`, inspect every changed audit-ai file, run `git status --short`, and compare
frozen production paths against the recorded pre-task baseline. If this task added any frozen
production change, report `FIX_GATE=BLOCKED` and stop.

- [ ] **Step 4: Report restart matrix and stop before runtime.**

Expected:

```text
RUNTIME_RESTART_REQUIRED: AI=YES BACKEND=NO FRONTEND=NO
```

Do not restart any service. After the user's manual AI restart, execute exactly one synthetic
compatibility gate. Only if it passes, run PM44 03 semantic once; only after it passes, run PM44 07
semantic once. Do not claim completion before all three runtime layers pass.
