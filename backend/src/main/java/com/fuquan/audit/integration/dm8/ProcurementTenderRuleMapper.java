package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcurementTenderRuleMapper {
    List<ProcurementTenderRulePersistenceRow> findAll();

    List<ProcurementTenderRulePersistenceRow> findEnabled();
}
