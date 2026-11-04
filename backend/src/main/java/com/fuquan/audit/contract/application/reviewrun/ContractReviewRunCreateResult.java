package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ContractReviewRunCreateResult(
        ContractReviewRun run, List<ContractReviewRunMaterial> materials,
        List<ContractReviewStep> steps, List<ContractReviewStepAttempt> attempts, boolean replayed) {
    public ContractReviewRunCreateResult {
        Map<UUID, ContractReviewStepType> parentTypes = new HashMap<>();
        for (var step : steps) {
            if (parentTypes.putIfAbsent(step.stepId(), step.stepType()) != null)
                throw new IllegalArgumentException("duplicate review step identity");
        }
        materials = List.copyOf(materials);
        steps = steps.stream().sorted(Comparator.comparingInt((ContractReviewStep step) ->
                step.stepType().ordinal()).thenComparing(step -> step.stepId().toString())).toList();
        attempts = attempts.stream().sorted(Comparator.comparingInt((ContractReviewStepAttempt attempt) -> {
            var parentType = parentTypes.get(attempt.stepId());
            if (parentType == null) throw new IllegalArgumentException("review attempt has no parent step");
            return parentType.ordinal();
        }).thenComparingInt(ContractReviewStepAttempt::attemptNo)
                .thenComparing(attempt -> attempt.attemptId().toString())).toList();
    }
}
