package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewRunManifestFactoryTest {
    private final ReviewRunManifestFactory factory = new ReviewRunManifestFactory();

    @Test
    void createOnlyMaterialValidationIsCompletedAndFourAttemptsRemainNotStarted() {
        UUID runId = UUID.randomUUID();
        var manifest = factory.create(runId, Instant.parse("2026-09-15T00:00:00Z"));
        assertThat(manifest.steps()).extracting(step -> step.stepType()).containsExactly(
                ContractReviewStepType.MATERIAL_VALIDATION, ContractReviewStepType.F02_DETERMINISTIC,
                ContractReviewStepType.SEMANTIC_AI, ContractReviewStepType.BREACH, ContractReviewStepType.TERM);
        assertThat(manifest.steps()).extracting(step -> step.status()).containsExactly(
                ContractReviewStepStatus.COMPLETED, ContractReviewStepStatus.PENDING,
                ContractReviewStepStatus.PENDING, ContractReviewStepStatus.PENDING,
                ContractReviewStepStatus.PENDING);
        assertThat(manifest.steps().get(0).outputResourceType()).isEqualTo("RUN_MATERIAL_SNAPSHOT");
        assertThat(manifest.steps().get(0).outputResourceId()).isEqualTo(runId);
        assertThat(manifest.steps().get(0).currentAttemptNo()).isZero();
        assertThat(manifest.attempts()).hasSize(4);
        assertThat(manifest.attempts()).allSatisfy(attempt -> {
            assertThat(attempt.attemptNo()).isEqualTo(1);
            assertThat(attempt.status()).isEqualTo(ContractReviewStepStatus.PENDING);
            assertThat(attempt.externalCallState()).isEqualTo(ReviewStepExternalCallState.NOT_STARTED);
            assertThat(attempt.operationIdentity()).matches("[0-9a-f]{64}");
            assertThat(attempt.leaseOwner()).isNull();
            assertThat(attempt.provider()).isNull();
            assertThat(attempt.outputResourceId()).isNull();
        });
        assertThat(manifest.steps().stream().filter(step -> step.stepType() == ContractReviewStepType.F02_DETERMINISTIC)
                .findFirst().orElseThrow().outputResourceId()).isNull();
        assertThat(factory.create(runId, Instant.parse("2026-09-15T01:00:00Z")).attempts())
                .extracting(attempt -> attempt.operationIdentity())
                .containsExactlyElementsOf(manifest.attempts().stream().map(attempt -> attempt.operationIdentity()).toList());
    }
}
