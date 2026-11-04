package com.fuquan.audit.integration.dm8;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIntakeDm8SqlStructureTest {

    @Test
    void migrationEnforcesDurableIdentityAndSameProjectOwnership() throws Exception {
        Path migration = Path.of("..", "db", "dm8", "V010__project_intake_core.sql");
        assertThat(migration).exists();
        String sql = Files.readString(migration).toUpperCase();
        assertThat(sql).contains("CREATE TABLE PROJECT (")
                .contains("CREATE TABLE PROJECT_FILE (")
                .contains("CREATE TABLE PROJECT_EXTRACTION_RESULT (")
                .contains("CREATE TABLE CONTRACT_CANDIDATE (")
                .contains("CREATE TABLE CONTRACT_CANDIDATE_LINE_ITEM (")
                .contains("STORAGE_KEY VARCHAR(64) NOT NULL")
                .contains("UNIQUE (STORAGE_KEY)")
                .contains("UNIQUE (PROJECT_ID, ID)")
                .contains("FOREIGN KEY (PROJECT_ID, PARENT_ARCHIVE_FILE_ID)")
                .contains("FOREIGN KEY (PROJECT_ID, SOURCE_CONTRACT_FILE_ID)")
                .contains("FOREIGN KEY (PROJECT_ID, SOURCE_FILE_ID)")
                .contains("FOREIGN KEY (PROJECT_ID, CANDIDATE_ID)")
                .contains("UNIQUE (SOURCE_CONTRACT_FILE_ID)")
                .contains("UNIQUE (EXTRACTION_KEY)")
                .contains("VERSION_NO BIGINT DEFAULT 0 NOT NULL")
                .contains("PROMOTED_CONTRACT_ID VARCHAR(36)")
                .contains("PROMOTED_BY VARCHAR(36)")
                .contains("PROMOTED_AT TIMESTAMP")
                .contains("FOREIGN KEY (PROMOTED_CONTRACT_ID) REFERENCES CONTRACT (ID)")
                .contains("CHECK (VERSION_NO >= 0)")
                .contains("PROMOTION_STATE = 'DRAFT'")
                .contains("PROMOTED_CONTRACT_ID IS NULL")
                .contains("PROMOTED_BY IS NULL")
                .contains("PROMOTED_AT IS NULL")
                .contains("PROMOTION_STATE = 'PROMOTED'")
                .contains("PROMOTED_CONTRACT_ID IS NOT NULL")
                .contains("PROMOTED_BY IS NOT NULL")
                .contains("PROMOTED_AT IS NOT NULL")
                .contains("VALUE_TEXT VARCHAR(2000)")
                .contains("VALUE_AMOUNT DECIMAL(18,2)")
                .contains("SOURCE_TEXT VARCHAR(240) NOT NULL")
                .contains("PAGE_NUMBER INTEGER")
                .contains("PARAGRAPH_INDEX INTEGER")
                .contains("ITEM_KEY VARCHAR(200)")
                .contains("DECIMAL(18,2)")
                .doesNotContain("AMOUNT_VALUE")
                .doesNotContain("CONFIRMED_BY")
                .doesNotContain("PROJECT_CONTRACT")
                .doesNotContain(" BLOB");
    }

    @Test
    void projectMapperXmlUsesRecordConstructorAutoMapping() throws Exception {
        for (String mapper : java.util.List.of("ProjectMapper.xml", "ProjectFileMapper.xml",
                "ProjectExtractionResultMapper.xml", "ContractCandidateMapper.xml")) {
            String xml = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8", mapper));
            assertThat(xml).contains("resultType=\"com.fuquan.audit.integration.dm8")
                    .doesNotContain("<resultMap")
                    .doesNotContain("resultMap=");
        }
        String candidateMapper = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractCandidateMapper.xml")).toUpperCase();
        assertThat(candidateMapper).contains("VERSION_NO", "PROMOTED_CONTRACT_ID", "PROMOTED_BY", "PROMOTED_AT");
        assertThat(java.util.Arrays.stream(ContractCandidatePersistenceRow.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .contains("versionNo", "promotedContractId", "promotedBy", "promotedAt");
    }

    @Test
    void projectMapperSeparatesConfirmedProjectsFromPackageBackedIntakeDrafts() throws Exception {
        String mapper = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ProjectMapper.xml")).toUpperCase();

        assertThat(mapper).contains("VERSION_NO &GT; 0")
                .contains("VERSION_NO = 0")
                .contains("EXISTS")
                .contains("FROM PROJECT_FILE")
                .contains("CATEGORY = 'PACKAGE'");
    }

    @Test
    void projectMapperDiscoversOrdinaryPreparationDraftsWithMatchingPageAndCountPredicates() throws Exception {
        String mapper = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ProjectMapper.xml")).toUpperCase();

        assertThat(mapper).contains("FINDPREPARATIONDRAFTPAGE")
                .contains("COUNTPREPARATIONDRAFTS")
                .contains("P.VERSION_NO = 0")
                .contains("EXISTS (SELECT 1 FROM PROJECT_FILE PF WHERE PF.PROJECT_ID = P.ID)")
                .contains("PF.CATEGORY = 'PACKAGE'")
                .contains("ORDER BY P.CREATED_AT DESC, P.ID DESC");
        assertThat(mapper).containsPattern("NOT EXISTS \\(\\s*SELECT 1 FROM PROJECT_FILE PF\\s*"
                + "WHERE PF\\.PROJECT_ID = P\\.ID AND PF\\.CATEGORY = 'PACKAGE'");
    }

    @Test
    void v017AddsNullableParseProvenanceWithoutRecreatingProjectFile() throws Exception {
        Path migration = Path.of("..", "db", "dm8", "V017__project_file_parse_provenance.sql");
        assertThat(migration).exists();
        String sql = Files.readString(migration).toUpperCase();

        assertThat(sql).contains("ALTER TABLE PROJECT_FILE ADD PARSE_METHOD VARCHAR(32)")
                .contains("ALTER TABLE PROJECT_FILE ADD PARSE_ENGINE VARCHAR(100)")
                .contains("ALTER TABLE PROJECT_FILE ADD PARSE_CONFIDENCE DECIMAL(5,4)")
                .contains("ALTER TABLE PROJECT_FILE ADD PAGE_COUNT INTEGER")
                .contains("ALTER TABLE PROJECT_FILE ADD PARSE_DURATION_MS BIGINT")
                .contains("'PDF_TEXT'", "'DOCX_TEXT'", "'OCR'")
                .contains("PARSE_CONFIDENCE >= 0", "PARSE_CONFIDENCE <= 1")
                .contains("PAGE_COUNT >= 0", "PARSE_DURATION_MS >= 0")
                .doesNotContain("CREATE TABLE PROJECT_FILE")
                .doesNotContain("DROP TABLE")
                .doesNotContain("DELETE FROM")
                .doesNotContain("UPDATE PROJECT_FILE");

        String v010 = Files.readString(Path.of("..", "db", "dm8", "V010__project_intake_core.sql"))
                .toUpperCase();
        assertThat(v010).doesNotContain("PARSE_METHOD", "PARSE_ENGINE", "PARSE_CONFIDENCE",
                "PAGE_COUNT", "PARSE_DURATION_MS");
    }

    @Test
    void projectFileMapperAndPersistenceRowUseTheSameProvenanceColumns() throws Exception {
        String mapper = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ProjectFileMapper.xml")).toUpperCase();
        assertThat(mapper).contains("PARSE_METHOD", "PARSE_ENGINE", "PARSE_CONFIDENCE",
                "PAGE_COUNT", "PARSE_DURATION_MS");
        assertThat(java.util.Arrays.stream(ProjectFilePersistenceRow.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .contains("parseMethod", "parseEngine", "parseConfidence", "pageCount", "parseDurationMs");
    }

    @Test
    void runbookProjectFileEvidenceQueryUsesCanonicalMigrationColumns() throws Exception {
        String v010 = Files.readString(Path.of("..", "db", "dm8", "V010__project_intake_core.sql"))
                .toUpperCase();
        int tableStart = v010.indexOf("CREATE TABLE PROJECT_FILE (");
        int tableEnd = v010.indexOf("\n);", tableStart);
        assertThat(tableStart).isGreaterThanOrEqualTo(0);
        assertThat(tableEnd).isGreaterThan(tableStart);

        Set<String> canonicalColumns = new HashSet<>();
        Matcher baseColumn = Pattern.compile(
                "(?m)^\\s+([A-Z][A-Z0-9_]*)\\s+(?:VARCHAR|BIGINT|CLOB|TIMESTAMP|DECIMAL|INTEGER)\\b")
                .matcher(v010.substring(tableStart, tableEnd));
        while (baseColumn.find()) {
            if (!"CONSTRAINT".equals(baseColumn.group(1))) canonicalColumns.add(baseColumn.group(1));
        }

        String v017 = Files.readString(Path.of("..", "db", "dm8",
                "V017__project_file_parse_provenance.sql")).toUpperCase();
        Matcher additiveColumn = Pattern.compile("ALTER TABLE PROJECT_FILE ADD ([A-Z][A-Z0-9_]*)\\s")
                .matcher(v017);
        while (additiveColumn.find()) canonicalColumns.add(additiveColumn.group(1));

        String runbook = Files.readString(Path.of("..", "docs", "runbook",
                "ai-ocr-project-intake-dev29.md")).toUpperCase();
        String evidenceWhere = "FROM PROJECT_FILE\nWHERE PROJECT_ID = '<RECORDED-PROJECT-UUID>'";
        int fromIndex = runbook.indexOf(evidenceWhere);
        int selectIndex = runbook.lastIndexOf("SELECT ", fromIndex);
        assertThat(fromIndex).isGreaterThanOrEqualTo(0);
        assertThat(selectIndex).isGreaterThanOrEqualTo(0);
        Set<String> selectedColumns = new HashSet<>(Arrays.stream(
                        runbook.substring(selectIndex + "SELECT ".length(), fromIndex).split(","))
                .map(value -> value.replaceAll("\\s+", "").trim())
                .filter(value -> !value.isEmpty())
                .toList());

        assertThat(canonicalColumns).containsAll(selectedColumns);
    }
}
