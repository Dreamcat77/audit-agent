package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
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
public class InMemoryProjectFileIdentityAssessmentRepository implements ProjectFileIdentityAssessmentRepository {
    private final Map<UUID, ProjectFileIdentityAssessment> byId = new HashMap<>();
    private final Set<UUID> staged = new HashSet<>();

    @Override public synchronized ProjectFileIdentityAssessment insertIfAbsent(ProjectFileIdentityAssessment value) {
        ProjectFileIdentityAssessment existing = byId.putIfAbsent(value.assessmentId(), value);
        if (existing != null && !existing.equals(value)) throw new IllegalStateException("identity assessment ID conflict");
        return existing == null ? value : existing;
    }
    @Override public synchronized ProjectFileIdentityAssessment stageInsertIfAbsent(ProjectFileIdentityAssessment value) {
        ProjectFileIdentityAssessment stored = insertIfAbsent(value);
        if (stored == value) staged.add(value.assessmentId());
        return stored;
    }
    @Override public synchronized void commitStagedInsert(ProjectFileIdentityAssessment value) {
        if (!value.equals(byId.get(value.assessmentId()))) throw new IllegalStateException("staged identity assessment mismatch");
        staged.remove(value.assessmentId());
    }
    @Override public synchronized void rollbackUncommittedInsert(ProjectFileIdentityAssessment value) {
        if (staged.remove(value.assessmentId()) && value.equals(byId.get(value.assessmentId())))
            byId.remove(value.assessmentId());
    }
    @Override public synchronized Optional<ProjectFileIdentityAssessment> findById(UUID id) {
        return staged.contains(id) ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }
    @Override public synchronized Optional<ProjectFileIdentityAssessment> findLatestByFileId(UUID fileId) {
        return byId.values().stream().filter(value -> value.fileId().equals(fileId)
                        && !staged.contains(value.assessmentId()))
                .max(Comparator.comparing(ProjectFileIdentityAssessment::createdAt)
                        .thenComparing(value -> value.assessmentId().toString()));
    }
}
