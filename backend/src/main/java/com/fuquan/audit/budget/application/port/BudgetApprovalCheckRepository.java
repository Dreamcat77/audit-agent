package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetCheckResult;
import java.util.List;

/**
 * 预算审批校验记录持久化 Port。
 */
public interface BudgetApprovalCheckRepository {

    /**
     * 批量保存校验结果记录。
     *
     * @param results 校验结果列表
     */
    void saveAll(List<BudgetCheckResult> results);
}
