# Customer Portal + Contract UX Integration Verification

## Identity and scope

- Branch: `feature/customer-portal-contract-flow-20260928`
- Worktree: `D:\code\audit_agent_1D\.worktrees\customer-portal-contract-flow-20260928`
- Exact baseline: `integration/pre-main-contract-f06-20260928@ccf674e0f6bc00fd6d6a8fb33acdfbdedf2c585e`
- Tested production-code commit: `6d8e32bbe1de5b96622f6876d8abd6c48f42d723`
- Backend tree: `41a6c49f273ab637a704ef74354020e8f059138a`
- Frontend tree at the tested production-code commit: `e6a073524a73b069c2987748e1acbcccfa655d77`
- FastAPI tree: `ac54beac60faee67dee57e6ecdd6b2c1f68dbef3`
- Change boundary: frontend presentation/orchestration, tests, design, plan and this evidence only. There is no backend, FastAPI, DM8 migration, schema or deployment change.

The customer reference ZIP was inspected only as a design reference. No source, dependency, runtime file, mock data or configuration was imported from it.

## Implemented product flow

- One authenticated portal shell and one API/session boundary.
- Fixed navigation order: 工作台 / 预算审查 / 采购审查 / 合同审核 / 黑名单 / 风险预估.
- Workbench exposes budget, procurement and contract as primary tools, with blacklist and risk as lower-weight entries.
- Contract center defaults to persisted audit records, includes a pending/preparation tab, one primary material upload, and a disabled OA entry.
- Ordinary multi-file selection creates one Project and uploads files with immutable per-file operation identities. ZIP delegates to the existing secure package intake.
- Empty picker and prevalidation failures perform no business write. Partial success retains successful files; uncertain outcomes retain the original operation identity.
- A durably proven terminal ZIP failure releases only the finished failed operation so a replacement selection can start. It is not treated as an uncertain replay.
- Risk assessment is a read-only projection of persisted REVIEW/INSUFFICIENT contract audit tasks. It does not synthesize a score, finding, model call or write.
- Exact task/run navigation continues to use the existing Contract workspace, frozen evidence, source navigation, copy, ACK and report behavior.

## Whole-branch review

Review range: `ccf674e0f6bc00fd6d6a8fb33acdfbdedf2c585e..6d8e32bbe1de5b96622f6876d8abd6c48f42d723`.

One Important finding was confirmed: the new contract portal wrapper collapsed the existing secure ZIP state machine's terminal `FAILED` state into `OUTCOME_UNCERTAIN`, leaving an operation that could never replay. A regression test reproduced the missing rejection before the fix. The minimal fix preserves uncertain operation identities but releases a durably proven terminal failure. Focused and nearby verification then passed 16/16.

No unresolved Critical or Important finding remains in the reviewed change. Existing large frontend bundle size remains a non-blocking build warning and was not expanded into an architectural rewrite.

## Automated verification

| Gate | Environment and command | Observed result |
| --- | --- | --- |
| Backend full | JDK 17, `backend\.\mvnw.cmd clean verify` | 1,994 tests; 0 failures; 0 errors; 2 skipped; BUILD SUCCESS |
| FastAPI full | approved Python 3.11.16 venv, `python -m pytest -q` from `services/audit-ai` | 415 passed; 1 upstream Starlette deprecation warning |
| Frontend full, isolated | Node runner with `node --test --experimental-strip-types "src/**/*.test.ts"` | 682 passed; 0 failed |
| Frontend full, shared isolation | Node runner with `node --test --experimental-strip-types --test-isolation=none "src/**/*.test.ts"` | 682 passed; 0 failed |
| Frontend production build | `npm.cmd run build` | PASS; 2,420 modules transformed; existing large-chunk warning |
| Diff check | working tree and full branch range | PASS after the documented whitespace-only correction |
| Credential scan | repository scan excluding build/dependency output | no supplied application password, private key, token, database/Redis/model credential added; matches were limited to committed empty templates, a synthetic private-key test marker and an older existence-only evidence reference |

The first sandboxed frontend full-run attempt produced only `spawn EPERM` worker-launch errors. Both required runners were then repeated with permission to spawn their normal local workers and passed with the counts above.

## Built artifacts

- Backend JAR SHA-256: `4F0EE722FBF766E9DA7D269D24C6C1D52955E3AA8DEA84814FDE7D02398B75BC`
- Frontend `index.html` SHA-256: `E95F64663436A5F9BE47FD8B8806930BBF73A10252675A335FC761C8A4EE04D7`
- Frontend CSS SHA-256: `E73C3D6B577A4DD2247C3BBD3F6328C5D4706863952B00BCFB82478CA239B1B5`
- Frontend JavaScript SHA-256: `7FFAA321308442D454A05A8D44F40B254C7181C1934EF45D6DC282FFB40C8B52`

## Browser and integration status

The pre-E2E checkpoint is assigned by the commit containing this evidence. Browser checks at 1366×768 and 1920×1080 and the single authorized new 01 chain are pending until that exact checkpoint is pushed and the approved integration identities are revalidated. No 01–06 Project, Contract, ReviewRun or provider call was created during the automated gates recorded above.

Only one new 01 chain is authorized after the checkpoint. A failed attempt must be preserved and stops the run; 02–06 are outside this execution.

## Single authorized 01 browser attempt

### Exact runtime identity

- Pre-E2E checkpoint and tested code SHA: `be1626a29fb5b1edf91ed7f75c6f38f1673be380`
- Remote checkpoint: `origin/feature/customer-portal-contract-flow-20260928@be1626a29fb5b1edf91ed7f75c6f38f1673be380`
- Runtime build ID: `20260928-200103-be1626a29fb5-e3b0c44298fc`
- Runtime backend JAR SHA-256: `2E1C367B6754430E18AEA4501DC6096A798F406971A929CEB0AD778B4DE9618A`
- Runtime frontend bundle aggregate SHA-256: `5ee10bc6048c0667278240996ded5aa414a880f6fcc7aaa044651df6934bc32f`
- Runtime FastAPI source SHA-256: `a4f4c404598f37a74e435eb28800e1aaed025295eb2e39b806554ccad08b0354`
- Isolated ports: frontend `5174`, backend `8889`, FastAPI `8002`.
- Isolated session namespace: `audit-agent:customer-portal-contract-flow:be1626a2`.
- Isolated storage root: `D:\code\audit_agent_1D\.worktrees\customer-portal-contract-flow-20260928\.runtime\customer-portal-contract-flow\integration\storage`.
- The pre-existing final-closeout listeners on `5173/8888/8001` were identified by command line and left untouched.
- The scheduled ReviewRun worker was disabled for this attempt after the guarded start review found one unrelated fact-extraction attempt from 2026-09-24 still marked `IN_PROGRESS`. Source inspection proved the scheduled ReviewRun worker reads only ReviewRun step attempts, but client compatibility mode was selected to remove takeover risk entirely.

The approved environment identity, DM8 major version 8, Redis reachability, A/C migration state, authority constraint, private provider identity and exact model ID were verified before start without printing credentials. Contract worker `claimable`, `expired`, `running` and `validLease` were all zero. The unrelated old fact attempt had no update in the last 15 minutes and was not modified.

### Browser observations

