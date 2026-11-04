-- V029: allow a returned procurement demand to create a new approval instance.
-- Historical instances, nodes, and operation logs remain unchanged.

ALTER TABLE PROCUREMENT_APPROVAL_INSTANCE
    DROP CONSTRAINT UK_PROC_APPROVAL_INSTANCE_PROJECT;

CREATE INDEX IDX_PROC_APPROVAL_INSTANCE_PROJECT
    ON PROCUREMENT_APPROVAL_INSTANCE (PROJECT_ID, STARTED_TIME);

COMMIT;
