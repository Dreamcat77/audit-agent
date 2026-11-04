package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementProjectMapperStructureTest {

    @Test
    void mapperReadsProcurementDemandWithFilteringAndPagination() throws Exception {
        String xml = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ProcurementProjectMapper.xml"));

        assertThat(xml).contains("FROM PROCUREMENT_DEMAND", "DELETED = 0", "ROW_NUMBER() OVER",
                "BUDGET_APPROVAL_ID AS PROJECT_CODE", "PROCUREMENT_NAME AS PROJECT_NAME",
                "SOURCE_PROJECT_ID", "property=\"sourceMaterialIds\" column=\"SOURCE_MATERIAL_IDS\"",
                "<select id=\"count\"");
    }

    @Test
    void sourceProjectMapperRequiresConfirmedDurableParsedProject() throws Exception {
        String xml = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ProcurementSourceProjectMapper.xml"));
        String migration = Files.readString(Path.of("..", "db", "dm8",
                "V045__procurement_source_project.sql"));

        assertThat(xml).contains("<resultMap id=\"sourceProjectRow\"", "property=\"projectNo\" column=\"PROJECT_NO\"",
                "property=\"updatedAt\" column=\"UPDATED_AT\"", "resultMap=\"sourceProjectRow\"",
                "P.VERSION_NO &gt; 0", "TRIM(P.NAME) &lt;&gt; ''",
                "TRIM(P.PROJECT_NO) &lt;&gt; ''", "PROJECT_FILE_PARSE_RESULT",
                "PR.PARSE_STATUS = 'PARSED'", "findEligibleById");
        assertThat(migration).contains("SOURCE_PROJECT_ID VARCHAR(36)",
                "FOREIGN KEY (SOURCE_PROJECT_ID) REFERENCES PROJECT (ID)",
                "IDX_PROC_DEMAND_SOURCE_PROJECT");
    }
}
