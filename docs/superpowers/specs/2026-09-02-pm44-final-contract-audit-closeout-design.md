# PM44 Final Contract Audit Closeout Design

## Goal

Close the existing Project Intake → Contract Promotion → F02 → semantic/clause review flow for the
2026-09-02 customer demonstration. The implementation reuses the current bounded contexts and
durable projections; it does not introduce a new project model, a fake dashboard, or a model-owned
business verdict.

## Product flow

The integrated contract workspace has exactly five top-level progress stages:

1. `MATERIAL_SELECTION` — 审核材料
2. `MATERIAL_READING` — 材料读取
3. `DETERMINISTIC` — 精确校验
4. `AI_REVIEW_GROUP` — 智能语义审查
5. `ASSISTANCE` — 审核建议

The fourth stage owns three visible sub-branches: semantic comparison, breach clause review, and
term/termination review. It becomes terminal only when all three branches are terminal. A failed
semantic branch plus two successful deterministic clause branches is `ATTENTION`; the assistance
stage still completes with truthful review guidance. F02 and the three fourth-stage branches start
concurrently, while the DOM keeps the deterministic result before the grouped branches.

## Model trust boundary

Python accepts provider output through a tolerant normalization layer. Unknown object fields are
ignored; category and attention aliases are normalized; each hint is independently filtered for
shape, evidence ownership, strong verdicts, and monetary calculations. Invalid evidence entries are
removed rather than replaced, and a hint with no valid selected-file evidence is removed. Safe hints
survive beside unsafe hints. Unsafe summaries are replaced with a neutral summary. A structurally
processable result with zero remaining hints is still HTTP 200 and `available=true`.

Retry is reserved for transport/provider failures, invalid JSON, a non-object provider result, or a
top-level hints value that cannot be processed. Schema drift and per-hint safety filtering do not
consume the single retry budget. Python never publishes `locatorAvailable`.

Java owns locator truth. The application service keeps the real selected `ProjectFile.parsedText`,
passes only redacted model-visible text through the AI port, and then computes locator availability
against the real parsed text using NFKC plus whitespace normalization. A valid selected-file
evidence item remains useful at file level even when its excerpt cannot be located.

## Evidence interaction

Normal semantic items show a concise one-line list of real comparison-source filenames. Review
items expand the comparison sources, problem material, and exact excerpt. Every source action opens
the actual ProjectFile. Verified excerpts use the existing parsed-text/OCR text viewer, scroll to the
center, and render an amber `<mark>`. The selection remains until another source is chosen or the
user clears it. When a locator is unavailable, the file still opens and the UI states exactly:
“已打开来源材料，未能自动定位到具体文字。” No fallback paragraph is highlighted as if it were
verified.

F02 and deterministic clause panels use the same customer vocabulary (`对比来源`, `问题材料`,
`原文摘录`) whenever the underlying API has real source identities. They never invent a second
source merely to make a comparison look richer.

## Deterministic clauses and existing product slices

The breach analyzer keeps a semicolon-delimited rate and cap in the same clause context. For a
67,000 yuan contract, `1%/周` produces 670 yuan per week and an explicit `20%` cap produces 13,400
yuan. The quality/service scenario remains distinct from delay. The term analyzer continues to
recognize supported Chinese, slash, and ISO dates plus signing/effective/termination conditions.

The current real contract-task dashboard/filter flow and Project supplement upload flow remain the
source of truth. Regression tests lock real API usage, requested filters, five material-completeness
categories, ordinary PDF/DOCX/PNG/JPG/JPEG supplements, sequential processing, partial-success
retention, and ZIP rejection. Customer copy omits implementation/provider/request details.

## Failure and verification policy

AI unavailability never discards completed F02 or clause results. No synthetic material, result,
highlight, dashboard count, or successful upload is shown. Backend technical logs retain safe
correlation metadata without source/model output or credentials.

Completion requires fresh backend `clean verify`, Python 3.11 pytest, all frontend tests and build,
diff inspection, and a secret/customer-copy scan. A real Qwen pass runs only when the already
protected runtime is available without reading or printing secrets; otherwise it is reported as
`REAL_QWEN_RETEST_PENDING_USER_RUNTIME`.
