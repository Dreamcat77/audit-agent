package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectFileParseResultMapper {
    int insert(@Param("row") ProjectFileParseResultPersistenceRow row);
    ProjectFileParseResultPersistenceRow findById(@Param("parseResultId") String parseResultId);
    ProjectFileParseResultPersistenceRow findByFileIdAndParseVersion(@Param("fileId") String fileId,
                                                                       @Param("parseVersion") int parseVersion);
    ProjectFileParseResultPersistenceRow findLatestByFileId(@Param("fileId") String fileId);
}
