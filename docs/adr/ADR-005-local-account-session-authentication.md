# ADR-005: Local account and session authentication

## Context

The authentication contract is now fixed as system-owned username/password accounts. User identity, password hashes, roles, login state, and security audit facts belong in DM8. Browser authentication needs server-side sessions, CSRF protection, a local profile without Redis, and a non-local profile that can use the existing Redis infrastructure without abusing `CachePort`.

## Decision

- Use Spring Security with a custom `AuthenticationProvider` and an application-owned `UserAccountRepository` Port.
- Canonicalize usernames by trimming and applying `Locale.ROOT` lowercase, allow only `[a-z0-9._-]`, and enforce uniqueness on canonical `SYS_USER.USERNAME`.
- Use `PasswordEncoderFactories.createDelegatingPasswordEncoder()`. New hashes use bcrypt and retain the delegating `{bcrypt}` prefix. Raw passwords exist only in the short-lived login request/authentication call and are never persisted or logged.
- Keep users, hashes, roles, failed counts, lock timestamps, and login timestamps in DM8. `local` uses an optional environment-seeded in-memory account repository; migrations never create a default administrator password.
- Increment failed-login counts with an atomic database expression and an equivalent `ConcurrentMap.compute` local implementation. The maximum attempt count and lock duration are configuration properties.
- Return an authentication decision from the transactional application service. The Spring Security provider converts a failed decision to the same generic 401 only after account state and `AUTH_LOGIN_AUDIT` commit. A successful decision is committed before SecurityContext and Session creation.
- Use standard Servlet `HttpSession` in `local`. Use Spring Session Data Redis in `dev29`/`customer` with the `audit-agent:sessions` namespace. Spring Session does not use `CachePort`.
- Use `HttpSessionCsrfTokenRepository`; login and logout remain CSRF-protected. Expose the standard token through `GET /api/v1/auth/csrf`.
- Use explicit-save `HttpSessionSecurityContextRepository`, change the Session ID on authentication, clear the CSRF token after authentication, and invalidate the Session on logout.
- Set the Session cookie `HttpOnly=true`, `Path=/`, and `SameSite=Lax`. `customer`/`dev29` default `Secure=true`; `local` uses `Secure=false` for HTTP development.
- Production is same-origin. Optional local CORS origins are explicit, credentialed, and reject `*`. Their preflight allow-list includes `Content-Type`, `Idempotency-Key`, `X-CSRF-TOKEN`, and `X-Request-Id`; this reserves only an HTTP header and does not implement business idempotency.
- `SpringSecurityAuthContext` is the only Application-facing current-user adapter. Business Application code must not read `SecurityContextHolder`.
- Source IP currently means `HttpServletRequest.remoteAddr`; forwarded headers are not trusted without a separately confirmed proxy topology.

## Consequences

Redis loss can invalidate sessions and require login again, but it cannot remove user, role, login-audit, or business facts. A failed durable login-audit insert fails the authentication attempt instead of being ignored. Session compatibility with TongRDS remains a separate product validation task.

## Status

Accepted for Round A. `local` is automated-test verified. DM8, Redis Session, dev29, customer, and TongRDS runtime behavior are **NOT VERIFIED**.
