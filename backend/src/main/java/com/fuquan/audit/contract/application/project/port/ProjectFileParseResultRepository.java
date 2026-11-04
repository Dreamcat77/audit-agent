package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import java.util.Optional;
import java.util.UUID;

/** Durable append-only store for immutable file parse attempts. */
public interface ProjectFileParseResultRepository {
    ProjectFileParseResult insertIfAbsent(ProjectFileParseResult result);
    Optional<ProjectFileParseResult> findById(UUID parseResultId);
    Optional<ProjectFileParseResult> findByFileIdAndParseVersion(UUID fileId, int parseVersion);
    Optional<ProjectFileParseResult> findLatestByFileId(UUID fileId);
}
