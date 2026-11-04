package com.fuquan.audit.contract.application.ai;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Server-produced text-validation metadata; never a source of authoritative money facts. */
public record ContractMonetaryObservation(String protocol, String mode,
        List<Warning> warnings, List<WorkWarning> workWarnings) {
    public static final String PROTOCOL = "contract-money-observation-v1";
    public static final String OBSERVE_LABEL = "【联调参考意见｜金额相关模型表述尚待人工复核】";

    public ContractMonetaryObservation {
        if (!PROTOCOL.equals(protocol)) throw new IllegalArgumentException("unsupported monetary observation protocol");
        requireMode(mode);
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        workWarnings = workWarnings == null ? List.of() : List.copyOf(workWarnings);
        if (warnings.size() > 2048 || workWarnings.size() > 64)
            throw new IllegalArgumentException("monetary observation exceeds bounds");
    }

    public static String requireMode(String mode) {
        if (!Set.of("STRICT", "OBSERVE").contains(mode == null ? "" : mode))
            throw new IllegalArgumentException("unsupported contract semantic money mode");
        return mode;
    }

    public record Warning(String fieldPath, String errorKind, String category, String textSha256,
            List<String> ruleSubtypes) {
        public Warning {
            if (fieldPath == null || !fieldPath.matches("[A-Za-z0-9_.\\[\\]]{1,512}")
                    || !Set.of("MONETARY_VALUE", "MONETARY_CALCULATION").contains(errorKind == null ? "" : errorKind))
                throw new IllegalArgumentException("invalid monetary warning");
            if (category != null) ContractTextAnalysisResult.SemanticCategory.valueOf(category);
            hash(textSha256);
            ruleSubtypes = ruleSubtypes == null ? List.of() : List.copyOf(ruleSubtypes);
            if (ruleSubtypes.stream().anyMatch(value -> !Set.of("ABSOLUTE_VALUE", "LABELLED_NUMBER", "ARITHMETIC",
                    "RATE_COMPARISON", "UNSAFE_CONCLUSION").contains(value))
                    || ruleSubtypes.stream().distinct().count() != ruleSubtypes.size())
                throw new IllegalArgumentException("invalid monetary warning rule subtypes");
        }
    }

    public record WorkWarning(String logicalKey, int ordinal, String purpose,
            String operationFingerprint, int warningCount, String warningsSha256) {
        public WorkWarning {
            hash(logicalKey); hash(operationFingerprint); hash(warningsSha256);
            if (ordinal < 1 || warningCount < 1 || warningCount > 2048
                    || !Set.of("ANALYZE", "REPAIR", "SYNTHESIZE").contains(purpose == null ? "" : purpose))
                throw new IllegalArgumentException("invalid monetary work warning");
        }
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid monetary warning digest");
    }
}