- Normal application login: PASS.
- Fixed navigation order: PASS (`工作台 / 预算审查 / 采购审查 / 合同审核 / 黑名单 / 风险预估`).
- Workbench at 1366×768: PASS; runtime screenshot `workbench-1366x768.png`, SHA-256 `ADD2DC34B7799FFCE73798B5E2FA4270B1C36344B4AA79B84C1B834FF0CE085D`.
- Contract center at 1920×1080: PASS; default tab was `审核记录`, OA action was disabled, runtime screenshot `contract-home-1920x1080.png`, SHA-256 `9F22849359422060CCE546548409B17DE9B090AA1C12FE098DFE9C129DE6392F`.
- The first login request was rejected before authentication because the reference CORS override listed the already occupied 5173 origin, while this isolated frontend used 5174. A session-only 5174 origin was added to the ignored runtime launcher. The reference env and repository configuration were not modified. The corrected normal login passed.

Screenshots are retained only under the ignored runtime directory. They contain no password, token, cookie, request body or original material text.

### Preserved failure and root cause

The same visible browser session selected all five approved 01 originals. The first business write, `POST /api/v1/projects`, returned HTTP 500 with request ID `2acbd989-70d5-45c3-b0e6-2340c04ac5bd`. No file upload request followed.

The safe backend diagnostic classified the failure as `DataIntegrityViolationException` with DM8 root cause `invalid column RESOURCE_NAME` in `OperationAuditMapper.insert`. A separate read-only catalog probe confirmed that the integration database's `AUDIT_OPERATION_LOG` lacks all ten columns expected from the repository's V032/V033 audit migrations:

- `RESOURCE_NAME`, `BEFORE_DATA`, `AFTER_DATA`, `FAILURE_REASON`;
- `CONFIRMATION_FIELD_CODE`, `CONFIRMATION_FIELD_NAME`, `CONFIRMATION_FIELD_VALUE`;
- `CONFIRMATION_RULE_PROMPT`, `CONFIRMATION_AI_PROMPT`, `CONFIRMATION_NOTE`.

The same read-only probe found `NEW_PROJECT_ROWS_SINCE_ATTEMPT=0` for the authenticated owner. Therefore the failed create transaction did not produce a Project. ProjectId, CandidateId, ContractId and ReviewRunId do not exist for this attempt.

### Stop condition

- `PROJECT_ID`: not created.
- `CONTRACT_ID`: not created.
- `RUN_ID`: not created.
- Uploaded files: `0/5`.
- OCR/A-mini/semantic/provider calls: `0`.
- Automatic extraction matrix: not reached.
- Eight-category result, evidence navigation, opinion copy, ACK/report, refresh/relogin/exact-history reopen: not reached.
- 02–06: not started.
- DM8 migration/DDL: not executed.
- DB/Redis/storage cleanup: not executed.
- Services owned by this worktree were stopped by PID ledger; ports `5174/8889/8002` are free.

Status: **BLOCKED_BY_INTEGRATION_SCHEMA_DRIFT**. The current task explicitly prohibits migration, and durable operation audit is fail-closed. Disabling audit, changing the mapper to ignore missing columns, directly inserting a Project, or retrying as a second 01 chain would violate the approved boundary. A future continuation requires a separately approved integration DM8 change window for the exact missing V032/V033 audit columns, followed by a new authorization for one 01 browser attempt.

## 2026-09-29 integration schema repair and second authorized 01

### Code and environment identity

- Repository and remote HEAD before execution: `ce40afe6df7e9556b4ecf0f94e523767319e0c85`.
- E2E product-code SHA and artifact source: `be1626a29fb5b1edf91ed7f75c6f38f1673be380`.
- `git diff --exit-code be1626a2 ce40afe6 -- backend frontend services db`: PASS. The two commits differ only by evidence/documentation.
- Reference integration environment SHA-256: `1221AA61449609CD2DA399F0103F71292A751CBB72F5A99B7A65CC70008FB3D5`.
- Sanitized integration DB endpoint identity SHA-256: `ea24dd3ed5269551aa1011f8db51715170b228abc01ef105db78b1d7ff3ea9fe`.
- Sanitized integration schema identity SHA-256: `8794bddd98fa7711df16714260f462e51f4d3cbe1543865cd439bc0d18f8880b`.
- DM8 major version 8, Redis reachability, authority constraint, private provider identity and exact approved model ID were verified. The four ReviewRun worker-risk counts were zero. One unchanged fact attempt from 2026-09-24 remained stale; the recent-fact count was zero and the scheduled ReviewRun worker remained disabled.
- Runtime build ID: `20260928-200103-be1626a29fb5-e3b0c44298fc`.
- Backend JAR SHA-256: `2E1C367B6754430E18AEA4501DC6096A798F406971A929CEB0AD778B4DE9618A`.
- Frontend bundle aggregate SHA-256: `5ee10bc6048c0667278240996ded5aa414a880f6fcc7aaa044651df6934bc32f`.
- FastAPI source SHA-256: `a4f4c404598f37a74e435eb28800e1aaed025295eb2e39b806554ccad08b0354`.
- Isolated ports `5174/8889/8002`, Redis namespace and storage root remained the same as the first attempt. Services owned by this worktree were stopped after evidence capture; all three ports were free. Existing final-closeout services were not stopped.

### INTEGRATION_SCHEMA_REPAIR

| Gate | Result |
| --- | --- |
| `AUDIT_OPERATION_LOG` pre-DDL row count | `2961` |
| Original table and primary key | table present; `PK_AUDIT_OPERATION_LOG` enabled on `ID`; required mapper baseline columns matched |
| `PRE_MIGRATION_CLASSIFICATION` | `V032=NOT_APPLIED`, `V033=NOT_APPLIED`; all ten authorized columns absent |
| Existing `dexp/dimp` full-schema mechanism | attempted before DDL but stopped with `dexp` parameter parsing error; no dump and no DB write |
| Protected JDBC logical recovery point | PASS; complete `AUDIT_OPERATION_LOG` catalog and all 2,961 rows exported under ignored runtime ACL directory |
| Backup catalog SHA-256 | `2F63ED2C85E98845CBBE0A6527769947C514412DDD6F0ED8891513EB5B4C3E3F` |
| Backup restore SQL SHA-256 | `A2C5848211E7658B6129B7F70747B3D9F2B25DC08C644609C504022056B19697` |
| Backup manifest SHA-256 | `C39D790395B398E54EF97A71344AFED68136FD84BAF86FD3AE6008EF14C761FD` |
| `V032_RESULT` | original repository migration executed; nine statements applied; immediate four-column catalog verification passed |
| `V033_RESULT` | original repository migration executed only after V032 verification; thirteen statements applied; immediate six-column verification passed |
| `POST_MIGRATION_SCHEMA` | PASS; all ten columns are nullable `VARCHAR` with exact lengths and no default; table row count remains `2961` |

`V032_EXECUTED=true`; `V033_EXECUTED=true`. No other migration, table change, cleanup, audit bypass or 0.26 operation was performed.

### Single newly authorized 01 browser chain

The normal login, fixed six-item navigation, new Portal workbench, Contract center default `审核记录`, disabled OA control and five-file picker passed. The first navigation wait timed out before any business write; a read-only diagnostic proved that the Contract page and its persisted task queries rendered normally. The same authorized chain resumed before its first write.

The browser then selected the five approved 01 originals and produced exactly one new Project through the normal UI:

- `PROJECT_ID=3b016abe-b9d2-4fa2-8df7-41f075ae65dc`.
- Five file uploads returned HTTP 200 and all five durable files reached `PARSED`.
- Persisted role assessments: five `MACHINE_DETERMINISTIC` and two `MACHINE_AI`, all `COMPLETED`.
- Deterministic candidate facts present before human confirmation: `PROJECT_NO=1`, `CONTRACT_NAME=1`, `PARTY_A=1`, `PARTY_B=1`.
- Project metadata remained `VERSION_NO=0`; name/project number/category had not yet been durably confirmed. The UI displayed the extracted project-number suggestion, but it was not counted as an adopted formal fact.
- No role decision, identity assessment/decision, A-mini fact attempt/candidate, Contract Candidate, Project Contract, ReviewRun or contract model-call audit existed for this Project.

