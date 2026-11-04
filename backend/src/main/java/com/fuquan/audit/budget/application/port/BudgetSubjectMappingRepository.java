package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import java.util.List;

/**
 * 预算科目映射数据访问 Port。
 */
public interface BudgetSubjectMappingRepository {

    /**
     * 根据系统科目编码查询映射记录（仅返回 ACTIVE 状态）。
     *
     * @param systemSubjectCode 系统科目编码
     * @return 匹配的映射记录列表
     */
    List<BudgetSubjectMapping> findBySystemSubjectCode(String systemSubjectCode);

    /**
     * 查询所有 ACTIVE 预算批复映射，供材料科目匹配使用。
     */
    default List<BudgetSubjectMapping> findAllActive() {
        return List.of();
    }
}
