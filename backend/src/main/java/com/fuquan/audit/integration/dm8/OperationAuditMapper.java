package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OperationAuditMapper {

    int insert(OperationAuditPersistenceRow row);
    List<OperationAuditPersistenceRow> find(@Param("userId") String userId, @Param("action") String action,
                                            @Param("resourceType") String resourceType,
                                            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