The browser was closed after the upload checkpoint and then logged in again to continue through the visible `待继续` entry. The exact Project was absent from both durable collections used by that page. Source and runtime evidence agree on the cause:

- `ProjectMapper.findPage` returns only `PROJECT.VERSION_NO > 0`;
- `ProjectMapper.findIntakeDraftPage` returns `VERSION_NO = 0` only when a `PACKAGE` ProjectFile exists;
- the simplified ordinary-file intake creates a `VERSION_NO = 0` Project without a `PACKAGE` file.

Therefore an ordinary multi-file Project is reachable only through in-memory `onOpenProject` state immediately after upload. Refresh, browser close or relogin before metadata confirmation leaves no supported UI resume entry. Direct API writes, direct database edits, fabricated navigation state and a second Project were not used.

### Result matrix at stop

| Required result | Status | Evidence |
| --- | --- | --- |
| Portal/navigation | PASS | normal login and fixed six-entry order |
| Upload/new simplified flow | PARTIAL | one Project and 5/5 parsed files; durable resume entry missing |
| Project metadata/role/identity | BLOCKED | metadata still version 0; role/identity decisions 0 |
| Candidate/Confirmation/Promotion | NOT REACHED | candidate count 0 |
| Contract | NOT REACHED | Project Contract count 0 |
| Five review steps | NOT REACHED | ReviewRun count 0 |
| Eight-category projection | NOT REACHED | no run |
| F02/Semantic | NOT REACHED | no run; A-mini and semantic model-call audit count 0 |
| Evidence navigation | NOT REACHED | no findings/run |
| Opinion copy / ACK / report | NOT REACHED | no run |
| Refresh/relogin | FAIL | exact pre-confirmation Project cannot be reopened from either visible collection |
| History exact-run reopen | NOT REACHED | no run |
| OA / risk claims | PASS for boundary | OA remained disabled; no risk score was created |

`CANDIDATE_ID`: none. `CONTRACT_ID`: none. `RUN_ID`: none.

`AUTO_EXTRACTION_MATRIX`: five files parsed; four deterministic fact candidates listed above; two completed machine-AI role-assessment records; no A-mini attempt, automatic Contract adoption or human field correction was completed. The intended manual project name/category edits were never submitted and are not counted as durable manual corrections.

`FIVE_STEP_RESULT`, `EIGHT_CATEGORY_RESULT`, `SOURCE_NAVIGATION`, `COPY_RESULT`, `ACK_REPORT_RESULT`, and `HISTORY_REOPEN_RESULT`: not reached.

`UI_01_GOLDEN_CHAIN=FAIL` with blocker `SIMPLIFIED_INTAKE_PROJECT_NOT_REDISCOVERABLE`. This is a new product recovery defect, not a remaining V032/V033 failure. Per the authorized fail-closed rule, no second Project, second 01 chain, 02–06 run, additional migration or product-code fix was started. A separate implementation task must add a truthful durable resume path for non-package `VERSION_NO=0` Projects and cover refresh/relogin before a new 01 authorization.

An additional integration schema drift remains visible but was outside this change window: `GET /api/v1/notifications/unread-count` returns 500 because `PROCUREMENT_NOTIFICATION` is absent. It did not prevent Contract page rendering and was not repaired.

## 2026-09-29 final 01 golden chain after preparation-draft rediscovery fix

### Exact code and runtime provenance

- `E2E_CODE_SHA=196db100e17f9bbd98a8be979baf172f23ce39e3`.
- Local HEAD and `origin/feature/customer-portal-contract-flow-20260928` were equal to that SHA before the first business write.
- Runtime build ID: `20260929-110029-196db100e17f-e3b0c44298fc`.
- Backend JAR SHA-256: `7AE184D2677FC58BEDED6ED20287676F41C67350ABA58BAD98AB3FB1060A5A9C`.
- Frontend bundle aggregate SHA-256: `3b25f63bc03a1d27b2662d38d1d2062a6cd9977673308f4b1732fbafe34d7d06`.
- FastAPI source SHA-256: `a4f4c404598f37a74e435eb28800e1aaed025295eb2e39b806554ccad08b0354`.
- Runtime ports: frontend `5174`, backend `8889`, FastAPI `8002`.
- Redis namespace: `audit-agent:customer-portal-contract-flow:be1626a2`.
- Storage root: `D:\code\audit_agent_1D\.worktrees\customer-portal-contract-flow-20260928\.runtime\customer-portal-contract-flow\integration\storage`.
- Integration DM8 major version `8`; endpoint identity SHA-256 `ea24dd3ed5269551aa1011f8db51715170b228abc01ef105db78b1d7ff3ea9fe`; schema identity SHA-256 `8794bddd98fa7711df16714260f462e51f4d3cbe1543865cd439bc0d18f8880b`.
- V032 and V033 were reclassified read-only as `ALREADY_MATCHING`; all ten fields matched exact type, length, nullability and default expectations. No migration or DDL was executed.
- The approved private provider identity and exact model `audit-qwen-27b-16k:latest` were verified before startup. No provider, model or safety boundary was changed.
- The task's accepted starting evidence records the exact-SHA Backend, FastAPI and both Frontend full suites as PASS. This browser closeout used a fresh exact-SHA build and did not repeat those full suites.

### Durable identities and preparation recovery

- `PROJECT_ID=f8e10628-fad0-4413-8db0-ea10f82f461f`.
- `CANDIDATE_ID=89eb0e8b-7466-4464-84ba-7e44e5b11654`.
- `CONFIRMATION_ID=36c7e6b9-3a8f-4b2f-9a9f-a4dbf3c187ad`.
- `CONTRACT_ID=a90b8655-79b5-44c3-a5ba-3df94fa14048`.
- `RUN_ID=a9d92850-54dc-4546-bc12-60161c143cd5`.

The normal authenticated Portal showed the fixed navigation order, Contract center defaulted to `审核记录`, OA remained disabled, and the one visible file selection uploaded the five approved 01 originals. The Project create and all five uploads returned HTTP 200. The five durable files all reached `PARSED`.

Before metadata confirmation the Project had `VERSION_NO=0`, five ordinary files and zero `PACKAGE` files. Browser refresh intentionally left the in-memory Project screen. After logout and normal login, `待继续` returned the exact Project from `GET /api/v1/projects/preparation-drafts`; it was absent from ZIP intake drafts, reopened the same Project and retained all five parsed files. No additional `POST /api/v1/projects` occurred.

- `PREPARATION_DRAFT_REDISCOVERY=PASS`.
- `REFRESH_BEFORE_METADATA=PASS`.
- `RELOGIN_BEFORE_METADATA=PASS`.
- `NO_DUPLICATE_PROJECT=PASS`.

### Automatic extraction and human action matrix

| Classification | Fields | Result |
| --- | --- | --- |
| `AUTO_ADOPTED` | partyA, partyB, contractAmount, amountInWords, four line-item names, four declared amounts, awardAmount, awardSupplier | 14 final formal values originated from evidence-validated candidates |
| `HUMAN_CONFIRMED` | the same 14 values | the user confirmed existing candidates; this is not counted as manual fact creation |
| `HUMAN_ADDED_OR_CORRECTED` | contractNo test identity; contractName from source title; contractType from source title; four test item keys; award noticeNo test identity; award projectName and winningDate from source | recorded separately from automatic extraction |
| `NOT_EXTRACTED` | partyBCreditCode, awardSupplierCreditCode | left empty; no value was fabricated |
| `NOT_EVALUATED` | quantity and unitPrice for each of four line items | original materials do not state them; all four lines stayed `AMOUNT_ONLY` |

