package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.BudgetYearValidityConfig;
import java.util.List;

public interface BudgetYearValidityConfigRepository {

    default List<BudgetYearValidityConfig> findBySubjectCode(String subjectCode) {
        return List.of();
    }

    default List<BudgetYearValidityConfig> findAllActive() {
        return List.of();
    }
}
