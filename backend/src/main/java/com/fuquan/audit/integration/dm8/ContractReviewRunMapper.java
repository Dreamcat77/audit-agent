package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractReviewRunMapper {
    int insertRun(@Param("row") ContractReviewRunPersistenceRow row);
    int insertMaterial(@Param("row") ContractReviewRunMaterialPersistenceRow row);
    int insertStep(@Param("row") ContractReviewStepPersistenceRow row);
    int insertAttempt(@Param("row") ContractReviewStepAttemptPersistenceRow row);
    int updateRunCas(@Param("row") ContractReviewRunPersistenceRow row,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersionNo") long expectedVersionNo);
    int updateStepCas(@Param("row") ContractReviewStepPersistenceRow row,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersionNo") long expectedVersionNo);
    int updateAttemptCas(@Param("row") ContractReviewStepAttemptPersistenceRow row,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersionNo") long expectedVersionNo);
    ContractReviewRunPersistenceRow findRunById(@Param("runId") String runId);
    ContractReviewRunPersistenceRow findLatestByProjectIdAndContractIdAndRequestedBy(
            @Param("projectId") String projectId,
            @Param("contractId") String contractId,
            @Param("requestedBy") String requestedBy);
    ContractReviewRunPersistenceRow findRunByIdForUpdate(@Param("runId") String runId);
    ContractReviewStepPersistenceRow findStepById(@Param("stepId") String stepId);
    ContractReviewStepAttemptPersistenceRow findAttemptById(@Param("attemptId") String attemptId);
    List<ContractReviewRunMaterialPersistenceRow> findMaterialsByRunId(@Param("runId") String runId);
    List<ContractReviewStepPersistenceRow> findStepsByRunId(@Param("runId") String runId);
    List<ContractReviewStepPersistenceRow> findStepsByOutputResource(
            @Param("resourceType") String resourceType, @Param("resourceId") String resourceId);
    List<ContractReviewStepAttemptPersistenceRow> findAttemptsByRunId(@Param("runId") String runId);
}