Project name and category were human entered; the deterministic project-number suggestion was human confirmed. The selected award notice was explicitly associated as support authority through the visible UI. Durable A-mini attempts were `SUCCEEDED`: award accepted/rejected `2/2`, Contract accepted/rejected `13/13`. There were six durable fact-extraction model-call audit rows. Candidate extraction and human confirmation remained separate, and Formal Contract creation occurred only through Promotion.

### Exact ReviewRun results

The user explicitly started one ReviewRun. Read-only final DM8 proof found `PROJECT_RUN_COUNT=1`, five frozen materials, five steps, 27 findings and 48 validated evidence rows.

| Step | Terminal result | Durable output |
| --- | --- | --- |
| `MATERIAL_VALIDATION` | `COMPLETED` | exact five-file run material snapshot |
| `F02_DETERMINISTIC` | `COMPLETED` | one AuditTask and 8 AuditCheckResult rows |
| `SEMANTIC_AI` | `COMPLETED` | 8 persisted model-assisted findings plus evidence |
| `BREACH` | `COMPLETED` | 5 persisted deterministic findings plus evidence |
| `TERM` | `COMPLETED` | 6 persisted deterministic findings plus evidence |

F02 retained business-accurate results: amount words `PASS`, award amount `PASS`, award supplier `PASS`, line sum `PASS`, and four per-line arithmetic results `NOT_EVALUATED` because quantity and unit price were absent. No missing value was converted to `PASS`.

The first Semantic transport result failed validation with the safe reason `MISSING_REQUIRED_BREACH_CATEGORY`. Its repair succeeded; the remaining transport and repair calls succeeded. The step completed with four durable model-call audit rows including phase, outcome, safe reason, duration, finish reason and usage. No provider technical failure detail was presented as a user-facing page error.

### Eight-category projection

| Category | UI status | Attention count |
| --- | --- | ---: |
| 1. 合同条款量化一致性审查 | 资料不足或存在未评估项 | 4 |
| 2. 合同草本结构对标审查 | 资料不足或存在未评估项 | 0 |
| 3. 违约责任条款量化审查 | 发现需要关注的结果 | 5 |
| 4. 合同期限与终止条件格式审查 | 发现需要关注的结果 | 3 |
| 5. 合同主体资格审查 | 等待外部主体资格核验 | 0 |
| 6. 附件清单完整性审查 | 当前审核未覆盖 | 0 |
| 7. 合同生效条件与份数审查 | 当前审核未覆盖 | 0 |
| 8. 合同条款风险提示 | 本项已核对 | 0 |

The overview reported 12 attention items and four categories with incomplete coverage. Unavailable external qualification and uncovered attachment/effectiveness work were not presented as normal.

### Source navigation, copy and closeout

Three visible evidence controls switched the left material viewer to the expected frozen material and entered active `文本定位` mode. Each showed one highlighted match:

| Coverage | Frozen fileId | Validated locator | UI result |
| --- | --- | --- | --- |
| Contract body | `be9c6b25-9a5c-4bfe-8ea9-78704f4d069e` | frozen text offsets `286–373` with hash-backed bounded excerpt | exact Contract file selected and highlighted |
| Award/support | `2db94011-0382-4563-9a60-33ec67357df0` | frozen text offsets `223–327` with hash-backed bounded excerpt | exact Award Notice selected and highlighted |
| Tender material | `ec4e94db-1fda-456b-8960-86acc5768e8b` | frozen text offsets `1589–1624` with hash-backed bounded excerpt | exact Tender Document selected and highlighted |

All three evidence rows are FK-bound to the current run's exact file SHA, parse result, parse version and canonical text identity. The evidence document intentionally does not reproduce original text.

- Single-category opinion copy: PASS; browser success status and non-empty clipboard length `932` were observed.
- Overall opinion copy: PASS; browser success status and non-empty clipboard length `1447` were observed.
- One eligible Semantic finding (`e0894447-e10d-4c4b-bd15-dc2d62a5d8d3`) was acknowledged through the normal page. The POST returned 200, durable ACK count became one, and the source finding remained version 0 with the same category, attention and risk facts.
- Report generation returned 202 and produced report `2753ba49-52bd-4251-8c58-e95ba7531c82`, version 1, schema version 2, status `AVAILABLE`, artifact size `30728`. Its input snapshot identity exactly matched the ReviewRun input identity.

`SOURCE_NAVIGATION_3X=PASS`; `COPY_RESULT=PASS`; `ACK_RESULT=PASS`; `REPORT_RESULT=PASS`.

### Refresh, relogin and exact history

The user-facing `审核记录` list contained exactly one row for the test Contract. Initial open, browser refresh followed by normal navigation back to records, and logout/normal login followed by records navigation each reopened the same Project, Contract and exact Run. Each historical view loaded eight categories, all five frozen material tabs and the exact report artifact path. Historical mode exposed neither `重新发起审核` nor report generation.

Across these history operations, browser traffic contained zero ReviewRun-create POSTs and zero upload POSTs. Final read-only DM8 proof still showed `PROJECT_RUN_COUNT=1`, `REVIEW_MODEL_CALL_COUNT=4`, `FACT_MODEL_CALL_COUNT=6`, `ACK_COUNT=1`, `REPORT_COUNT=1`; the transaction was explicitly rolled back. Therefore history reads neither created a second run nor recalled the provider.

- `FINAL_REFRESH_RELOGIN=PASS`.
- `HISTORY_EXACT_RUN_REOPEN=PASS`.
- `PROVIDER_RECALLED_ON_HISTORY=NO`.

`GET /api/v1/notifications/unread-count` continued to return HTTP 500 because the known V031 `PROCUREMENT_NOTIFICATION` schema is absent. Login, navigation, Contract preparation, ReviewRun, closeout and historical state remained functional and unpolluted. `KNOWN_NONBLOCKING_V031_SCHEMA_DRIFT=YES`; V031 was not executed.

### Final status

`UI_01_GOLDEN_CHAIN=PASS` for exact code SHA `196db100e17f9bbd98a8be979baf172f23ce39e3`.

No blocker remains inside the authorized 01 Contract golden-chain scope. The known V031 notification drift remains non-blocking and unrepaired. Suites 02–06, other migrations, OA, risk-model expansion, main merge and 0.26 deployment were not started.

## Customer host deployment and single remote 01 verification (2026-09-29)

### Release identity and build evidence

- `DEPLOY_PRODUCT_SHA=196db100e17f9bbd98a8be979baf172f23ce39e3`.
- The deployment evidence branch started at `DEPLOY_EVIDENCE_SHA=ed5c2f1c2df7625a5b1c260f852ab6ec077a9ca3`. The evidence-only commit created after this section is recorded in the final handoff; it does not change the tested product tree.
- `RELEASE_ID=196db100-20260929-customer-portal`.
- `SSH_TARGET=<customer-host>`.
- `RELEASE_ARCHIVE_SHA256=9dc14c842a1ff74d428c4143ac66683e7f9ad265e456b15b59dd821a02e047fc`.
- `BACKEND_JAR_SHA256=52caf1a1ea20f180a571b8f65b6188ce94c25a049c5827461f862e0737edb0e1`.
- `FRONTEND_INDEX_SHA256=284cb966814a85067d9c1bb30a382ca65e7d7bf2f846d1972947b96103c0bfef`.
- `FRONTEND_AGGREGATE_SHA256=ce3d116c8fa296ce7844abe7888bd2ea2e0b7f082d6c6a547e6e19bf6a6625b7`.
- `FASTAPI_SOURCE_SHA256=bde63bfec785e1b1abee4f253155a6778858ac4e29d9709cd8032fb3d750a483`.
- `OLD_CURRENT_TARGET=/opt/audit-agent/releases/c3420cb-20260928-101122-ui-preview`.
- `NEW_CURRENT_TARGET=/opt/audit-agent/releases/196db100-20260929-customer-portal`.

