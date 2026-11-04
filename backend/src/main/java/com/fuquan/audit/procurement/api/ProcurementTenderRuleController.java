package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.procurement.application.ProcurementTenderRuleScanService;
import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 规则库只读查询；规则初始化由数据库迁移和 local 内置实现负责。 */
@RestController
@RequestMapping("/api/v1/procurement/tender-review-rules")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR')")
public class ProcurementTenderRuleController {
    private final ProcurementTenderRuleScanService service;

    public ProcurementTenderRuleController(ProcurementTenderRuleScanService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ProcurementTenderRule>> list() {
        return ApiResponse.success(service.findRules());
    }
}
