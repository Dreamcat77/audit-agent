# ADR-004: FastAPI HTTP adapters

## Context

The OCR and AI services are separate FastAPI processes. Their current routes and envelopes are draft contracts, and real services cannot be reached in this phase. Application Ports must accept real file/text input without exposing HTTP or Spring types.

## Decision

- Evolve `OcrPort` to accept an `OcrRequest` containing document ID, filename, content type, and defensively copied bytes.
- Evolve `AiReviewPort` to accept an `AiReviewRequest` containing source ID, primary/reference text, and check items.
- Keep application-owned OCR/AI result and finding/page types separate from private HTTP DTOs.
- Use Spring `RestClient`: OCR posts multipart field `file` to `/ai/v1/ocr/extract`; AI posts JSON to `/ai/v1/agent/contract-review`.
- Configure finite connect/read timeouts independently for OCR and AI. Do not automatically retry until idempotency and rate-limit rules are confirmed.
- Propagate an existing MDC `requestId` as `X-Request-Id`; do not invent one inside an adapter and do not forward user Authorization tokens or infrastructure credentials.
- Map connect/read failures, HTTP 4xx/5xx, non-200 envelopes, malformed JSON, and missing data to a fixed `EXTERNAL_SERVICE_ERROR` (HTTP 502) without exposing URLs or response bodies.
- Preserve the external service type and original cause for diagnostics. Log only request ID, service type, and cause class; do not log the cause message or throwable stack by default.
- Validate OCR MIME syntax before creating the downstream request. Invalid MIME syntax maps to local `INVALID_ARGUMENT` (HTTP 400), while valid types such as `application/pdf` and `image/png` proceed to multipart transport.
- Keep Spring HTTP/converter logging at INFO so request documents are not emitted by debug body logging.

## Consequences

The Java boundary is callable and mock-contract tested without a real network. Contract changes require only adapter DTO/mapping changes unless the application meaning also changes. There is no retry or circuit-breaker behavior in this baseline.

## Status

**CONTRACT TESTED AGAINST LOCAL MOCK ONLY / REAL FASTAPI NOT VERIFIED**. The contract remains a draft, not a final customer API.
