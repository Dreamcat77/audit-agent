package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Upload dates use the business calendar (UTC+08:00), persisted timestamps use UTC. */
public record BudgetHistoryFilter(String fileName, String projectName, Instant createdFrom, Instant createdBefore) {
    public BudgetHistoryFilter(String fileName, Instant createdFrom, Instant createdBefore) {
        this(fileName, null, createdFrom, createdBefore);
    }

    public static BudgetHistoryFilter of(String fileName, LocalDate from, LocalDate to) {
        return create(fileName, null, from, to);
    }

    public static BudgetHistoryFilter projectName(String projectName, LocalDate from, LocalDate to) {
        return create(null, projectName, from, to);
    }

    private static BudgetHistoryFilter create(String fileName, String projectName, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        String name = fileName == null || fileName.isBlank() ? null : fileName.strip();
        String project = projectName == null || projectName.isBlank() ? null : projectName.strip();
        if ((name != null && name.length() > 255) || (project != null && project.length() > 255)) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "名称查询条件过长");
        }
        if (from != null && (from.getYear() < 1 || from.getYear() > 9998)
                || to != null && (to.getYear() < 1 || to.getYear() > 9998)) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "日期超出支持范围");
        }
        ZoneOffset businessZone = ZoneOffset.ofHours(8);
        return new BudgetHistoryFilter(name, project,
                from == null ? null : from.atStartOfDay().toInstant(businessZone),
                to == null ? null : to.plusDays(1).atStartOfDay().toInstant(businessZone));
    }
}
