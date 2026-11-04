package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectFileRoleEvidence;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectFileRoleEvidenceRepository {
    ProjectFileRoleEvidence insertIfAbsent(ProjectFileRoleEvidence evidence);
    default List<ProjectFileRoleEvidence> insertBatchIfAbsent(List<ProjectFileRoleEvidence> evidence) {
        return evidence.stream().map(this::insertIfAbsent).toList();
    }
    Optional<ProjectFileRoleEvidence> findById(UUID evidenceId);
    List<ProjectFileRoleEvidence> findByAssessmentId(UUID assessmentId);
}
