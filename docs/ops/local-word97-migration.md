# Local Word97 schema gate and recovery

Status: migration **NOT_APPLIED**. Readonly Plan is verified against the approved local DM8 business identity. No DDL, backup, restore or service restart was performed for this migration.

The existing enabled CHECKs on `PROJECT_FILE.PARSE_METHOD` and `PROJECT_FILE_PARSE_RESULT.PARSE_METHOD` allow PDF_TEXT/DOCX_TEXT/OCR but not DOC_TEXT. The former remains nullable; the immutable parse result remains NOT NULL. The new Java parser cannot be deployed as live DOC persistence support until these two allowlists are expanded.

## Readonly commands

From the canonical integration tree:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/local-test/migrate-word97-local.ps1 -Mode Plan
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/local-test/migrate-word97-local.ps1 -Mode Verify
```

The wrapper imports only three literal DB environment assignments through the existing AST allowlist, checks the immutable approved identity, restores its environment, and suppresses raw errors. It never executes a private environment script. Verify intentionally fails while either old constraint remains. Apply is permanently disabled in this wrapper, before DB connection. A successful Plan is not successful migration.

`scripts/local/contract-test.ps1` Start and Preflight require `StartPreflight`, which refuses old/missing/drifted DOC constraints. Status still diagnoses the original running services; this is not a bypass for a new startup. All original SHA, model, source, storage and process ownership gates remain in force.

## Required controlled maintenance

The current user requires all services to stay running, so do not execute the operator SQL now. In a separately authorized maintenance window:

1. Stop all writers using their actual ownership records. Prove no Java/file/review worker can write. Prove the exact approved business schema, enabled constraint definitions and column types/nullability from the live catalog.
2. Make a **current complete typed backup** of all business and protected data; verify count, SHA and restore roundtrip. Record protected/business row fingerprints, unrelated catalog fingerprint and filesystem identity. The old pre-reset backup is identity authority only, not a usable migration backup.
3. Open a durable migration journal containing identity, source SHA, backup hashes, before state and next DDL intent. Recheck immediately before each DDL.
4. Execute the eight statements from `db/dm8/CONTRACT__project_file_word97_method.sql` individually. Each table first gains a wider temporary guard; only then is the old named check replaced. Verify exact next catalog state and unchanged row fingerprints after every statement. Do not execute this entire file blindly.
5. Run readonly Verify and the full existing preflight, then build/start the actual selected SHA normally. Verify genuine DOC root/ZIP ingestion, original file SHA, immutable DOC_TEXT parse identity and reopening in DM8. Those live checks are still NOT VERIFIED.

## Interrupted DDL or rollback

DM8 DDL may commit implicitly. On timeout, lost connection or unexpected catalog state, leave writers stopped. Reopen the **same** journal and backup; use Plan to identify exact OLD/NEW/temporary-guard state. Unknown, disabled or unguarded states fail closed. Do not replay earlier steps or drop a temporary guard without proving the final named check.

Prefer completing the additive forward migration after reconciliation. Reverting to an old check is unsafe if any DOC_TEXT row exists: do not delete rows or rewrite provenance to make rollback possible. A full restore, if separately approved, must use this maintenance window's verified complete backup and account for all writes since it; never restore the historical empty/reset backup. Keep original files, storage pointer, accounts, roles, permissions, rules and configuration. Do not restore the old 51 file references or delete old storage.

The tool does not claim automated Apply/restore support or representative-platform DDL atomicity. The public SQL and recovery procedure are operator preparation, not migration evidence.
