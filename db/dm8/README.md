# DM8 baseline SQL

V058 (`V059__client_review_identity.sql`) adds the single-client formal-name/alias
registry for deterministic review perspective and outbound model name protection.
No customer identity is seeded. Apply through the controlled DM8 migration process,
then provision approved names inside the deployment. See
`docs/runbook/client-review-identity.md` for worker draining, restart, Python header
enforcement and the required pre-production checks. This migration was not applied
to a real DM8 instance during local development.

V057 (`V057__material_extraction_deadline.sql`) adds nullable `DEADLINE_AT` to the
shared material ledger. Apply with `MaterialDeadlineMigration apply` only after the
existing V055 schema passes its guard, and before starting the timeout-enabled
backend. `check` is read-only; schema drift stops execution. Historical NULL deadlines
are not rewritten. The column preserves a material's 15-minute extraction deadline
across restarts; unrelated business data is unchanged.

DM8 是唯一正式关系数据库。仓库没有自动 migration runner；`Vxxx__*.sql` 必须由批准的受控流程
按环境实际 schema 状态执行。SQL 文件存在只表示 `MIGRATION EXISTS`，不表示
`DEPLOYED ON CUSTOMER DM8`。

## Current migration map

| File | Primary schema effect |
|---|---|
| `V001__audit_task_baseline.sql` | `AUDIT_TASK` |
| `V002__local_account_auth_observability.sql` | `SYS_USER`、`SYS_ROLE`、`SYS_USER_ROLE`、`AUTH_LOGIN_AUDIT`、`AUDIT_OPERATION_LOG` |
| `V003__f02_contract_review.sql` | `CONTRACT`、line item、award notice、rule config、`AUDIT_CHECK_RESULT`、`IDEMPOTENCY_RECORD` |
| `V003_f02_seed.sql` | F02 controlled seed/config，单独确认后执行 |
| `V004__contract_review_session.sql` | `CONTRACT_REVIEW_SESSION`、`CONTRACT_REVIEW_DOCUMENT`、`CONTRACT_REVIEW_MATERIAL_BINDING` |
| `V005__contract_review_session_soft_delete.sql` | ReviewSession tombstone compatibility columns |
| `V006__f01_budget_approval.sql` | `PROCUREMENT_DEMAND`、`BUDGET_SUBJECT_MAPPING`、`BUDGET_APPROVAL_CHECK`，并含 demo seed |
| `V007__f01_material_review.sql` | `BUDGET_REVIEW_TASK`、`BUDGET_REVIEW_ITEM` |
| `V008__f01_material_check_fields.sql` | F01 material check columns；与 V007 有重复 ADD 风险 |
| `V009__review_finding_acknowledgement.sql` | `REVIEW_FINDING_ACKNOWLEDGEMENT` |
| `V010__project_intake_core.sql` | `PROJECT`、`PROJECT_FILE`、`CONTRACT_CANDIDATE`、`PROJECT_EXTRACTION_RESULT`、candidate line item |
| `V011__project_contract_association.sql` | `PROJECT_CONTRACT` |
| `V012__f01_validation_risk_fields.sql` | F01 validation risk fields |
| `V013__f01_validation_confirm_log.sql` | `BUDGET_VALIDATION_CONFIRM_LOG` |
| `V014__f01_project_and_year_validation_config.sql` | `BUDGET_PROJECT_CONFIG`、`BUDGET_YEAR_VALIDITY_CONFIG` |
| `V015__f01_category_rules.sql` | `BUDGET_CATEGORY_RULES` |
| `V016__f04_budget_approval_program.sql` | `BUDGET_APPROVAL_RULE_CONFIG`、`BUDGET_APPROVAL_VALIDATION`、F04 indexes/seed |
| `V017__project_file_parse_provenance.sql` | ProjectFile parsed text/method/engine/confidence/page/duration/reason provenance |
| `V018__f03_blacklist.sql` | `BLACKLIST_SUPPLIER`、`BLACKLIST_ALERT`、`BLACKLIST_RULE` |
| `V019__f03_blacklist_operation_log.sql` | `BLACKLIST_SUPPLIER_OPERATION_LOG` |
| `V020__f01_budget_semantic_analysis.sql` | F01 semantic analysis persistence fields |
| `V021__f01_ai_provenance.sql` | F01 AI provenance fields |
| `V022__f01_ai_node_results.sql` | F01 AI node result fields |
| `V023__f06_procurement_quote_alert.sql` | F06 quote comparison facts |
| `V024__procurement_project_creation.sql` | Procurement project creation fields |
| `V025__procurement_approval_workflow.sql` | Procurement approval workflow facts |
| `V026__procurement_review_report.sql` | Procurement review reports |
| `V027__procurement_review_report_snapshot.sql` | Procurement report snapshots |
| `V028__procurement_approval_assignment_and_thresholds.sql` | Approval assignments and thresholds |
| `V029__procurement_approval_resubmission.sql` | Approval resubmission history |
| `V030__procurement_approval_return_snapshot.sql` | Approval return snapshots |
| `V031__procurement_notification.sql` | Procurement notifications |
| `V032__operation_audit_details.sql` | Operation audit detail fields |
| `V033__procurement_field_confirmation_audit.sql` | Field confirmation audit facts |
| `V034__procurement_agent_review_snapshot.sql` | Procurement agent review snapshot |
| `V035__f07_procurement_tender_document.sql` | Procurement tender document facts |

