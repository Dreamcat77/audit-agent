# Contract Evidence Repair Hardening Design

## 1. Objective and authoritative failures

This hardening step fixes two confirmed implementation defects in the internal contract-semantic
boundary while preserving the current public response and every frozen Backend, Frontend, database,
F02, OCR, project-ingestion, durable-review, acknowledgement, history, and viewer behavior.

The first defect is evidence-catalog unsatisfiability. The current implementation creates segments
up to roughly 1000 characters and marks the whole segment non-citable when any deterministic-value
mask occurs inside it. In the real PM44 03 shape, the contract, tender, and bid breach clauses share
segments with masks, so every genuine breach excerpt is server-side non-citable.

The second defect is semantic mutation during evidence repair. A first model result can contain a
valid `BREACH_LIABILITY` semantic hint but invalid evidence IDs. The current retry asks the model to
regenerate the entire result, allowing the correction to delete the valid breach category. Evidence
repair must instead be a typed delta applied to server-frozen semantics.

This change must not improve success by increasing the number of semantic answers.

## 2. Scope and public-contract freeze

Production changes are restricted to `services/audit-ai/**`. Any newly required production change
under `backend/src/main/**`, `frontend/src/**`, or `db/**` blocks the fix and requires explicit scope
expansion approval.

The existing public success response remains compatible in field presence, spelling, enum values,
nullability, and semantics:

```text
category
attention
title
description
evidenceRefs
evidenceItems
evidenceItems[].fileId
evidenceItems[].status = NORMAL | REVIEW | INSUFFICIENT
evidenceItems[].excerpt = string | null
```

Backend-owned `locatorAvailable` remains derived by the existing Backend authority. Internal
`hintId`, `evidenceId`, and `correctionPlan` values never cross the audit-ai public response.

## 3. Immutable source-span evidence segmentation

Every evidence source is partitioned by offsets into immutable, ordered spans. An internal segment
contains:

```text
EvidenceSegment
  evidence_id: str
  file_id: str
  material_category: str
  source_start: int
  source_end: int
  exact_text: str
  citable: bool
```

For every material, the following invariants hold:

```text
segment.exact_text == source.text[segment.source_start:segment.source_end]
first.source_start == 0
last.source_end == len(source.text)
segments[n].source_end == segments[n + 1].source_start
```

Those invariants prove `NO_LOSS`, `NO_DUPLICATION`, and `ORDER_PRESERVED` independently of string
reassembly. Normalized concatenation remains an additional business-text coverage assertion and
must report `SAFE_VISIBLE_TEXT_COVERAGE=100%`.

Natural boundaries are paragraph, line, and clause boundaries. The exact mask token
`[确定性数值字段已移除]` creates its own non-citable source span whenever possible. A mask may not
make preceding or following safe clauses non-citable. Only a natural atom longer than the public
excerpt bound is partitioned by the bounded chunker, using offset-preserving spans.

All segments, including non-citable mask context, remain visible to the model. Each analysis source
header exposes machine-readable `citable=true|false`. The provider may select only citable IDs, but
the router remains authoritative and rejects invented, non-citable, or operation-invisible IDs.

Before a provider call, audit-ai calculates whether every operation requiring
`BREACH_LIABILITY` has at least one citable segment containing a grounded quantified breach clause.
If breach text exists but every corresponding segment is non-citable, the request is rejected
without consuming a provider call and safely classified as `EVIDENCE_CATALOG_UNSATISFIABLE`.

## 4. Server-owned hint identity

After first-pass semantic normalization, the router assigns each accepted hint a request-local,
stable internal `hintId`. IDs are deterministic for the request and operation, for example
`A1:H1`. They are excluded from public serialization.

The provider never creates a hint ID. A repair response may reference only IDs supplied by the
server correction plan. Unknown or duplicate repair targets are invalid.

The first accepted values of `category`, `attention`, `title`, and `description` are frozen. An
evidence repair cannot add or delete hints and cannot replace any frozen semantic field.

## 5. Typed delta correction plan

After the first normalized result, the router collects all known defects before spending the one
semantic correction budget:

```text
ContractCorrectionPlan
  missing_required_categories[]
  evidence_repair_targets[]
```

The plan selects exactly one mode:

- `EVIDENCE_REPAIR`: all required categories exist; one or more existing hints have invalid
  evidence.
- `SEMANTIC_COMPLETENESS_CORRECTION`: required categories are missing; no existing hint needs
  evidence repair.
- `COMBINED_CORRECTION`: required categories are missing and existing hints also need evidence
  repair.

Every correction response has two strict typed delta arrays:

```text
missingCategoryHints[]
  category
  attention
  title
  description
  problemEvidenceIds[]
  comparisonEvidenceIds[]
  unavailableMaterialCategory?

evidenceRepairs[]
  hintId
  problemEvidenceIds[]
  comparisonEvidenceIds[]
  unavailableMaterialCategory?
```

`missingCategoryHints` may contain only categories named by the server as missing and cannot replace
an existing category. `evidenceRepairs` may contain only server-owned repair targets and cannot
contain semantic fields. Extra fields are rejected at schema level.

The server merges added missing-category hints and evidence deltas into the frozen first result,
then reruns the complete category, safety, evidence-ID, operation visibility, unavailable-material,
public reconstruction, and monetary-safety validation. Any remaining defect produces
`MODEL_INVALID_RESULT`; no third semantic answer is allowed.

An unprocessable first top-level response may retain the existing single full-result schema repair,
because there is no valid semantic result to freeze. Once semantic hints have been accepted, missing
category and evidence defects always use delta correction.

## 6. Retry and transport budgets

Budgets remain request-global:

```text
SEMANTIC_RETRY_BUDGET=1
TRANSPORT_RETRY_BUDGET=1
MAX_PROVIDER_CALLS=BASE_OPERATIONS+2
```

Normal analysis uses one model call. Evidence-only, category-only, or combined correction uses at
most one additional semantic call. A retryable transport failure may replay the exact same initial
or correction operation once, including the identical server correction plan. Transport replay
does not create another semantic attempt. No path reaches `BASE+3`.

## 7. Safe observability

Only the following additional structural fields are allowlisted:

```text
evidence_segment_count
citable_segment_count
non_citable_segment_count
required_breach_segment_count
required_breach_citable_segment_count
semantic_defect_types
```

Logs never include source text, source spans' contents, excerpts, prompts, provider responses,
credentials, authorization data, or secrets.

## 8. Mandatory verification

TDD must prove RED before production changes and GREEN afterward for:

1. exact mask isolation with adjacent citable breach text;
2. a sub-1000-character material no longer becoming one non-citable segment;
3. multiple masks in contract text with a later citable breach clause;
4. exact source-span coverage with no loss, duplication, overlap, or reordering;
5. continued rejection of non-citable, invented, and operation-invisible IDs;
6. server-owned stable hint IDs and evidence-only repair preserving all semantic fields;
7. rejection of semantic mutation in an evidence repair;
8. category-only correction when breach is truly missing;
9. combined delta correction handling both defect sets without replacing existing hints;
10. one semantic correction and one independent transport retry across chunks and synthesis;
11. a PM44-03-shaped, secret-free contract/tender/bid fixture with at least one citable breach
    candidate per relevant material before any provider call;
12. public response field, enum, nullability, and semantic equivalence with no internal-ID leak.

After focused and full audit-ai tests, run unchanged Backend consumer tests, Frontend consumer tests
and build, and `git diff --check`. Do not restart any service. Report the requested code gates and
the restart matrix, then wait for the user to restart AI before one synthetic gate and one PM44 03
run. PM44 07 remains gated on PM44 03 PASS.
