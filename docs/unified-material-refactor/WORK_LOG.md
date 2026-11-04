# Work Log

## 2026-10-28 — start and procurement entry

- Re-read the caller-provided implementation task and repository `AGENTS.md`.
- Confirmed actual worktree, feature branch, HEAD, cached upstream relation, and existing dirty files.
- Verified the pre-change backup manifest: 66 entries matched; no missing or changed source files.
- Changed `frontend/src/app/App.tsx`: automatic case upload is the default procurement view; formal procurement list/create/edit/approval controls are wrapped in an explicit, initially closed management section. Existing project-ID detail navigation remains on the prior branch.
- Improved `AutomaticCasePanel`: grouped batch language, drag/drop and file-picker upload, live byte progress, visible processing count, compact cross-file summary, module-state cards, and a visible unavailable-service state. Upload continues through the existing CSRF/session and durable idempotency flow.
- Added optional FormData upload progress to the existing shared `frontend/src/api/client.ts`, implemented through XHR while retaining the same CSRF fetch, cookie credentials, headers, and API envelope handling.
- Added focused tests for the primary entry, request transport/progress, and batch history presentation.
- Focused procurement + upload transport tests: 49 passed, 0 failed (`node --test --experimental-strip-types` with explicit test files).
- Full frontend test suite: 832 passed, 0 failed across 122 test files (`node --test --experimental-strip-types`, default isolated mode).
- Frontend production bundle: local Vite CLI exited 0, 2459 modules transformed. Existing chunk-size and mixed static/dynamic import warnings remain.
- `pnpm build` was attempted first but pnpm stopped at its ignored build-script policy before invoking the build. The already-installed local Vite CLI was then run directly and passed; no dependency approvals or package configuration were changed.
- `git diff --check` passed with only existing/worktree LF-to-CRLF warnings.
- Backend `mvnw clean verify` was started; its result is pending.
- A P0 recovery checkpoint will be written after the clean backend verification result is recorded.

## Scope boundary

No database migration, formal demand schema change, deployment, remote service operation, Git commit, or push has been performed.

## 2026-10-28 — resumed implementation and verified local changes

- Restored valid tool invocation; the previous unsupported calls were agent invocation errors, not evidence of an unavailable execution environment.
- Captured `.runtime/unified-resume-20261028-215526` before further edits, including source and SHA-256 manifest. Existing shared worktree changes are retained.
- Corrected the ambiguous required constructors in AuditCaseService and connected its production path to MaterialParsePort.
- Added ParsedMaterialTextAdapter as the primary legacy text boundary. Budget and procurement existing consumers use the versioned parser once; OfficeMaterialParseAdapter explicitly selects the raw Office reader to prevent recursion.
- Extended the raw reader with immutable typed Excel tables and actual OCR provenance. Kept original text and locators for existing fact extractors. Formula source/cached values are captured before evaluation; XLSX raw XML numeric values are retained. Workbook physical pages are not invented.
- Added the contract intake result adapter; original contract OCR/ZIP orchestration, parse-result persistence and recovery remain authoritative. This is shared result adaptation, not full extraction of the contract parsing engine.
- Fixed PPT slide grouping and preserved UNKNOWN for interrupted parse results.
- First targeted Java run: 76 tests, 0 failures, 0 errors, 4 skipped, BUILD SUCCESS (JDK 17).
- Reproduced two frontend failures: the existing UI/tests supplied upload progress but client.ts lacked the transport implementation. Added authenticated single-request XHR handling. Focused 2/2 passed; full frontend 832/832 passed; Vite production build passed.
- Python full regression: 2029 passed, 14 skipped, 43 subtests passed. Existing Starlette warning retained.
- First full Java run was interrupted after thread inspection proved the migration architecture guard was reading historical generated target-* output as source. Limited the exclusion to top-level generated target/target-* and added assertions that nested real resources, .mvn and startup scripts remain included. Full clean verify r2 is running; not yet marked verified.
- Connected to the authorized test server. Independent QA remains r5 with all three units active. Original port 80 currently points to a newer release than the old deployment memory. Captured a NEW baseline and a private archive of QA release/configuration; no business database export or DDL.
- Prepared a QA-only switch using the existing quiescence, real DM8 history, checksum, configuration preservation and rollback guards against this fresh baseline. No switch yet.

## 2026-10-28 — upload transport isolation and final local gate

- Full Java r2 finished: 3346 tests, 1 failure, 0 errors, 24 skipped. The sole failure was the protected shared HTTP client byte fingerprint after adding progress transport. No frozen hash or business assertion was changed.
- Restored client.ts byte-for-byte, including its protected CRLF representation, and moved procurement multipart progress to clientUpload.ts. CSRF/session/envelope/error handling stays compatible; uncertain network/timeout/abort never starts a replacement POST.
- Added transport coverage for 401/403/409/503 classification, machine error data and expired-session handling. Focused 4/4; full frontend 834/834 passed; production Vite build passed. Java clean verify r3 is running.
- GitHub connectivity rechecked successfully. Remote branch bf5e97ef is an ancestor of local HEAD; the existing three local commits are retained. No push yet.

## 2026-10-29 UTC — r6 已实际部署独立 QA

- Java clean verify r3 3346/0/0/24 skipped BUILD SUCCESS。
- 新候选包80文件，SHA e47deadc55e71480f5635e950b08a5640021b28fa0554d441193a66339c9022e，逐源码hash与验证日志hash已入manifest。
- QA切换实测通过：8表静默，真实DM8 131行历史精度/分页/owner隔离探针回滚PASS；新Java/AI/Web健康与静态资源SHA成功。
- 原80文件、原业务库和现场AI/数据库配置未改。r5恢复检查点保留。
- 启动r6新operation身份的历史重开、预算/采购/合同、报价特性和多格式有限测试队列；另以Chromium109上传用户原12份材料，尚待完成结果。
