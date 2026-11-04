package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewRunStatusAggregatorTest {
    private final ReviewRunStatusAggregator aggregator = new ReviewRunStatusAggregator();

    @Test
    void requiredUnknownDominatesEvenWhenEveryOtherStepCompleted() {
        assertThat(aggregator.status(steps(ContractReviewStepStatus.COMPLETED,
                ContractReviewStepStatus.UNKNOWN, ContractReviewStepStatus.COMPLETED,
                ContractReviewStepStatus.COMPLETED, ContractReviewStepStatus.COMPLETED)))
                .isEqualTo(ContractReviewRunStatus.UNKNOWN);
    }

    @Test
    void allRequiredCompletedProducesCompleted() {
        assertThat(aggregator.status(steps(ContractReviewStepStatus.COMPLETED,
                ContractReviewStepStatus.COMPLETED, ContractReviewStepStatus.COMPLETED,
                ContractReviewStepStatus.COMPLETED, ContractReviewStepStatus.COMPLETED)))
                .isEqualTo(ContractReviewRunStatus.COMPLETED);
    }

    @Test
    void failedBecomesRunFailureOnlyAfterEveryRequiredStepIsTerminal() {
        assertThat(aggregator.status(steps(ContractReviewStepStatus.COMPLETED,
                ContractReviewStepStatus.FAILED, ContractReviewStepStatus.COMPLETED,
                ContractReviewStepStatus.COMPLETED, ContractReviewStepStatus.COMPLETED)))
                .isEqualTo(ContractReviewRunStatus.FAILED);
        assertThat(aggregator.status(steps(ContractReviewStepStatus.COMPLETED,
                ContractReviewStepStatus.FAILED, ContractReviewStepStatus.PENDING,
                ContractReviewStepStatus.COMPLETED, ContractReviewStepStatus.COMPLETED)))
                .isEqualTo(ContractReviewRunStatus.RUNNING);
    }

    @Test
    void anyRequiredPendingOrRunningKeepsRunRunning() {
        assertThat(aggregator.status(steps(ContractReviewStepStatus.COMPLETED,
                ContractReviewStepStatus.RUNNING, ContractReviewStepStatus.PENDING,
                ContractReviewStepStatus.COMPLETED, ContractReviewStepStatus.COMPLETED)))
                .isEqualTo(ContractReviewRunStatus.RUNNING);
    }

    private static List<ContractReviewStep> steps(ContractReviewStepStatus... statuses) {
        UUID runId = UUID.randomUUID();
        var types = ContractReviewStepType.values();
        return Arrays.stream(types).map(type -> {
            var status = statuses[type.ordinal()];
            boolean material = type == ContractReviewStepType.MATERIAL_VALIDATION;
            return new ContractReviewStep(UUID.randomUUID(), runId, type, true, status,
                    "policy-v1", material ? 0 : 1,
                    material ? "RUN_MATERIAL_SNAPSHOT" : null,
                    material ? runId : null, null,
                    status == ContractReviewStepStatus.PENDING ? null : Instant.EPOCH,
                    status == ContractReviewStepStatus.COMPLETED ? Instant.EPOCH : null, 0);
        }).toList();
    }
}
