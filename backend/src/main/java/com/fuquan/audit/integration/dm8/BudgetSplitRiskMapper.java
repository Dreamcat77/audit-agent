package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BudgetSplitRiskMapper {
    List<BudgetSplitRiskRulePersistenceRow> findActiveRules();
    void insertAssessment(@Param("row") BudgetSplitRiskAssessmentPersistenceRow row);
    BudgetSplitRiskAssessmentPersistenceRow findAssessmentById(@Param("id") String id);
}