## Contract evolution migration namespace

Phase 1A/1B 的新增表使用独立、非数字的 Contract namespace，避免在尚未完成所有环境
schema preflight 时伪装成可自动顺序执行的 `V023`、`V024` 或 `V025`。这两个文件是
`CONTROLLED_MANUAL` 声明，不会改变既有 V001—V022 历史：

- V001—V022 是现有 shared historical migration line；`CONTRACT__*.sql` 是新的合同演进线。
- 仓库没有 automatic migration runner；Contract SQL 只能按 `DEPENDS-ON` 和获批准的 deployment plan 执行。
- 文件名、filesystem sort、alphabetical order 或 glob order 都不是执行顺序。
- SQL 文件存在不表示 customer DM8 已部署；每个环境都必须先完成 catalog preflight。

| File | Primary schema effect |
|---|---|
| `CONTRACT__project_file_parse_result.sql` | immutable `PROJECT_FILE_PARSE_RESULT` history |
| `CONTRACT__project_file_role_assessment.sql` | role assessment、evidence 与 append-only human decision history |
| `CONTRACT__contract_candidate_authority.sql` | parse/role-bound candidate provenance and parse-bound extraction evidence |
| `CONTRACT__project_file_identity_assessment.sql` | project identity assessment、evidence 与 human resolution history |
| `CONTRACT__contract_candidate_support_authority.sql` | current Candidate-owned award-support authority cohorts; P1-B application writes require approved/applied migration |
| `CONTRACT__review_session_project_file_binding.sql` | Project-backed ReviewSession references to authoritative ProjectFile material；also depends on V004、V005 and V011 |
| `CONTRACT__contract_candidate_confirmation.sql` | durable human candidate confirmation and promotion authority |
| `CONTRACT__contract_system_auto_authority.sql` | explicit SYSTEM_AUTO candidate authority vocabulary; never an automatic numbered migration |
| `CONTRACT__review_run.sql` | immutable review-run material manifest、steps and attempts |
| `CONTRACT__review_run_results.sql` | durable non-F02 finding and evidence facts |
| `CONTRACT__sanitized_debug_bundle.sql` | sanitized debug-bundle metadata and artifact identity |
| `CONTRACT__review_ack_report.sql` | non-F02 acknowledgement and versioned report facts；also depends on V009 acknowledgement semantics |
| `CONTRACT__contract_final_closeout.sql` | approved final-closeout amount-only Contract line basis; later A/C effects require their exact schema authorization |
| `CONTRACT__contract_final_closeout_fact_extraction.sql` | approved A-mini durable attempts, raw candidate validation and publication identity |
| `CONTRACT__contract_final_closeout_typed_clause.sql` | approved BREACH/TERM v2 typed bilateral values and frozen evidence links |
| `CONTRACT__contract_fact_v2_audit.sql` | A-mini v2 rejection/model-call audit and precision widening |
| `CONTRACT__contract_model_call_owner_unique_fix.sql` | DM8 owner-aware uniqueness for fact-extraction and review-step model calls |

