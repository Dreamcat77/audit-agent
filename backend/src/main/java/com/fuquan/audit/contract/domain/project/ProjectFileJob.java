package com.fuquan.audit.contract.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable parsing identity; never an AuditTask identity. */
public record ProjectFileJob(UUID id, UUID projectId, UUID fileId, String fileSha256, String actorId,
        String requestId, String traceId, String sourceIp, String status, int attemptNo,
        String leaseToken, Instant leaseUntil, String reasonCode, UUID parseResultId,
        Instant createdAt, Instant updatedAt) {
    public ProjectFileJob {
        Objects.requireNonNull(id); Objects.requireNonNull(projectId); Objects.requireNonNull(fileId);
        Objects.requireNonNull(actorId); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (fileSha256 == null || !fileSha256.matches("[a-f0-9]{64}") || attemptNo < 0
                || !Set.of("QUEUED", "PROCESSING", "SUCCEEDED", "FAILED", "UNKNOWN").contains(status)) {
            throw new IllegalArgumentException("invalid file processing identity or state");
        }
        if ("PROCESSING".equals(status)
                ? leaseToken == null || leaseToken.isBlank() || leaseUntil == null
                : leaseToken != null || leaseUntil != null) {
            throw new IllegalArgumentException("processing requires an exact lease");
        }
    }
    public static ProjectFileJob queued(UUID projectId, UUID fileId, String sha, String actorId,
            String requestId,String traceId,String sourceIp, Instant now) {
        return new ProjectFileJob(UUID.randomUUID(), projectId, fileId, sha, actorId, requestId,
                traceId, sourceIp, "QUEUED", 0, null, null, null, null, now, now);
    }
    public ProjectFileJob claim(String token, Instant now, Instant until) {
        return new ProjectFileJob(id, projectId, fileId, fileSha256, actorId, requestId, traceId, sourceIp,
                "PROCESSING", attemptNo+1, token, until, null, parseResultId, createdAt, now);
    }
    public ProjectFileJob renew(Instant until, Instant now) {
        return new ProjectFileJob(id, projectId, fileId, fileSha256, actorId, requestId, traceId, sourceIp,
                status, attemptNo, leaseToken, until, reasonCode, parseResultId, createdAt, now);
    }
    public ProjectFileJob finish(String next, String reason, UUID output, Instant now) {
        return new ProjectFileJob(id, projectId, fileId, fileSha256, actorId, requestId, traceId, sourceIp,
                next, attemptNo, null, null, reason, output, createdAt, now);
    }
    public record Attempt(UUID jobId, int attemptNo, String leaseToken, String status,
                          String reasonCode, Instant startedAt, Instant completedAt) {}
}
