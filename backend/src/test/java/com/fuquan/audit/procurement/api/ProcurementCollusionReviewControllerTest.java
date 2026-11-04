package com.fuquan.audit.procurement.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.procurement.application.ProcurementCollusionReviewService;
import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementCollusionDocument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementCollusionReviewControllerTest {

    @Test
    void returnsFileNamesForSimilarityPairsWhenBidderNamesAreDuplicated() {
        UUID projectId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ProcurementCollusionAssessment assessment = new ProcurementCollusionAssessment(UUID.randomUUID(), projectId,
                40, "WARNING", new BigDecimal("0.01"), new BigDecimal("0.977"), 0, 0,
                List.of(new ProcurementCollusionAssessment.PairSimilarity(firstId, secondId, "张三", "张三",
                        new BigDecimal("0.977"))), Instant.now());
        ProcurementCollusionReviewService.ReviewData data = new ProcurementCollusionReviewService.ReviewData(
                assessment, List.of(document(firstId, projectId, "投标文件_北京供应商.docx"),
                        document(secondId, projectId, "投标文件_上海供应商.docx")), null);

        ProcurementCollusionReviewController.ReviewResponse response =
                ProcurementCollusionReviewController.ReviewResponse.from(data);

        assertThat(response.similarities()).singleElement().satisfies(pair -> {
            assertThat(pair.fileNameA()).isEqualTo("投标文件_北京供应商.docx");
            assertThat(pair.fileNameB()).isEqualTo("投标文件_上海供应商.docx");
        });
    }

    @Test
    void acceptsLegacySimilarityJsonWithoutDocumentIds() throws Exception {
        ProcurementCollusionAssessment.PairSimilarity legacy = new ObjectMapper().readValue(
                "{\"bidderA\":\"张三\",\"bidderB\":\"张三\",\"similarity\":0.977}",
                ProcurementCollusionAssessment.PairSimilarity.class);

        assertThat(legacy.documentAId()).isNull();
        assertThat(legacy.documentBId()).isNull();
        assertThat(legacy.similarity()).isEqualByComparingTo("0.977");
    }

    private static ProcurementCollusionDocument document(UUID id, UUID projectId, String fileName) {
        return new ProcurementCollusionDocument(id, projectId, "张三", null, null, fileName, "docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 100L, "sha256",
                "storage-key", "用于围串标相似度分析的投标文件正文", Instant.now());
    }
}
