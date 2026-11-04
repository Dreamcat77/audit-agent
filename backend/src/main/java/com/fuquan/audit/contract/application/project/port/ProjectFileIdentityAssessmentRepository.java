package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import java.util.Optional;
import java.util.UUID;

public interface ProjectFileIdentityAssessmentRepository {
    ProjectFileIdentityAssessment insertIfAbsent(ProjectFileIdentityAssessment assessment);
    default ProjectFileIdentityAssessment stageInsertIfAbsent(ProjectFileIdentityAssessment assessment) {
        return insertIfAbsent(assessment);
    }
    default void commitStagedInsert(ProjectFileIdentityAssessment assessment) { }
    default void rollbackUncommittedInsert(ProjectFileIdentityAssessment assessment) { }
    Optional<ProjectFileIdentityAssessment> findById(UUID assessmentId);
    Optional<ProjectFileIdentityAssessment> findLatestByFileId(UUID fileId);
}
