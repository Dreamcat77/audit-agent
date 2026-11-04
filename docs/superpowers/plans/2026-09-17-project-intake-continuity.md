# Project Intake E2E Continuity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore a user-completable, durable Project Intake path from upload through role/identity confirmation, candidate confirmation, Contract promotion, and the existing review workspace without restoring filename/category authority.

**Architecture:** Add a Java application progression coordinator that starts deterministic role assessment after a durable parse and starts deterministic identity assessment after a durable human role decision. Add one read-only server progression projection per ProjectFile; React renders it and submits only explicit human commands. Ambiguous role results may call the existing FastAPI/OpenAI-compatible provider through a new non-authoritative DOCUMENT_ROLE port, while provider failure leaves parse and manual confirmation usable.

**Tech Stack:** Java 17, Spring Boot, MyBatis/DM8, JUnit/MockMvc, React/TypeScript/Vite, Python 3.11/FastAPI/Pydantic/pytest.

**Spec:** `docs/superpowers/specs/2026-09-14-contract-audit-evolution-master-design.md`, Phase 1C/1D/1E plans, and the 2026-09-17 Project Intake E2E continuity task.

## Global Constraints

- Work only in `.worktrees/contract-closeout-local-test` on `fix/project-intake-continuity-20260917` from `c674465`.
- DM8 remains the system of record; GET endpoints are read-only and browser state is never workflow truth.
- Candidate authority remains current human `CONTRACT_BODY` plus current human `CONFIRM_SAME_PROJECT`; AI is proposal-only.
- Reuse the existing parser/OCR, immutable ParseResult, extraction, candidate, confirmation, promotion, ReviewRun, filesystem, model gateway, idempotency, AuthContext, CAS, audit, and ownership seams.
- Do not modify V001-V022, apply controlled CONTRACT migrations, activate a production provider route, deploy, merge, push, create a PR, or persist runtime secrets/materials.
- Every production edit follows a witnessed RED test and minimal GREEN implementation.
- The known baseline failure in `ContractReviewRunResultsDm8SqlStructureTest` is recorded separately and is not opportunistically changed.

---

### Task 1: Reproduce the continuity break and role vocabulary gaps

