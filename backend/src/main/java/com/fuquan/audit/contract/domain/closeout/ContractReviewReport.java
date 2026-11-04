package com.fuquan.audit.contract.domain.closeout;

import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import java.util.List;

/** A version's snapshot and artifact identity remain frozen across recovery. */
public record ContractReviewReport(UUID reportId, UUID reviewRunId, int reportVersion,
        int reportSchemaVersion, Status status, String inputSnapshotIdentity,
        String findingSnapshotIdentity, String acknowledgementSnapshotIdentity, String snapshotJson,
        String artifactStorageKey, String artifactMediaType, Long artifactSize, String sha256,
        String generatedBy, Instant requestedAt, Instant generatedAt, Instant failedAt,
        Instant unknownAt, String safeReasonCode, String requestId, String traceId, long versionNo) {
    public enum Status { PENDING, GENERATING, AVAILABLE, FAILED, UNKNOWN }
    public void requireMutableTransitionTo(ContractReviewReport next) {
        if (status == Status.AVAILABLE || status == Status.FAILED || next.versionNo != versionNo + 1
                || !List.of(reportId, reviewRunId, reportVersion, reportSchemaVersion, inputSnapshotIdentity,
                        findingSnapshotIdentity, acknowledgementSnapshotIdentity, snapshotJson, generatedBy, requestedAt, requestId, traceId)
                    .equals(List.of(next.reportId, next.reviewRunId, next.reportVersion, next.reportSchemaVersion,
                        next.inputSnapshotIdentity, next.findingSnapshotIdentity, next.acknowledgementSnapshotIdentity,
                        next.snapshotJson, next.generatedBy, next.requestedAt, next.requestId, next.traceId))
                || artifactStorageKey != null && (!Objects.equals(artifactStorageKey, next.artifactStorageKey)
                    || !Objects.equals(artifactMediaType, next.artifactMediaType) || !Objects.equals(artifactSize, next.artifactSize)
                    || !Objects.equals(sha256, next.sha256))) throw new IllegalArgumentException("report version is immutable");
    }
}
