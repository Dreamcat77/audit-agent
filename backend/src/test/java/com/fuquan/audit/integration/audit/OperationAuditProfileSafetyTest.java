package com.fuquan.audit.integration.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.integration.audit.disabled.DisabledOperationAuditConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OperationAuditProfileSafetyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    DisabledOperationAuditConfiguration.class,
                    DurableOperationAuditFailClosedConfiguration.class)
            .withPropertyValues("audit.operation-audit.enabled=false");

    @ParameterizedTest
    @ValueSource(strings = {"dev29", "customer", "local-dev"})
    void nonLocalProfilesFailToStartWhenDurableAuditIsDisabled(String profile) {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "durable operation audit must be enabled for dev29, customer and local-dev profiles");
                });
    }

    @Test
    void localProfileRetainsExplicitDisabledTestSeam() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OperationAuditPort.class);
                });
    }
}
