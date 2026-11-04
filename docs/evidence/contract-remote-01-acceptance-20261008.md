# Existing remote Contract 01 ReviewRun recovery — 2026-10-08

## Result and scope

```text
01_EXISTING_RUN_RECOVERY_E2E=PASS
SEMANTIC_ATTEMPT_2_STATUS=COMPLETED
REPORT_STATUS=AVAILABLE
DEPLOYMENT_PROVENANCE_STATUS=UNVERIFIED
UPLOAD_TO_REPORT_SINGLE_SHA_E2E=NOT_CLAIMED
```

The existing Run was recovered through the normal UI and its remaining business functions were verified. Full deployment Git provenance remains outstanding. This is not a new upload-to-report single-SHA Golden Chain or a formal traceable release acceptance.

The user confirmed that the latest publication had finished and `current` would remain frozen. The subsequent authorization expressly allowed one controlled Semantic retry without a verified full product SHA, provided the running Backend's frozen-identity fix was independently verified. No new Project, Contract or ReviewRun was created. Only one retry, one finding acknowledgement and one report generation were submitted through UI controls. Existing F02/BREACH/TERM steps were not rerun. No production source, server configuration, provider configuration or schema was changed by this task.

Evidence was prepared in `feature/customer-portal-contract-flow-20260928`, based on local documentation/source HEAD `9ac5219b6ac77fadf1766d318d13ab0d636fe477`. That local HEAD and this document's commit are not the deployed product SHA. Budget/procurement commits were not overwritten or amended.

## Deployment identity and prerequisite gates

| Identity | Observed value |
| --- | --- |
| Actual current release | `/opt/audit-agent/releases/f489128-20261008-procurement-multi-material` |
| Full product Git SHA | UNVERIFIED; release-name prefix is not accepted as a full SHA |
| Backend actual directory | Current release's `backend` directory |
| Backend PID | `4110222` |
| Backend start time | `2026-10-08 10:13:41 CST`, as reported by systemd |
| Backend JAR SHA256 | `84dbec57222a97920d0cbcb7b35c497769051abf96a3ebd37e80c1e32dd64ac0` |
| Frontend index.html SHA256 | `176e5311f361fd2d6a48c1cdd822cd452e4a95400417687e53bc311956d01306` |
| Frontend loaded asset | `/assets/index-CHNAXTWG.js` |
| Frontend JS SHA256 | `bc76c54407d2bd82db2f58c1644b8e8e9bbd74c12d4225170dce022c344a6999` |
| FastAPI actual directory | `/opt/audit-agent/releases/5a06111-20261007-procurement-quote-completeness/services/audit-ai` |
| FastAPI runtime source fingerprint | `35bedea09ef8e7c4207174b240787f1fdc304c02205c2e777d3fc7cd768887c4` |

The FastAPI fingerprint covers 36 runtime files: Python files under `src/`, `pyproject.toml`, and `requirements-runtime.txt`; each file is hashed as deployed, and the sorted relative-path/hash lines are then hashed. It is a file fingerprint, not a Git commit. The deployment package's Backend JAR matched the running JAR hash. Current and the package lacked `RELEASE_MANIFEST.txt`/Git build identity; neither GitHub nor the server source repository resolved `f489128` when checked. Provenance was therefore kept UNVERIFIED under the explicit later authorization.

The known fix release JAR was independently checked against SHA256 `e26977cf6be53d8b93afd008b57cf6cf33746368d031611eacbe69340543d85d`. The current JAR's following classes were byte-identical to that known fix artifact:

| Class | Current class SHA256 |
| --- | --- |
| `FrozenSemanticRequestBuilder` | `84903439e6f9a8621fe90078c694409433d5f8ce4a41faa94b28b4ff12d0bc73` |
| `FrozenSemanticReviewStepExecutor` | `ae483580cb78f2d7c48f947df4a9f6852beb205a64e802f8959fa2a86f7296c2` |

This establishes that the current Backend contains the frozen-identity comparison correction and its input-validation classification; it does not establish the entire release's Git provenance.