The product release was built from a clean detached worktree at the exact product SHA. The Backend clean verification completed with 1,997 tests, zero failures, zero errors and two skipped. Both isolated and shared Frontend runners completed with 686 passing tests and zero failures, the production build completed, and the FastAPI suite completed with 415 passing tests and one deprecation warning. A package-content scan found no runtime environment, storage, source fixture or credential path in the archive.

### DM8 and runtime preflight

- DM8 reported 64-bit V8 and application schema `AUDIT_APP`.
- Catalog comparison covered 802 expected columns, 430 expected constraints and 71 expected indexes. Missing or mismatched objects: zero.
- All required historical Contract migrations, the P1-B support-authority chain, final-closeout fact/typed-clause migrations, and model-call owner uniqueness fix were classified `ALREADY_MATCHING`.
- F06 migrations V023 through V035, including V031 notifications and V032/V033, were classified `ALREADY_MATCHING`.
- The five F02 configuration rows and V028 roles each had the expected single-row identities.
- The final worker gate before cutover was claimable tasks 0, expired attempts 0, running tasks 0, valid leases 0, fact-extraction in-progress attempts 0 and active ReviewRuns 0.
- `DB_PREFLIGHT_RESULT=PASS_ALREADY_MATCHING`.
- `P1_B_SUPPORT_AUTHORITY_STATUS=ALREADY_MATCHING`.
- `V031_NOTIFICATION_STATUS=ALREADY_MATCHING`; notification unread smoke returned HTTP 200.
- `V032_STATUS=ALREADY_MATCHING`; `V033_STATUS=ALREADY_MATCHING`.
- `BACKUP_STATUS=NOT_REQUIRED_NO_DDL`.
- `DB_CHANGED=NO`; `MIGRATION_EXECUTED=NO`.

The approved private provider and exact model `audit-qwen-27b-16k:latest` were verified. The runtime preserves `OPENAI_COMPAT_STRUCTURED`, ordinary read timeout 60 seconds, Contract read timeout 600 seconds, total model budget 900 seconds, chunk size 6,000, maximum chunks 20, OCR CPU threads 1 and MKLDNN disabled. Backend uses AI read timeout 960,000 ms, Review worker timeout `PT20M` and role-assessment recovery age 15 minutes. Existing DB, Redis, session, storage, CORS and credential values were preserved.

- `SEMANTIC_TRANSPORT=OPENAI_COMPAT_STRUCTURED`.
- `MODEL_ID=audit-qwen-27b-16k:latest`.
- `AUDIT_AI_READ_TIMEOUT_MS=960000`.
- `AUDIT_CONTRACT_MODEL_READ_TIMEOUT_SECONDS=600`.
- `AUDIT_MODEL_TOTAL_BUDGET_SECONDS=900`.
- `AUDIT_CONTRACT_REVIEW_WORKER_TIMEOUT=PT20M`.
- `ENV_DIFF_ADDED`: explicit Java-side Contract fact-extraction enablement after the omission was found.
- `ENV_DIFF_UPDATED`: approved AI transport, timeout and feature settings required by the Golden runtime were aligned during deployment.
- `ENV_DIFF_PRESERVED`: all secrets, DB/Redis/session/storage/CORS identities and the existing demo-relax policy.
- `ENV_DIFF_DEFAULTED`: F06 policy thresholds remain code defaults and are not described as customer-approved policy.

During the one authorized 01 flow, read-only runtime diagnosis found that the Backend environment did not explicitly contain the Java-side `AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED` gate, although the FastAPI environment did. A protected timestamped Backend environment backup was created, exactly that boolean was atomically added, owner/group/mode were preserved, and only Backend was restarted. A content comparison excluding that key found no other Backend environment change. The FastAPI environment was not modified. No secret value was printed or stored in this document.

The protected Backend environment backup is `/var/backups/audit-agent/runtime-fact-20260929-143700/backend.env`.

### Deployment and smoke

- Uploaded archive hash and all unpacked release artifact hashes matched the local manifest.
- Python 3.11 runtime imports required by the release succeeded without installing or upgrading packages.
- `audit-ai`, `audit-backend`, `nginx`, `redis` and `DmServiceAUDITDB` are active.
- FastAPI `/health`, Backend actuator health, Backend API health and Nginx-proxied API health return success.
- The Frontend and normal authenticated login load through the LAN Nginx HTTP entry point.
- The six navigation entries, Budget page, Procurement page and Blacklist page loaded. Notification unread, preparation drafts, ordinary projects and Contract audit record reads returned without schema 500 responses.
- Nginx retained `proxy_read_timeout 650s`.
- The existing HTTPS certificate is expired. Nginx/TLS configuration was not changed; the authorized remote browser check used the actual LAN Nginx HTTP entry point.
- `ROLLBACK_REQUIRED=NO`; the new release remains current because product services and health gates are good and the 01 failure below is an invalid runtime ordering, not a code or health failure.
- `AI_SERVICE_HEALTH=PASS`; `BACKEND_HEALTH=PASS`; `NGINX_HEALTH=PASS`; `FRONTEND_HEALTH=PASS`.

### Single authorized remote 01 result

- `PROJECT_ID=519873f1-5d1e-41c8-a1fc-5d815c252d1c`.
- `CANDIDATE_ID=4269cfec-7112-4e85-b087-6db93ce4b5fd`.
- `CONTRACT_ID=NONE`.
- `RUN_ID=NONE`.
- Five approved 01 originals were uploaded through the visible application flow; all five reached `PARSED`.
- Refresh, logout, normal login and `待继续` rediscovered the exact Project through the ordinary preparation-draft flow. No second Project was created.
- Project name and category were human entered; the deterministic project number was confirmed. Four suggested roles were confirmed, while the fifth file role was manually corrected from `UNKNOWN` to `SUPPLEMENT`. All five identity decisions were human confirmed. Award support authority was associated through the visible UI.

After the Backend fact-extraction gate was corrected, normal visible actions ran CORE/LINE_ITEMS/AWARD extraction against the same Project:

| Source | Durable attempt | Safe model-call audit | Accepted | Rejected |
| --- | --- | ---: | ---: | ---: |
| Contract body | `SUCCEEDED` | four transport/repair rows: two `INVALID_TYPED_SCHEMA` failures followed by two successful repairs | 13 | 13 |
| Award notice | `SUCCEEDED` | two transport/repair rows: one `INVALID_TYPED_SCHEMA` failure followed by one successful repair | 2 | 2 |

The Candidate had already been materialized before the Java-side gate was corrected. The evidence-validated A-mini facts therefore did not enter that existing Candidate. Its automatic fact snapshot still had only `contractName`, `partyA` and `partyB`; `contractNo`, `partyBCreditCode`, `contractAmount`, `amountInWords`, all line items and the award reference were absent. No missing Contract fact was manually added or corrected, and no Formal Contract was promoted.

Continuing would have required manually filling facts, creating a second Project, or changing existing authority decisions. All are prohibited by the task and would falsely represent automatic extraction. The single remote 01 stopped at this material-extraction/adoption gate. It produced no Confirmation, Contract, ReviewRun, provider-backed Semantic result, evidence navigation, opinion copy, ACK/report or exact-run history to verify.

