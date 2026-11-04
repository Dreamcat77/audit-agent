package com.fuquan.audit.blacklist.application;

import com.fuquan.audit.blacklist.application.port.BlacklistAlertRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.blacklist.application.port.BlacklistSupplierRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistRuleRepository;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * F03 黑名单模块 — Application 层 Bean 注册。
 */
@Configuration
public class BlacklistConfigurationBeans {

    @Bean
    public BlacklistCheckPort blacklistCheckPort(
            BlacklistSupplierRepository supplierRepository,
            BlacklistAlertRepository alertRepository,
            BlacklistRuleRepository ruleRepository,
            FlowDebugLogger flowDebugLogger) {
        return new BlacklistCheckService(
                supplierRepository, alertRepository, ruleRepository, flowDebugLogger);
    }
}
