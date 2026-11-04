# ADR-009: Project intake and PM product alignment

> **Superseded on 2026-08-31.** The historical gate below explains why Project/ZIP was once
> blocked. Project Intake, secure ZIP ingestion, Project-to-Contract ownership, and durable finding
> acknowledgement were subsequently implemented. The current AI/OCR Project Intake decision is
> `docs/superpowers/specs/2026-08-31-ai-ocr-project-intake-demo-design.md`.

## Context

The current durable model starts at `Contract`. Phase B added contract-owned material preparation
through `ContractReviewSession`, while the existing direct F02 endpoint creates an `AuditTask` for
one deterministic five-rule execution. Product screens also need to discuss a future project-level
intake and ZIP package without presenting those future facts as if they already existed.

No approved project-material template, directory convention, document-classification rule, Office
parser scope, or Project persistence root is available in this repository. Implementing a ZIP path
now would therefore invent both business classification and security policy.

The earlier V005 implementation also exposed ReviewSession deletion. Product review has withdrawn
that user-visible workflow while retaining already-imported V005 schema and historical tombstones.

## Decision

- `Project`, `Contract`, `ReviewSession`, and `AuditTask` remain distinct semantic identities.
- A future `Project` should be an upstream aggregate in the `contract` bounded context and own one
  or more `Contract` records. This is an ownership proposal, not an implemented schema or API.
- `ReviewSession` remains a process for preparing and confirming materials for exactly one Contract.
  Its name is an “内审任务名称”, never a project name alias.
- `AuditTask` remains one concrete rule execution. READY ReviewSessions do not synthesize AuditTasks
  or read the legacy contract-level `/agent-results` projection.
- The future primary intake is a Project package. The current “从已录入合同创建” flow is an
  explicitly transitional entry for contracts already present in the system; it does not implement
  Project or ZIP intake.
- Current real READY projections do not invent project facts. They render “—” or hide the Project
  section. Fixed
  project names/numbers are allowed only in deterministic presentation fixtures and remain separate
  from contract numbers, bid/tender numbers, and award-notice numbers.
- The current product exposes no ReviewSession delete controller mapping, DataSource operation, UI
  action, or deletion dialog. V005 stays unchanged for installed-schema and historical-data
  compatibility. Existing tombstones remain filtered, and internal repository/CAS paths may remain
  as unreachable compatibility code.
- A ZIP is a future Project Intake Package, not a `ReviewDocumentKind.OTHER` upload. It requires an
  archive boundary that rejects path traversal, absolute paths, symbolic links, duplicate normalized
  paths, excessive entry count/depth/uncompressed size/compression ratio, and unsupported types
  before any file can become business material.
- Template validation and automatic document classification must wait for approved formal project
  templates, directory conventions, category semantics, and Office/text parser requirements.
- Finding acknowledgement is a future business fact separate from durable operation audit. A design
  must persist a durable finding identity, acknowledging user/time, request/trace IDs, result version,
  an idempotency key, and replay/concurrency semantics. This ADR does not add that schema or API.

## Gate result

`OWNERSHIP_DECISION = CONTRACT`

`PROJECT_ZIP_GATE = BLOCKED`

Consequently this change adds no V006, Project table, ZIP endpoint, extractor, classifier, template
validator, OCR/AI/Office parser, processing worker, session-driven F02, finding acknowledgement, or
session-scoped review result.

## Consequences

The current UI can align its terminology and presentation without pretending that project-level
facts are durable. ReviewSession continues to stop at READY, direct F02 continues to own its five
deterministic rules, and later Project/ZIP work must return through a separate architecture gate
with formal input examples and archive security tests.

## Status

Superseded. Retained as architecture history; its `PROJECT_ZIP_GATE = BLOCKED` result is not a
current implementation constraint.
