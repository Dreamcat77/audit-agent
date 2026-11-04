package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectFileIdentityEvidence;
import java.util.List;
import java.util.UUID;

public interface ProjectFileIdentityEvidenceRepository {
    void insertAll(UUID assessmentId, List<ProjectFileIdentityEvidence> evidence);
    default void stageInsertAll(UUID assessmentId, List<ProjectFileIdentityEvidence> evidence) {
        insertAll(assessmentId, evidence);
    }
    default void commitStagedInsert(UUID assessmentId) { }
    default void rollbackUncommittedInsert(UUID assessmentId) { }
    List<ProjectFileIdentityEvidence> findByAssessmentId(UUID assessmentId);
}
