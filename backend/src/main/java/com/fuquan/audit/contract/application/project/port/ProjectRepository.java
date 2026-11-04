package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.Project;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface ProjectRepository {
    void insert(Project project);
    Optional<Project> findById(UUID id);
    /** Write-transaction authority fence; DM8 adapter must lock the Project row. */
    default Optional<Project> findByIdForUpdate(UUID id) { return findById(id); }
    List<Project> findPage(int offset, int limit, String keyword);
    long count(String keyword);
    List<Project> findIntakeDraftPage(int offset, int limit);
    long countIntakeDrafts();
    List<PreparationDraft> findPreparationDraftPage(int offset, int limit);
    long countPreparationDrafts();
    boolean compareAndSet(Project expected, Project updated);

    record PreparationDraft(UUID projectId, Instant createdAt, long uploadedFileCount) {
    }
}
