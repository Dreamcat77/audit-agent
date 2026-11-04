package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcurementApprovalNodeMapper {
    List<ProcurementApprovalNodePersistenceRow> findByInstanceId(@Param("instanceId") String instanceId);

    List<ProcurementApprovalTaskPersistenceRow> findPendingTasksByAssignee(@Param("userId") String userId);
    void insertBatch(@Param("list") List<ProcurementApprovalNodePersistenceRow> rows);
    void update(ProcurementApprovalNodePersistenceRow row);
}
