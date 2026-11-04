package com.fuquan.audit.budget.application;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;

public final class BudgetTextNormalizer {

    private BudgetTextNormalizer() {
    }

    public static String subject(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s\\u3000]+", "")
                .replaceAll("[，,。．.、;；:：/\\\\|()（）\\[\\]【】{}<>《》\"'“”‘’]+", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public static BigDecimal amount(String value) {
        return com.fuquan.audit.common.domain.RmbAmountParser.parse(value).orElse(null);
    }
}
