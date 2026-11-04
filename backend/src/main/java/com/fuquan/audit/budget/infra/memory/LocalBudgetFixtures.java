package com.fuquan.audit.budget.infra.memory;

import com.fuquan.audit.budget.domain.BudgetCategoryRule;
import com.fuquan.audit.budget.domain.BudgetProjectConfig;
import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import com.fuquan.audit.budget.domain.BudgetYearValidityConfig;
import com.fuquan.audit.budget.domain.ProcurementDemand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F01 预算审批 — local profile 测试数据。
 */
public final class LocalBudgetFixtures {

    private LocalBudgetFixtures() {}

    private static final UUID PROC_001 = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROC_002 = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID BSM_001 = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID BSM_002 = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID PROJECT_001 = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID PROJECT_002 = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID YEAR_001 = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID YEAR_002 = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID CATEGORY_001 = UUID.fromString("00000000-0000-0000-0000-000000000601");

    public static List<ProcurementDemand> procurementDemands() {
        return List.of(
                new ProcurementDemand(
                        PROC_001, "2024年办公设备采购", "BA-2024-001",
                        "M1001", "办公设备及耗材采购",
                        new BigDecimal("150000.00"), "某供应商A", "APPROVED",
                        Instant.parse("2024-01-15T08:00:00Z")),
                new ProcurementDemand(
                        PROC_002, "2024年IT设备采购", "BA-2024-002",
                        "M2001", "信息技术设备采购",
                        new BigDecimal("280000.00"), "某供应商B", "APPROVED",
                        Instant.parse("2024-02-20T08:00:00Z")));
    }

    public static List<BudgetSubjectMapping> budgetSubjectMappings() {
        return List.of(
                new BudgetSubjectMapping(
                        BSM_001, "M1001", "办公设备及耗材采购",
                        "M1001", "办公设备及耗材采购",
                        new BigDecimal("150000.00"), "2024", "某供应商A", "ACTIVE"),
                new BudgetSubjectMapping(
                        BSM_002, "M2001", "信息技术设备采购",
                        "M2001", "信息技术设备采购",
                        new BigDecimal("300000.00"), "2024", "某供应商B", "ACTIVE"));
    }

    public static List<BudgetProjectConfig> projectConfigs() {
        return List.of(
                new BudgetProjectConfig(
                        PROJECT_001, "P001", "办公设备采购项目",
                        "PROCUREMENT", "采购管理部", "ACTIVE", Instant.now(), Instant.now()),
                new BudgetProjectConfig(
                        PROJECT_002, "P002", "信息技术设备采购项目",
                        "PROCUREMENT", "采购管理部", "ACTIVE", Instant.now(), Instant.now()));
    }

    public static List<BudgetYearValidityConfig> yearValidityConfigs() {
        return List.of(
                new BudgetYearValidityConfig(
                        YEAR_001, "M1001", 2024, 2026, "ACTIVE", Instant.now(), Instant.now()),
                new BudgetYearValidityConfig(
                        YEAR_002, "M2001", 2024, 2026, "ACTIVE", Instant.now(), Instant.now()));
    }

    public static List<BudgetCategoryRule> categoryRules() {
        return List.of(
                new BudgetCategoryRule(
                        CATEGORY_001, "M1001", "办公设备及耗材采购",
                        "办公设备,电脑,打印机,复印机,耗材",
                        "办公费,差旅,培训",
                        "M1001 办公设备及耗材采购",
                        "ACTIVE", Instant.now(), Instant.now()));
    }
}
