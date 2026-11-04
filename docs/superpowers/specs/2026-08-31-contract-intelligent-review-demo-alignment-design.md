# Contract Intelligent Review Demo Alignment Design

## 1. Purpose and approved scope

This increment aligns the existing Project Intake, Project-backed Contract Review, FastAPI model
service, and deterministic F02 review into one truthful demonstration flow. It does not create a
second file intake path, move ProjectFile into ReviewSession, or turn AI into an amount-decision
engine.

The selected approach is a frontend-orchestrated thin slice over existing durable use cases:

1. Project intake keeps using the existing safe ZIP ingestion and durable refresh path.
2. The list page retains a presentation-only snapshot after a proven successful intake.
3. Contract review selects already persisted ProjectFiles.
4. One user action starts the existing F02 use case and the contract semantic AI use case as
   separate promises with separate states.
5. Java redacts monetary source content before it crosses the Contract AI port.
6. Python and the Java HTTP adapter both validate the typed semantic result.

The rejected alternatives are: coupling AI to the F02 transaction, creating a generic model
`ask(prompt)` gateway, and adding another upload or storage subsystem. Those options would weaken
failure isolation, bounded-context ownership, or deterministic authority.

## 2. Invariants

- `Project != Contract != ReviewSession != AuditTask`.
- `Project` remains the aggregate root for intake; ProjectFiles remain durable and reopenable.
- `ProjectFileIngestionService`, `FileStoragePort`, archive security, classifier, idempotency, and
  recovery remain the only package ingestion path.
- The five F02 rules and their START/COMPLETE/FAILURE persistence semantics are unchanged.
- Java `Money`/`BigDecimal` remains the sole authority for amounts, quantity × unit price,
  differences, ratios, and thresholds.
- Contract and Budget semantic analysis remain separate use cases and ports.
- AI output is non-authoritative review assistance and cannot overwrite F01/F02/F04 findings.
- F03 remains exact + Levenshtein deterministic matching; no AI blacklist feature is introduced.
- UI progress is derived from durable ProjectFile facts and actual Promise/use-case state. No timer
  simulates OCR, AI, or rule execution.
- Normal users do not see raw engines, request IDs, UUIDs, internal error codes, or adapter names.
- Technical call metadata is displayed only to `SYSTEM_ADMIN` and is collapsed by default.
- Logs never contain source text, parsed text, prompts, model bodies, credentials, authorization
  headers, or environment values.
- `services/audit-ai` remains a Python 3.11 service with lazy Paddle infrastructure. This change
  does not modify system Python or claim a new Paddle runtime verification.

## 3. End-to-end flow

```text
Project pre-processing
        |
        v
Durable ProjectFile
        |
        v
User selects existing processed materials
        |
        +--------------------------+
        |                          |
        v                          v
AI semantic review         deterministic precise review
(text only)                (money / arithmetic / exact identity)
        |                          |
        +------------+-------------+
                     |
                     v
              review assistance UI
                     |
                     v
                human review
```

### 3.1 Project preprocessing center

`ProjectListPage` remains the Project Management page. Its primary card is presented as “项目资料预处理”.
The existing intake operation still owns the selected ZIP and retained idempotency keys while an
outcome is uncertain. After `getProject()` proves the uploaded root file is terminal and available,
the hook copies only immutable display facts into `completedIntake` and the operation releases its
selected `File` and in-flight identities.

Successful intake stays on the list page. The completion card shows project name/no, total
processed non-package files, text-parser count, OCR count, and a factual failed-material warning.
The only transition to detail is an explicit “查看项目资料” action. Project list rows do not render
the raw Project UUID.

### 3.2 Project materials page

Package roots are removed from normal material cards and summarized once as “原始资料包已归档”.
Material cards expose friendly category/status/method labels, page count, and OCR confidence only
for OCR. Recognized text is in a closed `<details>` element by default. Original content retains the
existing authenticated URL.

The stable presentation mappings are:

| Durable value | Business label |
| --- | --- |
| `DOCX_TEXT` | Word 文本解析 |
| `PDF_TEXT` | PDF 文本解析 |
| `OCR` | OCR 文字识别 |

OCR confidence is formatted as a percentage with one decimal place. Raw `parseEngine`,
`parseDurationMs`, storage identity, and UUIDs are not part of normal material presentation.
Candidate and formal sections are titled “合同信息识别结果” and “已确认合同信息”. Missing supplier
integration placeholders are omitted.

### 3.3 Material selection

Project-backed Contract Review receives typed material facts rather than deciding eligibility from
Chinese labels. A material is semantic-eligible only when all of these facts hold:

- it belongs to the current project;
- category is not `PACKAGE`;
- ingestion status is `AVAILABLE`;
- parse status is `PARSED`;
- persisted parsed text is nonblank.

All eligible materials are selected by default. Users can deselect them; zero selection disables
the main action. Missing contract body produces a supplement warning and blocks intelligent review.
Missing cross-material sources produces a non-blocking “依据不足” warning.

## 4. Contract AI API and application boundary

The HTTP path remains:

```text
POST /api/v1/projects/{projectId}/ai/contract-text-analysis
```

Its JSON request is:

```json
{"fileIds":["00000000-0000-0000-0000-000000000001"]}
```

