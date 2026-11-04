package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ExtractionResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExtractionResultRepository {
    ExtractionResult insertIfAbsent(ExtractionResult result);
    Optional<ExtractionResult> findByExtractionKey(String extractionKey);
    List<ExtractionResult> findBySourceFileId(UUID projectId, UUID sourceFileId);
    default List<ExtractionResult> findBySourceFileIdAndParseResultId(
            UUID projectId, UUID sourceFileId, UUID parseResultId) {
        return findBySourceFileId(projectId, sourceFileId).stream()
                .filter(value -> parseResultId.equals(value.parseResultId())).toList();
    }
    default ExtractionResult bindCandidateIfUnbound(UUID projectId, UUID sourceFileId,
            UUID parseResultId, String extractionKey, UUID candidateId) {
        throw new UnsupportedOperationException("candidate evidence binding is not supported");
    }
    List<ExtractionResult> findByCandidateId(UUID projectId, UUID candidateId);
    List<ExtractionResult> findByProjectId(UUID projectId);
}
