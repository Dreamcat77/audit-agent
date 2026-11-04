# Procurement material upload and optional F06 analysis

Scope: procurement review only. Implementation `c215481` changes 18 files (431 additions,
99 deletions). Pre-change local backup: `.runtime/pre-procurement-assistance-20261007.bundle`.

## Behavior

- Project details has one review upload entry: materials/analysis, result confirmation, approval.
  F06 quotation analysis is auxiliary; it is no longer the first mandatory action.
- `POST /api/v1/procurement/projects/{projectId}/review-materials/upload` routes recognized
  Excel supplier/amount headers or the horizontal quotation matrix to the existing F06 service.
  Word/PDF sources reuse existing safe storage, parsing, rules and AI review services.
- Excel without recognized quotation headers returns HTTP 200 with `NOT_APPLICABLE` and no
  quotation result. Recognized but invalid quotation data fails validation; it is never
  represented as absent data or a successful analysis.
- Valid quotation data is analyzed and returned automatically with persisted price advice,
  budget and quotation-pattern results. Existing qualification caveats/risk findings remain.
- No quotation data is required to review Word/PDF material, generate its report and proceed.
  A quotation-only submission can also proceed to its report. Blacklist and collusion controls
  remain available as auxiliary analyses. Serious-risk acknowledgement and approval safeguards
  are retained.
- Historic step codes/orders stay in the existing DM8 schema. Read projection interprets the
  main stages from actual completed material/report/approval facts, including old workflows.
  Only analyses actually executed are marked completed; unrelated steps are not fabricated.
- Two bounded upload slots fail fast. F06 keeps its durable same-content identity, bounded
  decimal parsing and atomic comparison/workflow/business-audit commit. Workflow writers obtain
  a transaction-bound DM8 lock on the existing procurement project before reading/updating the
  workflow, including its first creation. No new worker pool, queue, migration or thresholds.
- Existing role boundaries remain: applicants can submit Word/PDF; F06 requires manager,
  auditor or system administrator. CSRF/authentication are enforced on the new endpoint.

## Executed local checks

Windows, JDK 17:

```powershell
cd backend
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
.\mvnw.cmd clean verify
cd ../frontend
node --test --experimental-strip-types 'src/**/*.test.ts'
npm.cmd run build
```

Final backend: 2116 tests, zero failures/errors, two skipped, BUILD SUCCESS at 17:02 on
2026-10-07. Frontend: 695 passed; production build passed (`index-6DLz45gk.js`), with the
existing bundle-size warning. Python source unchanged in this task.

New regression cases cover automatic quotation route and persisted replay, neutral non-quotation
input, corrupt recognized quotations, document review without quotations, bounded concurrent
capacity/release after failures, authentication/CSRF/quotation-role rejection, report gating,
quotation-only completion, absence of fabricated auxiliary facts and old workflow read projection.

## Target deployment and real-platform acceptance

Release: `/opt/audit-agent/releases/c215481-20261007-procurement-optional-comparison`.
Rollback: `/opt/audit-agent/releases/5a06111-20261007-pre-assistance-rollback`.
Deployment archive SHA-256: `b1d251d632b32fe0e7756c78347e3af312e6c138a80fbbdc82f6a43978bedd1d`.
Final backend JAR SHA-256: `25e9092f556931cda7ce85657a446600178d4f4a0dc10fa62d229d70a9184987`.

Server helper commands (credentials only from protected environment):

```sh
sudo bash /home/admin/procurement-assistance-20261007/deploy.sh \
  c215481 b1d251d632b32fe0e7756c78347e3af312e6c138a80fbbdc82f6a43978bedd1d
sudo bash /home/admin/procurement-assistance-20261007/acceptance.sh
systemctl is-active audit-backend.service audit-ai.service nginx
curl -fsS http://127.0.0.1/api/v1/health
```

Deployment returned `ASSISTANCE_RELEASE_OK`. Backend, AI and Nginx are active; site HTTP 200;
health HTTP 200 with `{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`.
Unauthenticated POST without CSRF to the new upload endpoint is rejected with HTTP 403.
Local authenticated MockMvc covers both allowed requests and CSRF/role rejection.

Real acceptance ran on the deployed JAR, existing DM8, existing dedicated test project and the
approved user workbook, without creating a demand or submitting approval:

```text
QUOTE_DM8_REOPEN_REPLAY_VERIFIED id=1bb451df-7419-3e8b-9520-c28ca9e964d7 items=12 suppliers=3 risk=WARNING
QUOTE_PRICE_RESULT amount=144480.00 saving=45600.00 status=CONDITIONAL names=[智联科技] cv=0.0157
QUOTE_BUSINESS_AUDIT_VERIFIED count=1
QUOTE_ATOMIC_ROLLBACK_VERIFIED probeRetained=false concurrentSameIdentity=true
UNIFIED_UPLOAD_WORKFLOW_REOPEN_VERIFIED quoteAutomatic=true reportAccessible=true
DM8_WORKFLOW_MAPPER_LOCK_VERIFIED transactionRolledBack=true
ORDINARY_MATERIAL_NO_QUOTE_VERIFIED status=NOT_APPLICABLE
DM8_WORKFLOW_ROW_LOCK_VERIFIED secondWriterWaited=true locksReleased=true businessDataChanged=false
```

Two simultaneous calls to the unified ingress returned the same existing durable comparison;
the result and single business audit were reopened unchanged. The MyBatis row-lock query was
executed within a rollback-only transaction. A two-connection DM8 test observed the second
`SELECT ... FOR UPDATE` wait until the first transaction rolled back, then complete and release
its own lock. No probe data was retained. The ordinary-sheet case returned neutral absence of
quotation information rather than an error or invented comparison. No DDL was executed.

## Limits

- Automatic price facts require the supported Excel quotation layouts. Unstructured Word/PDF
  quotation tables are not inferred into monetary facts; Word/PDF retains the existing review.
- Authenticated browser appearance/click flow: NOT VERIFIED; the available session is on the
  login screen. Real service-layer acceptance is not an authenticated HTTP/browser E2E claim.
- Scanned-file OCR: NOT VERIFIED in this run; separate target OCR chain remains
  REMOTE_E2E_PENDING_EXECUTION. TongWeb/TongRDS/TongHttpServer are unaffected and NOT VERIFIED.
- Implementation and previous pending commits were pushed successfully to the existing feature
  branch on this run; remote implementation head matches `c215481`.
