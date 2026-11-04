# Task 13 report — role APIs and durable ProjectFile projection

## Scope delivered

- Added `POST /api/v1/projects/{projectId}/files/{fileId}/role-assessments`.
- Added `POST /api/v1/projects/{projectId}/files/{fileId}/role-confirmations`.
- Both routes inherit the existing four-role guard, require CSRF and a validated `Idempotency-Key`, use the existing API envelope, and derive actor/request metadata from server context.
- Added a repository-backed `ProjectFileRoleProjectionService` and additive ProjectFile response fields for immutable parse identity, machine suggestion/status, decision status, and authoritative effective role/source/decision identity.
- Confirmation request ignores client actor-like properties; the response actor is the authenticated server user.
- Role evidence responses expose bounded locator/evidence fields but not storage keys, file SHA, full text, prompts, or raw model output.
- Existing upload, candidate, content, and Project APIs remain unchanged apart from additive file fields.

## Fixture policy

- The exact deterministic matrix uses synthetic parsed-document bodies and generic filenames:
  `001.pdf`, `扫描件.pdf`, `采购资料.pdf`, `响应材料.pdf`, and `附件.pdf`.
- The test stores generated non-sensitive DOCX bytes only to satisfy the existing file-integrity read path; classification consumes the separately persisted server-owned immutable canonical text.
- The existing upload route is also exercised with a generated synthetic DOCX and proves immediate parse-result projection without creating role truth.

## TDD evidence

RED:

```text
.\mvnw.cmd -Dtest=ProjectFileRoleApiIntegrationTest test
Tests run: 7, Failures: 6, Errors: 1
Expected role endpoints returned 404 before implementation.
```

GREEN:

```text
.\mvnw.cmd -Dtest=ProjectFileRoleApiIntegrationTest test
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

.\mvnw.cmd '-Dtest=ProjectFileRoleApiIntegrationTest,ProjectApiIntegrationTest,RoleAssessmentServiceTest,RoleConfirmationServiceTest,EffectiveDocumentRoleResolverTest' test
Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Verification boundary

- UNIT/INTEGRATION VERIFIED: deterministic matrix, authenticated endpoints, four allowed roles, disallowed role,
  CSRF, required idempotency header, same-key replay/conflict, AuthContext actor, additive upload/list projection,
  and GET-after-confirmation rediscovery.
- REAL DM8 NOT VERIFIED: the API integration suite runs with the local profile.
- REAL CUSTOMER MATERIAL NOT VERIFIED: no customer file or external environment was accessed.
