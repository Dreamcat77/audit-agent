package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectExtractionResultMapper {
    int insert(@Param("row") ProjectExtractionPersistenceRow row);
    ProjectExtractionPersistenceRow findByExtractionKey(@Param("key") String key);
    List<ProjectExtractionPersistenceRow> findBySource(@Param("projectId") String projectId, @Param("sourceId") String sourceId);
    List<ProjectExtractionPersistenceRow> findBySourceAndParse(@Param("projectId") String projectId,
            @Param("sourceId") String sourceId, @Param("parseResultId") String parseResultId);
    int bindCandidateIfUnbound(@Param("projectId") String projectId, @Param("sourceId") String sourceId,
            @Param("parseResultId") String parseResultId, @Param("key") String extractionKey,
            @Param("candidateId") String candidateId);
    List<ProjectExtractionPersistenceRow> findByCandidate(@Param("projectId") String projectId, @Param("candidateId") String candidateId);
    List<ProjectExtractionPersistenceRow> findByProject(@Param("projectId") String projectId);
}
