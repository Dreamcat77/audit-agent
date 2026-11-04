package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BudgetApprovalValidationMapper {
    int insert(@Param("row") BudgetApprovalValidationPersistenceRow row);

    BudgetApprovalValidationPersistenceRow findById(@Param("id") String id);

    List<BudgetApprovalValidationPersistenceRow> findPage(
            @Param("taskId") String taskId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long count(@Param("taskId") String taskId);
}