批准后的精确依赖顺序为：

```text
V010__project_intake_core.sql -> V017__project_file_parse_provenance.sql
CONTRACT__project_file_parse_result.sql -> CONTRACT__project_file_role_assessment.sql
CONTRACT__project_file_role_assessment.sql -> CONTRACT__contract_candidate_authority.sql
CONTRACT__contract_candidate_authority.sql -> CONTRACT__project_file_identity_assessment.sql
CONTRACT__project_file_identity_assessment.sql -> CONTRACT__contract_candidate_support_authority.sql
CONTRACT__contract_candidate_authority.sql + CONTRACT__project_file_identity_assessment.sql -> CONTRACT__contract_system_auto_authority.sql
CONTRACT__contract_candidate_support_authority.sql -> CONTRACT__review_session_project_file_binding.sql
CONTRACT__project_file_identity_assessment.sql -> CONTRACT__contract_candidate_confirmation.sql
CONTRACT__contract_candidate_confirmation.sql -> CONTRACT__review_run.sql
CONTRACT__review_run.sql -> CONTRACT__review_run_results.sql
CONTRACT__review_run_results.sql -> CONTRACT__sanitized_debug_bundle.sql
CONTRACT__sanitized_debug_bundle.sql -> CONTRACT__review_ack_report.sql
V003__f02_contract_review.sql + CONTRACT__contract_candidate_confirmation.sql + CONTRACT__review_run_results.sql -> CONTRACT__contract_final_closeout.sql
CONTRACT__contract_final_closeout.sql + CONTRACT__project_file_identity_assessment.sql + CONTRACT__contract_candidate_authority.sql -> CONTRACT__contract_final_closeout_fact_extraction.sql
CONTRACT__review_run_results.sql -> CONTRACT__contract_final_closeout_typed_clause.sql
CONTRACT__contract_fact_v2_audit.sql -> CONTRACT__contract_model_call_owner_unique_fix.sql
```

The Contract evolution line is CONTROLLED_MANUAL. It does not allocate a V023+ migration;
the V023+ numbered files belong to the independently approved Backend B procurement and budget line.
The dependency metadata, not alphabetical order, controls Contract migration execution. The support-authority
branch requires approval and application before P1-B application writes are enabled.

执行 Contract namespace 前，必须按下文 catalog preflight 对上述每个 SQL 的每张表、列、约束和索引
分别判定 `NOT_APPLIED`、`ALREADY_MATCHING`、`PARTIAL_SCHEMA_DRIFT` 或 `UNKNOWN`；
`PARTIAL_SCHEMA_DRIFT` 沿用下文既有的 `PARTIAL/SCHEMA_DRIFT` fail-closed 语义。
只有全部为 `NOT_APPLIED` 且执行/备份/回滚计划已获批准时，才能按上述顺序执行；
`ALREADY_MATCHING` 不重复 DDL，`PARTIAL/SCHEMA_DRIFT` 和 `UNKNOWN` 一律停止并另立 repair 任务。
仓库中存在这些文件不表示任何环境已执行，本阶段没有运行 migration。

## Preflight classification

首次执行或续接前，对每个 migration effect 做只读 catalog 查询，并将结果分类：

```text
NOT_APPLIED
ALREADY_MATCHING
PARTIAL/SCHEMA_DRIFT
UNKNOWN
```

- `NOT_APPLIED`：进入批准 migration plan。
- `ALREADY_MATCHING`：不要重复执行 DDL；单独处理 history reconciliation。
- `PARTIAL/SCHEMA_DRIFT`：停止，形成精确 repair + backup + rollback 任务。
- `UNKNOWN`：fail closed，不猜测。

表名存在不足以判定 matching；必须比较 column type/width/null/default、PK/UK/index 和必要 seed/config。

## Known migration debt

### V007 / V008

V007 创建 `BUDGET_REVIEW_ITEM` 时已包含 `SUBJECT_CODE`、`SUPPLIER_NAME`、
`APPROVAL_SUBJECT_CODE`、`APPROVAL_SUPPLIER_NAME`；V008 再次增加这些列。fresh schema 直接顺序执行
可能失败。不得在本次文档任务修改 migration；部署前另立 repair/normalization 任务并在真实 DM8
验证。

