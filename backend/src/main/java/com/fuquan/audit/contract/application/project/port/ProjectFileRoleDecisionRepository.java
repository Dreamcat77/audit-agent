package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import java.util.Optional;
import java.util.UUID;

public interface ProjectFileRoleDecisionRepository {
    ProjectFileRoleDecision insertIfAbsent(ProjectFileRoleDecision decision);

    /**
     * Fences a stream version while a confirmation transaction is still being assembled.
     * Database adapters rely on transaction isolation; local adapters keep the row invisible until commit.
     */
    default ProjectFileRoleDecision stageInsertIfAbsent(ProjectFileRoleDecision decision) {
        return insertIfAbsent(decision);
    }

    /** Publishes a locally staged decision. Database adapters need no extra work. */
    default void commitStagedInsert(ProjectFileRoleDecision decision) {
        // The surrounding database transaction publishes the insert at commit.
    }

    /** Removes only this transaction's unpublished local decision after a failed command. */
    default void rollbackUncommittedInsert(ProjectFileRoleDecision decision) {
        // The surrounding database transaction rolls the insert back.
    }

    Optional<ProjectFileRoleDecision> findById(UUID decisionId);
    Optional<ProjectFileRoleDecision> findByFileIdAndVersion(UUID fileId, long versionNo);
    Optional<ProjectFileRoleDecision> findLatestByFileId(UUID fileId);
}
