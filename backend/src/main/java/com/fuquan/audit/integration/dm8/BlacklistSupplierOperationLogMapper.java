package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BlacklistSupplierOperationLogMapper {

    void insert(BlacklistSupplierOperationLogPersistenceRow row);

    List<BlacklistSupplierOperationLogPersistenceRow> findByBlacklistId(
            @Param("blacklistId") String blacklistId);
}
