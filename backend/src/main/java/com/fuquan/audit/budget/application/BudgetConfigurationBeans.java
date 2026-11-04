package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetApprovalCheckRepository;
import com.fuquan.audit.budget.application.port.BudgetCheckPort;
import com.fuquan.audit.budget.application.port.BudgetSubjectMappingRepository;
import com.fuquan.audit.budget.application.port.ProcurementDemandRepository;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * F01 预算审批模块 — Application 层 Bean 注册。
 */
@Configuration
public class BudgetConfigurationBeans {

    @Bean
    public BudgetCheckPort budgetCheckPort(
            ProcurementDemandRepository procurementDemandRepository,
            BudgetSubjectMappingRepository subjectMappingRepository,
            BudgetApprovalCheckRepository approvalCheckRepository,
            FlowDebugLogger flowDebugLogger) {
        return new BudgetCheckService(
                procurementDemandRepository,
                subjectMappingRepository,
                approvalCheckRepository,
                flowDebugLogger);
    }
}
