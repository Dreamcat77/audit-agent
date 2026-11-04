# Round 2 infrastructure adapter delivery evidence — 2026-08-14

## Preflight and scope

- Repository: `D:\code\audit_agent_1D`
- Required branch: `feature/bootstrap-backend-baseline`
- Initial HEAD: `c6f9285 feat: establish backend application baseline`
- Initial working tree: clean
- Round 2 scope only; no F02 business rule, contract Controller, JWT/user/RBAC, task queue, Jenkins, systemd, Nginx, TongWeb adapter, or deployment work was added.
- No `git add`, commit, tag, push, reset, restore, clean, stash, switch, checkout, merge, rebase, or update-index operation was performed.
- No dev29 probe or connection was performed. Status remains **DEFERRED - VPN unavailable**.

## Toolchain and dependencies

Command:

```powershell
cd D:\code\audit_agent_1D\backend
.\mvnw.cmd -version
```

Verified output:

```text
Apache Maven 3.9.16
Java version: 17.0.19, vendor: Eclipse Adoptium
```

New direct dependencies:

- `org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.5`
- `org.springframework.boot:spring-boot-starter-data-redis:3.5.16` (managed by the Spring Boot parent)

Resolved infrastructure versions recorded by `dependency:tree`:

- MyBatis: `3.5.19`
- MyBatis Spring: `3.0.5`
- Spring Data Redis: `3.5.13`
- Lettuce: `6.6.0.RELEASE`
- Reactor Core: `3.7.19`, transitively required by Lettuce; it was not added or used for the HTTP adapters.

The full dependency tree was inspected. A coordinate-filtered tree for H2, MySQL drivers, PostgreSQL driver, Hibernate ORM, Jakarta Persistence, WebFlux, and Resilience4j returned no matching artifact and `BUILD SUCCESS`. No DM JDBC dependency/JAR is present. `hibernate-validator` remains the existing Bean Validation implementation and is not Hibernate ORM.

## DM8 / MyBatis repository boundary

- `AuditTaskRepository` remains application-owned.
- `Dm8AuditTaskRepository` depends on `AuditTaskMapper`; application/domain code does not import MyBatis.
- Mapper SQL is isolated in `src/main/resources/mappers/dm8/AuditTaskMapper.xml`.
- Save uses a simple existence query followed by INSERT or UPDATE inside a Spring transaction.
- This is baseline concurrency behavior only; optimistic locking is deferred to real task-state-transition work.
- Mapper-mocked tests cover new save, existing update, existing/missing find, UUID mapping, and status mapping.

DM JDBC driver status: **PENDING REAL DM8 MEDIA VERIFICATION**. No coordinate was guessed, no `systemPath` was added, and no vendor JAR was copied into Git.

## DM8 SQL

`db/dm8/V001__audit_task_baseline.sql` creates only:

```text
AUDIT_TASK
  ID VARCHAR(36) PRIMARY KEY
  STATUS VARCHAR(32) NOT NULL
```

Status: **DRAFT / NOT VERIFIED ON REAL DM8**. No real SQL execution result is claimed.

## Redis and TongRDS cache boundary

`RedisCacheAdapter` implements `CachePort` with `StringRedisTemplate` and only:

- GET;
- SET with a mandatory positive TTL;
- DEL.

Keys use the configurable namespace `AUDIT_CACHE_KEY_PREFIX` with safe default `audit-agent:`. Null/blank keys, null values, and null/zero/negative TTLs are rejected. Tests use mocked Redis operations and no `Thread.sleep` or real Redis connection.

- Redis: **IMPLEMENTATION UNIT TESTED / REAL SERVICE NOT VERIFIED**
- TongRDS: **PROTOCOL TARGET ONLY / NOT VERIFIED**

No TongRDS-specific adapter, Streams, Lua core behavior, Pub/Sub queue, module, flush command, or `TaskQueuePort` was added.

## Typed OCR and AI Ports

`OcrPort` now accepts `OcrRequest(documentId, fileName, contentType, binary content)`. The binary content is defensively copied on input and output. `OcrResult` now carries text, confidence, pages, and `simulated`; the local fake remains simulated.

