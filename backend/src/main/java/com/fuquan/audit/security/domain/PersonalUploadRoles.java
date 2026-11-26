package com.fuquan.audit.security.domain;

import java.util.Set;

/** Job labels share the same tools; ownership is always the account, never its role. */
public final class PersonalUploadRoles {
    public static final String PROCUREMENT = "PROCUREMENT_USER";
    public static final String BUDGET = "BUDGET_USER";
    public static final String CONTRACT = "CONTRACT_USER";
    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    public static final Set<String> CODES = Set.of(PROCUREMENT, BUDGET, CONTRACT);
    private PersonalUploadRoles() {}
    public static boolean applies(CurrentUser actor) {
        // Existing three account codes are the deployed ordinary-user identity.
        // Legacy specialist/admin API authorities keep their existing checks;
        // creating a person always assigns one of CODES and cannot grant them.
        return actor != null && !actor.roles().contains(SYSTEM_ADMIN)
                && actor.roles().stream().anyMatch(CODES::contains);
    }
    public static boolean allowsModule(CurrentUser actor, String module) {
        return actor != null && Set.of("budget","procurement","contract","blacklist").contains(module);
    }
}
