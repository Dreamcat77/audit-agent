# Compatibility matrix

| Component | Current status | Evidence or next condition |
| --- | --- | --- |
| Local account authentication | IMPLEMENTED / LOCAL VERIFIED | Spring Security API, in-memory user, Session/CSRF/lock/audit integration tests |
| DM8 user/login/operation-audit adapters | IMPLEMENTED / MAPPER-MOCKED UNIT TESTED | Real DM8 JDBC transaction and SQL smoke test still required |
| DM8 JDBC Driver | PENDING REAL MEDIA VERIFICATION | Verify filename/version/checksum from approved DM8 installation media |
| DM8 `V001` / `V002` / `V003` / `V004` SQL | DRAFT / NOT VERIFIED ON REAL DM8 | Execute with dedicated non-SYSDBA application account; verify DDL, constraints, composite FKs, indexes, conditional row counts, timestamps, and transactions |
| F02 deterministic review | IMPLEMENTED / LOCAL VERIFIED | Five Java rules, API/security/idempotency/task/audit integration tests; no AI/OCR call |
| DM8 F02 adapters | IMPLEMENTED / MAPPER-MOCKED UNIT TESTED | Contract/config/result/task/idempotency mappings tested without claiming real DM8 execution |
| Durable F02 idempotency | LOCAL VERIFIED / REAL DM8 NOT VERIFIED | In-memory atomic concurrency tests; DM8 unique-insert and conditional-update adapter tests; Redis not used |
| Contract ReviewSession to `READY` | IMPLEMENTED / LOCAL VERIFIED | Domain, local CAS, API/security, replacement/history and recovery tests; no OCR/AI or session-driven F02 |
| DM8 V004 ReviewSession adapters/schema | IMPLEMENTED / STRUCTURE TESTED / REAL DM8 NOT VERIFIED | MyBatis mappings and DM8 DDL structure are automated-test verified; execute composite constraints and short transactions on approved DM8 |
| Filesystem material storage | IMPLEMENTED / LOCAL VERIFIED | Streaming SHA-256, signature validation, same-FileStore atomic create-only hard-link publication, startup non-overwrite probe and recovery tests; each production filesystem must pass the probe |
| Redis cache adapter | UNIT TESTED / REAL SERVICE NOT VERIFIED | Mocked `StringRedisTemplate`; GET, SET+TTL, DEL only |
| Spring Session Data Redis | CONFIGURED / REAL SERVICE NOT VERIFIED | Verify Session create/read/rotate/expire/logout against actual Redis environment |
| TongRDS as CachePort target | PROTOCOL TARGET / NOT VERIFIED | Exact media/version/command subset required |
| TongRDS + Spring Session | NOT VERIFIED | Dedicated compatibility spike for hashes, TTL, expiration, serialization, Session rotation/logout |
| TongRDS MQ / asynchronous review queue | NOT IMPLEMENTED / NOT VERIFIED | No `TaskQueuePort`, `@Async`, Redis queue, worker, or product compatibility claim |
| Micrometer/OpenTelemetry tracing | LOCAL VERIFIED / NO EXPORTER | Inbound MDC and outbound W3C `traceparent` tests; no Zipkin/Jaeger/OTLP/APM configured |
| Logstash structured technical logging | LOCAL STARTUP VERIFIED | Spring Boot built-in format; external log platform and customer retention are not configured |
| FastAPI OCR | MOCK HTTP CONTRACT TESTED / REAL SERVICE NOT VERIFIED | Existing local `MockRestServiceServer` tests plus Boot-managed RestClient change |
| FastAPI AI | MOCK HTTP CONTRACT TESTED / REAL SERVICE NOT VERIFIED | Existing local `MockRestServiceServer` tests plus Boot-managed RestClient change |
| TongWeb | NOT VERIFIED | Exact edition/version/patch/media required; executable-JAR-first remains current baseline |
| TongHttpServer | NOT VERIFIED | Exact version/topology/media and trusted-proxy policy required |
| dev29 | DEFERRED - VPN unavailable | No probe, connection, deployment, DM8, Redis, or HTTP integration performed |
| customer | NOT VERIFIED | Configuration template only; no customer secrets or environment verification |

This matrix does not claim product certification or customer-environment compatibility.