### V006 demo data

V006 包含固定 demo procurement/budget 数据。客户部署前必须识别目标库是否已有这些 row，并决定
独立 seed/清理策略；不能把 demo 数据当生产事实。

### V016 F04

必须同时核对 F04 两张表、主键/唯一约束/查询索引、字段宽度与 `PM_DEMO_20260828` seed。
实际对象全部匹配时标 `ALREADY_MATCHING / DO_NOT_REAPPLY`；缺失标 `NOT_APPLIED`；任一部分不一致
标 `SCHEMA_DRIFT`。正式甲方审批规则仍需客户输入，demo seed 不等于验收规则。

### Customer schema drift

当前部署交接已提示可能存在 legacy F01、F04 required tables/indexes、字段宽度差异，以及 migration
effect 已匹配但 history 不一致的情形。本仓库审计任务禁止连接客户库，因此具体状态全部
`NOT_VERIFIED`，不能把上述类别写成已修复事实。

## Execution safety

- 使用最小权限业务账号；应用不得使用 SYSDBA 或 SYSAUDITOR。
- DDL/repair 前完成备份、执行顺序、失败停止点和回滚演练。
- 不把用户名、密码、完整 JDBC URL 或客户地址写入脚本/日志/Git。
- 先完成 DM8 事务，再更新/失效 Redis cache。
- migration 执行后用应用真实读写路径和 catalog 查询共同验证，不以“脚本无报错”单独判 PASS。
- 真实验证必须记录 command、DM8/environment/version、观察结果和脱敏 evidence。

当前客户部署流程见 [customer deployment runbook](../../docs/runbook/customer-deployment-current.md)。

## Procurement and F06 migration line

`V023__f06_procurement_quote_alert.sql` creates the F06询比价 analysis facts:

- `PROCUREMENT_QUOTE_CHECK` stores one batch summary, including budget, average quote,
  standard deviation, coefficient of variation and the deterministic risk level;
- `PROCUREMENT_QUOTE_CHECK_ITEM` stores each supplier quote and its over-budget/deviation
  evidence, linked to the batch summary by a foreign key;
- amounts use `DECIMAL(18,2)`, CV and ratios use `DECIMAL(12,4)`, and binary flags use
  `SMALLINT` for DM8 portability.

Apply V023 once after V022. The migration is versioned SQL only; the application does not
auto-migrate, and execution against a real DM8 schema remains **NOT VERIFIED** until the
approved migration process and repository smoke test are completed.

`V024__procurement_project_creation.sql` additively adds the optional procurement-demand
creation fields used by `POST /api/v1/procurement/projects`:

- `DEPARTMENT_NAME`, `PROCUREMENT_TYPE`, and `PROCUREMENT_METHOD` for the request basics;
- `REQUIREMENT_DESCRIPTION`, `APPLICANT_NAME`, and `ATTACHMENT_NAME` for request metadata.

The create flow deliberately leaves `SUPPLIER_NAME` null; suppliers are introduced by the
later quote-material and blacklist-check workflows. Apply V024 once after V023 before using
the DM8 procurement-project create/list endpoints. The attachment column stores the optional
file-name metadata only; file content remains owned by the file-storage module. As with the
other migrations in this file, real DM8 DDL and transaction behavior remain **NOT VERIFIED**.

`V025__procurement_approval_workflow.sql` creates the procurement approval instance,
node, and append-only operation-log facts used by the approval endpoints. Apply V025 once
after V024.

`V026__procurement_review_report.sql` creates `PROCUREMENT_REVIEW_REPORT`, which stores
the report number, generated time, project relation, and aggregated risk level. Report
details are assembled from the existing agent-review, F06, and F03 facts; they are not
duplicated in this table. Apply V026 once after V025.

`V034__procurement_agent_review_snapshot.sql` creates `PROCUREMENT_AGENT_REVIEW_SNAPSHOT`.
It stores one JSON snapshot per procurement project, protected by a unique project
constraint. A refresh updates the existing row instead of appending history. The
application explicitly removes the snapshot when a project is edited or deleted because
the project module uses logical deletion. Real DM8 execution remains **NOT VERIFIED**.

