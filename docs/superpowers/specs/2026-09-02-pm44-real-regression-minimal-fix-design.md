# PM44 Real Regression Minimal Fix Design

## Scope

Repair the confirmed PM44 regressions without redesigning contract review or replacing the existing
viewer, acknowledgement, history, deterministic F02, breach completeness, or monetary-safety
boundaries. The real PM44 matrix remains the acceptance authority; committed tests may reproduce its
structures but must not commit the supplied customer test files.

## A. Typed DOCX table facts

`ParsedProjectDocument` will carry typed, non-persistent table metadata in addition to its existing
flat text segments. A table contains rows and a row contains cells; every level retains its own
`sourceLocator`. Existing segments and `fullText()` remain unchanged so parsed-text persistence,
source display, AI redaction, and highlighting do not receive duplicated text.

`ProjectDocxTextParser` will populate the typed structure directly from `XWPFTable`,
`XWPFTableRow`, and `XWPFTableCell`. `ProjectFactExtractor` will recognize a line-item table only
when one header row maps all five required columns: item key, item name, quantity, unit price, and
declared amount. It will read subsequent cells directly by mapped column index and produce the five
existing `LINE_ITEM_*` fact types. It must not concatenate a whole table and run the legacy line-item
regex. The legacy paragraph/PDF fixture syntax remains supported.

No database schema, durable Contract DTO, or F02 rule changes are allowed. Candidate, promotion,
DM8 persistence, GET contract, and F02 continue to consume the existing line-item model.

## B. Canonical audit-slot presentation

The presentation layer owns the stable review scope. Section 4.1 always contains these slots:

- `CONTRACT_STRUCTURE`
- `PROJECT_IDENTITY`
- `SUPPLIER_IDENTITY`
- `DELIVERY_TERMS`
- `SERVICE_WARRANTY`
- `SCOPE_AND_SUBCONTRACTING`

Sections 4.2 and 4.3 contain `BREACH_LIABILITY` and `TERM_TERMINATION`. A slot may contain multiple
model hints. `OTHER_TEXT` remains available as an additional slot when the model returns that
category.

Slot completeness uses a presentation-only state: `ASSESSED` when at least one model hint exists and
`UNASSESSED` otherwise. `UNASSESSED` means “未形成可靠判断”; it is not AI `attention`, F02
`NOT_EVALUATED`, INFO, PASS, or a risk verdict.

The main semantic result remains the sole owner of semantic REVIEW/INFO/INSUFFICIENT conclusions.
The deterministic term helper may expose already-detected facts and exact problem evidence inside
the term slot while the slot remains `UNASSESSED`; it cannot manufacture a semantic hint.

## C. Problem evidence and locator truth

The AI gateway prompt will require every REVIEW/INSUFFICIENT hint about visible material to provide a
problem `evidenceItem` with an allowed `fileId` and a short exact excerpt. A blank field must quote
the visible label and blank placeholder. If an entire required material is missing or there is
objectively no excerpt, the provider may return an explicit INSUFFICIENT evidence item with a null
excerpt; the UI then reports locator unavailable.

An unavailable locator is not a model assertion. For an absent required material the provider must
name the missing known category in the transport-only `unavailableMaterialCategory` field, and the
router accepts it only when that category is actually absent from the request. A null excerpt is
also accepted when the referenced server input is objectively blank. Present nonblank input never
becomes locator-unavailable merely because the model omitted or invented an excerpt. The hint must
also explicitly describe the same missing material category; it cannot borrow an unrelated absent
category to bypass exact evidence for a visible anomaly. For fail-closed separation, the hint title
itself must be a dedicated, affirmative missing-material expression; cross-field word matching,
negated absence, and mixed visible-anomaly titles are rejected.

The Python router validates file membership and verifies every non-null excerpt against the visible
text using NFKC plus whitespace-insensitive matching. An invented excerpt or an omitted problem item
for a REVIEW/INSUFFICIENT hint is a completeness violation. It consumes the same bounded retry gate
already used by contract analysis: at most one retry for the request and at most two attempts for an
operation. `MISSING_REQUIRED_BREACH_CATEGORY` is checked first and keeps its existing reason and
fail-closed behavior. A second invalid result is `MODEL_INVALID_RESULT`; no nested or presentation
retry is added.

Java remains the final locator authority. The FastAPI adapter ignores provider locator claims and
the Java use case sets `locatorAvailable` only after matching against real unredacted server input.
Term-helper excerpts originate from a server-side regex match against the same parsed material; the
helper response will explicitly mark those matched excerpts locatable. The frontend may consume
that flag but may not infer it. Event-effectiveness helper detection is conservative and directional:
signing/sealing must precede effectiveness in the same bounded clause, so text that merely discusses
signing after the contract is already effective does not create a helper fact.

## D. Verification boundaries

Each defect follows red-green-refactor discipline: failing regression, observed failure, minimal
production change, focused tests, and cross-feature tests. Required regressions cover PM44-like 01
three-row ingestion and five F02 PASS categories; PM44-like 05 five risk categories; breach
completeness and one-retry behavior; 04 blank signing date, event-based effectiveness, and exact
problem evidence; 07 service/warranty conflict evidence; acknowledgement persistence; history reload;
monetary isolation; and exact/paragraph/unavailable viewer behavior.

Code-level tests are distinct from real runtime and PM44 E2E. The user-managed AI, backend, and
frontend processes are never stopped or restarted by the implementation. After code verification,
report `RUNTIME_RESTART_REQUIRED` and wait for the user to restart before final runtime validation.
Until authenticated runtime access exists, real write E2E remains `AUTH_NOT_AVAILABLE` and must not
be inferred from fixtures.
