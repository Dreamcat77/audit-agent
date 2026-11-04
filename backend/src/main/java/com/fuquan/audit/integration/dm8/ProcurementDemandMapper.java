package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ProcurementDemandMapper {

    ProcurementDemandPersistenceRow findById(@Param("id") String id);

    List<ProcurementDemandPersistenceRow> findByIds(@Param("ids") List<String> ids);
}
