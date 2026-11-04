package com.fuquan.audit.contract.application.reviewrun.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Private semantic execution records, never authoritative business findings. */
public final class SemanticCheckpointTypes {
    private SemanticCheckpointTypes() { }
    public enum Purpose { ANALYZE, REPAIR, SYNTHESIZE }
    public enum UnitStatus { PLANNED, CLAIMED, SENT, VERIFIED, REPAIR_REQUIRED, FAILED, UNKNOWN }
    public enum PlanStatus { ACTIVE, COMPLETE }
    public record Parent(UUID runId, UUID stepId, UUID attemptId, String leaseOwner) {
        public Parent { Objects.requireNonNull(runId); Objects.requireNonNull(stepId);
            Objects.requireNonNull(attemptId); text(leaseOwner, 128); }
    }
    public record Access(Parent parent, UUID planId, long fence) {
        public Access { Objects.requireNonNull(parent); Objects.requireNonNull(planId);
            if (fence < 1) throw new IllegalArgumentException("invalid checkpoint fence"); }
    }
    public record PlanSpec(String inputFingerprint, String policyFingerprint, String planJson,
            Instant deadlineAt, long timeLimitMs, int callLimit, int repairLimit) {
        public PlanSpec { hash(inputFingerprint); hash(policyFingerprint); json(planJson);
            Objects.requireNonNull(deadlineAt);
            if (timeLimitMs < 1 || callLimit < 1 || repairLimit < 0 || repairLimit > callLimit)
                throw new IllegalArgumentException("invalid semantic budget"); }
    }
    public record UnitSpec(String logicalKey, int ordinal, Purpose purpose,
            String inputFingerprint, List<String> dependencies) {
        public UnitSpec { hash(logicalKey); hash(inputFingerprint); Objects.requireNonNull(purpose);
            dependencies = List.copyOf(dependencies); dependencies.forEach(SemanticCheckpointTypes::hash);
            if (ordinal < 1 || purpose == Purpose.REPAIR || dependencies.contains(logicalKey)
                    || new HashSet<>(dependencies).size() != dependencies.size())
                throw new IllegalArgumentException("invalid initial semantic unit"); }
    }
    public record CallMetadata(UUID callAuditId, String provider, String model, String requestFingerprint) {
        public CallMetadata { Objects.requireNonNull(callAuditId); text(provider, 128); text(model, 200);
            hash(requestFingerprint); }
    }
    public record CallUsage(String finishReason, Integer promptTokens, Integer completionTokens,
            Integer totalTokens) {
        public CallUsage { if (finishReason != null && !Set.of("STOP", "LENGTH", "CONTENT_FILTER",
                "TOOL_CALLS", "UNKNOWN", "MISSING").contains(finishReason))
                throw new IllegalArgumentException("invalid finish reason");
            for (Integer count : new Integer[]{promptTokens, completionTokens, totalTokens})
                if (count != null && count < 0) throw new IllegalArgumentException("negative usage"); }
    }
    public record Plan(UUID planId, UUID runId, UUID stepId, UUID originalAttemptId,
            UUID currentAttemptId, PlanSpec spec, String owner, long fence, Instant leaseUntil,
            PlanStatus status, int usedCalls, int reservedCalls, int usedRepairs, int reservedRepairs,
            long usedMs, long reservedMs, String finalResultSha256, long versionNo,
            Instant createdAt, Instant updatedAt) {
        public Plan { Objects.requireNonNull(planId); Objects.requireNonNull(runId); Objects.requireNonNull(stepId);
            Objects.requireNonNull(originalAttemptId); Objects.requireNonNull(currentAttemptId);
            Objects.requireNonNull(spec); Objects.requireNonNull(status); text(owner, 128);
            Objects.requireNonNull(leaseUntil); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
            if (fence < 1 || versionNo < 0 || usedCalls < 0 || reservedCalls < 0 || usedRepairs < 0
                    || reservedRepairs < 0 || usedMs < 0 || reservedMs < 0
                    || (long) usedCalls + reservedCalls > spec.callLimit()
                    || (long) usedRepairs + reservedRepairs > spec.repairLimit())
                throw new IllegalArgumentException("invalid durable budget");
            if (status == PlanStatus.COMPLETE) hash(finalResultSha256);
        }
        public Plan change(UUID attemptId, String newOwner, long newFence, Instant until,
                PlanStatus nextStatus, int calls, int callsReserved, int repairs, int repairsReserved,
                long milliseconds, long millisecondsReserved, String resultHash, Instant now) {
            return new Plan(planId, runId, stepId, originalAttemptId, attemptId, spec, newOwner,
                    newFence, until, nextStatus, calls, callsReserved, repairs, repairsReserved,
                    milliseconds, millisecondsReserved, resultHash, versionNo + 1, createdAt, now);
        }
    }
    public record Unit(UUID executionId, UUID planId, String logicalKey, int executionNo,
            int ordinal, Purpose purpose, UUID attemptId, String inputFingerprint,
            List<String> dependencies, UnitStatus status, String owner, long fence,
            Instant leaseUntil, long reservedMs, long durationMs, String resultJson,
            String resultSha256, String safeReason, CallMetadata call, int callOrdinal,
            long versionNo, Instant createdAt, Instant updatedAt) {
        public Unit { Objects.requireNonNull(executionId); Objects.requireNonNull(planId); hash(logicalKey);
            Objects.requireNonNull(purpose); Objects.requireNonNull(attemptId); hash(inputFingerprint);
            dependencies = List.copyOf(dependencies); dependencies.forEach(SemanticCheckpointTypes::hash);
            Objects.requireNonNull(status); text(owner, 128); Objects.requireNonNull(leaseUntil);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
            if (executionNo < 1 || ordinal < 1 || fence < 1 || reservedMs < 0 || durationMs < 0 || versionNo < 0)
                throw new IllegalArgumentException("invalid durable unit");
            reason(safeReason);
            if (resultJson != null) { json(resultJson); hash(resultSha256);
                if (!digest(resultJson).equals(resultSha256)) throw new IllegalArgumentException("result digest mismatch"); }
            if (status == UnitStatus.VERIFIED && resultJson == null)
                throw new IllegalArgumentException("verified unit requires private result");
            if (status != UnitStatus.PLANNED && status != UnitStatus.CLAIMED && (call == null || callOrdinal < 1))
                throw new IllegalArgumentException("execution requires call identity");
        }
        public Unit finish(UnitStatus next, long duration, String result, String hash, String reason, Instant now) {
            return new Unit(executionId, planId, logicalKey, executionNo, ordinal, purpose, attemptId,
                    inputFingerprint, dependencies, next, owner, fence, leaseUntil, reservedMs,
                    duration, result, hash, reason, call, callOrdinal, versionNo + 1, createdAt, now);
        }
    }
    public static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid SHA256");
    }
    public static void reason(String value) {
        if (value != null && !value.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("invalid safe reason");
    }
    public static void text(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException("invalid bounded text");
    }
    public static void json(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 262144)
            throw new IllegalArgumentException("private JSON exceeds bound");
        try { if (new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(value) == null) throw new IllegalArgumentException("missing JSON"); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("invalid private JSON"); }
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
