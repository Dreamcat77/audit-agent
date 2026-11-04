# DM8 JDBC driver provisioning runbook

## Current status

**DM JDBC DRIVER: PENDING REAL DM8 MEDIA VERIFICATION**

Known connection contract:

- driver class: `dm.jdbc.driver.DmDriver`
- URL shape: `jdbc:dm://host:5236`

The real JAR filename, vendor version, checksum, license terms, and approved Maven coordinates are not known in this Round. No third-party repackaging is accepted as evidence.

## Required account boundary

Use a dedicated least-privilege application account for backend runtime and validation. The business application must not connect as `SYSDBA` or `SYSAUDITOR`. This runbook does not create, name, or assign a password to a real account.

## Preferred provisioning path

1. Obtain the JDBC JAR from the actual customer/DM8 installation media after access is restored.
2. Record its filename, product version, checksum, origin, and license approval.
3. Verify the driver contains `dm.jdbc.driver.DmDriver` and matches the target DM8 server support matrix.
4. Publish the verified JAR to the company internal Maven repository under coordinates approved by architecture/security review.
5. Add the normal Maven dependency and verify resolution in the controlled build environment.

## Controlled fallback

After explicit human approval, install the same verified JAR into a controlled local Maven repository using agreed internal coordinates. Record the command, checksum, operator, and machine scope as evidence. This is not a portable CI or release solution and must not silently replace internal-repository publication.

## Prohibited paths

- Do not guess Maven coordinates or download an unverified third-party packaged driver.
- Do not use Maven `systemPath`.
- Do not copy a vendor JAR into `src/lib` or another repository directory and commit it to Git.
- Do not put database usernames or passwords in source, POM files, or committed YAML.

## Verification after provisioning

1. Resolve the approved driver and capture its version/checksum.
2. Configure `AUDIT_DB_URL`, `AUDIT_DB_USERNAME`, and `AUDIT_DB_PASSWORD` outside Git.
3. Confirm the account is a dedicated application account.
4. Execute `db/dm8/V001__audit_task_baseline.sql` and then `db/dm8/V002__local_account_auth_observability.sql` in a controlled schema.
5. Validate direct task INSERT/SELECT/UPDATE and the V002 constraints and indexes.
6. Run Java smoke tests through `Dm8AuditTaskRepository` and `Dm8UserAccountRepository`.
7. Validate atomic concurrent failed-login increments, lock transition, successful reset, and role loading.
8. Validate `AUTH_LOGIN_AUDIT` and `AUDIT_OPERATION_LOG` inserts and confirm the application account has no normal update/delete path for audit facts.
9. Validate account state plus login audit commit and rollback behavior in one transaction.
10. Record results and update the compatibility matrix; until then all DM8 runtime claims remain **NOT VERIFIED**.