`V028__procurement_approval_assignment_and_thresholds.sql` extends the approval workflow
with node role requirements, reassignment/admin-acting audit fields, the five procurement
business roles, and the `REASSIGN` operation type. Apply V028 once after V027. The Java
service reads the F04 thresholds from `audit.procurement.approval.*`; environment variables
can override the defaults. Real DM8 execution remains **NOT VERIFIED**.

`V029__procurement_approval_resubmission.sql` removes the one-instance-per-project unique
constraint and replaces it with a project/time index. This allows a returned draft to create
a new approval instance while preserving all earlier instances, nodes, and operation logs.
Apply V029 once after V028 before enabling approval resubmission. Real DM8 execution remains
**NOT VERIFIED**.

`V030__procurement_approval_return_snapshot.sql` adds the returned-project JSON snapshot used
to reject an unchanged resubmission. `V031__procurement_notification.sql` creates durable
in-app notifications for returned procurement requests. `V032__operation_audit_details.sql`
extends the existing append-only `AUDIT_OPERATION_LOG` with object name, before/after JSON,
and failure reason fields. `V033__procurement_field_confirmation_audit.sql` adds the immutable
field-level evidence captured when a user confirms a procurement pre-review warning. Apply
V030, V031, V032, and V033 once, in that order, after V029. These migrations have structure
tests but remain **NOT VERIFIED ON REAL DM8** until executed through the approved deployment
process.

## Backend B procurement and budget extensions

The numbered Backend B line continues after V035 and is independent from the non-numeric
Contract evolution namespace:

- `V036__f07_tender_rule_scan.sql` through `V040__f07_tender_rule_legal_metadata.sql`
  extend the historical F07 implementation corresponding to schedule feature F10;
- `V041__procurement_review_workflow.sql` persists the procurement review workflow;
- `V042__f08_procurement_collusion_review.sql` and
  `V043__f11_split_purchase_assessment.sql` implement the historical F08/F11 procurement
  persistence while external product naming follows the schedule numbering;
- `V044__f05_budget_split_risk.sql` creates the deterministic F05 split-risk rule and
  durable assessment tables.
- `V045__procurement_source_project.sql` adds the nullable legacy-compatible
  `PROCUREMENT_DEMAND.SOURCE_PROJECT_ID` association to the confirmed Project Intake aggregate.
  Historical rows remain readable with this association. New material-based requests use V046.
- `V046__business_material_intake.sql` adds procurement-owned material metadata, field suggestions,
  and the original one-primary-material association.
- `V047__procurement_multi_material_link.sql` adds an additive multi-material association for each
  procurement demand while retaining the V046 primary-material link for rollback compatibility.
  `V048__procurement_material_order_100.sql` widens its order constraint from 10 to 100;
  verify the enabled old constraint before applying, and retain a range check throughout the change.
  `V049__budget_subject_name_utf8_capacity.sql` widens the two budget item subject-name columns
  to 800 bytes for 200-character Unicode names on DM8 instances using byte semantics.
  Both migrations are controlled manual changes; their presence does not prove customer deployment.
  source evidence, durable operation identity and lease fencing. `PROCUREMENT_MATERIAL_LINK`
  associates new demands with material records without altering the existing demand table or
  V045 foreign key. Demand and link inserts share a transaction; the link insert validates both
  the saved demand and parsed material. It does not add a budget upload entry.
  Run the environment-only helper `tools/ProcurementMaterialMigration.java` with `preflight` first;
  `apply <sql-path> <protected-backup-directory>` refuses an existing or partial schema and snapshots
  procurement rows/catalog before DDL. DM8 DDL is not assumed transactional: stop on any failure,
  inspect the catalog, and never blindly rerun. Release rollback leaves the additive schema intact.
  `complete-link` is a narrow recovery mode for an empty, fully matching intake table with both
  intake indexes/constraints present and no link table. It snapshots the visible historical
  catalog and executes only the two link-table statements; any different partial state is refused.

Apply these files only through the approved, audited migration procedure and only after
classifying the target schema. The application does not auto-migrate. V044 alone was
verified on 2026-09-30 with the configured representative DM8 application schema account:
three active rules and the assessment table were readable. That evidence does not prove
that older migration sequences were applied in order.

