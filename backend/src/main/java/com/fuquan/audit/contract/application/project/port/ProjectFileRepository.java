package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface ProjectFileRepository {
    void insert(ProjectFile file);
    void update(ProjectFile file);
    Optional<ProjectFile> findByProjectIdAndId(UUID projectId, UUID id);
    /** Locks the stable source file row until the current business transaction completes. */
    default Optional<ProjectFile> findByProjectIdAndIdForUpdate(UUID projectId, UUID id) {
        throw new UnsupportedOperationException("authoritative file row locking must be provided by the adapter");
    }
    /** Serializes local-profile commands across their whole write transaction; DM8 uses its row lock. */
    default <T> T withFileAuthorityFence(UUID projectId, UUID id, Supplier<T> command) {
        throw new UnsupportedOperationException("source file command fence must be provided by the adapter");
    }
    Optional<ProjectFile> findByProjectIdAndParentArchiveFileIdAndRelativePath(
            UUID projectId, UUID parentArchiveFileId, String normalizedRelativePath);
    List<ProjectFile> findByProjectId(UUID projectId);
}
