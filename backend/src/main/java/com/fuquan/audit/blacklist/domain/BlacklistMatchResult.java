package com.fuquan.audit.blacklist.domain;

import java.util.UUID;

/**
 * 单次供应商命中结果。
 */
public record BlacklistMatchResult(
        String supplierName,
        String supplierCode,
        String matchedName,
        String matchType,
        int matchScore,
        boolean matched,
        UUID blacklistId,
        String blacklistType,
        String blacklistReason,
        String alertLevel) {

    public BlacklistMatchResult(
            String supplierName,
            String matchedName,
            String matchType,
            int matchScore,
            boolean matched) {
        this(supplierName, null, matchedName, matchType, matchScore, matched,
                null, null, null, null);
    }

    public static BlacklistMatchResult exact(
            String supplierName, String supplierCode, BlacklistSupplier entry, String alertLevel) {
        return new BlacklistMatchResult(
                supplierName, supplierCode, entry.supplierName(), "EXACT", 100, true,
                entry.id(), entry.blacklistType(), entry.reason(), alertLevel);
    }

    public static BlacklistMatchResult fuzzy(
            String supplierName, String supplierCode, BlacklistSupplier entry,
            int score, String alertLevel) {
        return new BlacklistMatchResult(
                supplierName, supplierCode, entry.supplierName(), "FUZZY", score, true,
                entry.id(), entry.blacklistType(), entry.reason(), alertLevel);
    }

    public static BlacklistMatchResult exact(String supplierName, String matchedName) {
        return new BlacklistMatchResult(supplierName, null, matchedName, "EXACT", 100, true,
                null, null, null, null);
    }

    public static BlacklistMatchResult fuzzy(String supplierName, String matchedName, int score) {
        return new BlacklistMatchResult(supplierName, null, matchedName, "FUZZY", score, true,
                null, null, null, null);
    }

    public static BlacklistMatchResult none(String supplierName) {
        return new BlacklistMatchResult(supplierName, null, null, null, 0, false,
                null, null, null, null);
    }
}
