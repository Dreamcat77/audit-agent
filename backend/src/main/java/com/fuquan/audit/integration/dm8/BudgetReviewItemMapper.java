package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BudgetReviewItemMapper {

    void insertBatch(@Param("list") List<BudgetReviewItemPersistenceRow> rows);

    void deleteByTaskAndRow(@Param("taskId") String taskId, @Param("rowNumber") int rowNumber);

    List<BudgetReviewItemPersistenceRow> findByTaskId(@Param("taskId") String taskId);

    BudgetReviewItemPersistenceRow findById(@Param("id") String id);
}
