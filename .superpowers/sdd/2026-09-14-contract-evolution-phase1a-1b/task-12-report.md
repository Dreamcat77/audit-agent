# Task 12 report — effective document role resolver

## Scope delivered

- Added the single `EffectiveDocumentRoleResolver` used by later server projections.
- Precedence is exact and closed: latest valid human decision; otherwise a compatible legacy category only for a `LEGACY_IMPORTED` parsed file with no decision; otherwise `UNKNOWN`.
- `PACKAGE` and legacy `UNKNOWN` are explicitly excluded from authoritative role mapping.
- A decision bound to a superseded parse result does not remain effective after reparse.
- The result exposes source, durable decision identity/version, and current immutable parse identity.
- Machine proposal confidence is not an input and `PROJECT_FILE.CATEGORY` is never updated.

## TDD evidence

RED:

```text
.\mvnw.cmd -Dtest=EffectiveDocumentRoleResolverTest test
BUILD FAILURE: EffectiveDocumentRoleResolver/Result/Source missing
```

GREEN:

```text
.\mvnw.cmd -Dtest=EffectiveDocumentRoleResolverTest test
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Verification boundary

- UNIT VERIFIED: precedence, decision supersession, legacy compatibility, PACKAGE/UNKNOWN exclusion, and reparse invalidation.
- REAL DM8 NOT VERIFIED: repository behavior is exercised through the local adapter only in this focused task.
- REAL CUSTOMER MATERIAL NOT VERIFIED: all values are synthetic and no external environment was accessed.
