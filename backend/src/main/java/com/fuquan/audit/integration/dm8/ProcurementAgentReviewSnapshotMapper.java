package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcurementAgentReviewSnapshotMapper {

    int update(ProcurementAgentReviewSnapshotPersistenceRow row);

    void insert(ProcurementAgentReviewSnapshotPersistenceRow row);

    ProcurementAgentReviewSnapshotPersistenceRow findByProjectId(String projectId);

    List<ProcurementAgentReviewSnapshotPersistenceRow> findByProjectIds(
            @Param("projectIds") List<String> projectIds);

    int deleteByProjectId(String projectId);
}
