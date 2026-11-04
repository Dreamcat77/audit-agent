# Audit AI Provider Reliability Design

## 1. Objective and runtime evidence

This change repairs the contract semantic provider boundary without changing any Backend,
Frontend, database, F02, durable DTO, review-progress, acknowledgement, history, promotion,
OCR-ingestion, or materials-viewer production behavior.

The real PM44 03 failure sequence is authoritative:

```text
FIRST_SEMANTIC_FAILURE=MISSING_REQUIRED_BREACH_CATEGORY
SECOND_SEMANTIC_RESULT=NOT_PRODUCED
SECOND_CALL_FAILURE=CONNECT_ERROR
FINAL_OUTCOME=MODEL_GATEWAY_ERROR
```

The final 502 was not a second evidence-validation failure. The first provider result omitted a
mandatory semantic category; the correction call then failed before producing a semantic result.
The repair therefore has two independent concerns: make mandatory coverage explicit on the first
call, and let one transport retry preserve an already-selected semantic correction.

## 2. Scope freeze

Production changes are restricted to `services/audit-ai/**`. In particular, no production change
is allowed under `backend/src/main/**`, `frontend/src/**`, or `db/**`. F02 deterministic rules,
Contract and Review durable DTOs, ReviewProgress, FindingAcknowledgement, ReviewHistory,
ProjectMaterialsViewer, project/material/contract promotion, and OCR ingestion remain frozen.

If implementation requires a frozen production change, work stops and reports:

```text
SCOPE_EXPANSION_REQUIRED=YES
REQUIRED_SCOPE=<exact paths>
REASON=<why audit-ai cannot preserve the contract>
PUBLIC_CONTRACT_IMPACT=<field and semantic impact>
```

Test execution may exercise frozen consumers, but must not modify their production code.

## 3. Public contract freeze

The provider protocol becomes internal to audit-ai. The HTTP response consumed by Backend retains
the existing envelope and all existing fields. In particular, contract hints retain:

```text
category
attention
title
description
evidenceRefs
evidenceItems
```

Evidence items retain their current field names and enum semantics:

```text
fileId
status = NORMAL | REVIEW | INSUFFICIENT
excerpt
```

No `evidenceId` crosses the audit-ai response boundary. Backend continues to generate the final
public `locatorAvailable` value by validating audit-ai's server-owned excerpt against unredacted
parsed text. The frontend therefore receives the same `fileId`, exact `excerpt`, and
`locatorAvailable` fields without adaptation.

Semantic attention and evidence status remain separate types. Reconstruction uses the existing
mapping only:

- a REVIEW hint's problem evidence has evidence status `REVIEW`;
- an INSUFFICIENT hint's problem evidence has evidence status `INSUFFICIENT`;
- comparison evidence has evidence status `NORMAL`;
- no new evidence-status value is introduced.

## 4. Request-wide evidence catalog

Audit-ai segments every redacted material into immutable exact source segments before model
chunking. Each segment contains:

```text
EvidenceSegment
  evidence_id: str
  file_id: str
  material_category: str
  exact_text: str
  citable: bool
```

IDs are deterministic and request-wide unique. An ID resolves to exactly one immutable segment for
the entire request. IDs do not restart at chunk boundaries. The stable format uses the material
category prefix plus a monotonically increasing request-wide segment number, for example
`C:S1`, `B:S2`, and `T:S3`.

Segmentation is line/paragraph aware and bounded so a citable public excerpt cannot exceed the
existing 1000-character public limit. Each segment is an exact substring of one source material.
Whitespace may be separated at boundaries, but normalized business-text coverage must satisfy:

```text
normalize(original material text)
== normalize(concatenated segment exact_text for that material)
```

All current business text remains visible to the model. Segmentation adds only evidence ID,
file ID, material category, and citable metadata. It must not filter candidate clauses, hide normal
content, or reduce source materials. Mask-only or otherwise unsafe monetary segments remain visible
but are marked non-citable, so the server never reconstructs the mask token as a public excerpt.

The chunk packer accounts for business text separately from bounded metadata overhead. Adding
evidence markers may increase the rendered prompt size, but may not reduce the old accepted
business-text coverage or silently discard text.

## 5. Internal model draft and public reconstruction

The provider returns an internal draft, not the public DTO:

```text
ModelHintDraft
  category
  attention
  title
  description
  problemEvidenceIds[]
  comparisonEvidenceIds[]
  unavailableMaterialCategory?
```

Both evidence-ID arrays accept multiple IDs, including multiple segments from one file. The router
validates every ID against the request-wide catalog and the IDs visible to that operation.
Invented, ambiguous, or non-citable problem IDs are invalid semantic evidence.

For a valid draft, the router reconstructs the existing public hint:

- `evidenceRefs` is the stable, de-duplicated list of file IDs referenced by problem and comparison
  IDs;
- every problem ID becomes an evidence item with the existing problem status and the catalog's
  server-owned exact text;
- every comparison ID becomes an evidence item with status `NORMAL` and server-owned exact text;
- repeated evidence IDs are removed, while distinct segments from the same file remain distinct
  evidence items;
- `OTHER_TEXT` remains a valid category.

An absent-material INSUFFICIENT result may use `unavailableMaterialCategory` only when the category
is objectively absent and at least one valid visible comparison/anchor ID exists. Reconstruction
then preserves the existing truthful unavailable representation with `excerpt=null`. A visible
anomaly must use a citable problem ID; no excerpt is fabricated.

The contract-equivalence fixture

