package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractCandidateConfirmationOpenApiStructureTest {
    @Test
    void documentsTwoStageConfirmationAndOnlyIdentityBasedPromotion() throws Exception {
        String yaml = Files.readString(Path.of("..", "docs", "openapi.yaml"));
        assertThat(yaml).contains(
                "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations:",
                "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations/{confirmationId}:",
                "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations/latest:",
                "ConfirmationRequest:", "ConfirmationResponse:");
        String promotion = yaml.substring(yaml.indexOf("    PromotionRequest:"),
                yaml.indexOf("    ApiResponseConfirmationResponse:"));
        assertThat(promotion).contains("confirmationId:", "expectedVersion:")
                .doesNotContain("contractNo:", "contractAmount:", "lineItems:", "awardNotice:");
    }
}
