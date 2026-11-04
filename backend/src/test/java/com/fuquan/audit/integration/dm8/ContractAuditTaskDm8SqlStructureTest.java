package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractAuditTaskDm8SqlStructureTest {

    @Test
    void latestContractF02QueryUsesOneStableRowPerContractAndStablePageOrder() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/resources/mappers/dm8/AuditTaskMapper.xml"), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(mapper).contains("id=\"findLatestPage\"")
                .contains("PARTITION BY SOURCE_ID ORDER BY CREATED_AT DESC, ID DESC")
                .contains("SOURCE_TYPE = #{sourceType}")
                .contains("REVIEW_TYPE = #{reviewType}")
                .contains("ORDER BY CREATED_AT DESC, ID DESC")
                .doesNotContainIgnoringCase("SELECT *");
    }
}
