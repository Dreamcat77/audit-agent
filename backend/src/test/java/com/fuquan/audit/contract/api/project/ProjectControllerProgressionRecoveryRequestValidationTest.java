package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class ProjectControllerProgressionRecoveryRequestValidationTest {
    @Test
    void acceptsServerPublishedFactExtractionBlocker() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new ProjectController.ProgressionRecoveryRequest(
                    "FACT_EXTRACTION_PENDING", "a".repeat(64));

            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }
}
