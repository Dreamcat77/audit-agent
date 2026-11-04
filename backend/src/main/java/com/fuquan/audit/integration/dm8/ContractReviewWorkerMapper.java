package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractReviewWorkerMapper {
    int advanceRetryStep(@Param("stepId") String stepId, @Param("runId") String runId,
            @Param("expectedVersion") long version, @Param("previousAttemptNo") int previousAttemptNo,
            @Param("nextAttemptNo") int nextAttemptNo);
    int resumeRetryRun(@Param("runId") String runId, @Param("expectedVersion") long version,
            @Param("expectedStatus") String status);
    com.fuquan.audit.contract.application.reviewrun.port.ContractReviewWorkerRepository.BusinessInitiator
            findBusinessInitiator(@Param("userId") String userId);
    List<ContractReviewStepAttemptPersistenceRow> findClaimCandidates(
            @Param("limit") int limit, @Param("now") LocalDateTime now);
    List<ContractReviewStepAttemptPersistenceRow> findExpiredLeases(
            @Param("limit") int limit, @Param("now") LocalDateTime now);
    int compareAndSetAttempt(@Param("expected") ContractReviewStepAttemptPersistenceRow expected,
            @Param("next") ContractReviewStepAttemptPersistenceRow next,
            @Param("condition") String condition, @Param("now") LocalDateTime now);
}
