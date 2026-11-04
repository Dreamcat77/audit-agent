package com.fuquan.audit.contract.application.project;

/** Stable, bounded marker used to distinguish machine decisions from historic human decisions. */
public final class AutomaticDecisionProvenance {
    public static final String REASON_PREFIX = "SYSTEM_AUTO_CLASSIFICATION:v1:";

    private AutomaticDecisionProvenance() { }

    public static boolean isAutomatic(String reason) {
        return reason != null && reason.startsWith(REASON_PREFIX);
    }
}
