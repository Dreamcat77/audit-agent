package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewRunStatusAggregator {
    public ContractReviewRunStatus status(List<ContractReviewStep> steps) {
        var required = List.copyOf(steps).stream().filter(ContractReviewStep::required).toList();
        var types = required.stream().map(ContractReviewStep::stepType)
                .collect(java.util.stream.Collectors.toSet());
        if (required.size() != ContractReviewStepType.values().length
                || !types.equals(EnumSet.allOf(ContractReviewStepType.class)))
            throw new BusinessException(BusinessErrorCode.CONFLICT, "review run required step manifest is incomplete");
        if (required.stream().anyMatch(step -> step.status() == ContractReviewStepStatus.UNKNOWN))
            return ContractReviewRunStatus.UNKNOWN;
        if (required.stream().allMatch(step -> step.status() == ContractReviewStepStatus.COMPLETED))
            return ContractReviewRunStatus.COMPLETED;
        boolean terminal = required.stream().allMatch(step -> step.status() == ContractReviewStepStatus.COMPLETED
                || step.status() == ContractReviewStepStatus.FAILED);
        if (terminal && required.stream().anyMatch(step -> step.status() == ContractReviewStepStatus.FAILED))
            return ContractReviewRunStatus.FAILED;
        return ContractReviewRunStatus.RUNNING;
    }
}
