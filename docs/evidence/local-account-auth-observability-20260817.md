# Local account authentication and observability evidence — 2026-08-17

## Scope and evidence safety

This evidence covers Round A only: Request/Trace correlation, technical logging, durable audit boundaries, system-owned username/password authentication, CSRF, CORS, and Session behavior. It does not claim F01-F15, contract review, OCR/AI business, external deployment, or product compatibility.

The HTTP run used a synthetic local-only account. Its password was generated in process, passed only to the offline hash tool through stdin, and cleared after the run. This file does not contain a raw password, password hash, Cookie value, Session ID, or CSRF Token.

## Maven gate

Executed from `backend`:

```powershell
.\mvnw.cmd clean verify
```

Result: **BUILD SUCCESS** on Java 17 and Maven Wrapper 3.9.16.

- Production sources compiled: 85
- Test sources compiled: 31
- Test classes: 31
- Tests: 98
- Failures: 0
- Errors: 0
- Skipped: 0

The automated suite includes Request ID validation, trace/span/MDC, W3C outbound propagation, configurable trace sampling, structured logging startup, profile defaults, authenticated/anonymous HTTP_OUT user correlation, log redaction, account status and lock behavior, 24-request concurrent failure counting, login/logout audit correlation, CSRF, explicit CORS headers, Session fixation/logout, local no-DM8/no-Redis wiring, non-local operation-audit fail-closed behavior, insert-only operation audit, and DM8 Mapper/Adapter mock tests.

## Strict Review Fix verification

- `audit.operation-audit.enabled=false` produced an ApplicationContext startup failure for both `dev29` and `customer`: `durable operation audit must be enabled for dev29 and customer profiles`. The explicit disabled Port bean remains available only under `local`.
- A real login Session followed by `GET /api/v1/auth/me` produced `HTTP_OUT ... status=200 ... userId=<the synthetic CurrentUser.id>`. In the same integration test, anonymous `GET /api/v1/health` produced `HTTP_OUT ... status=200 ... userId=anonymous`. The assertion matches the seeded `CurrentUser.id`, rather than merely checking for a non-anonymous string.
- Local CORS preflight with an explicit allowed origin accepted `Content-Type`, `Idempotency-Key`, `X-CSRF-TOKEN`, and `X-Request-Id`. The existing wildcard-with-credentials rejection test continues to pass. No idempotency Port or business implementation was added.
- `management.tracing.sampling.probability` now resolves from `${AUDIT_TRACE_SAMPLING_PROBABILITY:1.0}`. The default remains full sampling and no exporter was added.

## Local HTTP run

The packaged executable JAR was started with profile `local` on a loopback-only temporary port. A real HTTP client retained the Cookie and CSRF value internally; the output recorded only safe metadata.

| Request | Result | Safe observation |
| --- | --- | --- |
| `GET /api/v1/health` | 200 | `X-Request-Id` preserved; response status `UP` |
| `GET /api/v1/auth/csrf` | 200 | standard header name returned; Token and Session Cookie present, values not printed |
| `POST /api/v1/auth/login` | 200 | synthetic user and roles returned; Session ID rotated |
| `GET /api/v1/auth/me` | 200 | authenticated user and roles returned |
| `POST /api/v1/auth/logout` | 200 | `logged_out` returned |
| `GET /api/v1/auth/me` after logout | 401 | JSON `authentication required`; old Session no longer authenticated |

Every supplied legal evidence Request ID was returned unchanged. The login technical log contained a 32-hex Trace ID, 16-hex Span ID, and the same Request ID. The following local flow markers were observed:

```text
HTTP_IN
AUTH_ATTEMPT
USER_REPOSITORY_READ
AUTH_SUCCESS
LOGIN_AUDIT_INSERT
SESSION_CREATED
HTTP_OUT
```

The local log was scanned against the run's actual password, delegating hash, pre/post-auth Session Cookie values, and CSRF values after the process stopped: **0 sensitive runtime value matches**.

`InMemoryLoginAuditAdapter` insertion is demonstrated by `LOGIN_AUDIT_INSERT` in the real request flow and by integration tests that directly inspect correlated success/failure/logout events. `InMemoryOperationAuditAdapter` enablement and append behavior are verified by wiring and Port contract tests; no Round A business endpoint creates an operation event merely for demonstration.

## Persistence and transaction evidence

`V002__local_account_auth_observability.sql`, the DM8 Mappers, and the DM8 adapters are covered by SQL-structure and mock adapter tests. These tests verify atomic failed-count SQL, role mapping, insert-only audit APIs, and mandatory transaction semantics at the adapter boundary. They are not a substitute for an approved DM8 runtime test.

## Verification status

- Authentication, Servlet Session, CSRF/CORS, Request/Trace correlation, flow logging, local login audit: **LOCAL VERIFIED**
- DM8 Mapper/Adapter behavior and outbound FastAPI HTTP propagation against mocks: **MOCK VERIFIED**
- DM8, Redis Session, and FastAPI integration on dev29: **DEV29 NOT VERIFIED**
- Customer runtime/deployment: **CUSTOMER NOT VERIFIED**
- TongRDS Cache and Spring Session compatibility: **TONGRDS NOT VERIFIED**

Configuration, migrations, or automated mocks must not be cited as evidence of the unverified external states above.
