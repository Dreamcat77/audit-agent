# Contract AI Stability Closeout Design

## Scope and invariants

This approved closeout hardens the existing Contract semantic-analysis path without adding AI
persistence, schema, tasks, history, RAG, a generic prompt API, or changes to F01/F02/F03/Project
lifecycle. Java deterministic `BigDecimal` rules remain authoritative. No runtime deployment or
remote probe is part of this change.

## Python request and retry boundary

Each Contract request has one request-wide retry token. A request with `N` chunks therefore makes
at most `N + 1` provider calls. The token may be consumed by a typed/safety rejection
(`INVALID_TYPED_SCHEMA`, `INVALID_EVIDENCE_REF`, `STRONG_VERDICT`, `MONETARY_VALUE`, or
`MONETARY_CALCULATION`) or by a retryable provider failure (`CONNECT_TIMEOUT`, `READ_TIMEOUT`,
`CONNECT_ERROR`, `PROVIDER_429`, `PROVIDER_5XX`, `INVALID_PROVIDER_JSON`, or
`INVALID_PROVIDER_RESPONSE`). Explicit provider 4xx errors and local configuration/input errors do
not retry. A second failure remains fail closed under the stable public error contract.

A safety retry sends the original sanitized source plus a fixed internal retry instruction. It
never sends the rejected response back to the provider. Attempt durations accumulate into the
final result. Budget keeps its existing single-call business behavior.

`X-Request-Id` is passed explicitly from the FastAPI route through the application operation to
gateway, rejection, and completion logs. Log fields remain allowlisted; attempt counts are
non-negative integers and safety/gateway reasons use separate strict allowlists. No source,
prompt, response body, credential, or authorization value is logged.

## Frontend lifecycle boundary

`useContractSemanticAnalysis` owns semantic request state independently of F02. It uses a
synchronous pending ref, request generation, AbortController, contract/project identity, and an
ordered selection key. Each request captures both file IDs and friendly material facts. A result
may commit only while mounted and while all captured identities still match. Contract/project/
selection changes abort and invalidate only the AI branch; `AbortError` is not user-visible.
Evidence labels are resolved from the successful request snapshot, never the current picker.

The picker warns, but does not block or mutate selection, when normalized filename plus category
appears more than once. No digest or schema is added.

## Timeout and verification

The Java-to-FastAPI AI read timeout defaults to 150000 ms so one 60-second provider call plus one
bounded retry and application overhead can complete. OCR remains 120000 ms. Python 3.11, Backend,
Frontend tests/build, diff checks, and secret scans are mandatory. Real Qwen remains
`REAL_QWEN_RETEST_PENDING_USER_RUNTIME` until the user reruns it with protected local runtime
configuration.
