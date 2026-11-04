package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.domain.closeout.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractReviewCloseoutMapper {
    int insertAcknowledgement(AcknowledgementRow row);
    AcknowledgementRow findAcknowledgement(String id);
    AcknowledgementRow findAcknowledgementByFinding(String id);
    List<AcknowledgementRow> findAcknowledgementsByRun(String id);
    int insertReport(ReportRow row);
    ReportRow findReport(String id);
    List<ReportRow> findReportsByRun(String id);
    Integer maxReportVersion(String id);
    int updateReport(@Param("expected") ReportRow expected, @Param("next") ReportRow next);
    record AcknowledgementRow(String acknowledgementId, String projectId, String contractId, String reviewRunId, String reviewStepId, String attemptId, String findingId, long findingVersionNo, String acknowledgedBy, LocalDateTime acknowledgedAt, String handlingNote, String requestId, String traceId, LocalDateTime createdAt, long versionNo) {
        public static AcknowledgementRow from(ContractReviewFindingAcknowledgement v) {
            return new AcknowledgementRow(v.acknowledgementId().toString(), v.projectId().toString(), v.contractId().toString(), v.reviewRunId().toString(), v.reviewStepId().toString(), v.attemptId().toString(), v.findingId().toString(), v.findingVersionNo(), v.acknowledgedBy(), date(v.acknowledgedAt()), v.handlingNote(), v.requestId(), v.traceId(), date(v.createdAt()), v.versionNo());
        }
        public ContractReviewFindingAcknowledgement toDomain() {
            return new ContractReviewFindingAcknowledgement(UUID.fromString(acknowledgementId), UUID.fromString(projectId), UUID.fromString(contractId), UUID.fromString(reviewRunId), UUID.fromString(reviewStepId), UUID.fromString(attemptId), UUID.fromString(findingId), findingVersionNo, acknowledgedBy, instant(acknowledgedAt), handlingNote, requestId, traceId, instant(createdAt), versionNo);
        }
    }
    record ReportRow(String reportId, String reviewRunId, int reportVersion, int reportSchemaVersion, String status, String inputSnapshotIdentity, String findingSnapshotIdentity, String acknowledgementSnapshotIdentity, String snapshotJson, String artifactStorageKey, String artifactMediaType, Long artifactSize, String sha256, String generatedBy, LocalDateTime requestedAt, LocalDateTime generatedAt, LocalDateTime failedAt, LocalDateTime unknownAt, String safeReasonCode, String requestId, String traceId, long versionNo) {
        public static ReportRow from(ContractReviewReport v) {
            return new ReportRow(v.reportId().toString(), v.reviewRunId().toString(), v.reportVersion(), v.reportSchemaVersion(), v.status().name(), v.inputSnapshotIdentity(), v.findingSnapshotIdentity(), v.acknowledgementSnapshotIdentity(), v.snapshotJson(), v.artifactStorageKey(), v.artifactMediaType(), v.artifactSize(), v.sha256(), v.generatedBy(), date(v.requestedAt()), date(v.generatedAt()), date(v.failedAt()), date(v.unknownAt()), v.safeReasonCode(), v.requestId(), v.traceId(), v.versionNo());
        }
        public ContractReviewReport toDomain() {
            return new ContractReviewReport(UUID.fromString(reportId), UUID.fromString(reviewRunId), reportVersion, reportSchemaVersion, ContractReviewReport.Status.valueOf(status), inputSnapshotIdentity, findingSnapshotIdentity, acknowledgementSnapshotIdentity, snapshotJson, artifactStorageKey, artifactMediaType, artifactSize, sha256, generatedBy, instant(requestedAt), instant(generatedAt), instant(failedAt), instant(unknownAt), safeReasonCode, requestId, traceId, versionNo);
        }
    }
    private static LocalDateTime date(Instant v) { return v == null ? null : LocalDateTime.ofInstant(v, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime v) { return v == null ? null : v.toInstant(ZoneOffset.UTC); }
}
