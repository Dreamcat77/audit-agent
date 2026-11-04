package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import java.util.Objects;

/** Immutable server-owned execution context; executors receive no persistence repository. */
public record ReviewExecutionContext(
        ContractReviewRun run, ContractReviewStep step, ContractReviewStepAttempt startedAttempt,
        CurrentUser actor, ProjectRequestMetadata metadata,
        List<ContractRuleConfiguration> f02Configurations, ExternalRequestMarker externalRequestMarker) {
    public ReviewExecutionContext {
        Objects.requireNonNull(run); Objects.requireNonNull(step); Objects.requireNonNull(startedAttempt);
        Objects.requireNonNull(actor); Objects.requireNonNull(metadata); Objects.requireNonNull(externalRequestMarker);
        f02Configurations = List.copyOf(f02Configurations);
    }

    public void markExternalRequested(String requestFingerprint) {
        externalRequestMarker.markRequested(requestFingerprint);
    }

    @FunctionalInterface
    public interface ExternalRequestMarker {
        void markRequested(String requestFingerprint);
    }
}
