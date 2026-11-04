package com.fuquan.audit.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ActiveProfileGuardTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(ActiveProfileGuard.class);

    @Test
    void failsClearlyWhenNoAllowedProfileIsActive() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(ActiveProfileGuard.ERROR_MESSAGE);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "local-dev", "dev29", "customer"})
    void acceptsExactlyOneRuntimeProfile(String profile) {
        contextRunner
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"local,dev29", "dev29,customer"})
    void rejectsMultipleRuntimeProfiles(String profiles) {
        contextRunner
                .withPropertyValues("spring.profiles.active=" + profiles)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(ActiveProfileGuard.ERROR_MESSAGE);
                });
    }
}
