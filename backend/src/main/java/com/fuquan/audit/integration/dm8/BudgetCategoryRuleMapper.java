package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BudgetCategoryRuleMapper {

    List<BudgetCategoryRulePersistenceRow> findByCategoryCode(@Param("categoryCode") String categoryCode);

    List<BudgetCategoryRulePersistenceRow> findAllActive();
}
