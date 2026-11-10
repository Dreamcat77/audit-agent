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
        return actor != null && !actor.roles().contains(SYSTEM_ADMIN)
                && actor.roles().stream().anyMatch(CODES::contains);
    }
    public static boolean allowsModule(CurrentUser actor, String module) {
        if (!applies(actor)) return true;
        return switch (module) {
            case "budget" -> actor.roles().contains(BUDGET);
            case "procurement" -> actor.roles().contains(PROCUREMENT);
            case "contract" -> actor.roles().contains(CONTRACT);
            default -> false;
        };
    }
}
