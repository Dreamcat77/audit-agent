package com.fuquan.audit.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ProfileLoggingConfigurationTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void localAndLocalDevEnableFlowDebugWhileDevAndCustomerDisableIt() throws IOException {
        assertThat(property("application-local.yml", "audit.observability.flow-debug-enabled").toString())
                .isEqualTo("${AUDIT_OBSERVABILITY_FLOW_DEBUG_ENABLED:true}");
        assertThat(property("application-local-dev.yml", "audit.observability.flow-debug-enabled"))
                .isEqualTo(true);
        assertThat(property("application-dev29.yml", "audit.observability.flow-debug-enabled").toString())
                .isEqualTo("${AUDIT_OBSERVABILITY_FLOW_DEBUG_ENABLED:false}");
        assertThat(property("application-customer.yml", "audit.observability.flow-debug-enabled").toString())
                .isEqualTo("${AUDIT_OBSERVABILITY_FLOW_DEBUG_ENABLED:false}");
    }

    @Test
    void flowDebugHasDedicatedEnvironmentControlledLoggerLevel() throws IOException {
        String logger = "logging.level.com.fuquan.audit.common.observability.FlowDebugLogger";
        assertThat(property("application.yml", logger).toString())
                .isEqualTo("${AUDIT_FLOW_DEBUG_LOG_LEVEL:INFO}");
        assertThat(property("application-local.yml", logger).toString())
                .isEqualTo("${AUDIT_FLOW_DEBUG_LOG_LEVEL:DEBUG}");
    }

    @Test
    void devAndCustomerUseBuiltInLogstashStructuredLogging() throws IOException {
        assertThat(property("application-dev29.yml", "logging.structured.format.console"))
                .isEqualTo("logstash");
        assertThat(property("application-customer.yml", "logging.structured.format.console"))
                .isEqualTo("logstash");
    }

    @Test
    void durableAuditIsEnabledInEveryRuntimeProfile() throws IOException {
        assertThat(property("application-local.yml", "audit.operation-audit.enabled")).isEqualTo(true);
        assertThat(property("application-local-dev.yml", "audit.operation-audit.enabled")).isEqualTo(true);
        assertThat(property("application-dev29.yml", "audit.operation-audit.enabled")).isEqualTo(true);
        assertThat(property("application-customer.yml", "audit.operation-audit.enabled")).isEqualTo(true);
    }

    @Test
    void redisSessionNamespaceIsExplicitOnlyForNonLocalProfiles() throws IOException {
        assertThat(property("application-local.yml", "spring.session.redis.namespace")).isNull();
        assertThat(property("application-local-dev.yml", "spring.session.redis.namespace").toString())
                .contains("audit-agent:sessions");
        assertThat(property("application-dev29.yml", "spring.session.redis.namespace").toString())
                .contains("audit-agent:sessions");
        assertThat(property("application-customer.yml", "spring.session.redis.namespace").toString())
                .contains("audit-agent:sessions");
        assertThat(property("application-local.yml", "server.servlet.session.cookie.secure"))
                .isEqualTo(false);
        assertThat(property("application-customer.yml", "server.servlet.session.cookie.secure").toString())
                .contains(":true");
    }

    @Test
    void localDevRequiresExternalDatabaseAndStorageConfiguration() throws IOException {
        assertThat(property("application-local-dev.yml", "spring.datasource.url"))
                .isEqualTo("${AUDIT_DB_URL}");
        assertThat(property("application-local-dev.yml", "spring.datasource.username"))
                .isEqualTo("${AUDIT_DB_USERNAME}");
        assertThat(property("application-local-dev.yml", "spring.datasource.password"))
                .isEqualTo("${AUDIT_DB_PASSWORD}");
        assertThat(property("application-local-dev.yml", "audit.file-storage.root"))
                .isEqualTo("${AUDIT_FILE_STORAGE_ROOT}");
    }

    @Test
    void traceSamplingIsExternallyConfigurableWithFullSamplingDefault() throws IOException {
        assertThat(property("application.yml", "management.tracing.sampling.probability").toString())
                .isEqualTo("${AUDIT_TRACE_SAMPLING_PROBABILITY:1.0}");
    }

    private Object property(String resourceName, String key) throws IOException {
        List<PropertySource<?>> sources = loader.load(resourceName, new ClassPathResource(resourceName));
        return sources.stream()
                .map(source -> source.getProperty(key))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }
}
