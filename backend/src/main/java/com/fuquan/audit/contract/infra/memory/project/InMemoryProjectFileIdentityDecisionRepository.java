package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProjectFileIdentityDecisionRepository implements ProjectFileIdentityDecisionRepository {
    private final Map<UUID, ProjectFileIdentityDecision> byId = new HashMap<>();
    private final Map<StreamVersion, UUID> byVersion = new HashMap<>();
    private final Set<UUID> staged = new HashSet<>();
    @Override public synchronized ProjectFileIdentityDecision insertIfAbsent(ProjectFileIdentityDecision value) {
        var prior = byId.get(value.decisionId());
        if (prior != null) {
            if (!prior.equals(value)) throw new IllegalStateException("identity decision ID conflict");
            return prior;
        }
        var stream = new StreamVersion(value.fileId(), value.versionNo());
        var winnerId = byVersion.get(stream);
        if (winnerId != null) {
            var winner = byId.get(winnerId);
            if (!winner.equals(value)) throw new IllegalStateException("identity decision stream conflict");
            return winner;
        }
        byId.put(value.decisionId(), value);
        byVersion.put(stream, value.decisionId());
        return value;
    }
    @Override public synchronized ProjectFileIdentityDecision stageInsertIfAbsent(ProjectFileIdentityDecision value) {
        ProjectFileIdentityDecision stored = insertIfAbsent(value);
        if (stored == value) staged.add(value.decisionId());
        return stored;
    }
    @Override public synchronized void commitStagedInsert(ProjectFileIdentityDecision value) {
        if (!value.equals(byId.get(value.decisionId()))) throw new IllegalStateException("staged identity decision mismatch");
        staged.remove(value.decisionId());
    }
    @Override public synchronized void rollbackUncommittedInsert(ProjectFileIdentityDecision value) {
        if (staged.remove(value.decisionId()) && value.equals(byId.get(value.decisionId()))) {
            byId.remove(value.decisionId());
            byVersion.remove(new StreamVersion(value.fileId(), value.versionNo()));
        }
    }
    @Override public synchronized Optional<ProjectFileIdentityDecision> findById(UUID id) {
        return staged.contains(id) ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }
    @Override public synchronized Optional<ProjectFileIdentityDecision> findByFileIdAndVersion(UUID fileId, long version) {
        return findByIdNullable(byVersion.get(new StreamVersion(fileId, version)));
    }
    @Override public synchronized Optional<ProjectFileIdentityDecision> findLatestByFileId(UUID fileId) {
        return byId.values().stream().filter(value -> value.fileId().equals(fileId)
                        && !staged.contains(value.decisionId()))
                .max(Comparator.comparingLong(ProjectFileIdentityDecision::versionNo)
                        .thenComparing(value -> value.decisionId().toString()));
    }
    private Optional<ProjectFileIdentityDecision> findByIdNullable(UUID id) {
        return id == null ? Optional.empty() : findById(id);
    }
    private record StreamVersion(UUID fileId, long version) { }
}