- `REMOTE_UI_01_GOLDEN_CHAIN=FAIL`.
- `PREPARATION_DRAFT_REDISCOVERY=PASS`.
- `FIVE_STEP_RESULT=NOT_RUN`.
- `EVIDENCE_3X=NOT_RUN`.
- `COPY_RESULT=NOT_RUN`.
- `ACK_REPORT_RESULT=NOT_RUN`.
- `HISTORY_EXACT_RUN_REOPEN=NOT_RUN`.
- `BUDGET_PAGE_SMOKE=PASS`.
- `PROCUREMENT_PAGE_SMOKE=PASS`.
- `BLACKLIST_PAGE_SMOKE=PASS`.
- `NOTIFICATION_API_SMOKE=PASS`.

`REMAINING_BLOCKERS`: this unique remote 01 cannot become a valid Golden chain because Candidate materialization preceded activation of the Java A-mini gate. The preserved Project and Candidate are failure evidence and were not rewritten. A future full remote Golden-chain attempt requires separate authorization for a new Project; this task explicitly prohibits creating a second 01. Suites 02 through 06 were not started.

## Customer host Contract navigation identity fix continuation (2026-09-29)

### Exact release and preserved business identity

- `NAV_FIX_SHA=366c0cf2ab1b6c936f5459898ac54f3cf3143984`.
- `RELEASE_ID=366c0cf-20260929-contract-nav-fix`.
- `CURRENT_RELEASE=/opt/audit-agent/releases/366c0cf-20260929-contract-nav-fix`.
- `PROJECT_ID=361933f9-8c95-48a5-afb9-ce44bb9a9633`.
- `CANDIDATE_ID=5920912f-9a77-4893-8be9-c6b4db28aa71`.
- `CONFIRMATION_ID=85a3f0f7-6730-48ce-8f26-560403485940`.
- `CONTRACT_ID=069b873b-1ebf-49cd-a11d-5e412eff13f7`.

The Frontend navigation fix allows the authenticated opaque user identity to namespace continuation state while retaining strict UUID validation for Project and Contract identities. Focused tests passed 8/8 and 14/14, both full Frontend runners passed 691/691, and the production build completed. Backend and FastAPI artifacts were unchanged from the preceding release. The deployed Frontend asset was `/assets/index-BUSGuOD0.js`.

After deployment, normal login and the visible Contract `待继续` path rediscovered the exact Formal Contract. A read-only preflight showed no running attempts, no active ReviewRuns, no in-progress fact extraction, all expected Project/Candidate/Confirmation/Contract identities present exactly once, and `PROJECT_RUN_COUNT=0` before the authorized action.

### Unique authorized ReviewRun result

- `RUN_ID=3ab51812-801e-48b8-8590-9718569d2876`.
- `RUN_STATUS=FAILED`.
- `RUN_MATERIAL_COUNT=5`.

The Run was created once through the visible material picker with all five frozen materials selected. No second ReviewRun was created. A normal authenticated read returned the following durable step state:

| Step | Durable status | Safe reason | Output identity |
| --- | --- | --- | --- |
| `MATERIAL_VALIDATION` | `COMPLETED` | none | run material snapshot bound to the exact Run |
| `F02_DETERMINISTIC` | `COMPLETED` | none | `AUDIT_TASK_F02_NAVIGATION_V2`, resource `643d7f0f-a425-4f0b-be3a-9ea6e90ab8e7` |
| `SEMANTIC_AI` | `FAILED` | `STEP_EXECUTION_FAILED` | none |
| `BREACH` | `COMPLETED` | none | finding set `6373de79-0dc6-37e0-a3ed-916c2a1166f8` |
| `TERM` | `COMPLETED` | none | finding set `ec822be2-4aca-3668-9bf8-673b3fc486b2` |

The post-failure read-only database gate showed `RUNNING_ATTEMPTS=0`, `ACTIVE_RUNS=0`, `FACT_IN_PROGRESS=0` and `PROJECT_RUN_COUNT=1`. The failed Run remains durable and was not retried, modified or replaced. Candidate, Confirmation and Contract business facts were not changed.

Because the only authorized Run failed at `SEMANTIC_AI`, the instruction required an immediate stop. Eight-category completion, three representative source navigations, opinion copy, ACK, report generation and exact-run refresh/relogin history were not claimed or exercised after failure.

- `REMOTE_EXISTING_PROJECT_CONTINUATION=PASS`.
- `REMOTE_REVIEW_CHAIN_ON_FIX_SHA=FAIL`.
- `REMOTE_FULL_CHAIN_SINGLE_SHA=NO`.
- `EIGHT_CATEGORY_RESULT=NOT_VERIFIED_AFTER_FAILED_RUN`.
- `SOURCE_NAVIGATION_3X=NOT_RUN`.
- `COPY_RESULT=NOT_RUN`.
- `ACK_RESULT=NOT_RUN`.
- `REPORT_RESULT=NOT_RUN`.
- `HISTORY_EXACT_RUN_REOPEN=NOT_RUN`.
- `SECOND_REVIEW_RUN_CREATED=NO`.

`REMAINING_BLOCKER`: the exact deployed navigation-fix release reached ReviewRun execution, but its sole authorized Run failed in the persisted Semantic step with safe reason `STEP_EXECUTION_FAILED`. The failure is preserved for diagnosis. No live rule, environment, schema or business fact was changed, and suites 02 through 06 were not started.

## Read-only Semantic failure diagnosis (2026-09-29)

This diagnosis performed authenticated GETs, read-only DM8 transactions, bounded journal reads and an offline request-builder replay. It did not retry the step, create another ReviewRun, invoke FastAPI/provider, or modify business data, configuration, schema, service state or product source.

### Release and durable attempt identity

- `CURRENT_RELEASE=/opt/audit-agent/releases/366c0cf-20260929-contract-nav-fix`.
- `PRODUCT_SHA=366c0cf2ab1b6c936f5459898ac54f3cf3143984`.
- `FRONTEND_ASSET=/assets/index-BUSGuOD0.js`.
- Backend and FastAPI health remained `UP`.
- `RUN_ID=3ab51812-801e-48b8-8590-9718569d2876`.
- `SEMANTIC_STEP_ID=d2c431d6-9e00-38f0-9874-52d186292f04`.
- `SEMANTIC_ATTEMPT_ID=4f73c655-4acd-3c9b-8921-d599fa191b45`.
- `ATTEMPT_NO=1`; `ATTEMPT_VERSION=2`.
- `STEP_STATUS=FAILED`; `STEP_SAFE_REASON=STEP_EXECUTION_FAILED`.
- `ATTEMPT_STATUS=FAILED`; `ATTEMPT_SAFE_REASON=STEP_EXECUTION_FAILED`.
- `EXTERNAL_CALL_STATE=NOT_STARTED`.
- `EXTERNAL_FINGERPRINT_PRESENT=NO`; `PROVIDER_REQUEST_ID_PRESENT=NO`.
- Durable attempt provider/model are both absent.
- Attempt started at `2026-09-29T08:50:08.008003Z` and completed at `2026-09-29T08:50:08.031373Z`.

The existing diagnostics GET returned sanitized `UNKNOWN` for both safe reason and failure stage because `STEP_EXECUTION_FAILED` is outside its safe-code allowlist. This is an observability projection result, not a second root cause.

### Provider-path determination

