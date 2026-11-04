package com.fuquan.audit.contract.application.reviewrun.executor;

import com.fuquan.audit.contract.application.clause.ContractTermClauseAnalyzer;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.StepComputationResult;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import org.springframework.stereotype.Component;

/** Executes the existing deterministic term analyzer solely against frozen run inputs. */
@Component
public final class FrozenTermReviewStepExecutor implements ReviewStepExecutor {
    private final ContractTermClauseAnalyzer analyzer;
    private final FrozenClauseMaterialAdapter materials;
    private final FrozenClauseResultMapper results;

    public FrozenTermReviewStepExecutor(ContractTermClauseAnalyzer analyzer,
            FrozenClauseMaterialAdapter materials, FrozenClauseResultMapper results) {
        this.analyzer = analyzer;
        this.materials = materials;
        this.results = results;
    }

    @Override
    public ContractReviewStepType stepType() {
        return ContractReviewStepType.TERM;
    }

    @Override
    public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
        if (context.step().stepType() != ContractReviewStepType.TERM
                || !ReviewRunManifestFactory.TERM_POLICY_VERSION
                        .equals(context.step().stepPolicyVersion())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "unsupported frozen term policy");
        }
        var review = analyzer.analyze(materials.adapt(input.materials()));
        return results.mapTerm(review, input, context);
    }
}
