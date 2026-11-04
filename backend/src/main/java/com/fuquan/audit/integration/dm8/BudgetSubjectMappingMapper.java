package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BudgetSubjectMappingMapper {

    List<BudgetSubjectMappingPersistenceRow> findBySystemSubjectCode(@Param("systemSubjectCode") String systemSubjectCode);

    List<BudgetSubjectMappingPersistenceRow> findAllActive();
}
