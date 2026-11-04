# ADR-001: Infrastructure adapter boundary

## Context

The backend must remain a modular monolith whose review application/domain code is independent of DM8, MyBatis, Redis, TongRDS, and FastAPI client details. The `local` profile must start without any external service, while `dev29` and `customer` need explicit infrastructure wiring.

## Decision

- Application-owned Ports remain the dependency direction: `AuditTaskRepository`, `CachePort`, `OcrPort`, and `AiReviewPort`.
- MyBatis, Redis, multipart, JSON, `RestClient`, and HTTP DTOs exist only under infrastructure/integration packages.
- `local` uses in-memory/fake adapters and explicitly excludes JDBC and Redis auto-configuration.
- Adapter selection is defined only by profile annotations: local adapters use `@Profile("local")`; DM8, Redis-protocol cache, and FastAPI adapters use `@Profile({"dev29", "customer"})`.
- Do not publish YAML adapter selector properties unless Java code actually consumes and tests them.
- External authentication remains a target boundary; no dev29/customer `AuthContext` adapter is implemented.
- DM8 remains the system of record. Cache data remains disposable and recomputable.

## Consequences

Application and domain code can be tested without vendor media or network access. External adapters require separate real-environment verification. Infrastructure failures are mapped at the adapter boundary and do not leak Spring client exceptions into the application API.

## Status

Accepted for the Round 2 baseline. External services are **NOT VERIFIED**.
