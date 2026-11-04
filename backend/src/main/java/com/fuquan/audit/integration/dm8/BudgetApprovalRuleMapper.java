package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BudgetApprovalRuleMapper {
    List<BudgetApprovalRulePersistenceRow> findActiveByRuleSetCode(
            @Param("ruleSetCode") String ruleSetCode);

    List<BudgetApprovalRulePersistenceRow> findByRuleSetCode(
            @Param("ruleSetCode") String ruleSetCode);
}