- `MODEL_CALL_COUNT=0`; `NO_DURABLE_PROVIDER_CALL_AUDIT`.
- The audit-ai journal contained zero `POST /ai/v1/agent/contract-text-analysis` requests in the exact three-second failure window.
- `FASTAPI_CONTRACT_ANALYSIS_REQUEST_COUNT=0`.
- `PROVIDER_PATH_REACHED=NO`.

The Backend journal associated the failing request with root cause class `java.lang.IllegalArgumentException` at `FrozenSemanticRequestBuilder.build:44`. The execute request returned 500 after 60 ms. No prompt, source text, request body or model response was read or recorded.

### Frozen input and offline builder replay

All five remote materials had unique run-material IDs, unique file IDs and unique positive ordinals. Every file SHA and canonical-text SHA had valid SHA-256 form; every parse version was 1, every parse status was `PARSED`, every canonical-text hash recomputation matched, and every frozen parse tuple matched its exact parse row. Role and identity decisions were present for every material; source-role disposition was absent for all five and is not a builder prerequisite.

| Ordinal | File ID | Effective role | Canonical hash | Redaction |
| ---: | --- | --- | --- | --- |
| 1 | `345dc3c7-a7c2-40c0-be7c-cc3074cd1776` | `SUPPLEMENT` | MATCH | PASS, nonblank, `7098950917d3620645bbba89d3543cc632df24b3330f247abc2ae3fc2234fc02` |
| 2 | `678e7db5-3eb6-48bd-899c-19da2f13e136` | `TENDER_DOCUMENT` | MATCH | PASS, nonblank, `9d08eb547c555a40bf2fd142a06dfc31708ad90efe269b91c46315c0a4b597c0` |
| 3 | `df75ca7c-acc0-489e-9d7c-1189b97ade16` | `CONTRACT_BODY` | MATCH | PASS, nonblank, `67115e6ac2999ad512429962c269291f4a9ff4cac4efd09de486901eaf81ef94` |
| 4 | `ed179154-74ec-4cb3-b2b1-d05a632b501d` | `BID_RESPONSE` | MATCH | PASS, nonblank, `2a423900ef7f4f88065c4d8d4bd9fe77e2d9d8e7acbde31af953f5cab5fa225d` |
| 5 | `f63e7568-f23c-4621-8aba-fa88b0190c5e` | `AWARD_NOTICE` | MATCH | PASS, nonblank, `1f19c9ea7d9de5d645fcc9006facc77f56eafe331d2ab8005ce3ed2d2d840a13` |

`FROZEN_INPUT_INTEGRITY=PASS`. Run and step policy both equal `contract-semantic-java-schema-v1-redaction-v1-grounding-v1`; run/step/attempt identities match.

Using the exact release JAR's `ContractAiTextRedactor` and `FrozenSemanticRequestBuilder`, an in-memory, read-only replay with the persisted frozen tuple and normalized durable context returned `REQUEST_BUILD=PASS`. The resulting fingerprint was `ec397dde24a81330e0938ac8a0d89cfa344360f24fb48a3c2c833019ba470c2b`; no durable fingerprint existed for comparison. The replay did not call `markExternalRequested` or `analyzeContractText`.

### Generic escape point and root cause

The original execution failed at `FrozenSemanticRequestBuilder.build:44`, which is the combined execution-context invariant. All constituent ID, type and policy checks are durably satisfied. The failing constituent is the full-record comparison `input.run().equals(context.run())`.

The start coordinator constructs the in-memory RUNNING run with `Clock.systemUTC().instant()`, persists it through a DM8 `TIMESTAMP`, and the DM8 repository returns the un-reloaded `next` object to the execution context. `FrozenReviewInputLoader` immediately reloads the same run from DM8. Record equality therefore includes mutable execution status/version/timestamps and database precision normalization. During this run no other step completed before Semantic failed: Semantic started first, the other executable steps only started, and the first other completion occurred 41 ms after Semantic failed. The durable identities and policies remained unchanged. The mismatch is consequently the newly persisted/reloaded execution metadata, with `startedAt` precision normalization as the concrete differing field path.

`FrozenSemanticReviewStepExecutor.execute` calls `requests.build` before its semantic failure-classification try/catch. The uncategorized `IllegalArgumentException` escapes to `ReviewStepExecutionService.execute`, which folds it into `STEP_EXECUTION_FAILED`.

- `GENERIC_FAILURE_ESCAPE_POINT=FrozenSemanticRequestBuilder.build:44 -> FrozenSemanticReviewStepExecutor.execute:65 -> ReviewStepExecutionService.execute:96-98`.
- `ROOT_CAUSE=full ContractReviewRun record equality crosses a DM8 persistence precision boundary for mutable execution metadata`.
- `OBSERVABILITY_GAP=YES`.

### Successful integration Golden comparison

The successful integration Golden Run `a9d92850-54dc-4546-bc12-60161c143cd5` also had five parse-version-1 materials, complete canonical hash integrity and the same model policy. Its role sequence was `AWARD_NOTICE, SUPPLEMENT, CONTRACT_BODY, BID_RESPONSE, TENDER_DOCUMENT`; the failed remote sequence was `SUPPLEMENT, TENDER_DOCUMENT, CONTRACT_BODY, BID_RESPONSE, AWARD_NOTICE`. Both sequences satisfy the builder's sorted frozen-material contract.

The Golden Semantic attempt completed with `EXTERNAL_CALL_STATE=COMPLETED`, a durable fingerprint and four model-call audit rows using `OPENAI_COMPATIBLE` and `audit-qwen-27b-16k:latest`. The failed remote attempt stopped before external marking, so provider/model and model-call rows are absent.

`FAILED_REMOTE_VS_GOLDEN_STRUCTURAL_DELTA`: material identities and ordinal role sequence differ, while material count, parse versions, canonical integrity and policy versions match. The decisive execution delta is pre-provider full-record context equality; it is unrelated to model output or transport.

### Retry policy and recommended correction

`ReviewStepRetryPolicy` classifies the same-run Semantic step as `ELIGIBLE`: both step and attempt are `FAILED`, the step is not material validation, and external state is `NOT_STARTED`. A retry would create attempt #2 inside the same Run ID, not a second ReviewRun. This diagnosis did not execute that retry.

- `CODE_FIX_REQUIRED=YES`.
- `ENV_FIX_REQUIRED=NO`.
- `DB_FIX_REQUIRED=NO`.

Recommended minimal fix:

1. Replace full `ContractReviewRun.equals` in `FrozenSemanticRequestBuilder` with explicit immutable frozen identity comparisons: run/project/contract IDs, input snapshot identity, contract snapshot identity and model policy.
2. Preserve the existing step, attempt and policy identity checks.
3. Move or wrap request construction in the Semantic validation mapping so an expected context/input validation failure becomes `SEMANTIC_INPUT_INVALID`, while preserving fail-closed behavior.
4. Add regression coverage for a persisted/reloaded DM8 timestamp precision difference and for mutable run status/version changes that do not alter frozen input identity.

`RETRY_NOT_EXECUTED=YES`; `SECOND_REVIEW_RUN_CREATED=NO`.

## Customer host Semantic run-identity fix deployment and retry gate (2026-09-29)

### Exact code, tests and release

- `BASE_NAV_FIX_SHA=366c0cf2ab1b6c936f5459898ac54f3cf3143984`.
- `SEMANTIC_FIX_SHA=6d952598b6d86f9d2b0c7dba3d936d531f9ff930`.
- `OLD_RELEASE=/opt/audit-agent/releases/366c0cf-20260929-contract-nav-fix`.
- `NEW_RELEASE=/opt/audit-agent/releases/6d95259-20260929-semantic-run-identity-fix`.
- The Backend JAR SHA-256 is `e26977cf6be53d8b93afd008b57cf6cf33746368d031611eacbe69340543d85d`.
- The deployed Frontend remains the byte-identical `index-BUSGuOD0.js`; its direct SHA-256 is `660c9951a05d785460b60eb250caa19861b38f5587b0fb2f6b8fd3571fb4c4d7`.
- FastAPI source and the packaged DM8 migrations are unchanged from the base release. No migration or DDL was executed.

