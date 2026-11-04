package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Anchors an exact raw excerpt solely against verified frozen parse text. */
@Component
public class FrozenEvidenceGrounder {
    private static final Pattern EXACT_CANONICAL_LOCATOR = Pattern.compile(
            "^parse-result:([0-9a-fA-F-]{36}):canonical-codepoint:([0-9]+)-([0-9]+)$");

    public GroundedEvidence ground(FrozenReviewMaterial material, String excerpt, boolean deterministic) {
        String text = requireMaterialAndExcerpt(material, excerpt);

        int first = text.indexOf(excerpt);
        if (first < 0 || text.indexOf(excerpt, first + 1) >= 0) {
            if (deterministic) throw conflict();
            return new GroundedEvidence(false, null, null,
                    ContractSnapshotCanonicalizer.sha256(excerpt), excerpt);
        }
        int end = first + excerpt.length();
        int startOffset = text.codePointCount(0, first);
        int endOffset = text.codePointCount(0, end);
        if (text.offsetByCodePoints(0, startOffset) != first
                || text.offsetByCodePoints(0, endOffset) != end) {
            if (deterministic) throw conflict();
            return new GroundedEvidence(false, null, null,
                    ContractSnapshotCanonicalizer.sha256(excerpt), excerpt);
        }
        return new GroundedEvidence(true, startOffset, endOffset,
                ContractSnapshotCanonicalizer.sha256(excerpt), excerpt);
    }

    /** Uses a parse-bound canonical locator when present; malformed exact locators never fall back. */
    public GroundedEvidence groundDeclared(FrozenReviewMaterial material, String excerpt,
            String sourceLocator, boolean deterministic) {
        String text = requireMaterialAndExcerpt(material, excerpt);
        if (sourceLocator == null || !sourceLocator.startsWith("parse-result:")) {
            return ground(material, excerpt, deterministic);
        }
        Matcher matcher = EXACT_CANONICAL_LOCATOR.matcher(sourceLocator);
        if (!matcher.matches()) return unavailableOrConflict(excerpt, deterministic);
        try {
            UUID parseResultId = UUID.fromString(matcher.group(1));
            int start = Integer.parseInt(matcher.group(2));
            int end = Integer.parseInt(matcher.group(3));
            int length = text.codePointCount(0, text.length());
            if (!parseResultId.equals(material.parseResultId()) || start < 0 || end <= start || end > length
                    || !codePointSlice(text, start, end).equals(excerpt)) {
                return unavailableOrConflict(excerpt, deterministic);
            }
            return new GroundedEvidence(true, start, end,
                    ContractSnapshotCanonicalizer.sha256(excerpt), excerpt);
        } catch (RuntimeException invalid) {
            return unavailableOrConflict(excerpt, deterministic);
        }
    }

    private static String requireMaterialAndExcerpt(FrozenReviewMaterial material, String excerpt) {
        Objects.requireNonNull(material, "material must not be null");
        String text = material.canonicalText();
        if (text == null || material.canonicalTextSha256() == null
                || !ContractSnapshotCanonicalizer.sha256(text).equals(material.canonicalTextSha256()))
            throw conflict();
        if (excerpt == null || excerpt.isBlank()
                || excerpt.codePointCount(0, excerpt.length()) > 1000)
            throw conflict();
        return text;
    }

    private static GroundedEvidence unavailableOrConflict(String excerpt, boolean deterministic) {
        if (deterministic) throw conflict();
        return new GroundedEvidence(false, null, null,
                ContractSnapshotCanonicalizer.sha256(excerpt), excerpt);
    }

    private static String codePointSlice(String value, int start, int end) {
        return value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end));
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "frozen review evidence cannot be uniquely grounded");
    }

    /** Null offsets and hash explicitly mean locator unavailable, not an invented first match. */
    public record GroundedEvidence(boolean locatorAvailable, Integer startOffset,
            Integer endOffset, String excerptSha256, String boundedExcerpt) { }
}
