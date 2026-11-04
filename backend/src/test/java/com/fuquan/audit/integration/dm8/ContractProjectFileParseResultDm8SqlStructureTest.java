package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ContractProjectFileParseResultDm8SqlStructureTest {
    private static final Path SQL = Path.of("..", "db", "dm8", "CONTRACT__project_file_parse_result.sql");

    @Test
    void declaresControlledContractNamespaceDependenciesAndImmutableParseResultStructure() throws Exception {
        assertThat(Files.readAllLines(SQL).subList(0, 6)).containsExactly(
                "-- MIGRATION-NAMESPACE: CONTRACT",
                "-- MIGRATION-ID: project-file-parse-result",
                "-- DEPENDS-ON: V010__project_intake_core.sql,V017__project_file_parse_provenance.sql",
                "-- PURPOSE: immutable parse result history for ProjectFile",
                "-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED");
        String sql = Files.readString(SQL).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(sql).contains("CREATE TABLE PROJECT_FILE_PARSE_RESULT",
                "PARSE_RESULT_ID VARCHAR(36) NOT NULL", "PROJECT_ID VARCHAR(36) NOT NULL",
                "FILE_ID VARCHAR(36) NOT NULL", "FILE_SHA256 VARCHAR(64) NOT NULL",
                "PARSE_VERSION INTEGER NOT NULL", "PARSE_ORIGIN VARCHAR(32) NOT NULL",
                "PARSE_STATUS VARCHAR(32) NOT NULL", "PARSE_METHOD VARCHAR(32) NOT NULL",
                "ENGINE VARCHAR(100) NOT NULL", "ENGINE_VERSION VARCHAR(100)", "CANONICAL_TEXT CLOB",
                "CANONICAL_TEXT_SHA256 VARCHAR(64)", "PAGE_COUNT INTEGER", "DURATION_MS BIGINT",
                "CONFIDENCE DECIMAL(5,4)", "SUPERSEDES_PARSE_RESULT_ID VARCHAR(36)",
                "CREATED_AT TIMESTAMP", "COMPLETED_AT TIMESTAMP", "VERSION_NO BIGINT",
                "CONSTRAINT PK_PROJECT_FILE_PARSE_RESULT PRIMARY KEY (PARSE_RESULT_ID)",
                "CONSTRAINT UK_PROJECT_FILE_PARSE_RESULT_FILE_VERSION UNIQUE (FILE_ID, PARSE_VERSION)",
                "CONSTRAINT UK_PROJECT_FILE_PARSE_RESULT_BINDING UNIQUE (PARSE_RESULT_ID, PROJECT_ID, FILE_ID, FILE_SHA256, PARSE_VERSION)",
                "CONSTRAINT FK_PROJECT_FILE_PARSE_RESULT_PROJECT FOREIGN KEY (PROJECT_ID) REFERENCES PROJECT (ID)",
                "CONSTRAINT FK_PROJECT_FILE_PARSE_RESULT_FILE FOREIGN KEY (PROJECT_ID, FILE_ID) REFERENCES PROJECT_FILE (PROJECT_ID, ID)",
                "CONSTRAINT FK_PROJECT_FILE_PARSE_RESULT_SUPERSEDES FOREIGN KEY (SUPERSEDES_PARSE_RESULT_ID) REFERENCES PROJECT_FILE_PARSE_RESULT (PARSE_RESULT_ID)",
                "CK_PROJECT_FILE_PARSE_RESULT_ORIGIN", "CURRENT_PIPELINE", "LEGACY_IMPORTED",
                "CK_PROJECT_FILE_PARSE_RESULT_STATUS", "CK_PROJECT_FILE_PARSE_RESULT_METHOD",
                "CK_PROJECT_FILE_PARSE_RESULT_CONFIDENCE", "CK_PROJECT_FILE_PARSE_RESULT_PAGE_COUNT",
                "CK_PROJECT_FILE_PARSE_RESULT_DURATION", "IDX_PROJECT_FILE_PARSE_RESULT_FILE",
                "IDX_PROJECT_FILE_PARSE_RESULT_PROJECT");
    }

    @Test
    void mapperAndRepositoryAreInsertOnly() throws Exception {
        String mapper = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8", "ProjectFileParseResultMapper.xml"));
        String mapperInterface = Files.readString(Path.of("src", "main", "java", "com", "fuquan", "audit", "integration", "dm8", "ProjectFileParseResultMapper.java"));
        String repository = Files.readString(Path.of("src", "main", "java", "com", "fuquan", "audit", "integration", "dm8", "Dm8ProjectFileParseResultRepository.java"));
        assertThat(mapper.toUpperCase(Locale.ROOT)).doesNotContain("<UPDATE", "UPDATE ");
        assertThat(mapperInterface.toUpperCase(Locale.ROOT)).doesNotContain("UPDATE");
        assertThat(repository.toUpperCase(Locale.ROOT)).doesNotContain("UPDATE");
    }
}
