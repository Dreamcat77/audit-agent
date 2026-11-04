package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcurementReviewWorkflowMapper {
    String lockProject(@Param("projectId") String projectId);
    void insertWorkflow(ProcurementReviewWorkflowPersistenceRow row);
    void insertSteps(@Param("steps") List<ProcurementReviewStepPersistenceRow> steps);
    int updateWorkflow(ProcurementReviewWorkflowPersistenceRow row);
    int updateStep(ProcurementReviewStepPersistenceRow row);
    ProcurementReviewWorkflowPersistenceRow findWorkflowByProjectId(@Param("projectId") String projectId);
    List<ProcurementReviewStepPersistenceRow> findStepsByWorkflowId(@Param("workflowId") String workflowId);
}
