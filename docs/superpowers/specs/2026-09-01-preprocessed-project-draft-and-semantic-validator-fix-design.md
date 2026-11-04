# Preprocessed Project Draft and Semantic Validator Fix Design

## Purpose

This approved P0 correction separates a preprocessed Project intake draft from a formal business
Project without creating another aggregate, file table, upload path, or storage subsystem. It also
removes the design conflict between Java monetary redaction and Python semantic-output validation
that caused a real `MODEL_INVALID_RESULT` response from qwen-plus.

The correction stays on `feature/ai-ocr-project-intake-demo` and does not deploy any environment.

## Verified root causes

### Project lifecycle

Production Project writes have exactly two lifecycle transitions:

1. `Project.blank(...)` inserts `versionNo == 0`.
2. `Project.confirmMetadata(...)` is the only method that increments Project version, through the
   only DM8 `PROJECT` UPDATE (`ProjectMapper.compareAndSet`).

Package upload, archive expansion, parsing, OCR, fact extraction, and candidate assembly update
ProjectFile/extraction/candidate records but never Project version. Therefore `versionNo == 0`
reliably means metadata has never been confirmed, and `versionNo > 0` means it has been confirmed
at least once. The domain exposes this as `Project.metadataConfirmed()`; clients consume the typed
projection and do not repeat the version comparison.

The current bug has two direct causes: the repository returns every Project from `GET /projects`,
and `useProjectList` inserts a completed blank intake Project into the same frontend collection.

### Model validation

Java currently changes `合同金额：407000` into
`合同金额：[金额由确定性规则处理]`, retaining monetary labels and adding the word `金额`.
Python `_MONETARY_OUTPUT` treats bare nouns such as `金额`, `预算`, `报价`, and `人民币` as a
sufficient reason to reject an otherwise valid typed result. A safe sentence such as
`本项不涉及金额判断` therefore returns 502 `MODEL_INVALID_RESULT`.

## Project projection design

`Project` remains the aggregate root and ProjectFile remains owned by the same Project ID.
No schema change is required.

- `GET /api/v1/projects` returns only Projects for which `metadataConfirmed()` is true.
- `GET /api/v1/projects/intake-drafts` returns a paged `IntakeDraft` projection for unconfirmed
  Projects that own at least one `PACKAGE` ProjectFile.
- The draft projection contains the Project identity, package filename, durable intake status,
  parsed/OCR/failed counts, unique non-conflicting extracted `PROJECT_NAME`/`PROJECT_NO`
  suggestions, and creation time.
- A blank Project created by the secondary path has no package and is not presented as a
  preprocessed batch. Its direct detail route remains available to the user who just created it.
- After metadata confirmation the same Project changes from version 0 to version 1+, disappears
  from intake drafts, appears in formal projects, and retains every existing ProjectFile ID.

The Project Management page has three explicit sections: Project preprocessing, preprocessed
materials, and formal projects. “查看资料” and “用于新建项目” both reopen the durable Project;
the latter leads to the existing metadata confirmation UI populated from durable extraction facts.

## Monetary isolation design

Java replaces each labelled monetary field and value as one unit with
`[确定性数值字段已移除]`. It removes labels such as contract amount, unit price, declared amount,
winning amount, budget amount, and quotation when they introduce a monetary value. It preserves
project/contract identities, supplier/credit code, dates, delivery, warranty, scope, model, item
name, and quantity.

The gateway system prompt states that monetary fields were removed and the model must not
proactively discuss, infer, restore, compare, or calculate them.

Python validates model output using separate fail-closed detectors:

1. concrete currency values, including symbols, values followed by 元, and Chinese uppercase RMB;
2. arithmetic expressions and percentages;
3. monetary comparison/conclusion language, such as amount differences, thresholds, savings,
   quotation comparisons, or budget balances;
4. strong audit/legal verdict language.

Bare monetary nouns and explicit disclaimers such as `本项不涉及金额判断` are allowed. Dangerous
output is rejected, never rewritten.

## Safe rejection observability

Every contract-model rejection logs only allowlisted metadata with one reason:

- `INVALID_EVIDENCE_REF`
- `STRONG_VERDICT`
- `MONETARY_VALUE`
- `MONETARY_CALCULATION`
- `INVALID_TYPED_SCHEMA`

Allowed accompanying fields are operation, reason, chunk index/count, model, and duration. Source
text, prompts, response bodies, credentials, and authorization are never logged. The external
response remains the stable 502 `MODEL_INVALID_RESULT` envelope and does not expose the reason.

## Verification

Tests prove draft/formal projection transitions, same-ID/file reuse, frontend three-section
presentation and endpoint use, neutral Java redaction, safe disclaimers, all requested unsafe model
fixtures, reason logging, evidence/strong-verdict rejection, four-material and multi-chunk results,
and Budget/OCR regression. Full backend, frontend, build, and Python 3.11 gates remain mandatory.

Real qwen-plus retesting is not represented by fakes. Without model configuration in this process,
the final status is `REAL_QWEN_RETEST_PENDING_USER_RUNTIME`.