**Files:**
- Create: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectIntakeContinuityApiIntegrationTest.java`
- Modify: `backend/src/test/java/com/fuquan/audit/contract/application/project/role/DeterministicDocumentRoleFixtureMatrixTest.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/role/DocumentRoleSignalExtractor.java`

**Interfaces:**
- Consumes: existing public Project create/upload/role/identity/candidate/confirmation/promotion APIs.
- Produces: an executable regression demonstrating the missing `PARSED -> ROLE_ASSESSED` trigger and literal Chinese role fixtures.

- [x] Write a MockMvc test that uploads a generic-name native DOCX containing a contract body and expects the durable file projection to contain `roleAssessmentId`, `suggestedRole=CONTRACT_BODY`, `roleDecisionStatus=NEEDS_CONFIRMATION`, and no candidate.
- [x] Run `backend/.\mvnw.cmd '-Dtest=ProjectIntakeContinuityApiIntegrationTest' test`; expect failure because `roleAssessmentId` is absent while parse is `PARSED`.
- [x] Add literal deterministic cases: `竞争性磋商采购文件 -> TENDER_DOCUMENT`, `响应文件 -> BID_RESPONSE`, `成交通知书/成交供应商/成交金额 -> AWARD_NOTICE`, `采购合同 -> CONTRACT_BODY`, and `澄清与补充说明 -> SUPPLEMENT`.
- [x] Run the fixture matrix and observe any missing terminology RED before adding the minimal signal expressions.

### Task 2: Add durable server progression orchestration and projection

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectIntakeProgressionService.java`
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectIntakeProgression.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/ProjectFileIngestionService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/application/project/role/RoleConfirmationService.java`
- Modify: `backend/src/main/java/com/fuquan/audit/contract/api/project/ProjectController.java`
- Test: `backend/src/test/java/com/fuquan/audit/contract/api/project/ProjectIntakeContinuityApiIntegrationTest.java`

**Interfaces:**
- Consumes: `RoleAssessmentService.assess`, `IdentityAssessmentService.assess`, existing role/identity projections, candidate/confirmation repositories, and ProjectContract query.
- Produces: `progressAfterUpload(projectId, metadata)`, `progressAfterRoleConfirmation(file, roleDecision, metadata)`, and a read-only `project(file)` returning `stage`, `blocker`, and `availableActions`.

- [x] Extend the RED API test to assert no human role decision means no candidate and `availableActions=[CONFIRM_ROLE]`.
- [x] Implement a stable server-owned role-assessment key derived from immutable parse ID; after upload COMPLETE (and upload replay), assess every current parsed file lacking a current completed assessment. Do not reparse.
- [x] Add a RED test: after public role confirmation, identity assessment appears automatically; candidate remains absent and progression is `NEEDS_IDENTITY_CONFIRMATION` with `CONFIRM_PROJECT_IDENTITY`.
- [x] Implement a stable identity-assessment key bound to role decision and Project version; invoke it only after the durable role confirmation returns. Do not create an identity decision.
- [x] Add a RED test: role `CONTRACT_BODY` plus public `CONFIRM_SAME_PROJECT` automatically yields exactly one candidate; repeat both confirmations with the same keys and assert one decision/candidate.
- [x] Project read-only stage precedence: parse state, role assessment/decision, identity assessment/decision, candidate, latest candidate confirmation, promoted association. Expose only server-derived actions `CONFIRM_ROLE`, `CONFIRM_PROJECT_IDENTITY`, `CONFIRM_CONTRACT_CANDIDATE`, `PROMOTE_CONTRACT`, `START_REVIEW`.
- [x] Verify GET counts do not change assessment/decision/candidate/audit/idempotency stores.

### Task 3: Add non-authoritative DOCUMENT_ROLE fallback

**Files:**
- Create: `backend/src/main/java/com/fuquan/audit/contract/application/project/role/DocumentRoleAssessmentPort.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/ai/fastapi/FastApiDocumentRoleAssessmentAdapter.java`
- Create: `backend/src/main/java/com/fuquan/audit/integration/ai/fake/UnavailableDocumentRoleAssessmentAdapter.java`
- Modify: role assessment service/coordinator/domain projection files only as needed to persist `MACHINE_AI` with existing provenance columns.
- Modify: `backend/src/main/java/com/fuquan/audit/integration/http/FastApiContractPaths.java`
- Create: `services/audit-ai/src/audit_ai/model/document_role.py`
- Modify: `services/audit-ai/src/audit_ai/model/gateway.py`, `model/router.py`, `config.py`, and capability routing support.
- Test: focused Java adapter/service tests and `services/audit-ai/tests/test_document_role_route.py`.

**Interfaces:**
- Java request: project/file/parse ownership plus bounded server-issued evidence segments (`segmentId`, offsets, text hash, bounded text).
- FastAPI response: `proposedRole`, decimal confidence, candidate roles/reason codes, selected evidence segment IDs, and provider/model/revision/prompt/schema provenance.

- [x] Write Python RED tests for strict request/response schemas, allowlisted roles, evidence IDs, disabled-by-default route, and reuse of `ModelGatewayPort`.
- [x] Extend the existing gateway with `DocumentRoleOperation`; do not add a second HTTP/provider client. Keep the capability disabled by default and enable it only through explicit local runtime configuration.
- [x] Write Java RED adapter tests for role/confidence/schema/provenance/segment ownership/hash validation and invalid/unavailable mapping to safe reason codes.
- [x] Persist a second `MACHINE_AI` assessment only when deterministic is UNKNOWN, conflicted, or below the explicit fallback threshold. Never create role/identity decisions or candidates from AI.
- [x] Add a controlled timeout/unavailable test proving parse remains PARSED, progression remains `NEEDS_ROLE_CONFIRMATION`, manual role selection remains available, and the workflow can still reach a candidate.

### Task 4: Make the existing Project workspace progression-driven

**Files:**
- Modify: `frontend/src/features/project/types.ts`
- Modify: `frontend/src/features/project/data/projectDataSource.ts`
- Modify: `frontend/src/features/project/data/httpProjectDataSource.ts`
- Create: `frontend/src/features/project/projectIntakeProgression.ts`
- Create: `frontend/src/features/project/projectIntakeProgression.test.ts`
- Modify: `frontend/src/features/project/useProjectWorkspace.ts`
- Create: `frontend/src/features/project/components/ProjectFileProgressionPanel.tsx`
- Modify: `frontend/src/features/project/components/ProjectDetailPage.tsx`
- Modify: `frontend/src/features/project/components/CandidateConfirmationPanel.tsx`
- Modify: candidate confirmation flow tests where the two visible CTAs change.

**Interfaces:**
- Add `confirmRole(projectId,fileId,{assessmentId,selectedRole,expectedVersion,reason},key)` and `confirmIdentity(...CONFIRM_SAME_PROJECT...)`.
- Consume `file.progression.stage/blocker/availableActions`; do not infer blockers from `candidate == null`.

- [x] Write Node RED tests for exact HTTP role/identity commands, server-action-driven CTA visibility, refresh reconstruction, and no client-created identity/role truth.
- [x] Render durable stages and the CTAs `确认/修正材料类型`, `确认该材料属于当前项目`, `确认合同内容`, `生成正式合同`, `开始合同审核` in the existing workspace.
- [x] Retain immutable browser operation keys while an outcome is uncertain; reload Project detail after each command.
- [x] Split the existing combined confirmation/promotion presentation into two explicit buttons while retaining Phase 1E's separate durable operations and refresh-safe promotion retry.

### Task 5: Full continuity, recovery, local integration, and real materials

**Files:**
- Modify tests/README/OpenAPI only when an observed RED requires it.
- Do not add customer files, runtime env, raw OCR/model output, or secrets.

**Interfaces:**
- Full public path: upload -> parse/OCR once -> role proposal -> human role -> identity assessment -> human SAME -> candidate -> candidate confirmation -> promotion -> existing review workspace/run.

- [x] Run focused backend suites for intake, parse/OCR-once, role, identity, materialization, confirmation, promotion, ReviewRun, and API continuity.
- [x] Run focused frontend tests, then all repository Node test files and `npm.cmd run build`.
- [x] If FastAPI changed, run its complete pytest suite.
- [x] Run DM8 migration preflight only; if a new migration is required, stop real DB mutation and report `BLOCKED_BY_CONTROLLED_MIGRATION`.
- [x] Load the existing local integration runtime script without printing it; start services using existing documented commands; verify backend/FastAPI/frontend health.
- [x] Log in with the user-supplied test account only at runtime, never in files/log summaries. Exercise every real material through public APIs and report filename, parse method, proposal/source/confidence, human role/identity, candidate/contract/run IDs only.
- [x] Test same-key retries, refresh/reopen, lost-response promotion replay, OCR/POI single invocation, and AI-unavailable manual continuation.
- [x] Run fresh `backend/.\mvnw.cmd clean verify`, frontend full tests/build, FastAPI tests, `git diff --check`, `git status --short`, `git diff --stat`, V001-V022 diff, and secret/material scans.
