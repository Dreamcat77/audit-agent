package com.fuquan.audit.procurement.application;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** F04 采购审批金额阈值。金额单位为人民币元，支持通过配置覆盖。 */
@ConfigurationProperties("audit.procurement.approval")
public record ProcurementApprovalProperties(
        BigDecimal requestApprovalThreshold,
        BigDecimal committeeThreshold,
        BigDecimal cityLeadershipThreshold,
        BigDecimal partyCommitteeThreshold) {

    public ProcurementApprovalProperties {
        requestApprovalThreshold = defaultValue(requestApprovalThreshold, "10000");
        committeeThreshold = defaultValue(committeeThreshold, "50000");
        cityLeadershipThreshold = defaultValue(cityLeadershipThreshold, "200000");
        partyCommitteeThreshold = defaultValue(partyCommitteeThreshold, "2000000");
        if (requestApprovalThreshold.signum() < 0
                || committeeThreshold.signum() < 0
                || cityLeadershipThreshold.signum() < 0
                || partyCommitteeThreshold.signum() < 0) {
            throw new IllegalArgumentException("procurement approval thresholds must not be negative");
        }
        if (requestApprovalThreshold.compareTo(committeeThreshold) > 0
                || committeeThreshold.compareTo(cityLeadershipThreshold) > 0
                || cityLeadershipThreshold.compareTo(partyCommitteeThreshold) > 0) {
            throw new IllegalArgumentException("procurement approval thresholds must be ascending");
        }
    }

    public ProcurementApprovalProperties() {
        this(null, null, null, null);
    }

    private static BigDecimal defaultValue(BigDecimal value, String fallback) {
        return value == null ? new BigDecimal(fallback) : value;
    }
}
