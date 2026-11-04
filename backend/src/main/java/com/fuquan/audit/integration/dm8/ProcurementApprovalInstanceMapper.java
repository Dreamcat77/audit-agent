package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcurementApprovalInstanceMapper {
    ProcurementApprovalInstancePersistenceRow findByProjectId(@Param("projectId") String projectId);
    void insert(ProcurementApprovalInstancePersistenceRow row);
    void update(ProcurementApprovalInstancePersistenceRow row);
}
