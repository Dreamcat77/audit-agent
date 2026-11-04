package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BudgetValidationConfirmLogMapper {

    void insert(@Param("row") BudgetValidationConfirmLogPersistenceRow row);

    List<BudgetValidationConfirmLogPersistenceRow> findByBatchId(@Param("batchId") String batchId);
}