The controller accepts 1–50 unique UUIDs and calls
`ContractTextAnalysisUseCase.analyze(UUID projectId, List<UUID> selectedFileIds)`. The use case
loads project-owned files, rejects rather than skips every invalid selection, preserves caller
order, reopens the persisted parsed text, requires it to be nonblank, redacts monetary content, and
constructs the existing typed `ContractTextAnalysisRequest`.

Foreign IDs, PACKAGE files, pending/failed ingestion, non-PARSED files, empty text, duplicates, and
an empty selection are business validation failures. No invalid selection is silently ignored.

## 5. Programmatic monetary isolation

`ContractAiTextRedactor` is an application component executed before the Contract AI port. It
replaces labelled amount/price values and Chinese uppercase RMB values with
`[金额由确定性规则处理]`. It preserves project/contract identifiers, dates, credit codes, model
numbers, quantities without price inputs, and normal clauses.

For the approved regression fixture, downstream text must not contain `407000.00`,
`人民币肆拾万柒仟元整`, `5000.00`, or `500000.00`, while it must retain the project name,
`CG-2026-002`, `91310000TEST00001X`, and `20个工作日`.

Python receives already sanitized text and is additionally instructed never to infer or restore
masked monetary values. Defense is layered: Java prevents source exposure; Python validates model
output; Java validates the downstream envelope and evidence allowlist.

## 6. Typed semantic result and evidence integrity

Contract hints become typed semantic checks:

- categories: `PROJECT_IDENTITY`, `SUPPLIER_IDENTITY`, `DELIVERY_TERMS`, `SERVICE_WARRANTY`,
  `SCOPE_AND_SUBCONTRACTING`, `OTHER_TEXT`;
- attention: `INFO`, `REVIEW`, `INSUFFICIENT`;
- fields: category, attention, title, description, evidenceRefs.

Every `evidenceRef` must exactly equal a selected material `fileId`. Python rejects invented
references before responding; `FastApiAiReviewAdapter` rechecks them against the originating
request. The frontend resolves references to friendly filenames and never shows UUIDs as evidence
labels.

Python treats strong legal/audit verdicts and monetary calculation/conclusion language as
`MODEL_INVALID_RESULT`. It does not silently rewrite dangerous model output. Budget keeps its
existing typed item schema and validation path.

## 7. Review orchestration and UI state

`ContractReviewWorkspace` delegates focused responsibilities to:

- `ProjectReviewMaterialPicker`: typed eligibility, selection, and material sufficiency copy;
- `IntelligentReviewPipeline`: factual status of materials, AI, and deterministic review;
- `AiSemanticReviewPanel`: semantic categories, supportive wording, and friendly evidence sources;
- `TechnicalCallDetails`: admin-only collapsed provenance.

The main “开始智能审核” handler invokes deterministic review once and semantic analysis once.
The promises are started independently and settled independently. AI failure leaves F02 state and
result untouched; F02 failure leaves a completed AI result visible with an explicit precise-check
failure message. AI loading lasts exactly as long as the actual request.

F02 result presentation groups the four monetary rules under “金额与明细精确校验” and the supplier
rule under “主体身份精确核对”. Stored `RiskLevel`, `CheckStatus`, and rule facts remain unchanged;
only user-facing words map FAIL to “需复核”, NOT_EVALUATED to “材料不足/暂无法判断”, and blocking
facts to “建议优先复核”.

## 8. Safe observability and configuration

Java `FlowDebugLogger.logAiAnalysis` accepts only safe identifiers and non-negative numeric
`selectedFileCount`/`checkCount` metadata. The controller logs operation, project resource ID,
outcome, model, duration, selected count, and semantic check count. Request ID remains correlated
through MDC and is not copied into normal UI.

Python safe logging adds numeric-only `material_count`, `chunk_count`, `input_chars`, and
`output_hint_count` fields. Unsupported names such as `source_text`, `prompt`, and `api_key` remain
rejected by the allowlist.

`application-local-ai.yml` externalizes AI/OCR connect and read timeouts, with demo-safe defaults of
5000/60000 ms for AI and 5000/120000 ms for OCR. No IP, path, credential, or token is committed.

## 9. Error and recovery behavior

- Project intake uncertainty continues to retain one create/upload identity and the selected ZIP.
- Proven terminal intake failure allows a replacement operation with new identities.
- Contract AI validation errors return the repository's normal business error response.
- Downstream schema, semantic-safety, or evidence failures become the existing safe external service
  error; the downstream body is not exposed.
- Model not configured remains a real unavailable response/error; no fake suggestions are created.
- F02 durable idempotency and recovery behavior is not altered by the frontend orchestration.

## 10. Verification boundary

Automated gates cover backend selected-file validation/redaction/adapter/controller/logging, Python
schema and semantic safety, frontend completion/material selection/orchestration/presentation, the
unchanged F02 suite, Budget semantic regression, OCR contract regression, full frontend tests and
build, and secret/diff scans.

The existing manual real Paddle and Qwen evidence is not rerun destructively. If model variables are
not already set in the current process, runtime status is
`REAL_MODEL_SMOKE_NOT_RUN_IN_THIS_PROCESS`. Automated fake/Mock results are never reported as real
OCR or model output.
