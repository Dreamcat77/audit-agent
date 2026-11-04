# Contract Review Run Phase 2A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

Goal: Create the durable immutable ReviewRun foundation on top of Phase1 authoritative Project/File/Contract facts without yet executing review engines.

Architecture: DM8 owns ReviewRun state. Run creation freezes server-owned Contract and ProjectFile identities in one atomic transaction. GET reopens frozen rows and never follows later mutable business inputs. A completed same-key POST verifies and replays its frozen command and manifest, ignoring later authority drift; a new key resolves current readiness.

Tech Stack: Java 17, Spring Boot, DM8, MyBatis, existing IdempotencyPort / OperationAuditPort.

Spec: `docs/superpowers/specs/2026-09-14-contract-audit-evolution-master-design.md`.

Baseline: isolated `feature/contract-review-run-phase2a-20260915` worktree at `e34f98415a13a79f74fdfca708467f41f59a0bc9`; initial local backend `clean verify` observed 971 tests, zero failures/errors/skips, BUILD SUCCESS. The reviewed Phase1 worktree and old migrations are unchanged.

## Task 1: ReviewRun domain model and canonical Contract snapshot

- [x] Read existing Contract, line-item and award facts; add RED tests for frozen identity, BigDecimal scale, NFKC text, fixed JSON order and changed facts.
- [x] Add immutable run/material/step/attempt records, status enums and Java-owned canonical JSON/SHA.
- [x] Run focused GREEN; retain nullable genuine legacy provenance rather than inventing candidate/confirmation IDs.

## Task 2: Controlled Contract review-run migration

- [x] Add RED structure tests for the exact header and four tables only.
- [x] Add `db/dm8/CONTRACT__review_run.sql` with run, material, step and attempt keys, constraints and strong existing composite ownership FKs.
- [x] Keep `V001`–`V022` and all prior Contract migrations byte-for-byte unchanged; mark real DM8 execution NOT VERIFIED.

## Task 3: DM8 and memory repository ports/adapters

- [x] Add focused insert/reopen ports and RED memory/DM8 mapper tests.
- [x] Implement local unique/ownership enforcement and DM8 MyBatis insert/reopen for the four table models.
- [x] Verify DM8 row mapping via mock mapper and explicit CLOB binding; do not claim real-platform verification.

## Task 4: Material readiness and immutable snapshot builder

- [x] Add RED adversarial tests for ProjectContract/Candidate/Contract ownership, selected source file, parse/hash/current role/current identity and legacy provenance.
- [x] Resolve only the client-selected files in lexical file-ID order under the run-create write transaction, locking mutable Project/association/Contract/Candidate/File rows and rereading exact parse/decision facts.
- [x] Require current human SAME_PROJECT identity for every selected file; CURRENT_PIPELINE requires human role, while proven LEGACY_IMPORTED known role may freeze null roleDecisionId.

## Task 5: Run creation and durable idempotency transaction

- [x] Add RED tests for one PENDING run, completed same-key replay after authority drift, changed client request 409, audit once and partial local insert failure.
- [x] Preallocate real runId, resolve canonical input, claim `REVIEW_RUN_CREATE`, and insert run/materials/steps/attempts/audit/SUCCEEDED in one short `@Transactional` coordinator without external HTTP/OCR/filesystem/F02 work.
- [x] Make a completed POST replay prove the stored resource and frozen original command fingerprint; new keys still require current readiness, while GET remains historical/read-only. Local fake cleanup is limited to unpublished rows; DM8 rollback remains NOT VERIFIED.

## Task 6: Initial five-step/four-attempt manifest

- [x] Add RED manifest tests for MATERIAL_VALIDATION COMPLETED with run-material-snapshot output and attemptNo 0.
- [x] Freeze required F02_DETERMINISTIC, SEMANTIC_AI, BREACH and TERM as PENDING with exactly one first PENDING/NOT_STARTED attempt each; all execution output IDs remain null.
- [x] Generate deterministic internal operation identities; do not implement claims, leases, retries or engines.

## Task 7: Durable GET/reopen projection

- [x] Add RED API tests for known runId reopens, wrong project/contract ownership and read-only behavior.
- [x] Load only persisted run/children, verify frozen Contract/input hashes and original manifest, and return safe NOT_FOUND on wrong ownership.
- [x] Allow later attempt rows without rejecting the original five-step/four-first-attempt proof; GET never triggers a command or reads latest Project/File/Contract state.

## Task 8: Focused API, security and OpenAPI

- [x] Add RED MockMvc/OpenAPI tests for POST 202, GET, CSRF, role guard, server-owned actor and strict create body.
- [x] Add a focused controller using AuthContext, Idempotency-Key and only expectedContractVersion/materialFileIds; reject any client authority claim before transactions.
- [x] Document CreateReviewRunRequest, ReviewRunResponse, ReviewRunMaterialResponse, ReviewStepResponse and ReviewStepAttemptResponse plus POST/GET paths.

## Task 9: Snapshot immutability and adversarial regressions

- [x] Add tests for material-order equivalence, duplicate IDs, not-same/likely/conflict/insufficient identity, stale decision versions, parse mismatch, unselected source, legacy null confirmation and same-key concurrency.
- [x] Persist old run, then upload/confirm a later file, create a new run, change the selected file's human role/identity decisions, and re-GET both original frozen snapshots.
- [x] Mutate current Contract version/amount in a local fixture after commit and prove old run JSON/SHA/input identity remain unchanged on GET.

## Task 10: Full regression and human code-review handoff

- [x] Run focused Phase2A suite and `backend\ .\mvnw.cmd clean verify`; record total/failure/error/skip/BUILD result.
- [x] Run diff/namespace/scope checks; ensure no F02 execution, finding/evidence, worker, frontend/Python, legacy promotion caller, customer material, or prior SQL edit.
- [x] Review verification evidence and request bounded code review if practical, with no task-report files.
- [x] Create one local commit after all gates pass; do not push, merge, deploy or start Phase2B. Stop for human review.

Rollback boundary: the controlled-manual SQL is additive. Disabling new run creation leaves Phase1 Project/candidate/promotion and existing F02/semantic/clause endpoints intact. No prior candidate, Contract, ReviewSession, AuditTask, ProjectFile binary, parse artifact or historical migration is deleted, recomputed or backfilled. Local unit/integration evidence must be separate from REAL DM8 and REAL CUSTOMER MATERIAL NOT VERIFIED.
