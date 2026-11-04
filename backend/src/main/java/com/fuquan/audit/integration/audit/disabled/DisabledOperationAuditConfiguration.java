package com.fuquan.audit.integration.audit.disabled;

import com.fuquan.audit.common.application.port.OperationAuditPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "audit.operation-audit.enabled", havingValue = "false")
public class DisabledOperationAuditConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DisabledOperationAuditConfiguration.class);

    @Bean
    OperationAuditPort disabledOperationAuditPort() {
        log.warn("event=OPERATION_AUDIT_DISABLED module=COMMON layer=CONFIG outcome=disabled");
        return event -> {
            // Deliberate local/test-only seam. Non-local profiles fail closed instead.
        };
    }
}