Backend/FastAPI health and the Nginx-proxied API health returned HTTP 200 / UP. Backend, FastAPI, Nginx, Redis and DM8 remained active. Actual worker configuration was enabled and authenticated exact-Run GET returned `SERVER_WORKER`.

Immediate pre-retry global claimable/running/expired/UNKNOWN counts were all zero. The original owner matched the normally logged-in test administrator; Project/Contract association matched. All five original frozen files existed and their stored-file SHA256, size and exact parse tuples matched. No history material was reparsed.

### Browser transport qualification

The visible UI was exercised at the existing HTTPS origin `https://192.168.0.26`. Its certificate was expired. The temporary test browser was limited to the server public-key SPKI verified over the authorized SSH connection; system/browser global trust and the server certificate were not changed. The existing certificate SHA256 was `f892c5667e7f92d45a49830ff6fe083d6c94088ad4559a63a48581ad88a7bc28`.

Native secure-context WebCrypto and clipboard APIs were used. No fake session, DOM override, clipboard mock, browser trace or screenshot containing credentials was used. Certificate trust/renewal for ordinary users remains a deployment limitation outside this task.

## Fixed business identities and unique retry

```text
PROJECT_ID=361933f9-8c95-48a5-afb9-ce44bb9a9633
CONTRACT_ID=069b873b-1ebf-49cd-a11d-5e412eff13f7
RUN_ID=3ab51812-801e-48b8-8590-9718569d2876
SEMANTIC_STEP_ID=d2c431d6-9e00-38f0-9874-52d186292f04
ATTEMPT_1_ID=4f73c655-4acd-3c9b-8921-d599fa191b45
ATTEMPT_1_STATUS=FAILED/NOT_STARTED
ATTEMPT_2_ID=b92ef5b1-e8de-4bf0-b0fa-2379faf08466
ATTEMPT_2_STATUS=COMPLETED/COMPLETED
PROJECT_RUN_COUNT=1
SEMANTIC_ATTEMPT_COUNT=2
RUN_FINAL_STATUS=COMPLETED
RETRY_UI_CLICK_COUNT=1
RETRY_POST_COUNT=1
ATTEMPT_3_CREATED=NO
```

Normal path: login → Contract Review → Pending materials → exact confirmed Project → Start Contract Review. The UI restored the original Run by GET, projected `SERVER_WORKER`, and displayed one failed-Semantic retry control. The actual UI retry returned HTTP 202. The harness allowed that exact path and previous attempt identity only once; new Run/Project and all other unapproved writes were blocked. Subsequent browser instances permanently blocked retry.

| Step | Final status | Current attempt |
| --- | --- | ---: |
| MATERIAL_VALIDATION | COMPLETED | 0 |
| F02_DETERMINISTIC | COMPLETED | 1 |
| SEMANTIC_AI | COMPLETED | 2 |
| BREACH | COMPLETED | 1 |
| TERM | COMPLETED | 1 |

Request build crossed the old failure boundary, durably marked REQUESTED, and completed. Model-call audit rows belonged exclusively to attempt #2 and shared its request fingerprint `f943d575c810818ddea6f1807e42cde9f9ba13583fddb57ba4e15e2af0e1445b`:

| Audit ID | Ordinal / phase | Outcome | Duration ms | Finish reason | Prompt / completion / total tokens |
| --- | --- | --- | ---: | --- | --- |
| `ef647502-e3d9-445b-a434-613851142346` | 1 / TRANSPORT | SUCCEEDED | 87569 | STOP | 4194 / 1889 / 6083 |
| `49c0d87f-d7c8-4d4a-877b-938d16beecde` | 2 / TRANSPORT | SUCCEEDED | 82026 | STOP | 2596 / 2137 / 4733 |

Both recorded model `audit-qwen-27b-16k:latest`. Transport remained `OPENAI_COMPAT_STRUCTURED`. No prompt, raw model response, original material text or credential is stored in this evidence.

## F02: original durable eight results preserved

Original AuditTask: `643d7f0f-a425-4f0b-be3a-9ea6e90ab8e7`. Fresh authenticated Run output and final DM8 query both returned the original eight checks. No new F02 attempt was created.

