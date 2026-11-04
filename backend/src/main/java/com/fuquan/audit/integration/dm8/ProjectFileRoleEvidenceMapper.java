package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectFileRoleEvidenceMapper {
    int insert(@Param("row") ProjectFileRoleEvidencePersistenceRow row);
    ProjectFileRoleEvidencePersistenceRow findById(@Param("evidenceId") String evidenceId);
    ProjectFileRoleEvidencePersistenceRow findBySignal(@Param("assessmentId") String assessmentId,
                                                        @Param("role") String role,
                                                        @Param("ruleCode") String ruleCode,
                                                        @Param("sourceLocator") String sourceLocator);
    List<ProjectFileRoleEvidencePersistenceRow> findByAssessmentId(@Param("assessmentId") String assessmentId);
}
