package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractReviewResultsMapper {
    int insertFinding(@Param("row") ContractReviewFindingPersistenceRow row);
    int insertEvidence(@Param("row") ContractReviewEvidencePersistenceRow row);
    List<ContractReviewFindingPersistenceRow> findFindingsByAttemptId(@Param("attemptId") String attemptId);
    List<ContractReviewFindingPersistenceRow> findFindingsByRunId(@Param("runId") String runId);
    List<ContractReviewEvidencePersistenceRow> findEvidenceByAttemptId(@Param("attemptId") String attemptId);
    List<ContractReviewEvidencePersistenceRow> findEvidenceByRunId(@Param("runId") String runId);
    int insertClauseComparison(@Param("row") ContractReviewClauseComparisonPersistenceRow row);
    int insertClauseValue(@Param("row") ContractReviewClauseValuePersistenceRow row);
    int insertClauseValueEvidence(@Param("valueId") String valueId,
            @Param("evidenceId") String evidenceId);
    List<ContractReviewClauseComparisonPersistenceRow> findClauseComparisonsByAttemptId(
            @Param("attemptId") String attemptId);
    List<ContractReviewClauseComparisonPersistenceRow> findClauseComparisonsByRunId(
            @Param("runId") String runId);
    List<ContractReviewClauseValuePersistenceRow> findClauseValues(
            @Param("comparisonId") String comparisonId);
}
