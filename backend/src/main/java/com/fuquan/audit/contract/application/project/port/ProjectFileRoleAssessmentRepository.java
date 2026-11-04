package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import java.util.Optional;
import java.util.UUID;

public interface ProjectFileRoleAssessmentRepository {
    ProjectFileRoleAssessment insertIfAbsent(ProjectFileRoleAssessment assessment);

    /**
     * Stages the PENDING row within START. Database adapters persist normally and rely on transaction isolation;
     * local adapters keep the row fenced but refuse execution CAS until {@link #commitStagedInsert}.
     */
    default ProjectFileRoleAssessment stagePendingInsertIfAbsent(ProjectFileRoleAssessment assessment) {
        return insertIfAbsent(assessment);
    }

    /** Publishes a locally staged PENDING row after START audit succeeds; database transactions need no extra work. */
    default void commitStagedInsert(ProjectFileRoleAssessment assessment) {
        // Database-backed implementations publish the insert when the surrounding transaction commits.
    }

    boolean compareAndSet(ProjectFileRoleAssessment expected, ProjectFileRoleAssessment updated);
    Optional<ProjectFileRoleAssessment> findById(UUID assessmentId);
    Optional<ProjectFileRoleAssessment> findLatestByFileId(UUID fileId);

    /**
     * Compensates an insert that belongs to a surrounding transaction which did not commit.
     * Database adapters rely on transaction rollback; local/test adapters remove only the exact staged PENDING row.
     */
    default void rollbackUncommittedInsert(ProjectFileRoleAssessment assessment) {
        // Database-backed implementations roll back with the surrounding transaction.
    }
}
