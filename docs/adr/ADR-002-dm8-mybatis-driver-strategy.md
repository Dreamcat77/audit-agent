# ADR-002: DM8 MyBatis and JDBC driver strategy

## Context

DM8 is the only formal relational database. MyBatis Spring Boot Starter 3.0.5 supports the Spring Boot 3.x line, but the approved DM8 JDBC JAR filename and version cannot be verified while VPN and real installation media are unavailable.

## Decision

- Use MyBatis Spring Boot Starter `3.0.5`; do not use MyBatis 4.x, MyBatis-Plus, JPA, or Hibernate.
- Keep mapper interfaces/XML and `Dm8AuditTaskRepository` in `integration.dm8`; review application/domain code does not import MyBatis.
- Use standard `spring.datasource.*` properties and the known driver class `dm.jdbc.driver.DmDriver`.
- Do not declare a guessed DM JDBC Maven dependency, use `systemPath`, or commit a vendor JAR.
- Prefer publishing the verified vendor artifact to the company internal Maven repository. A controlled local Maven repository installation is an explicitly reviewed fallback.

## Consequences

Repository code and mappings can be unit tested with a mocked Mapper, but non-local runtime cannot create a DM8 connection until the approved driver is provisioned. F02 replaces the original task `save` baseline with explicit `create`, lookup, and conditional `transition` operations. Task transitions match both status and `VERSION_NO`; an affected-row count other than one is a concurrency conflict. Durable idempotency claims first attempt an insert protected by the DM8 unique `(SCOPE, IDEMPOTENCY_KEY)` constraint.

## Status

Repository: **IMPLEMENTED / UNIT TESTED**. DM JDBC driver: **PENDING REAL DM8 MEDIA VERIFICATION**. SQL: **DRAFT / NOT VERIFIED ON REAL DM8**.
