package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetCheckResult;
import java.util.List;
import java.util.UUID;

/**
 * F01 预算审批校验 — 业务 Port 接口。
 * <p>
 * Application 层编排校验逻辑，不感知底层存储或外部服务。
 */
public interface BudgetCheckPort {

    /**
     * 根据采购需求 ID 执行预算审批材料校验。
     *
     * @param procurementId 采购需求 ID
     * @return 校验结果列表（科目编码、金额、供应商、科目名称）
     */
    List<BudgetCheckResult> checkBudgetApproval(UUID procurementId);
}
