package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ReviewExecutionOwnershipTest {
    @Test
    void workerOwnsExecutionOnlyWhenEnabledInAnApprovedRuntimeProfile() {
        assertThat(new ReviewExecutionOwnership(environment(true, "local-dev"))
                .mode()).isEqualTo(ReviewExecutionMode.SERVER_WORKER);
        assertThat(new ReviewExecutionOwnership(environment(true, "local"))
                .mode()).isEqualTo(ReviewExecutionMode.CLIENT_COMPATIBILITY);
        assertThat(new ReviewExecutionOwnership(environment(false, "customer"))
                .mode()).isEqualTo(ReviewExecutionMode.CLIENT_COMPATIBILITY);
    }

    private static MockEnvironment environment(boolean enabled, String profile) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("audit.contract.review-worker.enabled", Boolean.toString(enabled));
        environment.setActiveProfiles(profile);
        return environment;
    }
}
