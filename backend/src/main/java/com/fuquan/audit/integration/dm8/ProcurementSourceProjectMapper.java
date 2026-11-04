package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcurementSourceProjectMapper {
    List<ProcurementSourceProjectPersistenceRow> findEligible(
            @Param("keyword") String keyword, @Param("limit") int limit);

    ProcurementSourceProjectPersistenceRow findEligibleById(@Param("id") String id);
}