The fix replaces full mutable `ContractReviewRun` record equality with explicit frozen identity comparison and maps request-builder `IllegalArgumentException` to `SEMANTIC_INPUT_INVALID` without broadening the provider catch boundary. Focused regression passed 20/20; nearby review-run regression passed 54/54; Backend `clean verify` passed 2004 tests with 0 failures, 0 errors and 2 skipped. Independent review found no remaining Critical or Important issue.

The archive SHA-256 was verified before extraction. The manifest and unpacked Backend/Frontend artifact hashes matched. After switching `current`, `audit-ai`, `audit-backend`, `nginx`, `redis` and `DmServiceAUDITDB` were active. Direct FastAPI health, Backend actuator/API health, Nginx-proxied API health and the expected Frontend asset all passed. No environment file was modified.

The local feature branch contains the fix commit. A non-force source push was rejected by automatic approval review because it did not recognize authorization for this branch; the rejection was not bypassed. Consequently `REMOTE_SHA` remains `366c0cf2ab1b6c936f5459898ac54f3cf3143984` while the deployed release manifest identifies the exact local fix SHA above.

### Exact existing Run and authorized retry preflight

- `PROJECT_ID=361933f9-8c95-48a5-afb9-ce44bb9a9633`.
- `CONTRACT_ID=069b873b-1ebf-49cd-a11d-5e412eff13f7`.
- `RUN_ID=3ab51812-801e-48b8-8590-9718569d2876`.
- `OLD_SEMANTIC_ATTEMPT_ID=4f73c655-4acd-3c9b-8921-d599fa191b45`.
- `NEW_SEMANTIC_ATTEMPT_ID=NONE`.
- `PROJECT_RUN_COUNT=1`.
- `SEMANTIC_ATTEMPT_COUNT=1`.

The deployment preflight again showed `RUNNING_ATTEMPTS=0`, `ACTIVE_RUNS=0`, `FACT_IN_PROGRESS=0`, all required Project/Candidate/Confirmation/Contract identities present, their Project/Contract association present, and one ReviewRun for the Project. Normal authenticated exact and latest GETs both resolved the same Run. The Run and attempt remained:

| Step | Status | Attempt | External state | Safe reason |
| --- | --- | ---: | --- | --- |
| `MATERIAL_VALIDATION` | `COMPLETED` | 0 | n/a | none |
| `F02_DETERMINISTIC` | `COMPLETED` | 1 | durable output | none |
| `SEMANTIC_AI` | `FAILED` | 1 | `NOT_STARTED` | `STEP_EXECUTION_FAILED` |
| `BREACH` | `COMPLETED` | 1 | durable output | none |
| `TERM` | `COMPLETED` | 1 | durable output | none |

The normal UI path `登录 -> 合同审核 -> 待继续 -> 已确认审核对象 -> 开始合同审核` reopened the exact Project, Contract and Run. New ReviewRun POSTs were hard-blocked in the browser verification harness; none was attempted. The workspace displayed the persisted eight-category projection and its exact Run pointer, but no `重新处理` action.

The reason is a verified protocol gate rather than the timestamp fix: the live Run response reports `executionMode=CLIENT_COMPATIBILITY`. `retryableCurrentAttempt` only exposes retry for `SERVER_WORKER`, and `ContractReviewStepRetryController` is registered only when `audit.contract.review-worker.enabled=true`. The current runtime does not project worker ownership. The authorization explicitly prohibited environment-variable changes, and direct hidden-API execution was also prohibited. No retry POST was sent.

- `SEMANTIC_ATTEMPT_2_STATUS=NOT_CREATED`.
- `RUN_FINAL_STATUS=FAILED`.
- `FIVE_STEP_FINAL_RESULT=4_COMPLETED_1_FAILED`.
- `EIGHT_CATEGORY_RESULT=EXACT_RUN_PROJECTION_REOPENED; POST_RETRY_FINAL_NOT_VERIFIED`.
- `SOURCE_NAVIGATION_3X=NOT_RUN_AFTER_RETRY`.
- `COPY_RESULT=NOT_RUN_AFTER_RETRY`.
- `ACK_RESULT=NOT_RUN_AFTER_RETRY`.
- `REPORT_RESULT=NOT_RUN_AFTER_RETRY`.
- `HISTORY_EXACT_RUN_REOPEN=PASS_PRE_RETRY; POST_RETRY_NOT_RUN`.
- `PROVIDER_RECALLED_ON_HISTORY=NO`.
- `REMOTE_EXISTING_RUN_REPAIRED_BY_SAFE_RETRY=NO`.
- `REMOTE_SAME_RUN_SEMANTIC_RETRY=BLOCKED_BY_CLIENT_COMPATIBILITY_EXECUTION_MODE`.
- `REMOTE_FULL_CHAIN_SINGLE_SHA=NO`.
- `SECOND_REVIEW_RUN_CREATED=NO`.
- `ATTEMPT_3_CREATED=NO`.

`REMAINING_BLOCKER`: creating attempt #2 through the normal UI requires the runtime to expose worker ownership and register the retry controller. Changing that runtime setting is outside this authorization and may let the worker claim other durable pending work, so it was not changed. Attempt #1 remains immutable failure evidence; Project, Candidate, Confirmation and Contract facts were not modified.

### Review Worker enablement readiness (2026-09-29)

- `WORKER_ENABLE_PRECHECK=PASS`.
- Global preflight and postcheck both returned claimable, pending, running, valid lease, expired lease, active Run and UNKNOWN Run counts of `0`; `FACT_EXTRACTION_IN_PROGRESS=0`.
- `AUDIT_CONTRACT_REVIEW_WORKER_ENABLED=true`, with exactly one target key. All non-target configuration bytes were unchanged; owner/group/mode remained `root:admin/640`.
- `BACKEND_ENV_BACKUP_CREATED=YES`: `/var/backups/audit-agent/backend.env.pre-review-worker-20260929T112037Z`.
- `BACKEND_RESTART=PASS`; actuator and API health returned UP after restart. The current release remained `/opt/audit-agent/releases/6d95259-20260929-semantic-run-identity-fix`.
- Normal authenticated exact Run GET returned `REVIEW_EXECUTION_MODE=SERVER_WORKER`.
- `RETRY_UI_VISIBLE=NO`. The fresh normal-browser path reached the exact Project details but did not enter the ReviewRun workspace: it emitted no review-run GET or POST and remained on the confirmed contract detail. This was recorded without changing Frontend code because this task only authorized worker readiness and Git publication.
- Browser hard blocks remained active for retry and new-Run POSTs: `RETRY_POST_COUNT=0`, `SECOND_REVIEW_RUN_CREATED=NO`.
- Final durable state remained `RUN_STATUS=FAILED`, `PROJECT_RUN_COUNT=1`, `SEMANTIC_ATTEMPT_COUNT=1`, attempt #1 `FAILED/NOT_STARTED`, and `ATTEMPT_2_CREATED=NO`.
- Model-call audit count remained `0 -> 0`: `PROVIDER_CALLED_AFTER_WORKER_ENABLE=NO`.
- No migration, DDL, Project/Candidate/Confirmation/Contract mutation, ReviewRun creation, retry, FastAPI Semantic call or provider call was performed.
