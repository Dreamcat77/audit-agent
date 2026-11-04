package com.fuquan.audit.integration.audit;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Prevents a non-local runtime from starting when durable operation audit is disabled. */
@Configuration(proxyBeanMethods = false)
@Profile({"dev29", "customer", "local-dev"})
public class DurableOperationAuditFailClosedConfiguration {

    static final String ENABLED_PROPERTY = "audit.operation-audit.enabled";

    @Bean
    static BeanFactoryPostProcessor requireDurableOperationAudit(Environment environment) {
        boolean enabled = environment.getProperty(ENABLED_PROPERTY, Boolean.class, true);
        if (!enabled) {
            throw new IllegalStateException(
                    "durable operation audit must be enabled for dev29, customer and local-dev profiles");
        }
        return beanFactory -> {
            // Eager infrastructure guard only; the DM8 adapter remains the real Port implementation.
        };
    }
}