```text
problemEvidenceIds=["C:S1"]
comparisonEvidenceIds=["B:S2","T:S3"]
```

must reconstruct the same category, attention, title, description, evidence reference files, and
evidence-item status/excerpt semantics that the existing Backend and Frontend consume.

## 6. Explicit required categories

Before the first provider call, audit-ai derives operation metadata from server-visible source text.
Every model operation carries both:

```text
allowedCategories=<all existing nine categories>
requiredCategories=<minimum grounded answer set for this operation>
```

`requiredCategories` is never an allowlist. Requiring `BREACH_LIABILITY` must not prevent the model
from also returning CONTRACT_STRUCTURE, PROJECT_IDENTITY, SUPPLIER_IDENTITY, DELIVERY_TERMS,
SERVICE_WARRANTY, SCOPE_AND_SUBCONTRACTING, TERM_TERMINATION, or OTHER_TEXT.

The existing quantified-breach detector supplies `BREACH_LIABILITY` for every analysis operation
whose visible business text requires that category. The router validates the same explicit set, so
prompt and enforcement cannot diverge. Synthesis receives the union of all required analysis
categories and categories whose safe partial results must be preserved.

Canonical presentation slots remain outside audit-ai. A category without a reliable model result
continues to appear through the existing presentation contract as UNASSESSED; audit-ai does not
invent INFO/PASS results to fill slots.

## 7. Independent retry budgets and hard call bound

Retry state is request-scoped:

```text
SEMANTIC_RETRY_BUDGET=1
TRANSPORT_RETRY_BUDGET=1
MAX_PROVIDER_CALLS=BASE_OPERATIONS+2
```

`BASE_OPERATIONS` is the number of analysis chunks plus one only when synthesis is actually called.
All chunks and synthesis share the same two extra budgets. No operation receives private `+2`
allowance, and no execution may reach `BASE+3`.

Semantic attempt and transport attempt are separate coordinates:

```text
semanticAttempt: 1..2
transportAttempt: 1..2
semanticRetryReason: optional semantic correction retained across transport retries
transportRetryReason: optional safe transport reason used only for control/logging
```

Semantic failures include unprocessable typed output, a missing required category, and missing or
invalid problem evidence IDs. Transport failures include retryable connect/read failures, 429/5xx,
invalid provider JSON, and invalid provider response. Non-retryable provider 4xx fails immediately.

For PM44 03 the required state sequence is:

```text
call1 = semanticAttempt 1, transportAttempt 1, normal prompt
call2 = semanticAttempt 2, transportAttempt 1,
        semanticRetryReason=MISSING_REQUIRED_BREACH_CATEGORY
call3 = semanticAttempt 2, transportAttempt 2,
        semanticRetryReason=MISSING_REQUIRED_BREACH_CATEGORY,
        transportRetryReason=CONNECT_ERROR
```

Call 3 replays the same semantic correction. It is not a third semantic attempt and must not revert
to the initial prompt. If both retry budgets are exhausted, the last failure class determines the
safe public error: semantic exhaustion yields `MODEL_INVALID_RESULT`; transport exhaustion yields
`MODEL_GATEWAY_ERROR`.

## 8. Multi-chunk and synthesis behavior

Analysis chunks contain complete segmented business text and operation-visible evidence IDs.
Normalized partial results retain internal evidence IDs. Synthesis receives all safe partial hints
and a bounded server evidence catalog for every cited ID, allowing cross-file and cross-chunk
comparison without asking the model to copy excerpts.

Synthesis may select multiple problem and comparison IDs from the union exposed by partial results.
It cannot invent IDs or access hidden source. Existing safe synthesis compaction and the bounded
fallback remain: if synthesis input cannot fit, the router de-duplicates all safe partial hints
rather than dropping categories or returning a size-related 502.

## 9. Safe observability

Logs remain allowlisted and never contain source text, excerpts, model responses, prompts,
credentials, or authorization headers. Retry logs add only safe counters and enums:

```text
semantic_attempt
transport_attempt
semantic_retry_reason
transport_retry_reason
provider_call_count
semantic_retry_count
transport_retry_count
```

This makes the PM44 03 sequence auditable without exposing customer content.

## 10. Verification and completion gates

TDD must establish RED before each production change and then run focused GREEN tests. Required
regressions prove:

1. request-wide unique immutable evidence IDs;
2. normalized visible-text equivalence and all-material visibility;
3. multiple problem/comparison IDs and OTHER_TEXT compatibility;
4. current public DTO shape, enum values, and value semantics after reconstruction;
5. explicit requiredCategories on the first call without category suppression;
6. the exact PM44 semantic-failure/CONNECT_ERROR/recovery sequence;
7. one shared semantic retry, one shared transport retry, and never more than BASE+2;
8. multi-chunk/synthesis cross-file evidence and fallback coverage;
9. existing monetary filters, unavailable evidence truthfulness, breach completeness, and safe logs.

After focused tests, run the complete audit-ai suite and existing read-only Backend/Frontend
regressions needed to demonstrate consumer compatibility. Inspect the final diff to prove no new
frozen production modification. Do not restart AI, Backend, or Frontend. Report:

```text
RUNTIME_RESTART_REQUIRED: AI=YES/NO BACKEND=YES/NO FRONTEND=YES/NO
```

After the user manually restarts the required service, final real validation is strictly ordered:

```text
synthetic compatibility gate
-> PM44 03 semantic
-> PM44 07 semantic
```

The reliability repair is complete only when all three runtime layers pass.
