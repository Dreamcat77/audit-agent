package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8ProcurementCollusionRepositoryTest {

    @Mock
    private ProcurementCollusionMapper mapper;

    @Test
    void preservesTheExactProcurementDemandIdWhenMergingSplitPurchaseAssessment() {
        UUID projectId = UUID.fromString("10cf554e-0f6f-457c-8420-31eca1ab0301");
        ProcurementSplitPurchaseAssessment assessment = new ProcurementSplitPurchaseAssessment(UUID.randomUUID(),
                projectId, "INFO", 0, List.of(), Instant.parse("2026-09-27T12:00:00Z"));
        Dm8ProcurementCollusionRepository repository = new Dm8ProcurementCollusionRepository(mapper,
                new ObjectMapper());

        repository.saveSplitPurchaseAssessment(assessment);

        ArgumentCaptor<ProcurementSplitPurchaseAssessmentPersistenceRow> row =
                ArgumentCaptor.forClass(ProcurementSplitPurchaseAssessmentPersistenceRow.class);
        verify(mapper).mergeSplitAssessment(row.capture());
        assertThat(row.getValue().getProjectId()).isEqualTo("10cf554e-0f6f-457c-8420-31eca1ab0301");
    }

    @Test
    void mapperUsesSingleStatementMergesForConcurrentLatestResultWrites() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/mappers/dm8/ProcurementCollusionMapper.xml")) {
            assertThat(stream).isNotNull();
            String mapperXml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(mapperXml)
                    .contains("id=\"mergeAssessment\"")
                    .contains("MERGE INTO PROCUREMENT_COLLUSION_ASSESSMENT")
                    .contains("id=\"mergeSplitAssessment\"")
                    .contains("MERGE INTO PROCUREMENT_SPLIT_PURCHASE_ASSESSMENT")
                    .contains("FROM DUAL) source", "ON (target.PROJECT_ID = source.PROJECT_ID)",
                            "WHEN MATCHED THEN UPDATE SET", "WHEN NOT MATCHED THEN INSERT")
                    .doesNotContain("KEY (PROJECT_ID)")
                    .doesNotContain("id=\"updateAssessment\"")
                    .doesNotContain("id=\"insertAssessment\"");
        }
    }

    @Test
    void treatsAnInvalidLegacyFindingsPayloadAsMissingSoItCanBeBackfilled() {
        UUID projectId = UUID.fromString("10cf554e-0f6f-457c-8420-31eca1ab0301");
        ProcurementSplitPurchaseAssessmentPersistenceRow row = new ProcurementSplitPurchaseAssessmentPersistenceRow();
        row.setId("test-id-2");
        row.setProjectId(projectId.toString());
        row.setRiskLevel("INFO");
        row.setRiskScore(0);
        row.setFindingsJson("{}");
        row.setAssessedTime(LocalDateTime.of(2026, 9, 27, 12, 0));
        when(mapper.findSplitAssessment(projectId.toString())).thenReturn(row);
        Dm8ProcurementCollusionRepository repository = new Dm8ProcurementCollusionRepository(mapper,
                new ObjectMapper());

        assertThat(repository.findLatestSplitPurchaseAssessment(projectId)).isEmpty();
    }
}
