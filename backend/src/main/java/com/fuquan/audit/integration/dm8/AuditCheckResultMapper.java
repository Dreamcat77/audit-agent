package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditCheckResultMapper {

    int insert(AuditCheckResultPersistenceRow row);

    List<AuditCheckResultPersistenceRow> findByTaskId(@Param("taskId") String taskId);

    AuditCheckResultPersistenceRow findById(@Param("id") String id);
}
