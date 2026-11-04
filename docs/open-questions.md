# Open questions

The following questions do not block the local Round A baseline or the local F02 deterministic slice:

1. What exact TongWeb edition, version, patch level, supported Servlet level, and deployment packaging will the customer provide?
2. What exact TongRDS version and Redis protocol/command compatibility subset is supported for ordinary cache operations?
3. What exact TongHttpServer version and ingress topology will be used, and which proxies can be trusted before any forwarded-header source-IP strategy is enabled?
4. Which exact DM8 JDBC JAR filename/version/checksum will be verified from real media, which internal Maven coordinates will be approved, and which migration tool/process will execute `V001`/`V002`/`V003`?
5. What is the confirmed retention, archival, partitioning, access-control, export, and disposal policy for `AUDIT_OPERATION_LOG` and `AUTH_LOGIN_AUDIT` (for example 1/3/5 years or permanent)?
6. Does the actual TongRDS media support the Spring Session Data Redis command/TTL/serialization behavior used by the selected Spring Session version? This requires a dedicated compatibility spike.
7. When will the current draft FastAPI OCR and AI contracts be versioned/finalized, and what retry, idempotency, rate-limit, maximum document size, content-type, and error-taxonomy rules will apply?
8. Which dev29 validation steps may resume after VPN access is restored and architecture review is complete?
9. What retention, stale-`IN_PROGRESS` recovery, operator reconciliation, and archival policy applies to `IDEMPOTENCY_RECORD`? No automatic retry or cleanup policy is assumed in Java.
10. Which durable queue product and delivery/acknowledgement semantics will be approved before any OCR/AI asynchronous worker is designed? TongRDS MQ compatibility is not verified.

Authentication mode is no longer open: Round A selects system-owned username/password accounts, Spring Security, and server-side Session authentication. Full permission/menu/data-scope design is deliberately outside this round, not an unresolved authentication choice.

Current status for dev29 work: **DEFERRED - VPN unavailable**.

No real DM8, Redis, Spring Session Redis, TongRDS/TongRDS MQ, or FastAPI service is verified in this repository delivery. Configuration, SQL inspection, and adapter tests are not product-certification evidence.
