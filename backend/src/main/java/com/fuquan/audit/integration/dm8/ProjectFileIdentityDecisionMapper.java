package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectFileIdentityDecisionMapper {
    int insert(@Param("row") ProjectFileIdentityDecisionPersistenceRow row);
    ProjectFileIdentityDecisionPersistenceRow findById(@Param("decisionId") String decisionId);
    ProjectFileIdentityDecisionPersistenceRow findByFileIdAndVersion(@Param("fileId") String fileId,
            @Param("versionNo") long versionNo);
    ProjectFileIdentityDecisionPersistenceRow findLatestByFileId(@Param("fileId") String fileId);
}
