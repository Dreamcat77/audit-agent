# Budget project reference results — 2026-10-14

## Scope and behavior

Base application: `e17a1d3eca75c402c371f2adb58d0d59676f4b84`.
Working branch: `fix/budget-home-split-risk-20261014`.

New budget background uploads continuously check every extracted row, retaining
exceptions without generating manual confirmation facts. The original interactive
review/confirmation API and previously persisted results remain supported. In a
reference report, different subjects of the same project are valid; repeated
project/subject/supplier combinations retain a warning.

The default result and historical result views group exact project numbers within
budget year and department. Unidentified or invalid project associations remain
separate and are described as requiring verification. Identical facts/check outcomes
share source references; conflicting amounts remain visible and are never summed.
Results use Chinese reference statements rather than internal rule/status names.
Detailed upload progress and the per-file result selector are removed. Existing
fonts, theme, upload recovery, evidence storage and split-risk homepage visibility
are retained. No database migration, AI configuration or model protocol changes.

Implementation:

- `frontend/src/features/budget/BudgetProjectResults.tsx`
- `frontend/src/features/budget/budgetProjectGrouping.ts`
- `frontend/src/app/BudgetCheckPage.tsx`
- `backend/src/main/java/com/fuquan/audit/budget/application/BudgetReviewService.java`
- `backend/src/main/java/com/fuquan/audit/budget/application/BudgetMaterialService.java`

## Executable verification

Windows, JDK 17.0.18, backend directory:

```powershell
.\mvnw.cmd clean verify
```

Observed final result: **2198 tests; 0 failures; 0 errors; 2 skipped; BUILD SUCCESS**.
Regression coverage includes all rows after an invalid first row, preserved
over-budget facts, ambiguous approval not compared, different subjects under one
project, no automatic confirmation facts, and lease loss refusing result writes.
The lease is checked at the short result commit, not during external computation.

Node 24, worktree root:

```powershell
node --experimental-strip-types --test frontend/src/features/budget/budgetProjectResults.test.ts frontend/src/features/budget/budgetAuditWorkspaceStaticContract.test.ts
```

Observed: **7 passed, 0 failed**. Covers provenance, conflicting amounts,
unidentified projects, different years/departments, incomplete and unknown results,
and Chinese descriptions without raw exception contents.

Production frontend built with Vite 6.4.4 and
`tools/build-procurement-browser.mjs`. Actual browser verification:

```powershell
node tools/test-budget-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/frontend-overall-final-compatible budget-overall-v3-109
node tools/test-budget-material-background.mjs C:/Users/29339/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe .runtime/frontend-overall-final-compatible budget-overall-v3-151
node tools/test-procurement-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/frontend-overall-v2-compatible procurement-overall-v2-109
```

Observed versions: **109.0.5412.0**, **151.0.7922.34**; no page errors.
Budget tests exercise 12 files including a 21 MB file and directory drag, a failed
file, lost upload response, navigation/reload with immutable identities, project
grouping across files, one file with multiple projects, merged duplicate provenance,
return/navigation races, subsequent upload, all historical pages, history without
browser storage, Chinese spreadsheet export, and unavailable history not shown as
empty. Procurement regression also passes.

These browser workflows use HTTP fixtures. They are not evidence of customer model
success or customer ARM font rendering.

## Representative platform

26 test server: Kylin V10 x86_64, JDK 17, DM8, Redis, existing AI service.
The first real DM8 smoke test identified an added lease check outside its required
transaction. That candidate was rolled back to the prior release before proceeding.
The corrected code retains the existing `MANDATORY` lease check inside the short
coordinator commit; no transaction is held during parsing or model calls.

Final deployment/real HTTP outcome: **PASS**. The real test used the existing
`BudgetBackgroundRunner` with credentials generated in memory and passed through an
anonymous pipe to `/home/admin/budget-overall-http-20261014.py`.

Observed: 3 synthetic files; corrupt-file isolation; all 3 rows of each valid file
reviewed and persisted; 2 project numbers per valid file; different subjects do not
trigger a duplicate-project warning; exceptions remain and no manual confirmation
facts exist; replay reopens the same immutable identities; a new browser session
can rediscover all results through the history API. The generated test identity was
disabled after verification. Test audit/task facts are retained.

Final current release: `/opt/audit-agent/releases/budget-overall-v3-20261014.ondjNV`.
Recovery record: `/opt/audit-agent/update-backups/budget-overall-v3-20261014.2V2PCW`.
Backend JAR SHA-256: `c3da1eda9ee0312105e228ea268caaab7113caaecf7516d8e38090fdb5b7360f`.
All 6 packaged frontend resource HTTP hashes match. Backend and AI are active;
health response contains `code=200`, `data.status=UP`. AI health is `UP`.
AI, DB, Redis, Nginx and unit configuration hashes are unchanged. The final frontend
switch retained running backend/AI PIDs. Only the corrected backend rollout earlier
required a backend restart.

The model auxiliary calls showed gateway errors/timeouts as well as completed
requests. This is **not** evidence that model connectivity is fixed; deterministic
budget review continued to completion while retaining uncertainties and exceptions.

Test-server patch: `Budget_Overall_v3.tar.gz`, flat archive (no nested archive).
SHA-256: `74b9ab9b55aadded350b6e8c9d6ed801ef62c6fb09e2d2b97076a471cfd67b19`.
Packaged SQL resource and font/shim bytes match the prior application baseline.
No environment files, credentials or business materials are included.

Customer ARM installation/fonts and customer model gateway: **NOT VERIFIED**.
Browsers earlier than Chrome 109: **NOT VERIFIED**.
