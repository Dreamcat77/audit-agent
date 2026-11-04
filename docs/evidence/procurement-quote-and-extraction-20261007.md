# Procurement extraction completeness and F06 comparison

Scope: procurement material extraction/upload layout and F06 three-party price comparison.
Budget, contract, system management and OCR engine behavior are unchanged.

## Changes and semantics

- Dedicated procurement AI prompt checks all ten allowed demand fields. Java supplements explicitly
  labelled source values and multi-paragraph technical requirements with original locators/excerpts.
  Conflicting scalar values remain blank. Purchaser/contact/tender title are not substitutes for
  applicant department, applicant name or an explicit procurement method.
- Upload uses one file card and an aligned extraction button. A successfully uploaded material fills
  the form from that specific source; source selection never defaults to the first record.
- F06 accepts legacy supplier rows and horizontal multi-item comparisons. For a matrix, original
  decimal unit prices and quantities are required. Cached formula totals are accepted only after
  independent decimal recomputation. Item totals, supplier totals, missing items, duplicate supplier
  identities, duplicate item codes and conflicting material budgets are validated.
- Recommendation compares each supplier's complete basket. Item-level minima are evidence only;
  suppliers' offers are never spliced. Ties are retained. No price advice is given without three
  distinct comparable offers. Matched blacklist suppliers and over-ceiling offers are excluded.
  The ceiling is the lower of project budget and an explicit matrix material cap; project budget
  facts and existing CV/deviation/over-budget thresholds are unchanged.
- Advice remains CONDITIONAL pending supplier qualifications, actual specification response and
  fee/service confirmation. Existing risk flags remain visible alongside price advice.
- Result, detailed comparison CLOB, workflow and business audit commit together in one short
  transaction. Parsing runs outside transactions. Two bounded analysis/read operations fail fast.
  SHA-256 of file, project budget, actor and mapping/version yields stable operation identity;
  uncertain same-file retries rediscover committed results. DM8 unique primary key arbitrates
  concurrent identical submissions, with loser rollback before result readback.
- The additive controlled migration is `db/dm8/PROCUREMENT__f06_quote_comparison.sql`, creating
  only app-owned `PROCUREMENT_QUOTE_COMPARISON`. No legacy quotation/demand tables are altered.

## Local executable validation

Windows, JDK 17; integration checkout on the existing feature branch.

```powershell
cd backend
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
.\mvnw.cmd clean verify
cd ../frontend
node --test --experimental-strip-types 'src/**/*.test.ts'
npm.cmd run build
cd ../services/audit-ai
.\.venv-test\Scripts\python.exe -m pytest -q
```

Backend: 2106 tests, zero failures/errors, two skipped; BUILD SUCCESS at 2026-10-07 16:22 (JDK 17).
Frontend: 695 passed, zero failures. Production build succeeded; existing >500 kB bundle warning.
AI service: 423 passed. New backend tests cover incomplete/noncomparable offers, ties, blacklist
exclusion, total errors, cached unit-price rejection, duplicate identities, capacity release,
concurrent same-content replay and labelled field/technical requirement coverage.

User comparison workbook parsed with the real Apache POI adapter and BigDecimal domain rules:
12 items, three suppliers; cap 190080.00; conditional minimum basket 144480.00;
budget remainder 45600.00; CV 0.0157 and WARNING (existing concentration rule).
User-authored workbook recommendation/minimum columns are not trusted as calculation inputs.

## Deployment and real-platform verification

Executed on the authorized target server, Linux/JDK 17, real DM8 and the existing independent
Python/AI service. Implementation commit: `5a06111` (29 changed files, 711 insertions/62 deletions).
The local pre-change bundle is `.runtime/pre-procurement-quote-20261007.bundle`.

- Release: `/opt/audit-agent/releases/5a06111-20261007-procurement-quote-completeness`.
- Rollback: `/opt/audit-agent/releases/a919f29-20261007-pre-quote-rollback`.
- Schema backup: `/opt/audit-agent/backups/procurement-quote-20261007`.
- Final JAR SHA-256: `4ca04508d222da85180c816ce10abfc46c9d16c6248e511fad52039b904658b3`;
  local and deployed hashes match. Site HTML serves `index-BQUnn3yJ.js`.
