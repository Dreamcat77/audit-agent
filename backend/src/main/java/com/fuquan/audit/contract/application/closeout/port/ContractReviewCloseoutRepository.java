package com.fuquan.audit.contract.application.closeout.port;

import com.fuquan.audit.contract.domain.closeout.ContractReviewFindingAcknowledgement;
import com.fuquan.audit.contract.domain.closeout.ContractReviewReport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractReviewCloseoutRepository {
    void insertAcknowledgement(ContractReviewFindingAcknowledgement value);
    Optional<ContractReviewFindingAcknowledgement> findAcknowledgement(UUID id);
    Optional<ContractReviewFindingAcknowledgement> findAcknowledgementByFinding(UUID findingId);
    List<ContractReviewFindingAcknowledgement> findAcknowledgementsByRun(UUID runId);
    void insertReport(ContractReviewReport value);
    Optional<ContractReviewReport> findReport(UUID id);
    List<ContractReviewReport> findReportsByRun(UUID runId);
    int nextReportVersion(UUID runId);
    boolean compareAndSetReport(ContractReviewReport expected, ContractReviewReport next);
    /** Local profile rollback seam for unpublished facts; DM8 uses its transaction rollback. */
    default void rollbackUncommittedAcknowledgement(ContractReviewFindingAcknowledgement value) { }
    default void rollbackUncommittedReport(ContractReviewReport before, ContractReviewReport unpublished) { }
}
