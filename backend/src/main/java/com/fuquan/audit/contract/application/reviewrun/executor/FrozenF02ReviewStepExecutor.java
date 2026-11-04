package com.fuquan.audit.contract.application.reviewrun.executor;

import com.fuquan.audit.contract.application.ContractReviewCommand;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.F02RulePolicyFingerprint;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.StepComputationResult;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Executes the existing deterministic F02 workflow from exact review-run frozen inputs. */
@Component
public final class FrozenF02ReviewStepExecutor implements ReviewStepExecutor {
    private static final String OUTPUT_RESOURCE_TYPE = "AUDIT_TASK";

    private final ContractReviewService reviews;
    private final FrozenF02ResultMapper mapper;

    public FrozenF02ReviewStepExecutor(ContractReviewService reviews) {
        this(reviews, null);
    }

    @Autowired
    public FrozenF02ReviewStepExecutor(ContractReviewService reviews, FrozenF02ResultMapper mapper) {
        this.reviews = reviews; this.mapper = mapper;
    }

    @Override
    public ContractReviewStepType stepType() {
        return ContractReviewStepType.F02_DETERMINISTIC;
    }

    @Override
    public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
        FrozenF02ResultMapper.requireSupportedFrozenExecution(input, context);
        if (!new F02RulePolicyFingerprint().fingerprint(context.f02Configurations()).equals(input.run().ruleVersion()))
            throw new BusinessException(BusinessErrorCode.CONFLICT, "frozen F02 execution policy is incompatible");
        String internalKey = internalKey(
                context.run().runId(), context.step().stepId(), context.startedAttempt().attemptId());
        var metadata = context.metadata();
        var command = new ContractReviewCommand(
                input.frozenContract().id(), internalKey,
                metadata.requestId(), metadata.traceId(), metadata.sourceIp());
        var result = reviews.executeFrozen(
                input.frozenContract(), context.f02Configurations(), context.actor(), command);
        if (mapper != null) {
            return mapper.map(input, context, result.task().id(), result.findings());
        }
        return new StepComputationResult(
                OUTPUT_RESOURCE_TYPE, result.task().id(), List.of(), List.of(), null, null, null);
    }

    /** Stable F02 operation identity used both for first execution and durable recovery. */
    public static String internalKey(UUID runId, UUID stepId, UUID attemptId) {
        return ContractReviewService.REVIEW_RUN_INTERNAL_KEY_PREFIX
                + Objects.requireNonNull(runId) + ":"
                + Objects.requireNonNull(stepId) + ":"
                + Objects.requireNonNull(attemptId);
    }
}
