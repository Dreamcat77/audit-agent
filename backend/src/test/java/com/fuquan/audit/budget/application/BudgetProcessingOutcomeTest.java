package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.MaterialExtractionUnknown;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

class BudgetProcessingOutcomeTest {
    @Test void knownEvidenceProblemsHaveSpecificSafeReasonsButAreNotInsufficientMaterial() {
        var failure=new IllegalStateException("private upstream data",new IOException("BUDGET_AI_ROW_AMBIGUOUS"));
        assertThat(BudgetProcessingOutcome.reason(failure)).isEqualTo("BUDGET_AI_ROW_AMBIGUOUS");
        assertThat(BudgetProcessingOutcome.description("FAILED",BudgetProcessingOutcome.reason(failure)))
            .contains("具体条目").doesNotContain("private","材料不足");
    }
    @Test void upstreamCauseSurvivesUnknownWithoutChangingItsDurableMeaning() {
        var upstream=HttpServerErrorException.create(HttpStatus.BAD_GATEWAY,"private",HttpHeaders.EMPTY,
            "{\"code\":\"MATERIAL_AI_UNGROUNDED\",\"message\":\"private content\"}".getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);
        var failure=new MaterialExtractionUnknown("UNACKNOWLEDGED_CALL",upstream);
        assertThat(BudgetProcessingOutcome.reason(failure)).isEqualTo("MATERIAL_AI_UNGROUNDED");
        assertThat(BudgetProcessingOutcome.description("UNKNOWN",BudgetProcessingOutcome.reason(failure)))
            .contains("原文核对").doesNotContain("private","材料不足");
    }
    @Test void unrecognizedFailureNeverExposesMessagesOrInventsAMaterialDeficiency() {
        String privateText="PRIVATE_PASSWORD_SENTINEL";
        assertThat(BudgetProcessingOutcome.reason(new IOException(privateText))).isEqualTo("BUDGET_PROCESSING_UNCONFIRMED");
        assertThat(BudgetProcessingOutcome.description("UNKNOWN",privateText))
            .contains("本次处理已结束","需要管理员").doesNotContain(privateText);
        assertThat(BudgetProcessingOutcome.description("REVIEWED",privateText)).isNull();
        assertThat(BudgetProcessingOutcome.description("PROCESSING",privateText)).isNull();
    }
}
