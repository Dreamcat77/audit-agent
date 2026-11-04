package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractReviewSessionDm8SqlStructureTest {
    private final Path migration = Path.of("..", "db", "dm8", "V004__contract_review_session.sql");

    @Test
    void v004CreatesThreeTablesWithStatusesVersionsAndDm8Comments() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE CONTRACT_REVIEW_SESSION")
                .contains("CREATE TABLE CONTRACT_REVIEW_DOCUMENT")
                .contains("CREATE TABLE CONTRACT_REVIEW_MATERIAL_BINDING")
                .contains("STATUS IN ('PREPARING_MATERIALS', 'READY')")
                .contains("STATUS IN ('UPLOAD_PENDING', 'AVAILABLE', 'UPLOAD_FAILED')")
                .contains("COMMENT ON COLUMN CONTRACT_REVIEW_SESSION.CONTRACT_VERSION")
                .contains("不直接等同于未来 AuditTask 的最终 SOURCE_VERSION")
                .contains("VERSION_NO BIGINT NOT NULL")
                .doesNotContain("AUTO_INCREMENT")
                .doesNotContain("BYTEA")
                .doesNotContain("JSONB");
    }

    @Test
    void replacementAndBindingUseTrueCompositeForeignKeysAndSlotUnique() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CONSTRAINT UK_CR_DOC_SCOPE UNIQUE (SESSION_ID, SLOT_KEY, DOCUMENT_KIND, ID)")
                .contains("SESSION_ID, SLOT_KEY, DOCUMENT_KIND, REPLACES_DOCUMENT_ID")
                .contains("REFERENCES CONTRACT_REVIEW_DOCUMENT (SESSION_ID, SLOT_KEY, DOCUMENT_KIND, ID)")
                .contains("SESSION_ID, SLOT_KEY, DOCUMENT_KIND, BOUND_DOCUMENT_ID")
                .contains("CONSTRAINT UK_CR_BINDING_SLOT UNIQUE (SESSION_ID, SLOT_KEY)")
                .contains("IDX_IDEMPOTENCY_RESULT_RESOURCE");
    }

    @Test
    void v004DoesNotAlterAuditTaskAndMappersUseExplicitCas() throws Exception {
        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        String sessionXml = resource("/mappers/dm8/ContractReviewSessionMapper.xml");
        String documentXml = resource("/mappers/dm8/ReviewDocumentMapper.xml");
        String bindingXml = resource("/mappers/dm8/MaterialBindingMapper.xml");

        assertThat(sql).doesNotContain("ALTER TABLE AUDIT_TASK");
        assertThat(sessionXml).contains("STATUS = 'PREPARING_MATERIALS'")
                .contains("VERSION_NO = #{expectedVersion}");
        assertThat(documentXml).contains("STATUS = 'UPLOAD_PENDING'")
                .contains("VERSION_NO = #{expectedVersion}");
        assertThat(bindingXml).contains("BOUND_DOCUMENT_ID = #{expectedDocumentId}")
                .contains("ACTIVE_FLAG = #{expectedActiveFlag}")
                .contains("VERSION_NO = #{expectedVersion}");
    }

    @Test
    void v005OnlyAddsPairedSoftDeleteMarkerForeignKeyAndActiveIndex() throws Exception {
        String sql = Files.readString(
                Path.of("..", "db", "dm8", "V005__contract_review_session_soft_delete.sql"),
                StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE CONTRACT_REVIEW_SESSION ADD DELETED_AT TIMESTAMP NULL")
                .contains("ALTER TABLE CONTRACT_REVIEW_SESSION ADD DELETED_BY VARCHAR(36) NULL")
                .contains("FOREIGN KEY (DELETED_BY) REFERENCES SYS_USER (ID)")
                .contains("DELETED_AT IS NULL AND DELETED_BY IS NULL")
                .contains("DELETED_AT IS NOT NULL AND DELETED_BY IS NOT NULL")
                .contains("IDX_CR_SESSION_ACTIVE_READY")
                .doesNotContain("DROP TABLE")
                .doesNotContain("DELETE FROM")
                .doesNotContain("AUDIT_TASK");
    }

    @Test
    void softDeleteCasAndGlobalTaskProjectionAreDatabaseBoundAndStable() throws Exception {
        String sessionXml = resource("/mappers/dm8/ContractReviewSessionMapper.xml");
        String contractXml = resource("/mappers/dm8/ContractMapper.xml");

        assertThat(sessionXml)
                .contains("SET DELETED_AT = #{deletedAt}, DELETED_BY = #{deletedBy}")
                .contains("VERSION_NO = VERSION_NO + 1")
                .contains("WHERE ID = #{id} AND VERSION_NO = #{expectedVersion} AND DELETED_AT IS NULL")
                .contains("JOIN CONTRACT c ON c.ID = s.CONTRACT_ID")
                .contains("LEFT JOIN SYS_USER u ON u.ID = s.CREATED_BY")
                .contains("u.REAL_NAME AS CREATED_BY_NAME")
                .contains("s.DELETED_AT IS NULL")
                .contains("ROW_NUMBER() OVER (ORDER BY s.CREATED_AT DESC, s.ID DESC)")
                .doesNotContain("DELETE FROM " + "CONTRACT_REVIEW_SESSION")
                .doesNotContain("LIMIT ");
        assertThat(contractXml)
                .contains("ROW_NUMBER() OVER (ORDER BY UPDATED_AT DESC, ID DESC)")
                .contains("INSTR(UPPER(CONTRACT_NO), UPPER(#{keyword}))")
                .doesNotContain("${keyword}")
                .doesNotContain("LIMIT ");
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream(name)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
