package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Proves the original manifest without rejecting later durable attempts or extension steps. */
final class ReviewRunManifestVerifier {
    private static final Set<ContractReviewStepType> FOUNDATION_TYPES = Set.of(
            ContractReviewStepType.MATERIAL_VALIDATION, ContractReviewStepType.F02_DETERMINISTIC,
            ContractReviewStepType.SEMANTIC_AI, ContractReviewStepType.BREACH, ContractReviewStepType.TERM);

    private ReviewRunManifestVerifier() { }

    static boolean complete(UUID runId, List<ContractReviewStep> steps,
            List<ContractReviewStepAttempt> attempts) {
        Set<ContractReviewStepType> types = new HashSet<>();
        Set<UUID> stepIds = new HashSet<>();
        for (var step : steps) {
            if (!runId.equals(step.runId()) || !types.add(step.stepType())
                    || !stepIds.add(step.stepId())) return false;
        }
        if (!types.containsAll(FOUNDATION_TYPES)) return false;
        Set<String> attemptKeys = new HashSet<>();
        Set<UUID> firstAttemptStepIds = new HashSet<>();
        for (var attempt : attempts) {
            if (!runId.equals(attempt.runId()) || !stepIds.contains(attempt.stepId())
                    || !attemptKeys.add(attempt.stepId() + ":" + attempt.attemptNo())) return false;
            if (attempt.attemptNo() == 1) firstAttemptStepIds.add(attempt.stepId());
        }
        return steps.stream().filter(step -> step.stepType() != ContractReviewStepType.MATERIAL_VALIDATION)
                .filter(step -> FOUNDATION_TYPES.contains(step.stepType()))
                .allMatch(step -> firstAttemptStepIds.contains(step.stepId()));
    }
}