| Check | Count | Result |
| --- | ---: | --- |
| Amount in words vs amount | 1 | PASS; 67000.00 and 67000 numerically agree |
| Contract amount vs award amount | 1 | PASS; 67000 and 67000 |
| Contract supplier vs award supplier | 1 | PASS / MATCH |
| Line sum vs contract amount | 1 | PASS; 67000 and 67000 |
| Line arithmetic, LI-01 to LI-04 | 4 | NOT_EVALUATED; unknown quantity/unit price was retained |

The missing quantity/unit-price facts were not invented or converted to PASS.

## Eight-category UI result matrix

All eight rows and displayed check counts matched current exact-Run persisted output. Opening a category left exactly one first-level category expanded. PASS here means correct display and identity preservation; it does not mean every business scope is covered or compliant.

| Category | Current displayed state | Coverage | Items / attention |
| --- | --- | --- | ---: |
| 1. Quantitative consistency | Insufficient / unevaluated facts | COVERED | 8 / 4 |
| 2. Draft structure comparison | Insufficient / unevaluated scope | PARTIALLY_COVERED | 2 / 0 |
| 3. Breach liability | Attention | COVERED | 7 / 5 |
| 4. Term and termination | Attention | COVERED | 10 / 3 |
| 5. Party qualification | Waiting for external verification | WAITING_EXTERNAL | 0 / 0 |
| 6. Attachment completeness | Not covered | NOT_COVERED | 0 / 0 |
| 7. Effectiveness and copies | Not covered | NOT_COVERED | 0 / 0 |
| 8. Clause risk | Not covered | NOT_COVERED | 0 / 0 |

Unavailable/insufficient/uncovered results were not presented as normal. Cross-category references are not summed as unique findings. The scope gaps of this historical frozen Run are retained and visible in the overall opinion.

## Three source-navigation checks

Each was clicked from an actual expanded issue card. Persisted evidence file SHA and exact parse tuple matched the Run material; `validationStatus=VALIDATED`, locator available, bounded excerpt ≤1000 UTF-8 bytes. The UI selected the correct material, highlighted its evidence and scrolled it into the material viewport.

| Source | Evidence ID | File ID | Parse ID / version | Text offsets | Excerpt bytes | UI |
| --- | --- | --- | --- | --- | ---: | --- |
| Contract | `846ec313-40d9-3fa8-b1a2-786cb423b507` | `df75ca7c-acc0-489e-9d7c-1189b97ade16` | `3f66341a-b67e-453d-9dfa-e1a63f4be063` / 1 | 286–373 | 239 | PASS |
| Award notice | `232542a2-c3c3-3c75-bae1-89cd6abb4c39` | `f63e7568-f23c-4621-8aba-fa88b0190c5e` | `ebb1a1e8-5064-42a5-b6d6-c71cd37142cf` / 1 | 232–327 | 239 | PASS |
| Tender document | `520756d6-cc2b-40ce-b2b4-38436206c6fd` | `678e7db5-3eb6-48bd-899c-19da2f13e136` | `8da24990-aae8-42ef-896f-4e9c1b45c876` / 1 | 1589–1624 | 99 | PASS |

These were verified frozen-text locators, including the award PDF's parsed text; no PDF page number or bbox was invented. No source file was changed.

## Copy, acknowledgement and report

Both copy buttons reported success and their native clipboard contents matched the current persisted-output opinion builders after Windows CRLF-to-LF normalization. The overall opinion included the exact Run ID, every non-normal item, and uncovered scopes; missing concern count was zero. No model was called for copying. Only character counts and hashes were retained, not the copied opinion text.

- Category opinion: 910 LF-normalized characters; SHA256 `2ff4247b769966b15df4a47e572540782b6bcf919e765214a0ad3c5f1ee1cbf7`.
- Overall opinion: 1423 LF-normalized characters; SHA256 `f26289dd1e260a21710575e62d8bde09860e9e29251fbd807f9c8c4ced3de14b`.

