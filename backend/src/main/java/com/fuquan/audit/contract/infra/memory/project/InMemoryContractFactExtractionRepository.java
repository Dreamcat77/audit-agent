package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionCandidate;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRepository;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRejection;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryContractFactExtractionRepository implements ContractFactExtractionRepository {
    private final Map<UUID, ContractFactExtractionAttempt> attempts = new ConcurrentHashMap<>();
    private final Map<String, UUID> byOperation = new ConcurrentHashMap<>();
    private final Map<UUID, List<ContractFactExtractionCandidate>> candidates = new ConcurrentHashMap<>();
    private final Map<UUID, List<ContractFactExtractionRejection>> rejections = new ConcurrentHashMap<>();
    private final Map<UUID, List<ContractModelCallAudit>> modelCalls = new ConcurrentHashMap<>();

    @Override
    public synchronized Claim claim(ContractFactExtractionAttempt proposed) {
        String operationIdentity = operationIdentity(proposed.actorId(), proposed.operationKey());
        UUID existingId = byOperation.get(operationIdentity);
        if (existingId == null) {
            attempts.put(proposed.attemptId(), proposed);
            byOperation.put(operationIdentity, proposed.attemptId());
            return new Claim(true, proposed);
        }
        ContractFactExtractionAttempt existing = attempts.get(existingId);
        if (existing.status() == ContractFactExtractionAttempt.Status.FAILED) {
            ContractFactExtractionAttempt retried = new ContractFactExtractionAttempt(existing.attemptId(),
                    existing.projectId(), existing.fileId(), existing.fileSha256(), existing.parseResultId(),
                    existing.parseVersion(), existing.canonicalTextSha256(), existing.effectiveRole(),
                    existing.roleDecisionId(), existing.identityDecisionId(), existing.inputFingerprint(),
                    existing.actorId(), existing.operationKey(), existing.provenance(),
                    ContractFactExtractionAttempt.Status.IN_PROGRESS, existing.callCount() + 1,
                    0, 0, null, existing.createdAt(), proposed.updatedAt(), existing.versionNo() + 1);
            attempts.put(existingId, retried);
            candidates.remove(existingId);
            rejections.remove(existingId);
            modelCalls.remove(existingId);
            return new Claim(true, retried);
        }
        return new Claim(false, existing);
    }

    @Override public Optional<ContractFactExtractionAttempt> findById(UUID attemptId) {
        return Optional.ofNullable(attempts.get(attemptId));
    }
    @Override public Optional<ContractFactExtractionAttempt> findByOperation(String actorId,
            String operationKey) {
        return Optional.ofNullable(byOperation.get(operationIdentity(actorId, operationKey)))
                .map(attempts::get);
    }
    @Override public Optional<ContractFactExtractionAttempt> findLatest(UUID projectId, UUID fileId,
            UUID parseResultId) {
        return attempts.values().stream().filter(a -> a.projectId().equals(projectId)
                        && a.fileId().equals(fileId) && a.parseResultId().equals(parseResultId))
                .max(Comparator.comparing(ContractFactExtractionAttempt::updatedAt));
    }
    @Override public List<ContractFactExtractionCandidate> findCandidates(UUID attemptId) {
        return candidates.getOrDefault(attemptId, List.of());
    }
    @Override public List<ContractFactExtractionRejection> findRejections(UUID attemptId) {
        return rejections.getOrDefault(attemptId, List.of());
    }
    @Override public List<ContractModelCallAudit> findModelCalls(UUID attemptId) {
        return modelCalls.getOrDefault(attemptId, List.of());
    }
    @Override public Optional<ContractFactExtractionAttempt> findLatestForAuthority(UUID projectId,
            UUID fileId, UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
            DocumentRole effectiveRole, Provenance provenance) {
        return attempts.values().stream().filter(a -> a.projectId().equals(projectId)
                        && a.fileId().equals(fileId) && a.parseResultId().equals(parseResultId)
                        && a.roleDecisionId().equals(roleDecisionId)
                        && a.identityDecisionId().equals(identityDecisionId)
                        && a.effectiveRole() == effectiveRole && a.provenance().equals(provenance))
                .max(Comparator.comparing(ContractFactExtractionAttempt::updatedAt));
    }
    @Override public synchronized void complete(ContractFactExtractionAttempt expected,
            List<ContractFactExtractionCandidate> rows, int accepted, int rejected, Instant completedAt) {
        ContractFactExtractionAttempt current = requireExpected(expected);
        this.candidates.put(expected.attemptId(), List.copyOf(rows));
        attempts.put(expected.attemptId(), current.completed(
                ContractFactExtractionAttempt.Status.SUCCEEDED, accepted, rejected, null, completedAt));
    }
    @Override public synchronized void completeV2(ContractFactExtractionAttempt expected,
            List<ContractFactExtractionCandidate> rows,
            List<ContractFactExtractionRejection> rejectedRows,
            List<ContractModelCallAudit> calls, int accepted, int rejected, Instant completedAt) {
        ContractFactExtractionAttempt current = requireExpected(expected);
        candidates.put(expected.attemptId(), List.copyOf(rows));
        rejections.put(expected.attemptId(), List.copyOf(rejectedRows));
        modelCalls.put(expected.attemptId(), List.copyOf(calls));
        attempts.put(expected.attemptId(), current.completed(
                ContractFactExtractionAttempt.Status.SUCCEEDED, accepted, rejected, null, completedAt));
    }
    @Override public synchronized void fail(ContractFactExtractionAttempt expected,
            String reason, Instant failedAt) {
        ContractFactExtractionAttempt current = requireExpected(expected);
        attempts.put(expected.attemptId(), current.completed(
                ContractFactExtractionAttempt.failureStatus(reason), 0, 0, reason, failedAt));
    }
    @Override public synchronized void failV2(ContractFactExtractionAttempt expected,
            List<ContractFactExtractionRejection> rejectedRows,
            List<ContractModelCallAudit> calls, int rejected, String reason, Instant failedAt) {
        ContractFactExtractionAttempt current = requireExpected(expected);
        rejections.put(expected.attemptId(), merge(rejections.get(expected.attemptId()), rejectedRows));
        modelCalls.put(expected.attemptId(), merge(modelCalls.get(expected.attemptId()), calls));
        attempts.put(expected.attemptId(), current.completed(
                ContractFactExtractionAttempt.failureStatus(reason), 0, rejected, reason, failedAt));
    }

    private static <T> List<T> merge(List<T> existing, List<T> added) {
        java.util.ArrayList<T> values = new java.util.ArrayList<>();
        if (existing != null) values.addAll(existing);
        values.addAll(added);
        return List.copyOf(values);
    }

    private ContractFactExtractionAttempt requireExpected(ContractFactExtractionAttempt expected) {
        ContractFactExtractionAttempt current = attempts.get(expected.attemptId());
        if (current == null || current.status() != ContractFactExtractionAttempt.Status.IN_PROGRESS
                || current.versionNo() != expected.versionNo()) {
            throw new IllegalStateException("contract fact extraction state changed");
        }
        return current;
    }

    private static String operationIdentity(String actorId, String operationKey) {
        return actorId + "\u0000" + operationKey;
    }
}
