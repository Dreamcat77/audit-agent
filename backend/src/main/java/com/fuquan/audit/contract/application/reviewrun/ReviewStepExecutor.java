package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;

public interface ReviewStepExecutor {
    ContractReviewStepType stepType();
    StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context);
}
