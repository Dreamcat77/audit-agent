package com.fuquan.audit.contract.application.reviewrun.executor;

import com.fuquan.audit.contract.application.clause.BreachClauseAnalyzer;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.StepComputationResult;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import org.springframework.stereotype.Component;

/** Executes the existing deterministic breach analyzer solely against frozen run inputs. */
@Component
public final class FrozenBreachReviewStepExecutor implements ReviewStepExecutor {
    private final BreachClauseAnalyzer analyzer;
    private final FrozenClauseMaterialAdapter materials;
    private final FrozenClauseResultMapper results;

    public FrozenBreachReviewStepExecutor(BreachClauseAnalyzer analyzer,
            FrozenClauseMaterialAdapter materials, FrozenClauseResultMapper results) {
        this.analyzer = analyzer;
        this.materials = materials;
        this.results = results;
    }

    @Override
    public ContractReviewStepType stepType() {
        return ContractReviewStepType.BREACH;
    }

    @Override
    public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
        if (context.step().stepType() != ContractReviewStepType.BREACH
                || !ReviewRunManifestFactory.BREACH_POLICY_VERSION
                        .equals(context.step().stepPolicyVersion())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "unsupported frozen breach policy");
        }
        var review = analyzer.analyze(input.frozenContract().contractAmount().amount(),
                materials.adapt(input.materials()));
        return results.mapBreach(review, input, context);
    }
}