`V050__procurement_material_async_queue.sql` expands the existing intake status guard to
include `QUEUED`. Uploaded files are saved before queueing; workers discover queued and
expired processing records from DM8. This does not add a Redis queue or replace rows.
Before deploying the asynchronous API, use `tools/MaterialAsyncMigration.java` with
`check <snapshot-path>`, then `apply <new-snapshot-path>`, then `verify <snapshot-path>`.
The helper reads credentials only from `AUDIT_DB_*` process variables and snapshots
constraint definitions and row counts. It checks exact enabled guard definitions,
retains a wider guard during DDL and recognizes its own partial migration states.
Do not narrow the status guard during code rollback: queued records must remain durable
until a compatible worker drains them. Customer execution remains NOT VERIFIED until
the update script succeeds there. Already uploaded tasks survive browser closure;
unfinished browser uploads require selecting the same files with their original keys.

`V051__budget_material_background.sql` adds only `BUDGET_MATERIAL_JOB`. Its ID references
the real `BUDGET_REVIEW_TASK` ID. Actor/operation identity, content hash, claim token,
lease and terminal state are durable in DM8. Existing budget tasks and confirmation
rules are preserved. Use `tools/BudgetMaterialMigration.java check|apply|verify <sql-path>`
before enabling the new worker. Code rollback retains the table; reapply compatible
code to resume queued/expired work. No Redis queue is required.

Both schema-dependent material workers are opt-in. Keep
`AUDIT_PROCUREMENT_MATERIAL_WORKER_ENABLED=false` and
`AUDIT_BUDGET_MATERIAL_WORKER_ENABLED=false` until the target schema has passed the
V047-V051 catalog preflight, approved migration, and post-migration verification. The
enable flags do not perform or replace a migration.

`V052__budget_uploaded_material_batch.sql` adds the immutable upload membership and
per-file source/result CLOB snapshots used by the material-driven budget reference path.
It does not add an approval-amount column or populate budget mapping master data.
Run the controlled `tools/BudgetUploadedBatchPlatformCheck.java check|apply|probe <sql-path>`
with credentials supplied only by existing `AUDIT_DB_*` process variables. `check` is
read-only; `apply` creates only these two absent tables and their indexes. Partial or
different schemas stop the operation. `probe` uses the real repository in a rollback-only
transaction to verify CLOB binding, member readiness, lease fencing and atomic rollback.
The application does not execute this DDL automatically. Retain V052 tables on code rollback;
never discard uploaded facts, membership or results. Stop budget uploads and drain pending
work before reverting to code that cannot interpret this workflow. Customer execution
remains NOT VERIFIED until schema verification and real upload/result checks pass there.

`V053__budget_material_risk_results.sql` adds immutable, actor-scoped budget risk
reference snapshots from the uploaded-material workflow. Use
`tools/BudgetMaterialRiskPlatformCheck.java check|apply|probe <sql-path>` with the
existing `AUDIT_DB_*` environment. Apply only when the table is absent; existing
column, key, default or index drift stops deployment. Probe covers large CLOBs,
actor isolation, pagination, duplicate identity rejection and rollback on real DM8.
Retain the table and its history on code rollback. This feature does not create
procurement records or introduce an approval workflow.

`V054__material_resumable_transfer.sql` adds actor-scoped upload identities and
verified 4 MiB part metadata. Use `tools/MaterialTransferPlatformCheck.java
check|apply|probe <sql-path>` with the existing `AUDIT_DB_*` environment. The
guard creates absent tables/index only and stops on existing schema drift.
Redis is not involved in upload recovery. The probe rolls back its synthetic
records and verifies CLOBs, duplicate rejection and expired-lease fencing on DM8.
Keep these tables and the file storage root on rollback. Completed transfer
temporary files are removed after commit (and cleanup is retried on completion
replay); unfinished parts remain available for resume. No automatic expiry or
deletion of unfinished uploads is currently performed. A crashed assembly lease
can be reclaimed after 120 seconds; an active assembly renews every 30 seconds.
Re-select the original file using the same browser user/profile to resume after
a refresh. Browser data removal loses that local operation lookup. Customer ARM
deployment remains NOT VERIFIED until verified there.

