package com.fuquan.audit.contract.application.project.role;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operational lease used only to recover an abandoned deterministic computation attempt. */
@ConfigurationProperties("audit.role-assessment")
public record RoleAssessmentRecoveryPolicy(Duration runningRecoveryAge) {
    private static final Duration DEFAULT_RUNNING_RECOVERY_AGE = Duration.ofMinutes(15);

    public RoleAssessmentRecoveryPolicy {
        runningRecoveryAge = runningRecoveryAge == null ? DEFAULT_RUNNING_RECOVERY_AGE : runningRecoveryAge;
        if (runningRecoveryAge.isZero() || runningRecoveryAge.isNegative()) {
            throw new IllegalArgumentException("runningRecoveryAge must be positive");
        }
    }

    public static RoleAssessmentRecoveryPolicy defaults() {
        return new RoleAssessmentRecoveryPolicy(null);
    }

    public boolean isExpired(Instant startedAt, Instant now) {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return !startedAt.plus(runningRecoveryAge).isAfter(now);
    }
}
