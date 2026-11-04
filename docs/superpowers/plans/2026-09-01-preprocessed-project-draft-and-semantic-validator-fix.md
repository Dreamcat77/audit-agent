# Preprocessed Project Draft and Semantic Validator Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep unconfirmed package-backed Projects in a durable intake-draft projection and fix false-positive monetary output rejection without weakening AI safety.

**Architecture:** Reuse Project/ProjectFile and encode the already-proven version lifecycle as `Project.metadataConfirmed()`. Split repository queries into confirmed Projects and package-backed unconfirmed drafts. Replace monetary label/value pairs before the AI port, then validate model output with separate value, calculation/conclusion, verdict, evidence, and schema checks whose safe reasons are logged.

**Tech Stack:** Java 17, Spring Boot, MyBatis/DM8 SQL, React/TypeScript, Python 3.11, FastAPI/Pydantic/pytest.

**Spec:** `docs/superpowers/specs/2026-09-01-preprocessed-project-draft-and-semantic-validator-fix-design.md`

## Global Constraints

- Do not add a file/batch aggregate, storage system, upload route, or schema migration.
- ProjectFile identities and storage keys remain unchanged across metadata confirmation.
- Formal Project membership is derived only by the domain `metadataConfirmed()` lifecycle.
- AI never receives monetary labels/values and never gains monetary decision authority.
- Rejection logs contain safe metadata only; never source, prompt, response body, key, or authorization.
- Do not deploy or probe `<server-ip>`.

---

### Task 1: Domain and backend Project projections

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/domain/project/Project.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/port/ProjectRepository.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectApplicationService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/infra/memory/project/InMemoryProjectRepositories.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/Dm8ProjectRepositories.java`
- Modify: `backend/src/main/java/com/fuquan/audit/integration/dm8/ProjectMapper.java`
- Modify: `backend/src/main/resources/mappers/dm8/ProjectMapper.xml`
- Test: `backend/src/test/java/com/fuquan/audit/contract/domain/project/ProjectDomainTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/project/ProjectApplicationServiceTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectApiIntegrationTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/integration/dm8/ProjectIntakeDm8SqlStructureTest.java`

**Interfaces:**
- Produces: `Project.metadataConfirmed()`, confirmed `ProjectPage`, and paged `IntakeDraftPage`.
- Produces: `GET /api/v1/projects/intake-drafts` without changing create/upload/confirm routes.

- [ ] Write domain and application tests proving version 0 is unconfirmed, confirmed listing excludes it, a package-backed draft is projected, and confirmation moves the same ID/files into the formal list.
- [ ] Run the focused tests and verify RED because the lifecycle method/projection does not exist and the current list contains the blank Project.
- [ ] Add confirmed and package-backed-draft repository queries plus the application projection and controller DTO.
- [ ] Run focused domain/application/API/DM8 structure tests and verify GREEN.
- [ ] Run all Project backend tests and commit the backend slice.

### Task 2: Frontend draft/formal workflow

**Files:**
- Modify: `frontend/src/features/project/types.ts`
- Modify: `frontend/src/features/project/data/projectDataSource.ts`
- Modify: `frontend/src/features/project/data/httpProjectDataSource.ts`
- Modify: `frontend/src/features/project/useProjectList.ts`
- Modify: `frontend/src/features/project/projectListViewModel.ts`
- Modify: `frontend/src/features/project/components/ProjectListPage.tsx`
- Modify: `frontend/src/features/project/projectIntakeCompletion.ts`
- Test: `frontend/src/features/project/data/httpProjectDataSource.test.ts`
- Test: `frontend/src/features/project/projectListViewModel.test.ts`
- Test: `frontend/src/features/project/projectIntakeCompletion.test.ts`

**Interfaces:**
- Consumes: backend `Project.metadataConfirmed` and `IntakeDraftPage`.
- Produces: three page sections and two draft actions that reopen the same Project.

- [ ] Write tests for the typed draft projection, distinct draft/formal collections, endpoint request, and completion conversion without inserting a draft into formal Projects.
- [ ] Run focused Node tests and verify RED for missing types/functions/endpoint behavior.
- [ ] Implement `listIntakeDrafts`, reload both collections, refresh after intake, and render “已预处理资料” separately from “正式项目”.
- [ ] Run all Project frontend tests and the production build; commit the frontend slice.

### Task 3: Neutral Java monetary redaction

**Files:**
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/ai/ContractAiTextRedactor.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractAiTextRedactorTest.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/application/ai/ContractTextAnalysisUseCaseTest.java`

**Interfaces:**
- Produces: sanitized text containing `[确定性数值字段已移除]` with monetary labels/values removed and quantity/business clauses retained.

- [ ] Write tests for complete label/value removal and line-item preservation; run and verify RED against the old amount-oriented mask.
- [ ] Implement the minimal label/value and uppercase/currency redaction.
- [ ] Run redactor/use-case tests and verify GREEN; commit the Java isolation slice.

### Task 4: Python semantic validator and safe rejection reasons

**Files:**
- Modify: `services/audit-ai/src/audit_ai/model/router.py`
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`
- Modify: `services/audit-ai/src/audit_ai/logging.py`
- Test: `services/audit-ai/tests/test_model_routes.py`
- Test: `services/audit-ai/tests/test_safe_logging.py`

**Interfaces:**
- Consumes: typed contract result and already-sanitized Java source.
- Produces: 200 for safe monetary disclaimers; stable 502 for dangerous output; safe internal reason metadata.

- [ ] Add the exact real-regression fixture `本项不涉及金额判断` and verify RED with 502 `MODEL_INVALID_RESULT`.
- [ ] Add table-driven RED fixtures for concrete values, arithmetic/percentages, monetary conclusions, expanded strong verdicts, evidence, four-material, and multi-chunk behavior.
- [ ] Split validators by reason, add safe reason logging, and strengthen the contract system prompt without rewriting output.
- [ ] Run model/logging tests and the full Python 3.11 suite; commit the Python slice.

### Task 5: Full verification and delivery

**Files:**
- Verify all modified files and commits; no production deployment files are changed.

- [ ] Run `backend/.\\mvnw.cmd clean verify` and aggregate Surefire results.
- [ ] Run every frontend `*.test.ts` file and `npm.cmd run build`.
- [ ] Run the full Python 3.11 pytest suite.
- [ ] Run `git diff --check` and added-line credential-shape scan.
- [ ] Confirm Budget/Blacklist core files are untouched and no `<server-ip>` operation occurred.
- [ ] Commit any final documentation-only evidence adjustment, push `feature/ai-ocr-project-intake-demo`, and verify local/remote SHA equality.