`AiReviewPort` now accepts `AiReviewRequest(sourceId, primaryText, referenceText, checkItems)`. Application-owned `AiReviewResult`, `AiReviewFinding`, and `AiReviewItem` types isolate the application from private HTTP DTOs; the local fake remains simulated.

## FastAPI HTTP adapters

- OCR: `POST /ai/v1/ocr/extract`, multipart field `file`
- AI: `POST /ai/v1/agent/contract-review`, JSON draft request
- Client: Spring `RestClient`; no WebFlux, Feign, OkHttp, Resilience4j, or automatic retry
- Default connect timeout: 3000 ms per service
- Default read timeout: 30000 ms per service
- Overrides: `AUDIT_AI_CONNECT_TIMEOUT_MS`, `AUDIT_AI_READ_TIMEOUT_MS`, `AUDIT_OCR_CONNECT_TIMEOUT_MS`, `AUDIT_OCR_READ_TIMEOUT_MS`
- Existing MDC `requestId` is forwarded as `X-Request-Id`; absent MDC produces no downstream header.
- User Authorization tokens and infrastructure credentials are not forwarded.
- Spring web-client/converter logging is held at INFO to avoid request document body logging.

Mock HTTP tests cover method/path, OCR multipart content, AI request JSON, response mapping, confidence/pages/findings, request-ID propagation, absence behavior, connect/read failures, HTTP 4xx/5xx, malformed JSON, non-200 envelope code, and missing data.

Downstream failures map to `EXTERNAL_SERVICE_ERROR` / HTTP 502 with the fixed message `external service unavailable`. `ExternalServiceException` preserves the `AI`/`OCR` service type and original cause for diagnostics, while the handler logs only request ID, service type, and cause class. It does not log the cause message or pass a throwable to the logger. Client responses do not contain the internal URL, downstream response body, OCR text, or contract text.

OCR MIME syntax is validated before a downstream request is created. `application/pdf` and `image/png` are covered as valid types. Invalid MIME syntax maps to `INVALID_ARGUMENT` / HTTP 400 and produces no HTTP request.

Contract status: **CONTRACT TESTED AGAINST LOCAL MOCK ONLY / REAL FASTAPI NOT VERIFIED**. This remains a draft contract, not a final customer API.

## Profiles, adapter selection, authentication, and offline local behavior

- Exactly one of `local`, `dev29`, or `customer` must be active. None or multiple runtime profiles fail with a stable startup error.
- `local`: in-memory repository/cache, fake OCR/AI, and mock auth are selected by `@Profile("local")`; JDBC and Redis auto-configuration are explicitly excluded.
- `dev29`/`customer`: DM8, Redis-protocol cache, and FastAPI adapters are selected by `@Profile({"dev29", "customer"})`; deployment-specific values use environment variables.
- YAML `audit.adapters` selector properties were removed because no Java code consumed them. Profile annotations are the adapter-selection source of truth.
- TongRDS remains **PROTOCOL TARGET ONLY / NOT VERIFIED**.
- External authentication is **NOT IMPLEMENTED / CONTRACT PENDING**. Only `LocalMockAuthContext` exists; no JWT, SSO, or LDAP adapter exists.
- `AUDIT_AUTH_MODE` is **RESERVED FOR FUTURE AUTH ADAPTER** and is not consumed by current code.

An application-context test confirms the local profile creates neither a `DataSource` nor a `RedisConnectionFactory`.

The production `ValidationProbeController` and DTO were removed, and `/api/v1/validation-probe` was removed from the security allowlist. Bean Validation and malformed-JSON tests now use a test-only Controller/DTO under `src/test/java`. A production-context mapping assertion verifies the old API is not registered.

## Build and tests

Final command:

```powershell
cd D:\code\audit_agent_1D\backend
.\mvnw.cmd clean verify
```

Final result:

```text
Tests run: 65, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 15.762 s
Finished at: 2026-08-14T15:45:07+08:00
```

The Round 1 suite remains green and the executable JAR is `backend/target/audit-backend-0.1.0-SNAPSHOT.jar`.

## Real local runtime and HTTP evidence

Command:

