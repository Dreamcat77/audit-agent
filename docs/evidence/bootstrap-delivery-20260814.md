# Backend bootstrap delivery evidence — 2026-08-14

## Preflight

- Repository: `D:\code\audit_agent_1D`
- Branch: `feature/bootstrap-backend-baseline`
- Initial working tree: clean
- Initial HEAD/checkpoint: `394fb0a` / `pre-codex-20260814`
- Development and acceptance environment for this phase: Windows PowerShell 5.1
- No Git initialization, branch switch, commit, tag, push, hard reset, clean, or remote deployment was performed.
- No dev29 address or service was contacted or probed.

## Toolchain

```text
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Java version: 17.0.19, vendor: Eclipse Adoptium
```

- Java compiler: `javac 17.0.19`
- Spring Boot: `3.5.16` GA
- Maven Wrapper: `3.3.4`, official `only-script` distribution
- Maven distribution: `3.9.16`
- Maven bootstrap archive SHA-512 matched the checksum published beside the Maven Central artifact.
- The generated Windows wrapper contains a documented null guard required for PowerShell 5.1 directory metadata.

Command:

```powershell
cd D:\code\audit_agent_1D\backend
.\mvnw.cmd -version
```

Result: **PASS**, and the Wrapper reported Java `17.0.19`.

## Build and tests

Final command:

```powershell
cd D:\code\audit_agent_1D\backend
.\mvnw.cmd clean verify
```

Final result:

```text
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 12.735 s
Finished at: 2026-08-14T14:02:46+08:00
```

The build enforced Java `[17,18)` and Maven `[3.9,4.0)` and produced the executable JAR `backend/target/audit-backend-0.1.0-SNAPSHOT.jar`.

Test coverage includes:

- application context load under `local`;
- absence of Spring Security's generated default user/password;
- unified `/api/v1/health` response;
- minimal `/actuator/health` response;
- `ApiResponse` success and failure factories;
- every reserved `BusinessErrorCode` HTTP mapping;
- unexpected-error detail suppression;
- malformed JSON and constraint-violation HTTP 400 responses without internal details;
- `@NotBlank` and `@NotNull` validation through MockMvc;
- unified Spring Security JSON 401 and 403 responses through the real filter chain;
- Request ID generation, valid passthrough, 128-character boundary, oversized/blank/invalid/control-character replacement, response consistency, and MDC cleanup;
- local AuthContext and in-memory/fake adapter wiring;
- `Money` numeric equality for `100`, `100.0`, and `100.00`.

## Round 1.1 hardening

### Explicit profile guard

Profile status: **EXPLICIT ACTIVE PROFILE REQUIRED**.

- `spring.profiles.default=local` was removed.
- Startup accepts an explicitly active `local`, `dev29`, or `customer` profile.
- Every Fake/InMemory/Mock adapter remains restricted to `@Profile("local")`.
- Unit tests verify local acceptance and no-profile failure.
- A real no-profile JAR start exited with code 1 and root cause: `Explicit active profile required: one of local, dev29, customer`.

### Port ownership

Application-owned ports now reside at:

- `com.fuquan.audit.common.application.port.CachePort`
- `com.fuquan.audit.review.application.port.AiReviewPort`
- `com.fuquan.audit.review.application.port.OcrPort`

The AI/OCR result types moved with their Port contracts to `review.application.port`. Fake/InMemory implementations remain under `integration`. The old integration-owned Port types were deleted; no aliases or duplicate Ports remain.

### HTTP error hardening

- `HttpMessageNotReadableException` returns HTTP 400, code 400, message `malformed request body`, and null data.
- `ConstraintViolationException` returns HTTP 400, code 400, message `constraint validation failed`, and null data.
- Existing DTO Bean Validation still returns HTTP 400 with field errors.
- `ApiAuthenticationEntryPoint` returns unified JSON HTTP 401.
- `ApiAccessDeniedHandler` returns unified JSON HTTP 403.
- Both Security handlers use the configured Jackson `ObjectMapper` and are wired into the production `SecurityFilterChain` without adding JWT, login, users, RBAC, or a fake protected production endpoint.

### Request ID hardening

Client Request IDs are passed through only when they match `^[A-Za-z0-9._-]{1,128}$`. Missing, blank, oversized, control-character, and otherwise invalid values are replaced with a UUID. The final value is shared by MDC and the response header, and MDC is removed in `finally`.

## Runtime and HTTP evidence

