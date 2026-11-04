package com.fuquan.audit.integration.dm8;

import java.util.Map;

/** Pure classifier for complete catalog signatures (columns, constraints and indexes); no DDL execution. */
public final class ContractReviewCloseoutSchemaPreflight {
    private ContractReviewCloseoutSchemaPreflight() { }
    public static String classify(Map<String, String> expected, Map<String, String> observed, boolean readable) {
        if (!readable || expected == null || expected.isEmpty() || observed == null) return "UNKNOWN";
        if (expected.keySet().stream().noneMatch(observed::containsKey)) return "NOT_APPLIED";
        return expected.entrySet().stream().allMatch(e -> e.getValue().equals(observed.get(e.getKey())))
                ? "ALREADY_MATCHING" : "PARTIAL-SCHEMA_DRIFT";
    }
}