```powershell
java -jar target\audit-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local --debug=false
```

- Port 8080 before start: available
- Java PID: `22852`
- Process after checks: stopped
- Port 8080 after stop: released

Real request:

```powershell
curl.exe --silent --show-error --include http://127.0.0.1:8080/actuator/health
```

Real response:

```http
HTTP/1.1 200
X-Request-Id: d028a82b-5a0f-4265-a444-320479ed1dce
Content-Type: application/vnd.spring-boot.actuator.v3+json

{"status":"UP"}
```

Real request:

```powershell
curl.exe --silent --show-error --include -H "X-Request-Id: round2-1-local-health" http://127.0.0.1:8080/api/v1/health
```

Real response:

```http
HTTP/1.1 200
X-Request-Id: round2-1-local-health
Content-Type: application/json

{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
```

The removed production validation probe was also checked at runtime:

```powershell
curl.exe --silent --show-error --include -H "X-Request-Id: round2-1-validation-absent" http://127.0.0.1:8080/api/v1/validation-probe
```

```http
HTTP/1.1 401
X-Request-Id: round2-1-validation-absent
Content-Type: application/json;charset=UTF-8

{"code":401,"message":"authentication required","data":null}
```

The runtime response confirms the deleted path is no longer public. The production-context mapping test separately proves that no handler is registered for `/api/v1/validation-probe` (the security boundary responds before handler lookup for an unauthenticated unknown `/api/v1/**` path).

## Source, secret, and formatting scans

- Application/domain import scan: no MyBatis, Redis, Jackson, Spring HTTP, or `RestClient` imports.
- DM8 SQL scan: no `AUTO_INCREMENT`, `ON UPDATE CURRENT_TIMESTAMP`, BOOLEAN assumption, `MERGE`, `RETURNING`, or inline-index syntax.
- Source-scope scan: no F02 implementation, task queue, Redis Streams/Pub/Sub/flush behavior, WebClient, Feign, OkHttp, or Resilience4j.
- Adapter-selector scan: no `audit.adapters` property remains; adapter selection is profile-annotation driven.
- Production API scan: validation probe classes exist only in test scope; `/api/v1/validation-probe` has no production request mapping or security allowlist entry.
- Full-tree trailing-whitespace scan excluding build output: no matches.
- `git diff --check`: exit code 0.
- EOL check: `.env.example`, `backend/mvnw`, and `application-customer.yml` are LF; `backend/mvnw.cmd` is CRLF. The wrapper still reports Maven 3.9.16 / Java 17 after its mechanical EOL normalization.
- Secret/address scan: **PASS after semantic review**. Matches are environment-variable names/placeholders, policy/runbook text, official Maven Wrapper credential support, loopback mock/runtime URLs, and the JDK version string. No credential value, token/API key, customer/dev29 address, or real internal IP was found.

## External verification status

- DM8 Repository code: **IMPLEMENTED / UNIT TESTED**
- DM8 JDBC Driver: **PENDING REAL MEDIA VERIFICATION**
- DM8 SQL: **DRAFT / NOT VERIFIED ON REAL DM8**
- Redis Adapter: **UNIT TESTED / REAL SERVICE NOT VERIFIED**
- TongRDS: **PROTOCOL TARGET / NOT VERIFIED**
- FastAPI OCR: **MOCK HTTP CONTRACT TESTED / REAL SERVICE NOT VERIFIED**
- FastAPI AI: **MOCK HTTP CONTRACT TESTED / REAL SERVICE NOT VERIFIED**
- External Authentication: **NOT IMPLEMENTED / CONTRACT PENDING**
- TongWeb: **NOT VERIFIED**
- TongHttpServer: **NOT VERIFIED**
- Customer environment: **NOT VERIFIED**
- dev29: **DEFERRED - VPN unavailable**

## Git closure

- Final branch: `feature/bootstrap-backend-baseline`
- Staged changes: none
- Commits/tags/pushes created: none
- Tracked modified/deleted files reported by `git status`: 27 (including the EOL-only `backend/mvnw.cmd` normalization, which has no textual `git diff` hunk)
- Untracked new files: 32
- All Round 2 changes remain unstaged for human review.
