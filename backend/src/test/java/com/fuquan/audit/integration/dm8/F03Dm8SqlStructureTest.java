package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class F03Dm8SqlStructureTest {

    @Test
    void alertDecisionUsesCompareAndSetUpdate() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/mappers/dm8/BlacklistAlertMapper.xml")) {
            assertThat(stream).isNotNull();
            String mapper = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(mapper)
                    .contains("id=\"updateIfStatus\"")
                    .contains("AND ALERT_STATUS = #{expectedStatus}");
        }
    }

    @Test
    void alertCreationUsesAtomicInsertIfAbsent() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/mappers/dm8/BlacklistAlertMapper.xml")) {
            assertThat(stream).isNotNull();
            String mapper = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(mapper)
                    .contains("id=\"insertIfAbsent\"")
                    .contains("MERGE INTO BLACKLIST_ALERT")
                    .contains("target.BATCH_ID = source.BATCH_ID")
                    .contains("target.SUPPLIER_NAME = source.SUPPLIER_NAME")
                    .contains("WHEN NOT MATCHED THEN")
                    .contains("id=\"findByBatchAndSupplier\"");
        }
    }

    @Test
    void manualInitializationKeepsAlertIdempotencyConstraint() throws Exception {
        try (var stream = getClass().getResourceAsStream("/sql/f03_blacklist_ddl.sql")) {
            assertThat(stream).isNotNull();
            String ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(ddl)
                    .contains("UK_BLACKLIST_ALERT_BATCH_SUPPLIER")
                    .contains("ON BLACKLIST_ALERT (BATCH_ID, SUPPLIER_NAME)");
        }
    }
}
