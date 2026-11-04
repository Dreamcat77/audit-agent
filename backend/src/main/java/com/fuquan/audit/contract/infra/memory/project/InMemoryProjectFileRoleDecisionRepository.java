package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProjectFileRoleDecisionRepository implements ProjectFileRoleDecisionRepository {
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());

    @Override
    public ProjectFileRoleDecision insertIfAbsent(ProjectFileRoleDecision decision) {
        return insert(decision, false);
    }

    @Override
    public ProjectFileRoleDecision stageInsertIfAbsent(ProjectFileRoleDecision decision) {
        return insert(decision, true);
    }

    private ProjectFileRoleDecision insert(ProjectFileRoleDecision decision, boolean stagedInsert) {
        StreamVersion key = new StreamVersion(decision.fileId(), decision.versionNo());
        while (true) {
            State current = state.get();
            ProjectFileRoleDecision idExisting = current.byId().get(decision.decisionId());
            if (idExisting != null) {
                if (!idExisting.equals(decision)) {
                    throw new IllegalStateException("role decision identity conflict");
                }
                return idExisting;
            }
            ProjectFileRoleDecision streamWinner = current.byVersion().get(key);
            if (streamWinner != null) {
                if (!streamWinner.sameStreamFact(decision)) {
                    throw new IllegalStateException("role decision stream conflict");
                }
                return streamWinner;
            }
            Map<UUID, ProjectFileRoleDecision> nextById = new HashMap<>(current.byId());
            Map<StreamVersion, ProjectFileRoleDecision> nextByVersion = new HashMap<>(current.byVersion());
            Set<UUID> nextStaged = new java.util.HashSet<>(current.staged());
            nextById.put(decision.decisionId(), decision);
            nextByVersion.put(key, decision);
            if (stagedInsert) nextStaged.add(decision.decisionId());
            if (state.compareAndSet(current, new State(Map.copyOf(nextById), Map.copyOf(nextByVersion),
                    Set.copyOf(nextStaged)))) {
                return decision;
            }
        }
    }

    @Override
    public void commitStagedInsert(ProjectFileRoleDecision decision) {
        while (true) {
            State current = state.get();
            if (!decision.equals(current.byId().get(decision.decisionId()))) {
                throw new IllegalStateException("staged role decision identity is inconsistent");
            }
            if (!current.staged().contains(decision.decisionId())) return;
            Set<UUID> nextStaged = new java.util.HashSet<>(current.staged());
            nextStaged.remove(decision.decisionId());
            if (state.compareAndSet(current,
                    new State(current.byId(), current.byVersion(), Set.copyOf(nextStaged)))) return;
        }
    }

    @Override
    public void rollbackUncommittedInsert(ProjectFileRoleDecision decision) {
        StreamVersion key = new StreamVersion(decision.fileId(), decision.versionNo());
        while (true) {
            State current = state.get();
            if (!decision.equals(current.byId().get(decision.decisionId()))
                    || !decision.equals(current.byVersion().get(key))
                    || !current.staged().contains(decision.decisionId())) return;
            Map<UUID, ProjectFileRoleDecision> nextById = new HashMap<>(current.byId());
            Map<StreamVersion, ProjectFileRoleDecision> nextByVersion = new HashMap<>(current.byVersion());
            Set<UUID> nextStaged = new java.util.HashSet<>(current.staged());
            nextById.remove(decision.decisionId());
            nextByVersion.remove(key);
            nextStaged.remove(decision.decisionId());
            if (state.compareAndSet(current, new State(Map.copyOf(nextById), Map.copyOf(nextByVersion),
                    Set.copyOf(nextStaged)))) return;
        }
    }

    @Override public Optional<ProjectFileRoleDecision> findById(UUID decisionId) {
        State current = state.get();
        if (current.staged().contains(decisionId)) return Optional.empty();
        return Optional.ofNullable(current.byId().get(decisionId));
    }

    @Override public Optional<ProjectFileRoleDecision> findByFileIdAndVersion(UUID fileId, long versionNo) {
        State current = state.get();
        ProjectFileRoleDecision decision = current.byVersion().get(new StreamVersion(fileId, versionNo));
        return decision == null || current.staged().contains(decision.decisionId())
                ? Optional.empty() : Optional.of(decision);
    }

    @Override public Optional<ProjectFileRoleDecision> findLatestByFileId(UUID fileId) {
        State current = state.get();
        return current.byVersion().values().stream().filter(value -> value.fileId().equals(fileId)
                        && !current.staged().contains(value.decisionId()))
                .max(Comparator.comparingLong(ProjectFileRoleDecision::versionNo)
                        .thenComparing(value -> value.decisionId().toString()));
    }

    private record State(Map<UUID, ProjectFileRoleDecision> byId,
                         Map<StreamVersion, ProjectFileRoleDecision> byVersion,
                         Set<UUID> staged) {
        private static State empty() {
            return new State(Map.of(), Map.of(), Set.of());
        }
    }

    private record StreamVersion(UUID fileId, long versionNo) { }
}
