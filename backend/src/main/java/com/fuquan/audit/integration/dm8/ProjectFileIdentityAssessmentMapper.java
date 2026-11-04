package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectFileIdentityAssessmentMapper {
    int insert(@Param("row") ProjectFileIdentityAssessmentPersistenceRow row);
    ProjectFileIdentityAssessmentPersistenceRow findById(@Param("assessmentId") String assessmentId);
    ProjectFileIdentityAssessmentPersistenceRow findLatestByFileId(@Param("fileId") String fileId);
}
