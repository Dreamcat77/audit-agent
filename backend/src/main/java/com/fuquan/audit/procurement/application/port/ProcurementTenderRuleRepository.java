package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.util.List;

public interface ProcurementTenderRuleRepository {
    List<ProcurementTenderRule> findAll();

    List<ProcurementTenderRule> findEnabled();
}