One normal-UI acknowledgement returned HTTP 200 and persisted as `af6ae1c9-5bea-428d-a368-a7f44c54a08f`, for finding `e7e67903-b2cc-4f33-9e1d-f82dadd13692`, version 0, attempt #2. Before/after finding identity, status, risk, version and content hashes matched. ACK did not change the risk facts.

One normal-UI report POST returned HTTP 202:

```text
REPORT_ID=9ed779cb-c0b2-4015-bcf8-92359d9ee778
REPORT_VERSION=1
REPORT_STATUS=AVAILABLE
ARTIFACT_SIZE=27079
ARTIFACT_SHA256=573e9ae99a55947df188324a58a48f20e869131484dee9ef5aeed600e92cf0e0
INPUT_SNAPSHOT_IDENTITY=6c4e59e22973184fadf8d04665207d0665fe6c0c42c1f23c5ef1fe1278dcc65c
```

The normal download link succeeded. Download size/hash matched durable report metadata. Report input identity exactly matched the ReviewRun. This remains the existing HTML report/artifact format.

## History and final gates

| UI scenario | Exact Run GET | Status / attempts | Same report available | Frozen input identity |
| --- | --- | --- | --- | --- |
| Audit-record entry | PASS | COMPLETED / 2 | YES | SAME |
| New browser, audit records | PASS | COMPLETED / 2 | YES | SAME |
| Page refresh, reenter audit records | PASS | COMPLETED / 2 | YES | SAME |
| Normal logout/login, audit records | PASS | COMPLETED / 2 | YES | SAME |

History UI GETs restored the exact Run, rather than creating or following another Run. After these actions DM8 still contained one Project Run, two Semantic attempts, exactly two model-call audit rows, one ACK and one report. History reads caused no additional provider call or upload. There was no attempt #3. Backend restart was not performed in this task.

After the user freeze confirmation, every observed current pointer remained `f489128-20261008-procurement-multi-material`; Backend PID/start time/JAR hash and frontend hashes were unchanged at final verification. No switch was observed within this authorized recovery window. The earlier precheck's release changes are not reused as recovery evidence.

Final global pending/running/expired/UNKNOWN counts were zero. Pre-retry claimable was zero; during execution the single authorized attempt was the expected worker-owned work. Schema catalog counts remained 81 application-owned tables and 1183 columns throughout this recovery baseline. No DDL/migration or direct DB business write was performed by this task.

Separate existing defect: V045's `IDX_PROC_DEMAND_SOURCE_PROJECT` was absent (`ALL_INDEXES` count 0). The source column/FK and legacy synonym layout were retained; this task did not add/repair the index.

## Reproduction and limitations

Actual browser drivers, under ignored `.runtime/customer-portal-contract-flow/browser-driver`, were:

1. `node semantic-recovery-20261008.mjs` — normal UI preflight, single retry, readonly durable observation.
2. `node semantic-closeout-resume-20261008.mjs` — same completed Run, categories/locators/copy, one ACK and one report.
3. `node semantic-history-finish-20261008.mjs` — readonly clipboard/history checks and normal logout/login, permanently blocking retry/ACK/report POSTs.

Passwords were supplied through hidden runtime input only. Browser contexts were ephemeral; no auth request bodies, cookies/tokens, trace or screenshots were saved. Safe ignored JSON records contain only the recorded result identities/statuses/hashes.

Harness corrections were local to those ignored drivers: use browser GET under the pinned existing certificate instead of an independent TLS client; pass file names as a Map; distinguish cards with repeated headings by summary and source button; wait for the freshly loaded history list; normalize native Windows clipboard line endings. None changed server application code or facts, and the successful retry/ACK/report were never repeated during those corrections.

For manual inspection, normally log in to the existing site, choose Contract Review → Audit records → the existing contract → View. Reopen Run `3ab51812-801e-48b8-8590-9718569d2876`; the report is version 1. Do not start a new review or retry. Certificate expiration, missing full product provenance, V045 catalog drift and the historical Run's uncovered categories remain explicit limitations.

Final interpretation: **existing Run recovery and business-function acceptance passed; release source provenance remains to be supplied**. No claim of a complete new upload-to-report E2E on one verified product SHA is made.
