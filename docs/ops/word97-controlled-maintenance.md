# Controlled local Word97 maintenance

The standalone maintenance tool expands only the two existing parse-method CHECKs to admit `DOC_TEXT`. It does not change rows, nullability, files, storage pointers, accounts, roles, permissions, rules or audit history. `Word97LocalMigration` remains read only. The eight DDL statements still come from the approved public SQL and are checked structurally before connection.

Status of this deliverable: offline behavior tests only. Snapshot, DM8 DDL, implicit-commit recovery, live session gates and representative-platform end-to-end execution are **NOT VERIFIED** until an operator runs the commands and records observed results. A generated tool or document is not migration evidence.

## Controlled invocation

Run in `D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010`, branch `integration/pre-main-20261010`. The explicitly approved 40-character commit must be supplied through the mandatory `-ExpectedSourceSha` parameter; no default or environment fallback exists. The wrapper requires exact HEAD equality and a clean `git status --porcelain --untracked-files=all`, and forwards the same SHA to every writer-proof child process. Uncommitted changes and untracked source files block all three modes. Finish and commit all tool fixes before Snapshot; freeze the tool files throughout Snapshot/Apply/Verify. The stop proof binds that commit and the exact tool file hashes. Changed hashes fail closed even if a new SHA is supplied. The wrapper imports only three literal DB assignments through the existing AST allowlist and restores the caller's environment in `finally`. It never executes the private environment file.

Stop the actual owned backend, FastAPI, frontend and any review/file workers using their ownership records. The maintenance wrapper does not stop or kill processes. It repeatedly checks application listeners, current and historical PID ledgers, process creation identities and actual application launch arguments. A maintenance Java classpath containing the DM8 driver is not mistaken for a `-jar` business launch. Relative `-jar` targets, uvicorn without an absolute `--app-dir`, and workers without a provable absolute source remain UNKNOWN and block the operation. Only business launches whose actual source arguments prove an absolute other-worktree path may be ignored. An active or unknown writer blocks the operation. Foreign worktree processes are never killed.

Create the current snapshot only after controlled stop proof:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/local-test/maintain-word97-local.ps1 -Mode Snapshot -WritersStopped -ExpectedSourceSha '<approved 40-character SHA>' -BackupDirectory '<canonical worktree>\.runtime\contract-repair-test\word97-maintenance\e2e-1015'
```

Save the printed `WORD97_BACKUP_DIRECTORY` path. Use that exact directory for every subsequent action; do not copy, edit or regenerate its files.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/local-test/maintain-word97-local.ps1 -Mode Apply -BackupDirectory '<same printed directory>' -ExpectedSourceSha '<same approved SHA>'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/local-test/maintain-word97-local.ps1 -Mode Verify -BackupDirectory '<same printed directory>' -ExpectedSourceSha '<same approved SHA>'
```

Only direct child directories below the canonical ignored `.runtime/contract-repair-test/word97-maintenance` area are accepted. Prefer a short directory name to avoid Windows path limits. An incomplete snapshot directory remains evidence and cannot be reused or treated as verified. The wrapper creates a protected ACL granting the current owner and SYSTEM full control, verifies ownership and every backup artifact's ACL, and rejects reparse points. Current protected values and business text are stored only in that restricted ignored directory. Console output contains aggregate counts, migration stages and sanitized gates, never usernames, credentials, JDBC URLs or row values. ACL operations require a Windows token with the necessary file security privileges; permission failure blocks before DB connection.

The historical reset receipt is identity authority only. No historical archive is used as a current backup; no reset Apply/Restore path is called. Snapshot requires both original CHECKs at exact prefix zero. It backs up **every row of every current classified table**, including authentication, role/permission fields, rules, login/business audit and populated business tables. Each archive is reopened using the typed codec and verified for count, SHA, column/PK identity and complete row fingerprint, including CLOB, BLOB, decimal scale and timestamp nanoseconds.

## Invariants and journal

Each table follows four steps: add the wider temporary guard; drop the original CHECK; add the expanded named CHECK; drop the temporary guard. The two tables advance in the public SQL's exact eight-step order. Root `PROJECT_FILE.PARSE_METHOD` stays nullable; immutable result `PROJECT_FILE_PARSE_RESULT.PARSE_METHOD` stays NOT NULL.

The snapshot binds the exact approved database identity, source hashes, writer proof, complete typed archives, plan and catalog. The catalog projection retains all column/default/nullability, foreign key, index and trigger definition facts; only the exact two target CHECKs and their exact temporary guards are excluded. Unknown tables, unsupported types, unknown nullability, unavailable catalog/session facts, identity/hash drift and unrelated schema changes fail closed.

Before and after every DDL, the tool checks every current row fingerprint, the unchanged catalog projection, exact migration prefix, fixed database identity, local writer proof and the only-current-helper bidirectional loopback DM8 socket gate. The approved business identity has no `V$SESSIONS` privilege: this tool reuses the existing conservative local socket ownership proof and rejects other/foreign/unknown clients. This proof is scoped to the approved local DM8 endpoint; it is not a general remote deployment session proof.

The tool appends and forces an `INTENT` journal entry **before** executing each statement. It appends `CONFIRMED` only after proving the exact next catalog prefix and unchanged data. Entries form an append-only hash chain bound to this snapshot. A helper file lock prevents concurrent invocation against one directory. A torn, missing, rewritten, mismatched or impossible journal is not treated as a fresh operation.

## Interrupted DDL and recovery

DM8 DDL may commit implicitly. A timeout, lost response, process interruption or failed postcondition leaves the outcome **UNKNOWN** and writers must remain stopped. Do not infer rollback from a Java exception. Do not run the SQL file wholesale, replay earlier statements or remove a temporary guard manually.

Reopen the **same** backup and journal with Apply or Verify. The tool rechecks all backup hashes, rows, catalog and identity. An unresolved intent can match only its recorded previous prefix or its exact next prefix. It records `RECONCILED` and exits with `WORD97_RECONCILED_REOPEN_APPLY_REQUIRED` (or the corresponding Verify gate), without executing DDL in that invocation. Inspect the gate, then reopen the same command once more to continue the proven forward sequence. Any other prefix or drift remains blocked for investigation.

The default recovery is forward reconciliation. If **any** `DOC_TEXT` value exists, reverting to the old CHECK is prohibited. Do not delete rows, rewrite parse provenance, erase files or reset the schema to make rollback possible. The tool offers no automatic full-database overwrite/restore operation. A separately authorized disaster recovery must use this maintenance window's complete verified backup, preserve storage identity, and account for every write after the snapshot; historical reset backups cannot substitute for it.

After successful Apply and Verify, run the existing read-only Word97 Verify and the normal launcher preflight before starting the selected build. Then verify real DOC root and ZIP ingestion, original object SHA, immutable `DOC_TEXT` parse identity and reopening from DM8. Those live results must be recorded separately with commands, environment and observed responses.

## Offline verification

```powershell
$classes=Join-Path (Get-Location) '.runtime\word97-offline-tests\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
javac.exe -encoding UTF-8 -d $classes tools/local-test/ResetReviewTestData.java tools/local-test/ContractTestStorageProbe.java tools/local-test/ContractLocalMigration.java tools/local-test/Word97LocalMigration.java tools/local-test/Word97Maintenance.java tools/local-test/Word97MaintenanceTest.java
java.exe -ea -cp $classes Word97MaintenanceTest (Get-Location).Path
```

The offline tests write only synthetic ignored fixtures and exercise the production orchestration, archive reopening, journal reconciliation and PowerShell writer gates. They do not connect to DM8, stop services or call OCR/AI.
