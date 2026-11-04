package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ProjectFileRoleEvidenceRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleEvidence;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProjectFileRoleEvidenceRepository implements ProjectFileRoleEvidenceRepository {
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());

    @Override
    public ProjectFileRoleEvidence insertIfAbsent(ProjectFileRoleEvidence evidence) {
        return insertBatchIfAbsent(List.of(evidence)).get(0);
    }

    @Override
    public List<ProjectFileRoleEvidence> insertBatchIfAbsent(List<ProjectFileRoleEvidence> evidence) {
        List<ProjectFileRoleEvidence> proposed = List.copyOf(evidence);
        while (true) {
            State current = state.get();
            Map<UUID, ProjectFileRoleEvidence> nextById = new HashMap<>(current.byId());
            Map<SignalIdentity, ProjectFileRoleEvidence> nextBySignal = new HashMap<>(current.bySignal());
            java.util.ArrayList<ProjectFileRoleEvidence> winners = new java.util.ArrayList<>(proposed.size());
            for (ProjectFileRoleEvidence item : proposed) {
                ProjectFileRoleEvidence idExisting = nextById.get(item.evidenceId());
                if (idExisting != null) {
                    if (!idExisting.sameIdentityFact(item)) {
                        throw new IllegalStateException("role evidence identity conflict");
                    }
                    winners.add(idExisting);
                    continue;
                }
                SignalIdentity key = identity(item);
                ProjectFileRoleEvidence signalWinner = nextBySignal.get(key);
                if (signalWinner != null) {
                    if (!signalWinner.sameSignalFact(item)) {
                        throw new IllegalStateException("role evidence identity conflict");
                    }
                    winners.add(signalWinner);
                    continue;
                }
                nextById.put(item.evidenceId(), item);
                nextBySignal.put(key, item);
                winners.add(item);
            }
            if (state.compareAndSet(current, new State(Map.copyOf(nextById), Map.copyOf(nextBySignal)))) {
                return List.copyOf(winners);
            }
        }
    }

    @Override public Optional<ProjectFileRoleEvidence> findById(UUID evidenceId) {
        return Optional.ofNullable(state.get().byId().get(evidenceId));
    }

    @Override public List<ProjectFileRoleEvidence> findByAssessmentId(UUID assessmentId) {
        return state.get().bySignal().values().stream().filter(value -> value.assessmentId().equals(assessmentId))
                .sorted(Comparator.comparing(ProjectFileRoleEvidence::sourceLocator)
                        .thenComparing(value -> value.evidenceId().toString())).toList();
    }

    private static SignalIdentity identity(ProjectFileRoleEvidence value) {
        return new SignalIdentity(value.assessmentId(), value.role().name(), value.ruleCode(), value.sourceLocator());
    }

    private record State(Map<UUID, ProjectFileRoleEvidence> byId,
                         Map<SignalIdentity, ProjectFileRoleEvidence> bySignal) {
        private static State empty() {
            return new State(Map.of(), Map.of());
        }
    }

    private record SignalIdentity(UUID assessmentId, String role, String ruleCode, String sourceLocator) { }
}
