package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BudgetProjectConfigMapper {
    List<BudgetProjectConfigPersistenceRow> findByProjectCode(@Param("projectCode") String projectCode);
    List<BudgetProjectConfigPersistenceRow> findAllActive();
}