- Backend, AI and Nginx: `active`. Site: HTTP 200. Health: HTTP 200 with
  `{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`.
- Unauthenticated quotation/material GET requests: HTTP 401. No authentication was bypassed
  on the running web application.

Executable server commands (helpers retained under `/home/admin/procurement-quote-20261007`;
credentials come only from protected environment files):

```sh
sudo bash /home/admin/procurement-quote-20261007/quote-deploy.sh \
  5a06111 aeceffff4d262135367b67d13746f361014d254e0af49bfde5f784c98e3ec6f1
sudo bash /home/admin/procurement-quote-20261007/quote-acceptance.sh
readlink -f /opt/audit-agent/current
systemctl is-active audit-backend.service audit-ai.service nginx
curl -fsS http://127.0.0.1/api/v1/health
```

Deployment returned `QUOTE_RELEASE_OK`. Controlled schema apply verified app ownership,
exact columns/types/primary key and unchanged pre-existing counts (22 demands/18 checks).
Only the additive comparison table was created; no legacy rows were migrated or deleted.

Real supplied-file acceptance, using the existing dedicated test project and test identity
through service-layer harnesses compiled against the deployed JAR:

```text
QUOTE_DM8_REOPEN_REPLAY_VERIFIED id=1bb451df-7419-3e8b-9520-c28ca9e964d7 items=12 suppliers=3 risk=WARNING
QUOTE_PRICE_RESULT amount=144480.00 saving=45600.00 status=CONDITIONAL names=[智联科技] cv=0.0157
QUOTE_BUSINESS_AUDIT_VERIFIED count=1
QUOTE_ATOMIC_ROLLBACK_VERIFIED probeRetained=false concurrentSameIdentity=true
MATERIAL_SERVICE_RESULT id=4ec54e12-c793-4bb5-8dc8-6231500f4e88 status=PARSED method=DOCX_TEXT error=null
DM8_MATERIAL_REOPEN_VERIFIED facts=8 conflicts=0 money=800000.00
TENDER_COMPLETENESS_VERIFIED projectName=true budget=true technicalRequirements=3 missingDepartment=true missingApplicant=true missingSubject=true
DM8_FENCING_VERIFIED probeRolledBack=true
DM8_DEMAND_LINK_TRANSACTION_VERIFIED probesRolledBack=true
```

Sources: `三方比价测试材料.xlsx` (sheet `三方比价表`) and the procurement tender DOCX
from user-provided `测试.zip`. All three tender technical requirements (FusionServer, VRP,
OceanStor) are retained. No explicit applicant department/name or budget subject is present;
those fields remain empty. AI evidence is checked against source text before persistence.
Supplier totals are 144480.00 / 147410.00 / 150160.00; the material cap is 190080.00.
The existing test project's budget is 800000.00, so the persisted advice explicitly notes the
budget discrepancy and uses the lower cap without changing the project. Individual supplier
qualification proofs are absent; CONDITIONAL is not a legal compliance finding.

The first rollback probe accidentally reused existing quote-item IDs and failed on the real
database uniqueness constraint. The harness was corrected to generate fresh probe item IDs,
then the complete acceptance script passed. Production code did not require a change.
The initial cold concurrent submissions returned the same committed ID. Repeated execution
rediscovered that result and still observed one business audit. Probe writes were rolled back;
no procurement demand or approval was retained. Only approved test material/quote results remain.

## Verification limits and Git delivery

- Authenticated browser upload, form appearance and user-click end-to-end flow: NOT VERIFIED.
  The available browser session was on the login screen; no application credentials were guessed
  or accounts changed. Local frontend/API regressions passed; real acceptance above is explicitly
  service-layer verification, not authenticated HTTP/UI verification.
- Scanned-file OCR was not exercised by these DOCX-text/XLSX sources: NOT VERIFIED for this run;
  the separate target OCR chain remains REMOTE_E2E_PENDING_EXECUTION.
- TongWeb/TongRDS/TongHttpServer compatibility: NOT VERIFIED; unaffected by this task.
- Git implementation is committed locally. GitHub push attempts failed due to connection aborts/
  port-443 timeout, including HTTP/1.1 and Schannel retries. Deployed artifact hashes were verified
  independently of remote Git availability. No force push or history reset was used.
