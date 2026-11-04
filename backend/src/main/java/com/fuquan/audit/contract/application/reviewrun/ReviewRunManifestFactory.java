package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReviewRunManifestFactory {
    public static final String BREACH_POLICY_VERSION = "breach-analyzer-v5";
    public static final String TERM_POLICY_VERSION = "term-analyzer-v5";
    public record Manifest(List<ContractReviewStep> steps, List<ContractReviewStepAttempt> attempts) {
        public Manifest { steps = List.copyOf(steps); attempts = List.copyOf(attempts); }
    }

    public Manifest create(UUID runId, Instant now) {
        return create(runId, now, null);
    }

    public Manifest create(UUID runId, Instant now,
            ContractReviewExecutionPolicyResolver.ResolvedPolicy policy) {
        List<ContractReviewStep> steps = new ArrayList<>();
        List<ContractReviewStepAttempt> attempts = new ArrayList<>();
        for (var type : ContractReviewStepType.values()) {
            UUID stepId = stableId(runId + ":" + type.name());
            boolean validation = type == ContractReviewStepType.MATERIAL_VALIDATION;
            steps.add(new ContractReviewStep(stepId, runId, type, true,
                    validation ? ContractReviewStepStatus.COMPLETED : ContractReviewStepStatus.PENDING,
                    stepPolicy(type, policy), validation ? 0 : 1,
                    validation ? "RUN_MATERIAL_SNAPSHOT" : null, validation ? runId : null,
                    null, null, validation ? now : null, 0));
            if (!validation) {
                String operationIdentity = ContractSnapshotCanonicalizer.sha256(
                        "review-step-attempt-v1:" + runId + ":" + stepId + ":" + type + ":1");
                attempts.add(new ContractReviewStepAttempt(stableId(operationIdentity), runId, stepId,
                        1, operationIdentity, ContractReviewStepStatus.PENDING, null, null, null,
                        ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                        null, null, null, null, null, 0));
            }
        }
        return new Manifest(steps, attempts);
    }

    private static String stepPolicy(ContractReviewStepType type,
            ContractReviewExecutionPolicyResolver.ResolvedPolicy policy) {
        if (policy == null) return "review-step-policy-v1";
        return switch (type) {
            case MATERIAL_VALIDATION -> "material-snapshot-v1";
            case F02_DETERMINISTIC -> policy.ruleVersion();
            case SEMANTIC_AI -> policy.modelPolicyVersion();
            case BREACH -> BREACH_POLICY_VERSION;
            case TERM -> TERM_POLICY_VERSION;
        };
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
