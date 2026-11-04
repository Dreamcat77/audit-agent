package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcurementTenderFindingMapper {
    int deleteByReviewId(@Param("projectId") String projectId, @Param("reviewId") String reviewId);

    void insertBatch(@Param("list") List<ProcurementTenderFindingPersistenceRow> rows);

    List<ProcurementTenderFindingPersistenceRow> findByReviewId(
            @Param("projectId") String projectId, @Param("reviewId") String reviewId);
}
