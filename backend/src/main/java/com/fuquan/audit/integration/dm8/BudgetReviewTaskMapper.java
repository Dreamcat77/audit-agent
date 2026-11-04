package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface BudgetReviewTaskMapper {
    java.util.List<BudgetReviewTaskPersistenceRow> timedOutForCleanup(@org.apache.ibatis.annotations.Param("limit") int limit);

    void insert(@Param("row") BudgetReviewTaskPersistenceRow row);

    void update(@Param("row") BudgetReviewTaskPersistenceRow row);

    BudgetReviewTaskPersistenceRow findById(@Param("id") String id);

    BudgetReviewTaskPersistenceRow findByIdForUpdate(@Param("id") String id);

    List<BudgetReviewTaskPersistenceRow> findPage(
            @Param("batchId") String batchId,
            @Param("budgetYear") String budgetYear,
            @Param("departmentCode") String departmentCode,
            @Param("fileName") String fileName,
            @Param("createdFrom") java.time.LocalDateTime createdFrom,
            @Param("createdBefore") java.time.LocalDateTime createdBefore,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long count(
            @Param("batchId") String batchId,
            @Param("budgetYear") String budgetYear,
            @Param("departmentCode") String departmentCode,
            @Param("fileName") String fileName,
            @Param("createdFrom") java.time.LocalDateTime createdFrom,
            @Param("createdBefore") java.time.LocalDateTime createdBefore);
}
