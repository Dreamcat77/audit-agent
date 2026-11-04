package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectFileRoleAssessmentMapper {
    int insert(@Param("row") ProjectFileRoleAssessmentPersistenceRow row);
    int compareAndSet(@Param("row") ProjectFileRoleAssessmentPersistenceRow row,
                      @Param("expected") ProjectFileRoleAssessmentPersistenceRow expected);
    ProjectFileRoleAssessmentPersistenceRow findById(@Param("assessmentId") String assessmentId);
    ProjectFileRoleAssessmentPersistenceRow findActiveByFileIdAndInputFingerprint(
            @Param("fileId") String fileId, @Param("inputFingerprint") String inputFingerprint);
    ProjectFileRoleAssessmentPersistenceRow findLatestByFileId(@Param("fileId") String fileId);
}
