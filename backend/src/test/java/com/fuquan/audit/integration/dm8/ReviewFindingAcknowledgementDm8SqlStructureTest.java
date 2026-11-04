package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReviewFindingAcknowledgementDm8SqlStructureTest {
    private final Path migration = Path.of(
            "..", "db", "dm8", "V009__review_finding_acknowledgement.sql");
    private final Path mapper = Path.of(
            "src", "main", "resources", "mappers", "dm8", "FindingAcknowledgementMapper.xml");

    @Test
    void acknowledgementIsImmutableAndReferencesTheRealFindingAndUserFacts() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE REVIEW_FINDING_ACKNOWLEDGEMENT")
                .contains("ID VARCHAR(36) NOT NULL")
                .contains("CHECK_RESULT_ID VARCHAR(36) NOT NULL")
                .contains("ACKNOWLEDGED_BY VARCHAR(36) NOT NULL")
                .contains("ACKNOWLEDGED_AT TIMESTAMP NOT NULL")
                .contains("REQUEST_ID VARCHAR(128) NOT NULL")
                .contains("TRACE_ID VARCHAR(64) NOT NULL")
                .contains("RULE_VERSION BIGINT NOT NULL")
                .contains("CONSTRAINT PK_REVIEW_FINDING_ACKNOWLEDGEMENT PRIMARY KEY (ID)")
                .contains("CONSTRAINT UK_REVIEW_FINDING_ACK_CHECK_RESULT UNIQUE (CHECK_RESULT_ID)")
                .contains("CONSTRAINT FK_REVIEW_FINDING_ACK_RESULT FOREIGN KEY (CHECK_RESULT_ID)\n"
                        + "        REFERENCES AUDIT_CHECK_RESULT (ID)")
                .contains("CONSTRAINT FK_REVIEW_FINDING_ACK_USER FOREIGN KEY (ACKNOWLEDGED_BY)\n"
                        + "        REFERENCES SYS_USER (ID)")
                .contains("CONSTRAINT CK_REVIEW_FINDING_ACK_RULE_VERSION CHECK (RULE_VERSION >= 0)")
                .doesNotContain("ACKNOWLEDGED_BY UUID")
                .doesNotContain("ACTIVE_FLAG")
                .doesNotContain("UPDATED_AT")
                .doesNotContain("DELETED_AT")
                .doesNotContain("DELETED_BY")
                .doesNotContain("UPDATE REVIEW_FINDING_ACKNOWLEDGEMENT")
                .doesNotContain("DELETE FROM REVIEW_FINDING_ACKNOWLEDGEMENT");
    }

    @Test
    void acknowledgementMapperIsInsertOnlyAndTaskReadUsesTheRealResultTaskRelation() throws Exception {
        String sql = Files.readString(mapper, StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql)
                .contains("INSERT INTO REVIEW_FINDING_ACKNOWLEDGEMENT")
                .contains("ID, CHECK_RESULT_ID, ACKNOWLEDGED_BY, ACKNOWLEDGED_AT")
                .contains("REQUEST_ID, TRACE_ID, RULE_VERSION")
                .contains("FROM REVIEW_FINDING_ACKNOWLEDGEMENT A")
                .contains("JOIN AUDIT_CHECK_RESULT R ON R.ID = A.CHECK_RESULT_ID")
                .contains("WHERE R.TASK_ID = #{TASKID}")
                .contains("ORDER BY A.ACKNOWLEDGED_AT, A.ID")
                .doesNotContain("<UPDATE")
                .doesNotContain("<DELETE")
                .doesNotContain("MERGE")
                .doesNotContain("MERGE INTO REVIEW_FINDING_ACKNOWLEDGEMENT")
                .doesNotContain("UPDATE REVIEW_FINDING_ACKNOWLEDGEMENT")
                .doesNotContain("DELETE FROM REVIEW_FINDING_ACKNOWLEDGEMENT")
                .doesNotContain("SYS_USER")
                .doesNotContain("USERNAME")
                .doesNotContain("REAL_NAME");
    }
}
