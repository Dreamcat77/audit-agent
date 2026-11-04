package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectFileMapper {
    int insert(@Param("row") ProjectFilePersistenceRow row);
    int update(@Param("row") ProjectFilePersistenceRow row);
    ProjectFilePersistenceRow findByProjectIdAndId(@Param("projectId") String projectId, @Param("id") String id);
    ProjectFilePersistenceRow findByProjectIdAndIdForUpdate(@Param("projectId") String projectId, @Param("id") String id);
    ProjectFilePersistenceRow findByParentAndPath(@Param("projectId") String projectId,
                                                   @Param("parentId") String parentId, @Param("path") String path);
    List<ProjectFilePersistenceRow> findByProjectId(@Param("projectId") String projectId);
}
