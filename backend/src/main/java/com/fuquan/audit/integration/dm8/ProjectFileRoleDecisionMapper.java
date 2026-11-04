package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectFileRoleDecisionMapper {
    int insert(@Param("row") ProjectFileRoleDecisionPersistenceRow row);
    ProjectFileRoleDecisionPersistenceRow findById(@Param("decisionId") String decisionId);
    ProjectFileRoleDecisionPersistenceRow findByFileIdAndVersion(@Param("fileId") String fileId,
                                                                  @Param("versionNo") long versionNo);
    ProjectFileRoleDecisionPersistenceRow findLatestByFileId(@Param("fileId") String fileId);
}
