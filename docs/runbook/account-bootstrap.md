# Account bootstrap runbook

## Scope and safety

There is no registration API and `V002__local_account_auth_observability.sql` does not create an administrator. Bootstrap is an offline operator action after migrations are applied.

Never put a raw password in Git, shell history, command-line arguments, logs, screenshots, evidence, chat, or SQL. Do not use a real password in examples. The runtime account must not be `SYSDBA` or `SYSAUDITOR`.

## 1. Build the offline hash tool

From `backend`:

```powershell
.\mvnw.cmd -q -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target\password-tool-classpath.txt"
$auditRuntimeClasspath = Get-Content -LiteralPath 'target\password-tool-classpath.txt' -Raw
java -cp "target\classes;$auditRuntimeClasspath" com.fuquan.audit.security.tools.PasswordHashTool
```

The direct Java process reads the password twice through `Console.readPassword`, so input is not echoed. It prints one delegating bcrypt hash beginning with `{bcrypt}`. The tool deliberately rejects password command-line arguments.

If the operator uses the explicit `--stdin` mode for controlled automation, the pipeline must prevent terminal echo, history, process-list exposure, retained temporary files, and CI output. Do not record the raw input or resulting real hash in delivery evidence.

## 2. Bootstrap the DM8 account

Normalize the chosen username first: trim it, convert it to lowercase with locale-independent semantics, and confirm it matches `[a-z0-9._-]{1,128}`. Generate a UUID outside the SQL text. Replace every angle-bracket placeholder below in the controlled database session; do not save the completed statement in the repository.

```sql
INSERT INTO SYS_USER (
    ID, USERNAME, PASSWORD_HASH, REAL_NAME, STATUS,
    FAILED_LOGIN_COUNT, LOCKED_UNTIL, LAST_LOGIN_AT,
    PASSWORD_CHANGED_AT, CREATED_AT, UPDATED_AT, VERSION_NO
) VALUES (
    '<generated-user-uuid>', '<normalized-username>', '<delegating-bcrypt-hash>',
    '<real-name>', 'ACTIVE', 0, NULL, NULL,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
);

INSERT INTO SYS_USER_ROLE (USER_ID, ROLE_ID)
SELECT '<generated-user-uuid>', ID
FROM SYS_ROLE
WHERE ROLE_CODE = 'SYSTEM_ADMIN' AND STATUS = 'ACTIVE';
```

Execute both statements in one controlled DM8 transaction. Verify one `SYS_USER` row and one `SYS_USER_ROLE` row were created without selecting or printing `PASSWORD_HASH`. Commit only after the row counts and role mapping are correct.

## 3. Optional local account

The `local` profile starts with no login account unless both variables below are supplied outside Git:

```powershell
$env:AUDIT_LOCAL_ACCOUNT_USERNAME = '<normalized-username>'
$env:AUDIT_LOCAL_ACCOUNT_PASSWORD_HASH = '<delegating-bcrypt-hash>'
$env:AUDIT_LOCAL_ACCOUNT_REAL_NAME = '<display-name>'
$env:AUDIT_LOCAL_ACCOUNT_ROLES = 'SYSTEM_ADMIN'
```

Do not place these values in `.env`, `application-local.yml`, evidence, or source. `local` stores this account only in process memory.

For a local procurement approval walkthrough, provide four additional hashes outside Git before startup:

```powershell
$env:AUDIT_LOCAL_APPROVAL_REVIEWER_PASSWORD_HASH = '<delegating-bcrypt-hash>'
$env:AUDIT_LOCAL_APPROVAL_FINANCE_PASSWORD_HASH = '<delegating-bcrypt-hash>'
$env:AUDIT_LOCAL_APPROVAL_LEADER_PASSWORD_HASH = '<delegating-bcrypt-hash>'
$env:AUDIT_LOCAL_APPROVAL_COMMITTEE_PASSWORD_HASH = '<delegating-bcrypt-hash>'
```

The local profile then seeds `procurement-reviewer`, `finance-reviewer`, `procurement-leader`, and
`procurement-committee` with their matching approval roles. The four raw passwords are chosen and retained only
by the operator; they are never part of the repository or command line. For `local-dev` with DM8, use the
placeholder template `db/dm8/setup_local_dev_approval_users.sql` in a controlled database session instead.

## Verification status

- Offline hash behavior: **LOCAL TESTED**
- Local environment-seeded account: **LOCAL TESTED**
- DM8 bootstrap SQL: **NOT VERIFIED ON REAL DM8**
- dev29/customer account bootstrap: **NOT VERIFIED**
