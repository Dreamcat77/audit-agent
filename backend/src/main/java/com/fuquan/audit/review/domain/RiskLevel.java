package com.fuquan.audit.review.domain;

/**
 * 审查风险等级，按 GREEN、YELLOW、RED 由低到高排序。
 */
public enum RiskLevel {
    GREEN,
    YELLOW,
    RED;

    /**
     * 聚合多个结论中的最高风险等级。
     *
     * @param levels 待聚合风险等级，可包含空元素
     * @return 最高风险等级；集合为空或全为空时返回 GREEN
     */
    public static RiskLevel highest(Iterable<RiskLevel> levels) {
        RiskLevel highest = GREEN;
        for (RiskLevel level : levels) {
            if (level != null && level.ordinal() > highest.ordinal()) {
                highest = level;
            }
        }
        return highest;
    }
}
