# Procurement material intake verification — 2026-10-07

Scope: procurement material upload, source-grounded AI field suggestions, durable storage,
explicit source selection and editable demand fields. Budget upload/AI entry and unrelated pages
were not changed. No AI amount decisions or approval automation were introduced.

Code releases: `d08b30e`, `733d017`, `a919f29`. Final application release:
`/opt/audit-agent/releases/a919f29-20261007-procurement-material-intake`.
Backend artifact from `733d017` is unchanged in the final frontend release.
Local backup: `.runtime/before-procurement-intake-780cbfd.bundle` in the parent workspace.
Protected server backups: `/opt/audit-agent/backups/procurement-material-20261007` and
`/opt/audit-agent/backups/procurement-material-20261007-resolved`.
Complete application rollback:
`/opt/audit-agent/releases/780cbfd-20261007-pre-intake-rollback`.
Rollback retains the additive database tables and all parsed material facts.

## Executed checks

- Windows/JDK 17: `backend/mvnw.cmd clean verify` — 2098 tests, zero failures/errors, 2 skipped,
  `BUILD SUCCESS`. Final log: `backend-material-verify.log` in the integration checkout (ignored).
- Windows/Node: `node --test --experimental-strip-types "src/**/*.test.ts"` — 695 passed.
  `npm.cmd run build` — passed; existing bundle-size warning remains.
- Existing Python test environment: `.venv-test/Scripts/python.exe -m pytest -q` — 423 passed.
- `git diff --check` and staged sensitive-literal checks — passed.
- Real deployed HTTP: health and site entry 200; unauthenticated material endpoint 401.
  Health body: `{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`.
- Backend, AI and Nginx services active. Deployed asset `index-7dTjeg4V.js` contains the upload entry,
  explicit use action and procurement material API path.

## Representative DM8 / real AI

The application account accesses historical demand data through another schema and lacks ALTER
ownership. Initial additive execution created the intake table and indexes, then stopped with
DM8 permission error `-5520` before changing the demand table. No demand data was rewritten.
The implementation was adjusted to an application-owned `PROCUREMENT_MATERIAL_LINK` table;
`complete-link` validated the empty intake schema and executed only the missing link phase.
Visible catalog and demand snapshots were taken before recovery. Final catalog: one intake table,
one link table, 22 historical demand rows. New deployments use the final V046 script, which contains
no ALTER on the historical demand table.

The approved ZIP's tender DOCX was tested with the real configured model.
The extraction route returned HTTP 200, one project, two grounded fields (project name and
budget amount), model `audit-qwen-27b-16k:latest`. Fields without source evidence remain empty.

A one-shot service-layer runner used the production MyBatis mappers, DM8 transactions, shared
filesystem storage and real Java → FastAPI → model HTTP adapter. Executed through JDK 17 with
credentials loaded from protected environment files. Observed output:

```text
MATERIAL_SERVICE_RESULT id=7eaa9059-4bb7-489f-b8d7-1cda8ccb2eec status=PARSED method=DOCX_TEXT error=null
DM8_MATERIAL_REOPEN_VERIFIED facts=2 conflicts=0 model=audit-qwen-27b-16k:latest money=800000.00
DM8_FENCING_VERIFIED probeRolledBack=true
DM8_DEMAND_LINK_TRANSACTION_VERIFIED probesRolledBack=true
INTAKE_SCHEMA_VERIFIED parsed=1
```

Operation identity: `038b723c-756b-4290-be0f-0bdf4335f34f`. Same-file replay returned the same
material without repeating AI extraction. Field/evidence CLOB content reopened unchanged.
Fenced stale completion affected zero rows. Demand/material association probes were rolled back,
including rejection of a missing parsed material; historical demand count remained 22.
One approved parsed material is retained for user testing. The stored file was checked readable
by the existing backend service user. No procurement demand or approval was retained from probes.

## Limits

- Authenticated deployed browser upload/select/create: **NOT VERIFIED**. The available browser
  was on the login page with no usable authenticated session. No password was changed or guessed.
  The role/CSRF/upload/reopen/create path passed local API integration tests with a test-only model.
- New procurement path using real scanned-image OCR: **NOT VERIFIED**. DOCX text extraction was
  exercised with real material; shared OCR concurrency/release behavior passed regression tests.
- Extraction is bounded and synchronous: 2 material operations, 2 AI operations, one shared Java
  OCR call; busy requests fail fast. Durable lease fencing protects retries across processes.
  Model input/operation time, pages, text, bytes and DOCX expansion are bounded.
- DOCX/PDF/PNG/JPG supported. ZIP is still the existing Project Intake Package workflow; it was
  not added as a procurement material type. Multiple projects require separate material uploads.

User path: Procurement review → New procurement demand → upload material → upload/extract →
use extracted fields (or explicitly select an existing material) → check/edit missing or conflicting
fields → save draft or explicitly submit approval. Selection does not default to the first row.
