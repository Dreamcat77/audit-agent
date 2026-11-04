package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import java.util.Optional;
import java.util.UUID;

public interface ProjectFileIdentityDecisionRepository {
    ProjectFileIdentityDecision insertIfAbsent(ProjectFileIdentityDecision decision);
    default ProjectFileIdentityDecision stageInsertIfAbsent(ProjectFileIdentityDecision decision) {
        return insertIfAbsent(decision);
    }
    default void commitStagedInsert(ProjectFileIdentityDecision decision) { }
    default void rollbackUncommittedInsert(ProjectFileIdentityDecision decision) { }
    Optional<ProjectFileIdentityDecision> findById(UUID decisionId);
    Optional<ProjectFileIdentityDecision> findByFileIdAndVersion(UUID fileId, long versionNo);
    Optional<ProjectFileIdentityDecision> findLatestByFileId(UUID fileId);
}