The executable JAR was started with the explicit local profile and command-line debug disabled:

```powershell
java -jar target\audit-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local --debug=false
```

- Port 8080 before start: available
- Java PID: `6684`
- Readiness probe: successful
- Process after checks: stopped
- Port 8080 after stop: released

Real request:

```powershell
curl.exe --silent --show-error --include http://127.0.0.1:8080/actuator/health
```

Real response:

```http
HTTP/1.1 200
X-Request-Id: 008561bf-3e54-4b55-bcd7-06886b720f3a
Content-Type: application/vnd.spring-boot.actuator.v3+json

{"status":"UP"}
```

Real request with a client Request ID:

```powershell
curl.exe --silent --show-error --include -H "X-Request-Id: round11-runtime-20260814" http://127.0.0.1:8080/api/v1/health
```

Real response:

```http
HTTP/1.1 200
X-Request-Id: round11-runtime-20260814
Content-Type: application/json

{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
```

Real invalid Request ID request:

```powershell
curl.exe --silent --show-error --include -H "X-Request-Id: invalid request id" http://127.0.0.1:8080/api/v1/health
```

The invalid value was not passed through. The real response contained:

```http
HTTP/1.1 200
X-Request-Id: 9536f93d-7f9f-4a74-86e4-91745ddc7f6d
Content-Type: application/json

{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
```

## Architecture boundary evidence

- `local` loads `InMemoryAuditTaskRepository`, `InMemoryCacheAdapter`, `LocalMockAuthContext`, `FakeOcrAdapter`, and `FakeAiReviewAdapter`.
- Future infrastructure is isolated behind application-owned `AuditTaskRepository`, `CachePort`, `OcrPort`, `AiReviewPort`, and `AuthContext`.
- No DM JDBC, MyBatis-Plus, Spring Data Redis, H2, MySQL, or PostgreSQL dependency is present.
- No Tomcat-private, Nginx-private, TongWeb-private, TongRDS-private, or TongHttpServer-private application logic is present.
- DM8 remains the documented formal relational system of record; the current phase does not implement persistence.
- No F02 business rule was implemented. Only the scale-insensitive `Money` value boundary and tests were added.

## Secret check

Repository scans checked `password`, `secret`, `token`, `SYSDBA`, `SYSAUDITOR`, and IPv4-like values outside build output.

Result: **PASS after semantic review**. Matches are limited to environment-variable names, configuration keys with environment placeholders, policy/documentation text, official Maven Wrapper credential handling, and a test that verifies no generated default password exists. The only IPv4-like match was the JDK version segment `17.0.19.10` in `JAVA_HOME`; it is not an IP address. No credential value, customer address, dev29 address, token, or API key was found.

## External verification status

- DM8: **NOT VERIFIED IN THIS PHASE**
- Redis: **NOT VERIFIED IN THIS PHASE**
- FastAPI OCR: **NOT VERIFIED IN THIS PHASE**
- FastAPI/Qwen: **NOT VERIFIED IN THIS PHASE**
- TongWeb: **NOT VERIFIED**
- TongRDS: **NOT VERIFIED**
- TongHttpServer: **NOT VERIFIED**
- Customer environment: **NOT VERIFIED**
- dev29: **DEFERRED - VPN unavailable**
- dev29 integration: **DEFERRED - perform after local implementation rounds and architecture review**

Historical dev29 facts are documented only as **LAST KNOWN VERIFIED ENVIRONMENT** in `docs/source-baseline.md`; they are not represented as currently verified.

## Maven Wrapper Unix executable mode

Status: **PENDING MANUAL GIT INDEX STEP**.

This Round did not run `git add` or `git update-index`. Before commit, the human reviewer must run:

```powershell
git add .
git update-index --chmod=+x backend/mvnw
git ls-files -s backend/mvnw
```

Expected:

```text
100755 ... backend/mvnw
```

## Git closure

- Final branch: `feature/bootstrap-backend-baseline`
- Staged changes: none
- Commits/tags/pushes created by this work: none
- Untracked additions: `.env.example`, `backend/`, and `docs/`
- `git diff --check`: exit code 0. Git emitted only a pre-existing working-copy line-ending warning for tracked `AGENTS.md`; that user-owned file was not modified.
- A separate full-tree trailing-whitespace scan, including untracked files and excluding build output, returned no matches.
- `git --no-pager diff --stat`: no entries because all delivery additions remain untracked for user review.
