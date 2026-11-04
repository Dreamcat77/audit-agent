# Procurement spreadsheet analysis correction — 2026-10-19

Scope: budget/procurement shared spreadsheet reader, procurement material analysis and its existing status area. Contract business logic, deployment configuration, AI credentials and gateway settings are unchanged.

## Reproduced failure

The previously uploaded synthetic `合成大报价.xlsx` is 83,887,942 bytes. Its budget rows do not establish supplier quotations. Before this correction, an independent invocation of the deployed quotation adapter failed with `ENTRY_SIZE_LIMIT`: upload accepted the file, but the reader imposed an 8 MiB ZIP entry limit and 32 MiB expanded limit. The analysis request returned HTTP 422 before any model request. The page replaced this with a generic incomplete-analysis/retry message.

## Changes

- Shared 500 MiB original-file boundary and bounded temporary-file spooling replace the quotation chain's 100 MiB heap buffers. Temporary files are deleted on success/failure.
- The existing archive verifier still checks CRC, path traversal, collisions, encryption, entry count and compression ratios. Spreadsheet-specific expanded-content and XML-memory limits remain explicit; exceeding them fails rather than silently dropping content.
- Office text reading covers each worksheet before classification. Recognized standard quotations retain the established deterministic comparison. Nonstandard spreadsheets use the existing durable, segmented model extraction service under the distinct `PROCUREMENT_REVIEW` operation.
- Source evidence is checked against the original physical row, not merely generated row keys or header context. Supplier, quoted amount, column meaning and source units must agree. Budget/approval amounts do not become vendor offers. Unaccounted quotation rows prevent publication of partial quotation facts.
- Useful extracted project/material fields and original text are persisted with the source document. Known parse failures, unconfirmed model outcomes, processing and non-quotation results have different persisted markers. A stale processing marker reopens as unconfirmed, never as an endless confirmed-running state.
- A failed spreadsheet does not stop later files. A batch with a known failed or unconfirmed spreadsheet does not generate a complete report. Refresh only reads saved facts and does not issue model calls.
- The existing frontend area displays these statuses and grounded information. Layout and Chrome 109 color/font compatibility are retained. Completion navigation requires a real saved report.
- A second real-platform blocker was found after the original large workbook successfully completed model extraction: the legacy report workflow required a completed quotation/tender/collusion step, even for a proven non-quotation material. The material-scoped report path now verifies the frozen, nonempty source list and all readiness guards before completing only the REPORT step in the report transaction. It does not invent quotation or tender completion. The unscoped report endpoint retains its prerequisite guard. A non-quotation report explicitly states that no price comparison was performed.

No new database migration is required. Existing V016 SQL and business records are not rewritten.

## Local executable verification

- Windows / JDK 17: `cd backend; .\mvnw.cmd clean verify` — final v2 run: 2403 tests, 0 failures, 0 errors, 2 existing contract-fixture skips; BUILD SUCCESS. Log: `.runtime/spreadsheet-v2-clean-verify.log`. New report regressions exercise non-quotation completion without fabricated auxiliary-step completion and fail closed for processing, unknown, failed, empty or changed material scope.
- Python 3.11 test virtual environment: `.runtime/office-python-test/Scripts/python.exe -m pytest services/audit-ai/tests -q` — 547 passed, 1 existing Windows symlink skip.
- `cd frontend; npm run build` — PASS.
- Procurement frontend regressions through `node --test --experimental-strip-types` — 9 passed.
- `node .runtime/spreadsheet-ui.mjs` — real Chromium 109.0.5412.0 running the production component against controlled HTTP fixtures: processing indicator, separate failure/non-quotation messages, grounded-field display, no completion callback without a report, status preservation after refresh, no horizontal overflow; 0 browser errors. This browser check uses controlled responses, not the 26 model service.
- The quotation adapter regression writes an 80 MiB stored binary member into a valid XLSX, then actually previews and reads the quotations. Both succeed. Path traversal and a compressed bomb still fail closed.
- Adversarial tests cover mismatched budget/quote roles, a supplier in another row, an omitted quote row, foreign model evidence, unknown outcomes, reopening success without model calls, multiple worksheets and continuing a batch after one read failure.

## Representative platform verification

26 deployment/model/DM8 verification: PASS on the v2 candidate. The first candidate successfully read/classified the original workbook but returned HTTP 409 at the report workflow guard; v2 resolves that guard without fabricating completed quotation/tender steps.

Executed with the deployed JDK and runtime: `java -cp "$sheet_cp" BudgetBackgroundRunner "$sheet_v2/spreadsheet-probe.py"`. Exit 0; terminal result `PROCUREMENT_SPREADSHEET_REAL_MODEL_HTTP_DM8=PASS`. The synthetic test actor was disabled on completion; test tasks/audit history remain.

- Original 83,887,942-byte synthetic budget workbook: NON_QUOTATION, useful fields persisted, no quotation invented, saved reference report produced; 0.09 seconds by reusing the completed extraction. New-session reopen preserves its classification. Read-only DM8 inspection confirms AVAILABLE / NON_QUOTATION and a new full report snapshot; REPORT is COMPLETED while quotation/collusion remain PENDING and tender has no completed timestamp.
- Genuine synthetic 80 MiB padded quotation: resumable 4 MiB parts, QUOTE_ANALYZED, all 3 vendors retained, uppercase amount equals 178200, saved report; analysis 25.63 seconds (transfer time excluded).
- Nonstandard title/header plus two worksheets: real model extraction, all 3 vendors, exact 178200 / 181000 / 179500 amounts after Chinese uppercase and 万元 conversion; saved report; analysis 46.32 seconds.
- Corrupt workbook plus valid budget workbook in one upload: PARSE_FAILED and NON_QUOTATION independently; good file continues, no complete report is published; new-session reopen retains both states; analysis 6.91 seconds.
- Shared budget reader regression: real upload/background processing completes with uppercase declared amount 120000 and approval-reference amount 100000.

Candidate package hash: `2173af980bcf9e877f3e8d8878ea9867861c2ada6684c599076b9f6f53f532d2`.
Running backend hash: `42fa219bd114d0713b27918603eaa6002e0c6d638ba7127f8973553158e86d44` (matches locally built artifact).
Running release: `/opt/audit-agent/releases/spreadsheet-fix-v2-20261019.eVpH3K`.
Recovery record: `/opt/audit-agent/update-backups/spreadsheet-fix-v2-20261019.y37163`.
Server acceptance log: `/home/admin/spreadsheet-fix26-v2-20261019.ysboDf/acceptance-v2.log`.
V052–V056 preflight: matching. Backend/AI health HTTP 200, unauthenticated procurement HTTP 401, all 3 referenced frontend HTTP assets match deployed bytes. Configuration hashes unchanged; no dependency upgrades or new schema migrations.

Customer ARM/offline deployment is NOT VERIFIED by these tests; the runtime checks above apply to the representative 26 platform.

## Practical limits

500 MiB is the original-file upload/read boundary, not a promise to materialize unlimited spreadsheet cells, embedded objects or decompressed XML. There are still explicit limits on text, worksheets, expanded size, model calls and total time. Unsupported or unsafe content produces a failure/unconfirmed state rather than a clean or non-quotation conclusion. Model-derived fields are advisory and source-grounded; exact monetary parsing and comparison remain Java `BigDecimal` operations.

Working tree contains earlier authorized changes. This evidence does not imply a new commit or GitHub push.
