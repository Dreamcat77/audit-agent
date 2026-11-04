-- V009: durable, append-only acknowledgement facts for non-GREEN review findings.
-- This migration is additive. The acknowledgement is an immutable business fact and
-- does not provide an active flag, tombstone, update, revoke, or delete lifecycle.

CREATE TABLE REVIEW_FINDING_ACKNOWLEDGEMENT (
    ID VARCHAR(36) NOT NULL,
    CHECK_RESULT_ID VARCHAR(36) NOT NULL,
    ACKNOWLEDGED_BY VARCHAR(36) NOT NULL,
    ACKNOWLEDGED_AT TIMESTAMP NOT NULL,
    REQUEST_ID VARCHAR(128) NOT NULL,
    TRACE_ID VARCHAR(64) NOT NULL,
    RULE_VERSION BIGINT NOT NULL,
    CONSTRAINT PK_REVIEW_FINDING_ACKNOWLEDGEMENT PRIMARY KEY (ID),
    CONSTRAINT UK_REVIEW_FINDING_ACK_CHECK_RESULT UNIQUE (CHECK_RESULT_ID),
    CONSTRAINT FK_REVIEW_FINDING_ACK_RESULT FOREIGN KEY (CHECK_RESULT_ID)
        REFERENCES AUDIT_CHECK_RESULT (ID),
    CONSTRAINT FK_REVIEW_FINDING_ACK_USER FOREIGN KEY (ACKNOWLEDGED_BY)
        REFERENCES SYS_USER (ID),
    CONSTRAINT CK_REVIEW_FINDING_ACK_RULE_VERSION CHECK (RULE_VERSION >= 0)
);

COMMENT ON TABLE REVIEW_FINDING_ACKNOWLEDGEMENT IS
    'Append-only durable acknowledgement business facts, unique for each audit check result.';
COMMENT ON COLUMN REVIEW_FINDING_ACKNOWLEDGEMENT.ID IS
    'Server-generated acknowledgement UUID stored as text.';
COMMENT ON COLUMN REVIEW_FINDING_ACKNOWLEDGEMENT.CHECK_RESULT_ID IS
    'The uniquely acknowledged AUDIT_CHECK_RESULT fact.';
COMMENT ON COLUMN REVIEW_FINDING_ACKNOWLEDGEMENT.ACKNOWLEDGED_BY IS
    'Current SYS_USER identity that acknowledged the finding.';
COMMENT ON COLUMN REVIEW_FINDING_ACKNOWLEDGEMENT.ACKNOWLEDGED_AT IS
    'Server-side acknowledgement timestamp.';
COMMENT ON COLUMN REVIEW_FINDING_ACKNOWLEDGEMENT.REQUEST_ID IS
    'HTTP request identifier captured with the immutable acknowledgement fact.';
COMMENT ON COLUMN REVIEW_FINDING_ACKNOWLEDGEMENT.TRACE_ID IS
    'Trace identifier captured with the immutable acknowledgement fact.';
COMMENT ON COLUMN REVIEW_FINDING_ACKNOWLEDGEMENT.RULE_VERSION IS
    'Non-negative immutable rule-version snapshot copied from the associated result.';
