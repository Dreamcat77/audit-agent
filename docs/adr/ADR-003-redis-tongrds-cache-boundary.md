# ADR-003: Redis and TongRDS cache boundary

## Context

Redis is the current cache implementation and TongRDS is the customer target. The actual TongRDS version and Redis protocol/command subset are unavailable for verification.

## Decision

- Keep `CachePort` application-owned and implement it with `RedisCacheAdapter` using `StringRedisTemplate`.
- Restrict the adapter to GET, SET with a positive TTL, and DEL.
- Prefix all keys with a configurable namespace whose safe default is `audit-agent:`.
- Treat Redis/TongRDS only as cache-aside, short idempotency/lock storage, or disposable information, not as the system of record.
- Do not create a `TongRdsCacheAdapter` before real media and protocol verification.
- Do not use Streams, Lua as a core prerequisite, Pub/Sub as a reliable queue, modules, `FLUSHALL`, or `FLUSHDB`.
- If reliable asynchronous work is later required, design a separate `TaskQueuePort`; none is created in this Round.

## Consequences

The adapter has a deliberately small protocol surface. Reuse with TongRDS requires successful validation against the actual product media and documented command subset. No protocol-validation claim is made now.

## Status

Redis: **IMPLEMENTATION UNIT TESTED / REAL SERVICE NOT VERIFIED**. TongRDS: **PROTOCOL TARGET ONLY / NOT VERIFIED**.
