# Procurement unified material analysis — 2026-10-07

## Scope and implementation

Procurement review only. Budget, contract, system management and the Python AI service were not changed.
Implementation commits: `0477b71`, `5afbbb5`, `d0b4063`.
The code delta is reproducible with `git diff 0477b71^..d0b4063` (34 files).
Pre-change Git backup: `.runtime/pre-procurement-batch-20261007.bundle`.

The project detail review now has one multi-file upload entry followed by result confirmation
and approval. The separate F11 upload/score action is removed from the active wizard.
Users select 1–10 Word, PDF or Excel files (20 MB/file, 40 MB/batch).
Each file has a persisted result and expandable source/evidence. Missing bidder identity requires
confirmation on that material card. Classification uses explicit file/cover labels; it does not
invent bidder names, submitter IPs or authorized representatives.

The backend reuses existing safe storage, Word/PDF parsing, OCR adapter, F10 AI/rules and F06
quotation analysis. At least two distinct bidders trigger F11 automatically; at least three
explicit, unambiguous RMB totals can trigger total-price analysis. Price CV is used only when
quotation suppliers match the bidder set. Financial comparisons remain BigDecimal rules.
Blacklist checks and existing severe-risk acknowledgement/approval controls remain.

No DDL or rule-weight/threshold changes. Sources use PROCUREMENT_TENDER_DOCUMENT,
bidder facts use PROCUREMENT_COLLUSION_DOCUMENT, and results use existing DM8 tables.
The existing H2-style `MERGE ... KEY` in the two F11 snapshot statements was replaced with
DM8 `MERGE ... USING ... ON ... WHEN MATCHED/NOT MATCHED` after real-platform failure.

## Concurrency, transaction and recovery boundaries

- Two in-process batch slots; one active batch per project; sequential work within a batch.
- Dedicated F10 AI executor: at most two workers, no pending queue. Timeout cancels the Future;
  an adapter that ignores interruption retains its worker until exit, without allowing unbounded
  replacements. Tests cover interruption and saturation. This does not claim to cancel a request
  already running in the separate Python/model service.
- Parsing/OCR/AI execute outside project-row transactions. Source/result/workflow/audit commits
  use short transactions. F11 rechecks the document set and quote ID under the project lock.
- Content identity is stable across retries; no deletion of shared promoted objects.
- Refresh reads persisted results and never starts AI or blacklist writes.
- New material invalidates report completion; submitted approval rejects material replacement.
- Batch capacity is per application instance. Multi-instance global admission was NOT VERIFIED.
- Upload is synchronous, with persisted per-file recovery. There is no new background task queue;
  unknown responses require checking saved results and retrying the same files.

## Automated validation

Windows, JDK 17.0.18.8:

```powershell
cd backend
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
.\mvnw.cmd clean verify
```

Final observed result: **2137 tests, 0 failures, 0 errors, 2 skipped; BUILD SUCCESS**,
2026-10-07 18:43. Ignored log: `backend/.runtime-batch-verify.log`.

```powershell
cd frontend
node --test --experimental-strip-types "src/**/*.test.ts"
npm.cmd run build
```

Observed result: **697/697 passed**, Vite production build passed. Existing >500 KB chunk warning
remains. Ignored test log: `frontend/.runtime-batch-frontend.log`.
`git diff 0477b71^..d0b4063 --check` passed.
MockMvc integration covers multipart batch, permission/CSRF, automatic F11/F06, retry and reopening.

## Deployment and rollback

Final release: `/opt/audit-agent/releases/d0b4063-20261007-procurement-batch-analysis`.
Pre-task rollback: `/opt/audit-agent/releases/c215481-20261007-pre-batch-backup`.
Intermediate releases also retained. Final archive SHA256:
`d8f85f646bf2177c34c54b6da688c2150069be06ba74ff90ae0b3ce7ea20467d`.

Observed deployment output: three services active (backend, AI, Nginx),
health HTTP 200 and `FOLLOWUP_RELEASE_OK`. Release root was checked using `readlink -f`.
The first package omitted frontend/dist; this caused a real HTTP 500. The directory was corrected,
and subsequent packages include frontend/dist and check the entry before deployment succeeds.
No environment, systemd, Nginx, Redis or AI-service configuration changes were made.

