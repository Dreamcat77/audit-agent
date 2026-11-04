package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.domain.reviewrun.*;
import java.text.Normalizer;

/** Explicit customer UNKNOWN supersede is deliberately not configured. */
public final class ReviewStepRetryPolicy {
    public static final String VERSION = "worker-retry-v1";
    public void requireSafe(ContractReviewStep step, ContractReviewStepAttempt attempt) {
        if (step.status() != ContractReviewStepStatus.FAILED || attempt.status() != ContractReviewStepStatus.FAILED
                || step.stepType() == ContractReviewStepType.MATERIAL_VALIDATION
                || (attempt.externalCallState() != ReviewStepExternalCallState.NOT_STARTED
                    && attempt.externalCallState() != ReviewStepExternalCallState.COMPLETED))
            throw ReviewWorkerLeasePolicy.conflict();
        if (step.stepType() != ContractReviewStepType.SEMANTIC_AI
                && attempt.externalCallState() != ReviewStepExternalCallState.NOT_STARTED)
            throw ReviewWorkerLeasePolicy.conflict();
    }
    public static String normalizeReason(String reason) {
        if (reason == null) throw new IllegalArgumentException("retry reason is required");
        String normalized = Normalizer.normalize(reason, Normalizer.Form.NFKC).replace("\r\n", "\n").strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 1000
                || normalized.codePoints().anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\t'))
            throw new IllegalArgumentException("invalid retry reason");
        return normalized;
    }
}
