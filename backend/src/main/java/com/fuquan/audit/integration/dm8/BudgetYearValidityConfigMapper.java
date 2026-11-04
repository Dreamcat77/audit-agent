package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BudgetYearValidityConfigMapper {
    List<BudgetYearValidityConfigPersistenceRow> findBySubjectCode(@Param("subjectCode") String subjectCode);
    List<BudgetYearValidityConfigPersistenceRow> findAllActive();
}
