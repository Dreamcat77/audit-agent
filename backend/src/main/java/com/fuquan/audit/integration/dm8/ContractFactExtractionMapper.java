package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractFactExtractionMapper {
    int insertAttempt(@Param("row") ContractFactExtractionAttemptRow row);
    ContractFactExtractionAttemptRow findAttemptById(@Param("attemptId") String attemptId);
    ContractFactExtractionAttemptRow findAttemptByOperation(@Param("actorId") String actorId,
            @Param("operationKey") String operationKey);
    ContractFactExtractionAttemptRow findLatest(@Param("projectId") String projectId,
            @Param("fileId") String fileId, @Param("parseResultId") String parseResultId);
    ContractFactExtractionAttemptRow findLatestForAuthority(@Param("projectId") String projectId,
            @Param("fileId") String fileId, @Param("parseResultId") String parseResultId,
            @Param("roleDecisionId") String roleDecisionId,
            @Param("identityDecisionId") String identityDecisionId,
            @Param("effectiveRole") String effectiveRole, @Param("provider") String provider,
            @Param("modelName") String modelName, @Param("modelRevision") String modelRevision,
            @Param("promptVersion") String promptVersion, @Param("schemaVersion") String schemaVersion);
    int retryFailed(@Param("attemptId") String attemptId, @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") LocalDateTime updatedAt);
    int insertCandidate(@Param("row") ContractFactExtractionCandidateRow row);
    List<ContractFactExtractionCandidateRow> findCandidates(@Param("attemptId") String attemptId);
    int insertRejection(@Param("row") ContractFactExtractionRejectionRow row);
    List<ContractFactExtractionRejectionRow> findRejections(@Param("attemptId") String attemptId);
    int insertModelCall(@Param("row") ContractModelCallAuditRow row);
    List<ContractModelCallAuditRow> findModelCallsByFactAttempt(@Param("attemptId") String attemptId);
    List<ContractModelCallAuditRow> findModelCallsByReviewStepAttempt(@Param("attemptId") String attemptId);
    int complete(@Param("attemptId") String attemptId, @Param("expectedVersion") long expectedVersion,
            @Param("acceptedCount") int acceptedCount, @Param("rejectedCount") int rejectedCount,
            @Param("updatedAt") LocalDateTime updatedAt);
    int fail(@Param("attemptId") String attemptId, @Param("expectedVersion") long expectedVersion,
            @Param("reason") String reason, @Param("status") String status,
            @Param("updatedAt") LocalDateTime updatedAt);
    int failV2(@Param("attemptId") String attemptId, @Param("expectedVersion") long expectedVersion,
            @Param("rejectedCount") int rejectedCount, @Param("reason") String reason,
            @Param("status") String status, @Param("updatedAt") LocalDateTime updatedAt);
}
