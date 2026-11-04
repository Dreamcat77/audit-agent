package com.fuquan.audit.budget.application;

import java.util.List;

/** Central canonical snapshot encoding for ordered F04 step codes. */
public final class BudgetApprovalStepSnapshotCodec {

    private static final String DELIMITER = "|";

    private BudgetApprovalStepSnapshotCodec() {
    }

    public static String encode(List<String> stepCodes) {
        List<String> safeCodes = stepCodes == null ? List.of() : List.copyOf(stepCodes);
        for (String code : safeCodes) {
            if (code == null || code.isBlank() || code.contains(DELIMITER)) {
                throw new IllegalArgumentException("invalid approval step snapshot code");
            }
        }
        return String.join(DELIMITER, safeCodes);
    }

    public static List<String> decode(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return List.of();
        }
        return List.of(snapshot.split("\\|", -1));
    }
}
