package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectFileIdentityEvidenceMapper {
    int insert(@Param("row") ProjectFileIdentityEvidencePersistenceRow row);
    List<ProjectFileIdentityEvidencePersistenceRow> findByAssessmentId(@Param("assessmentId") String assessmentId);
}
