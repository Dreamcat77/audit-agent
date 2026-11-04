package com.fuquan.audit.contract.application.clause;

import java.math.BigInteger;

/** Normalizes only explicit quantities and equivalent spellings of the same time unit. */
final class ClauseDurationNormalizer {
    private ClauseDurationNormalizer() {
    }

    static String key(String quantity, String unit) {
        BigInteger number;
        if (quantity.matches("[0-9]+")) {
            number = new BigInteger(quantity);
        } else {
            int total = 0;
            int digit = 0;
            for (int index = 0; index < quantity.length(); index++) {
                char token = quantity.charAt(index);
                int value = "零一二三四五六七八九".indexOf(token);
                if (token == '两') value = 2;
                if (value >= 0) {
                    digit = value;
                } else {
                    int multiplier = token == '十' ? 10 : token == '百' ? 100 : -1;
                    if (multiplier < 0) return null;
                    total = Math.addExact(total, Math.multiplyExact(digit == 0 ? 1 : digit, multiplier));
                    digit = 0;
                }
            }
            number = BigInteger.valueOf(Math.addExact(total, digit));
        }
        String normalizedUnit = unit.startsWith("个") ? unit.substring(1) : unit;
        if (normalizedUnit.equals("天")) normalizedUnit = "日";
        return number + ":" + normalizedUnit;
    }
}
