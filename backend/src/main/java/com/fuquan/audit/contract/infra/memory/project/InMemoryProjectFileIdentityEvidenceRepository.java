package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityEvidenceRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityEvidence;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProjectFileIdentityEvidenceRepository implements ProjectFileIdentityEvidenceRepository {
    private final Map<UUID, List<ProjectFileIdentityEvidence>> byAssessment = new HashMap<>();
    private final Set<UUID> staged = new HashSet<>();
    @Override public synchronized void insertAll(UUID id, List<ProjectFileIdentityEvidence> values) {
        List<ProjectFileIdentityEvidence> immutable = List.copyOf(values);
        if (immutable.stream().anyMatch(value -> !id.equals(value.assessmentId())))
            throw new IllegalArgumentException("identity evidence assessment mismatch");
        List<ProjectFileIdentityEvidence> prior = byAssessment.putIfAbsent(id, immutable);
        if (prior != null && !prior.equals(immutable)) throw new IllegalStateException("identity evidence conflict");
    }
    @Override public synchronized void stageInsertAll(UUID id, List<ProjectFileIdentityEvidence> values) {
        insertAll(id, values);
        staged.add(id);
    }
    @Override public synchronized void commitStagedInsert(UUID id) { staged.remove(id); }
    @Override public synchronized void rollbackUncommittedInsert(UUID id) {
        if (staged.remove(id)) byAssessment.remove(id);
    }
    @Override public synchronized List<ProjectFileIdentityEvidence> findByAssessmentId(UUID id) {
        return staged.contains(id) ? List.of() : byAssessment.getOrDefault(id, List.of());
    }
}
