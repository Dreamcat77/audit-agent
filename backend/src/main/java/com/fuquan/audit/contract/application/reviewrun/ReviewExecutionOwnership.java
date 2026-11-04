package com.fuquan.audit.contract.application.reviewrun;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Safe client projection of the runtime that actually owns ReviewStep execution. */
@Component
public class ReviewExecutionOwnership {
    private final ReviewExecutionMode mode;

    public ReviewExecutionOwnership(Environment environment) {
        boolean configured = environment.getProperty(
                "audit.contract.review-worker.enabled", Boolean.class, false);
        boolean supportedProfile = environment.acceptsProfiles(
                Profiles.of("local-dev", "dev29", "customer"));
        this.mode = configured && supportedProfile
                ? ReviewExecutionMode.SERVER_WORKER
                : ReviewExecutionMode.CLIENT_COMPATIBILITY;
    }

    public ReviewExecutionMode mode() {
        return mode;
    }

    public boolean workerOwnsExecution() {
        return mode == ReviewExecutionMode.SERVER_WORKER;
    }
}