Real LAN curl checks against the authorized test host:

- `GET /`: HTTP 200.
- `GET /assets/index-C5gtB0F0.js`: HTTP 200.
- `GET /api/v1/health`: HTTP 200,
  `{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`.
- Unauthenticated `GET /api/v1/procurement/projects/{projectId}/review-materials/current`: HTTP 401.

## Real DM8 acceptance

The runner loaded the final release JAR using server JDK 17.0.19, real DM8 and configured external
adapters. It used the existing active test administrator's service identity in the isolated process;
it did not create a login Session or change accounts. The temporary listener was loopback only.
It backed up scoped pre-existing rows before writing. No approval was submitted or acknowledged.
The earlier test project was already submitted; its replacement was correctly refused. Acceptance
used an existing, unsubmitted equipment project with an unchanged budget of 800000.

Executable command (IDs retained only in protected server-local acceptance data):

```bash
sudo bash /home/admin/procurement-batch-20261007/acceptance.sh "$ACCEPTANCE_PROJECT_ID"
sudo bash /home/admin/procurement-batch-20261007/acceptance.sh "$ACCEPTANCE_PROJECT_ID" reopen
```

Inputs: the user's three bidder DOCX files and tender DOCX from 测试.zip. An initial SCP filename
encoding problem was corrected only for those four content-addressed source rows, with backups
and durable business audit. Subsequent input transfer used an archive to preserve UTF-8 names.

Final release observed outputs:

```text
MATERIAL_RESULT kind=BIDDER status=BIDDER_RECOGNIZED (three files)
MATERIAL_RESULT kind=TENDER status=DOCUMENT_REVIEWED ai=AI_TIMEOUT findings=7
DM8_MATCHED_MERGE_ROLLBACK_VERIFIED
AUTOMATIC_BATCH_DM8_VERIFIED files=4 bidders=3 pairs=3 risk=WARNING score=65
maxSimilarity=0.97710000 cv=0.00150000
CONTENT_REPLAY_VERIFIED sources=4 bidderRecords=3
AUTOMATIC_QUOTE_VERIFIED quotes=3 overBudget=1 blacklistChecked=true recommendation=PENDING
QUOTE_AMOUNT=798600.00
QUOTE_AMOUNT=799800.00
QUOTE_AMOUNT=801600.00
REPORT_AGGREGATION_VERIFIED tenderFindings=7 risk=CRITICAL
PROCESS_RESTART_REOPEN_VERIFIED files=4 bidders=3 pairs=3 risk=WARNING
```

Replaying the same files preserved quote/assessment IDs and source counts. Both F11 MERGE update
branches were exercised inside a transaction, read back, rolled back, and checked for preservation
of the original assessment identity. Inserts, update branches and transaction rollback: VERIFIED.

The source files provide total quotes but lack full comparable specifications/conditions;
recommendation PENDING is intentional and does not claim the cheapest bidder is qualified.
Missing IP/representative fields remain missing. Test writes are retained for user inspection.
Protected server log: `/home/admin/procurement-batch-20261007/final-acceptance.log`.
An independent invocation using the final JAR reopened all four files, three bidders and three
comparison pairs after the prior process exited: VERIFIED. Protected log:
`/home/admin/procurement-batch-20261007/final-reopen.log`.

## Limits

The real F10 AI attempt timed out; the external AI endpoint returned HTTP 502. Successful model
analysis on this batch is NOT VERIFIED. Seven deterministic tender findings are saved; the UI
reports AI unavailability instead of claiming an AI result. No model/AI-service settings were changed.
Browser entry/login rendering was checked, but the browser had no authenticated Session.
Authenticated production browser multipart/confirmation interaction is NOT VERIFIED; its API
behavior has local integration coverage and real server-side/DM8 service acceptance.
OCR scanned-PDF acceptance and multi-instance load testing were not run in this DOCX batch.

GitHub push for the two follow-up fixes encountered connection failures. Local commits and the
server release are retained; remote synchronization must be confirmed separately.
