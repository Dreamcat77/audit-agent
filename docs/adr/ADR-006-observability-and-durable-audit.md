# ADR-006: Observability, technical logs, and durable audit logs

## Context

`X-Request-Id` already identified one HTTP request, but it could not represent a distributed chain across Java, FastAPI, future tasks, and model calls. Engineering diagnostics and customer-facing long-term audit facts also had to be separated so that console logs would not become a business source of truth.

## Decision

- Preserve `RequestIdFilter` and its existing validation. One HTTP request has one Request ID.
- Add Micrometer Tracing with the OpenTelemetry bridge. Use the standard W3C Trace Context propagator; do not parse `traceparent` or generate downstream trace IDs in application code.
- A Trace ID represents the distributed chain and is distinct from Request ID. Micrometer places `traceId` and `spanId` in MDC; `RequestIdFilter` continues to place `requestId` in MDC.
- Build FastAPI clients from the Spring Boot-managed `RestClient.Builder` so observation instrumentation injects W3C context. Continue explicit `X-Request-Id` propagation. Preserve existing paths, DTOs, timeout factories, and error mapping.
- Add only `micrometer-tracing-bridge-otel`. Do not configure Zipkin, Jaeger, OTLP, or another exporter in this round.
- Keep tracing sampling at `1.0` by default while allowing deployment override through `AUDIT_TRACE_SAMPLING_PROBABILITY`.
- Use SLF4J/Logback technical logs. `dev29` and `customer` use Spring Boot's built-in Logstash JSON format because it needs no third-party encoder and includes MDC plus fluent key/value fields.
- `audit.observability.flow-debug-enabled` controls selected architecture-boundary DEBUG events. It is true in `local` and false in `dev29`/`customer`; health requests keep the existing access log but skip flow-debug entry logs.
- Technical logs are for operations and diagnostics only. They never become the system of record and must not contain passwords, hashes, cookies, Session IDs, CSRF/Authorization values, full request bodies, document/OCR/Prompt/model bodies, or sensitive SQL parameters.
- Transfer an authenticated `CurrentUser.userId` from the inner Spring Security chain to the outer `HTTP_OUT` logger through a request attribute. Do not extend the SecurityContext ThreadLocal lifetime or reload the user for logging; requests without a valid `CurrentUser` log `userId=anonymous`.
- Define append-only `OperationAuditPort` and `OperationAuditEvent` for business facts. `local` uses memory; `dev29`/`customer` use `Dm8OperationAuditAdapter` and `AUDIT_OPERATION_LOG`.
- `OperationAuditEvent` supports an optional `taskId` correlation field. F02 uses it for review start/completion/failure facts; rule-by-rule execution remains technical flow logging and is not copied into the durable audit table.
- Define a separate append-only `LoginAuditPort` and `AUTH_LOGIN_AUDIT` for authentication security facts.
- DM8 audit adapters require an existing transaction. Critical state changes and their audit inserts are composed in the same Application transaction; audit failures are never silently swallowed.
- `audit.operation-audit.enabled=false` is a local/test-only seam. In `dev29` and `customer`, an eager startup guard rejects this value and fails the ApplicationContext before the runtime can silently select a no-op Port. Every committed runtime profile still defaults it to true.

## Consequences

Logs can correlate `traceId`, `spanId`, and `requestId`, and downstream calls preserve both distributed and HTTP-request correlation without coupling business code to OpenTelemetry APIs. Durable audit growth and retention must be handled by a future confirmed customer policy; no Java cleanup period is assumed.

## Status

Accepted for Round A. MDC, W3C propagation, structured-log startup, and local append-only adapters are automated-test verified. External log platforms, DM8 runtime SQL, dev29/customer, and TongRDS are **NOT VERIFIED**.
