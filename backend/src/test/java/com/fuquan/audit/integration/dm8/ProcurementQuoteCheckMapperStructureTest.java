package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProcurementQuoteCheckMapperStructureTest {
    private final Path mapper = Path.of("src", "main", "resources", "mappers", "dm8",
            "ProcurementQuoteCheckMapper.xml");

    @Test
    void supplierQuoteTimelineUsesExistingProcurementDemandColumns() throws Exception {
        String xml = Files.readString(mapper, StandardCharsets.UTF_8).toUpperCase();

        assertThat(xml).contains("D.BUDGET_APPROVAL_ID AS PROJECT_CODE", "D.PROCUREMENT_NAME AS PROJECT_NAME");
        assertThat(xml).doesNotContain("D.PROJECT_CODE", "D.PROJECT_NAME");
    }
}
