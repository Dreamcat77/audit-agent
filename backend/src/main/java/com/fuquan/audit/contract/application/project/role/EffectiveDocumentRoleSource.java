package com.fuquan.audit.contract.application.project.role;

public enum EffectiveDocumentRoleSource {
    HUMAN_CONFIRMATION,
    SYSTEM_AUTO_DECISION,
    LEGACY_EFFECTIVE,
    NO_AUTHORITATIVE_ROLE;

    public boolean isAuthoritativeDecision() {
        return this == HUMAN_CONFIRMATION || this == SYSTEM_AUTO_DECISION;
    }
}
