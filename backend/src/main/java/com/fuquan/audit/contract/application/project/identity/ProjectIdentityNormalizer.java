package com.fuquan.audit.contract.application.project.identity;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Exact-comparison normalization; it does not infer aliases or ownership. */
public final class ProjectIdentityNormalizer {
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");
    private static final Pattern NUMBER_SEPARATOR_SPACE = Pattern.compile("\\s*([-/:])\\s*");

    private ProjectIdentityNormalizer() { }

    public static String projectNo(String raw) {
        String value = common(raw);
        return value == null ? null : NUMBER_SEPARATOR_SPACE.matcher(value).replaceAll("$1");
    }

    public static String projectName(String raw) {
        return common(raw);
    }

    private static String common(String raw) {
        if (raw == null) return null;
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