`V055__material_extraction_ledger.sql` adds acknowledged budget/procurement field
extraction units, a frozen policy fingerprint, and cumulative call/time budgets.
`tools/MaterialExtractionPlatformCheck.java check|apply|isolated-probe <sql-path>`
verifies the exact owned table/constraints and never repairs drift. `isolated-probe`
allocates and removes only its own UUID-named synthetic table; it does not apply the
migration to the application schema. Unique violations on DM8 can be classified as
DataIntegrityViolationException; the application proves the winning durable row.
The application does not run DDL automatically. Apply V055 before the new backend,
and start the matching AI service first so the opaque policy endpoint is available.

An acknowledged unit can resume after restart. CALLING without acknowledgement is
UNKNOWN/in progress, never replay permission. After 1100 seconds a rediscovery can
fence a stale CALLING unit as UNKNOWN; a late response cannot commit it. Unknown
operations require operator investigation; do not reset the ledger, change operation
IDs, or re-upload just to bypass it. Earlier completed units remain stored in DM8.
The cumulative limits remain 512 processing attempts / 900000 ms per immutable
material operation, including acknowledged capacity/network time. Transport errors,
invalid evidence and ambiguous commits cannot publish a partial result. Temporary
Python caches are only accelerators, not the business recovery record. No result
is copied from this ledger into budget approval master data.

`V056__procurement_full_report_snapshot.sql` adds a nullable CLOB for the complete
generated report and source material IDs/SHA-256. Use
`tools/ProcurementReportSnapshotPlatformCheck.java check|apply|isolated-probe <sql-path>`.
The existing AGENT_REVIEW_JSON and old report rows are not rewritten. New reports
read frozen chapters; legacy reports expose only the pre-review section actually
saved and explain that the other historical sections cannot be reconstructed.
Report reads/exports never invoke models or replace missing history with current
quotes, alerts or findings. Numeric tokens are deserialized directly into typed
BigDecimal fields, preserving decimal scale. Keep both new schema additions on
rollback; reverting code cannot preserve the new behavior, so drain new extraction
work and retain all operation IDs/history before a coordinated rollback.

### Local contract file jobs and bounded fact batches (2026-10-14)

`CONTRACT__project_file_processing_job.sql` adds only the durable Job/Attempt tables,
exact project/file foreign key, actor and parse identity constraints, lease/state guards
and FIFO indexes. `CONTRACT__contract_fact_batches.sql` widens only the existing fact
model-call outcome CHECK with `UNKNOWN`; owner/call uniqueness remains unchanged.

For the explicitly authorized local test schema, use
`tools/local-test/migrate-contract-local.ps1 -Mode Preflight|Apply|Verify -BackupDirectory ...`.
The helper pins migration hashes, backs up typed empty post-reset business tables and
catalog, hashes protected rows without exporting account/credential values, proves local
socket ownership, and verifies each DDL effect. DM8 DDL can commit implicitly: the helper
adds a wider temporary CHECK before dropping the old CHECK, retains protection until the
final definition is verified, and journals intent so an ambiguous result reopens the same
snapshot instead of blindly replaying DDL. Never run the raw DROP/ADD pair independently.

Rollback keeps additive tables and wider guards; resume compatible code to drain jobs.
The historical data-reset backup has a different catalog after migration and must not
be restored by bypassing catalog gates or dropping new tables. No FK disable or restore
of the deleted historical storage references is authorized. This local helper is not a
customer deployment script; customer schema/execution remain NOT VERIFIED.

### Personal upload roles (2026-11-04)

`V058__personal_upload_roles.sql` inserts the active procurement, budget and contract
personal roles into the existing `SYS_ROLE` table. It adds no schema objects and
does not change existing user assignments or historical ownership. Reapplying the
script is safe; role codes are the identity. Maintain account assignments directly
in the existing SYS_USER_ROLE table. Files and results are scoped by
the uploading account ID, including when a personal role is combined with an old
role. See `docs/personal-upload-roles.md`.

### Number reservation after workspace integration (2026-11-05)

`V058__personal_upload_roles.sql` retains its deployed identity. The incoming
client identity registry is `V059__client_review_identity.sql`. If the same
registry was already created from the former incoming V058 script, verify its
actual schema and retain its configuration; the rename does not authorize
replaying CREATE TABLE, resetting rows or changing a historical migration.
