package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.project.ExtractionKeyFactoryV2;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Candidate;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.ContextCode;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.FactType;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Request;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Result;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Segment;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Revalidates model candidates against current durable authority before publication. */
public final class ContractFactCandidateValidator {
    public static final String EXTRACTION_VERSION = "contract-fact-ai-v1";
    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,]*(?:\\.[0-9]+)?");
    private static final Pattern CREDIT_CODE = Pattern.compile("^[0-9A-Z]{18}$");
    private static final Set<FactType> DECIMAL_FACTS = Set.of(FactType.CONTRACT_AMOUNT,
            FactType.LINE_ITEM_QUANTITY, FactType.LINE_ITEM_UNIT_PRICE,
            FactType.LINE_ITEM_DECLARED_AMOUNT, FactType.AWARD_AMOUNT);
    private static final Set<FactType> LINE_FACTS = Set.of(FactType.LINE_ITEM_NAME,
            FactType.LINE_ITEM_QUANTITY, FactType.LINE_ITEM_UNIT_PRICE,
            FactType.LINE_ITEM_DECLARED_AMOUNT, FactType.LINE_ITEM_KEY);
    private static final Map<FactType, ContextCode> CONTEXTS = Map.ofEntries(
            Map.entry(FactType.CONTRACT_NO, ContextCode.CONTRACT_HEADER),
            Map.entry(FactType.CONTRACT_NAME, ContextCode.CONTRACT_HEADER),
            Map.entry(FactType.PARTY_A, ContextCode.CONTRACT_PARTIES),
            Map.entry(FactType.PARTY_B, ContextCode.CONTRACT_PARTIES),
            Map.entry(FactType.PARTY_B_CREDIT_CODE, ContextCode.CONTRACT_PARTIES),
            Map.entry(FactType.CONTRACT_AMOUNT, ContextCode.CONTRACT_AMOUNT),
            Map.entry(FactType.AMOUNT_IN_WORDS, ContextCode.CONTRACT_AMOUNT),
            Map.entry(FactType.LINE_ITEM_NAME, ContextCode.CONTRACT_LINE_ITEM),
            Map.entry(FactType.LINE_ITEM_QUANTITY, ContextCode.CONTRACT_LINE_ITEM),
            Map.entry(FactType.LINE_ITEM_UNIT_PRICE, ContextCode.CONTRACT_LINE_ITEM),
            Map.entry(FactType.LINE_ITEM_DECLARED_AMOUNT, ContextCode.CONTRACT_LINE_ITEM),
            Map.entry(FactType.LINE_ITEM_KEY, ContextCode.CONTRACT_LINE_ITEM),
            Map.entry(FactType.AWARD_AMOUNT, ContextCode.AWARD_FINAL_RESULT),
            Map.entry(FactType.AWARD_SUPPLIER, ContextCode.AWARD_FINAL_RESULT),
            Map.entry(FactType.AWARD_SUPPLIER_CREDIT_CODE, ContextCode.AWARD_FINAL_RESULT));
    private static final Map<FactType, String> PUBLISHED_TYPES = Map.ofEntries(
            Map.entry(FactType.CONTRACT_NO, "CONTRACT_NO"),
            Map.entry(FactType.CONTRACT_NAME, "CONTRACT_NAME"),
            Map.entry(FactType.PARTY_A, "PARTY_A"),
            Map.entry(FactType.PARTY_B, "PARTY_B"),
            Map.entry(FactType.PARTY_B_CREDIT_CODE, "CREDIT_CODE"),
            Map.entry(FactType.CONTRACT_AMOUNT, "AMOUNT_NUMERIC"),
            Map.entry(FactType.AMOUNT_IN_WORDS, "AMOUNT_UPPERCASE"),
            Map.entry(FactType.LINE_ITEM_NAME, "LINE_ITEM_NAME"),
            Map.entry(FactType.LINE_ITEM_QUANTITY, "LINE_ITEM_QUANTITY"),
            Map.entry(FactType.LINE_ITEM_UNIT_PRICE, "LINE_ITEM_UNIT_PRICE"),
            Map.entry(FactType.LINE_ITEM_DECLARED_AMOUNT, "LINE_ITEM_DECLARED_AMOUNT"),
            Map.entry(FactType.LINE_ITEM_KEY, "LINE_ITEM_KEY"),
            Map.entry(FactType.AWARD_AMOUNT, "AWARD_AMOUNT"),
            Map.entry(FactType.AWARD_SUPPLIER, "AWARD_SUPPLIER"),
            Map.entry(FactType.AWARD_SUPPLIER_CREDIT_CODE, "AWARD_SUPPLIER_CREDIT_CODE"));

    private final Provenance expectedProvenance;
    private final ExtractionKeyFactoryV2 keys = new ExtractionKeyFactoryV2();

    public ContractFactCandidateValidator(Provenance expectedProvenance) {
        this.expectedProvenance = Objects.requireNonNull(expectedProvenance);
    }

    public Validation validate(CurrentInput current, Request request, Result result, Instant now) {
        requireInputAuthority(current, request, result);
        if (ContractFactSourceTablePlanner.PROVENANCE.equals(expectedProvenance)
                && (!result.modelCalls().isEmpty() || !result.rejections().isEmpty()
                || result.batchStatus() != ContractFactCandidatePort.BatchStatus.PARTIAL
                || result.scope() != ContractFactCandidatePort.Scope.LINE_ITEMS
                || result.candidates().stream().anyMatch(candidate -> !candidate.candidateKey().matches("cfs-v1-[0-9a-f]{64}")))) {
            throw new ContractFactValidationException(Reason.INPUT_AUTHORITY_MISMATCH);
        }
        Map<String, Segment> segments = request.segments().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Segment::segmentId, value -> value));
        List<Decision> decisions = new ArrayList<>();
        List<ExtractionResult> publications = new ArrayList<>();
        for (Candidate candidate : result.candidates()) {
            Decision decision = validateCandidate(current, request, candidate, segments, now);
            decisions.add(decision);
            if (decision.publication() != null) publications.add(decision.publication());
        }
        return new Validation(decisions, publications);
    }

    private void requireInputAuthority(CurrentInput current, Request request, Result result) {
        if (current == null || request == null || result == null || !expectedProvenance.equals(result.provenance())
                || !Objects.equals(current.projectId(), request.projectId())
                || !Objects.equals(current.fileId(), request.fileId())
                || !Objects.equals(current.fileSha256(), request.fileSha256())
                || !Objects.equals(current.parseResultId(), request.parseResultId())
                || current.parseVersion() != request.parseVersion()
                || !Objects.equals(current.canonicalTextSha256(), request.canonicalTextSha256())
                || !sha256(current.canonicalText()).equals(current.canonicalTextSha256())) {
            throw new ContractFactValidationException(Reason.INPUT_AUTHORITY_MISMATCH);
        }
        int canonicalLength = codePointLength(current.canonicalText());
        for (Segment segment : request.segments()) {
            if (segment.startOffset() < 0 || segment.endOffset() <= segment.startOffset()
                    || segment.endOffset() > canonicalLength
                    || !codePointSlice(current.canonicalText(), segment.startOffset(), segment.endOffset())
                    .equals(segment.text())
                    || !sha256(segment.text()).equals(segment.textSha256())) {
                throw new ContractFactValidationException(Reason.INPUT_AUTHORITY_MISMATCH);
            }
            if (segment.rowIdentity() != null) {
                int index = current.canonicalText().offsetByCodePoints(0, segment.startOffset());
                int rowStartIndex = current.canonicalText().lastIndexOf('\n', index - 1) + 1;
                int newline = current.canonicalText().indexOf('\n', index);
                int rowStart = current.canonicalText().codePointCount(0, rowStartIndex);
                int rowEnd = newline < 0 ? canonicalLength : current.canonicalText().codePointCount(0, newline + 1);
                if (segment.endOffset() > rowEnd || !ContractFactSegmenter.rowIdentity(
                        current.parseResultId(), rowStart, rowEnd).equals(segment.rowIdentity())) {
                    throw new ContractFactValidationException(Reason.INPUT_AUTHORITY_MISMATCH);
                }
            }
        }
    }

    private Decision validateCandidate(CurrentInput current, Request request, Candidate candidate,
            Map<String, Segment> segments, Instant now) {
        if (candidate != null && candidate.factType() == FactType.LINE_ITEM_KEY
                && !ContractFactSourceTablePlanner.PROVENANCE.equals(expectedProvenance)) {
            return rejected(candidate, Reason.ROLE_NOT_AUTHORIZED);
        }
        Segment segment = candidate == null ? null : segments.get(candidate.segmentId());
        if (segment == null || !segment.sourceLocator().equals(candidate.sourceLocator())
                || candidate.excerptStart() < 0 || candidate.excerptEnd() <= candidate.excerptStart()
                || candidate.excerptEnd() > codePointLength(segment.text())
                || !codePointSlice(segment.text(), candidate.excerptStart(), candidate.excerptEnd())
                .equals(candidate.excerpt()) || codePointLength(candidate.excerpt()) > 240) {
            return rejected(candidate, Reason.SOURCE_NOT_EXACT);
        }
        if (candidate.contextCode() != CONTEXTS.get(candidate.factType())) {
            return rejected(candidate, Reason.CONTEXT_INVALID);
        }
        boolean lineFact = LINE_FACTS.contains(candidate.factType());
        boolean awardFact = candidate.factType().name().startsWith("AWARD_");
        if (("contract-fact-candidates-v2".equals(expectedProvenance.schemaVersion())
                || ContractFactSourceTablePlanner.PROVENANCE.equals(expectedProvenance)) &&
                ((request.scope() == ContractFactCandidatePort.Scope.LINE_ITEMS && !lineFact)
                || (request.scope() == ContractFactCandidatePort.Scope.AWARD && !awardFact)
                || (request.scope() == ContractFactCandidatePort.Scope.CORE && (lineFact || awardFact)))) {
            return rejected(candidate, Reason.ROLE_NOT_AUTHORIZED);
        }
        if (lineFact && segment.rowIdentity() != null
                && !ContractFactSegmenter.lineGroup(segment.rowIdentity()).equals(candidate.groupKey())) {
            return rejected(candidate, Reason.TYPED_VALUE_NOT_GROUNDED);
        }
        boolean decimalFact = DECIMAL_FACTS.contains(candidate.factType());
        if (decimalFact != (candidate.valueDecimal() != null)
                || decimalFact == (candidate.valueText() != null)
                || LINE_FACTS.contains(candidate.factType()) != (candidate.groupKey() != null)
                || (LINE_FACTS.contains(candidate.factType())
                && !candidate.groupKey().matches("line:[0-9a-f]{64}"))) {
            return rejected(candidate, Reason.TYPED_VALUE_NOT_GROUNDED);
        }
        boolean award = candidate.factType().name().startsWith("AWARD_");
        if (award && current.effectiveRole() != DocumentRole.AWARD_NOTICE) {
            return rejected(candidate, Reason.ROLE_NOT_AUTHORIZED);
        }
        if (award && !current.sameProjectConfirmed()) {
            return rejected(candidate, Reason.PROJECT_IDENTITY_NOT_CONFIRMED);
        }
        if (award && !awardFinalContextProven(segment, candidate)) {
            return rejected(candidate, Reason.CONTEXT_INVALID);
        }
        if (!award && current.effectiveRole() != DocumentRole.CONTRACT_BODY) {
            return rejected(candidate, Reason.ROLE_NOT_AUTHORIZED);
        }
        Normalized normalized = normalize(candidate, segment);
        if (normalized == null) return rejected(candidate, Reason.TYPED_VALUE_NOT_GROUNDED);
        PublicationEvidence evidence = publicationEvidence(candidate);
        if (evidence == null) return rejected(candidate, Reason.TYPED_VALUE_NOT_GROUNDED);
        int absoluteStart = segment.startOffset() + candidate.excerptStart() + evidence.startOffset();
        int absoluteEnd = segment.startOffset() + candidate.excerptStart() + evidence.endOffset();
        String locator = "parse-result:%s:canonical-codepoint:%d-%d".formatted(
                request.parseResultId(), absoluteStart, absoluteEnd);
        String factType = PUBLISHED_TYPES.get(candidate.factType());
        String version = ContractFactSourceTablePlanner.PROVENANCE.equals(expectedProvenance)
                ? ContractFactSourceTablePlanner.EXTRACTION_VERSION : EXTRACTION_VERSION;
        String extractionKey = keys.create(request.fileId(), request.parseResultId(),
                version, factType, candidate.groupKey(), locator);
        ExtractionResult publication = new ExtractionResult(UUID.randomUUID(), request.projectId(),
                request.fileId(), request.parseResultId(), null, extractionKey, version,
                factType, candidate.groupKey(), normalized.valueText(), normalized.normalizedValue(),
                normalized.valueAmount(), evidence.text(), null, null, locator, now);
        return new Decision(candidate, Status.ACCEPTED, Reason.ACCEPTED, publication);
    }

    /** Keeps the complete provider excerpt in the candidate audit and publishes an exact DM8-safe slice. */
    private static PublicationEvidence publicationEvidence(Candidate candidate) {
        String excerpt = candidate.excerpt();
        int total = codePointLength(excerpt);
        if (utf8Length(excerpt) <= 240) return new PublicationEvidence(excerpt, 0, total);
        Focus focus = focus(candidate);
        if (focus == null) return null;
        int start = focus.start();
        int end = focus.end();
        boolean expandLeft = true;
        while (start > 0 || end < total) {
            boolean changed = false;
            if (expandLeft && start > 0 && utf8Length(codePointSlice(excerpt, start - 1, end)) <= 240) {
                start--;
                changed = true;
            } else if (end < total && utf8Length(codePointSlice(excerpt, start, end + 1)) <= 240) {
                end++;
                changed = true;
            } else if (start > 0 && utf8Length(codePointSlice(excerpt, start - 1, end)) <= 240) {
                start--;
                changed = true;
            }
            if (!changed) break;
            expandLeft = !expandLeft;
        }
        String bounded = codePointSlice(excerpt, start, end);
        return utf8Length(bounded) <= 240 ? new PublicationEvidence(bounded, start, end) : null;
    }

    private static Focus focus(Candidate candidate) {
        String raw = candidate.excerpt();
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        if (codePointLength(raw) != codePointLength(normalized)) return null;
        if (candidate.valueDecimal() != null) {
            Matcher matcher = NUMBER.matcher(normalized);
            while (matcher.find()) {
                BigDecimal value = new BigDecimal(matcher.group().replace(",", ""));
                String suffix = normalized.substring(Math.min(matcher.end(), normalized.length()));
                if (suffix.stripLeading().startsWith("\u4e07")) value = value.multiply(new BigDecimal("10000"));
                if (value.compareTo(candidate.valueDecimal()) == 0) {
                    return new Focus(normalized.codePointCount(0, matcher.start()),
                            normalized.codePointCount(0, matcher.end()));
                }
            }
            return null;
        }
        if (candidate.valueText() == null) return null;
        String value = Normalizer.normalize(candidate.valueText().strip(), Normalizer.Form.NFKC);
        int index = normalized.indexOf(value);
        if (index < 0) return null;
        return new Focus(normalized.codePointCount(0, index),
                normalized.codePointCount(0, index + value.length()));
    }

    private static Normalized normalize(Candidate candidate, Segment segment) {
        if (candidate.factType() == FactType.LINE_ITEM_KEY) {
            String key = candidate.valueText();
            if (key == null || key.isBlank() || !key.equals(key.strip()) || utf8Length(key) > 200
                    || key.codePoints().anyMatch(Character::isISOControl) || !key.equals(candidate.excerpt())) return null;
            return new Normalized(key, key, null);
        }
        if (candidate.valueDecimal() != null) {
            BigDecimal value = candidate.valueDecimal();
            int effectiveScale = Math.max(value.stripTrailingZeros().scale(), 0);
            if (value.signum() < 0 || value.precision() > 18
                    || effectiveScale > decimalScale(candidate.factType())
                    || !decimalGrounded(segment, candidate, value)) return null;
            String canonical = value.stripTrailingZeros().toPlainString();
            return new Normalized(canonical, canonical, value);
        }
        if (candidate.valueText() == null) return null;
        String normalized = normalizeText(candidate.valueText());
        String evidence = normalizeText(candidate.excerpt());
        if (normalized == null || evidence == null || normalized.length() > 500
                || !evidence.contains(normalized)) return null;
        if ((candidate.factType() == FactType.PARTY_B_CREDIT_CODE
                || candidate.factType() == FactType.AWARD_SUPPLIER_CREDIT_CODE)
                && !CREDIT_CODE.matcher(normalized).matches()) return null;
        return new Normalized(candidate.valueText().strip(), normalized, null);
    }

    private static int decimalScale(FactType factType) {
        return factType == FactType.LINE_ITEM_QUANTITY || factType == FactType.LINE_ITEM_UNIT_PRICE ? 4 : 2;
    }

    private static boolean decimalGrounded(Segment segment, Candidate candidate, BigDecimal expected) {
        String normalizedExcerpt = Normalizer.normalize(candidate.excerpt(), Normalizer.Form.NFKC);
        Matcher matcher = NUMBER.matcher(normalizedExcerpt);
        while (matcher.find()) {
            BigDecimal value = new BigDecimal(matcher.group().replace(",", ""));
            String suffix = normalizedExcerpt.substring(Math.min(matcher.end(), normalizedExcerpt.length()));
            if (suffix.stripLeading().startsWith("\u4e07")) value = value.multiply(new BigDecimal("10000"));
            if (value.compareTo(expected) != 0) continue;
            String before = normalizeContext(codePointSlice(segment.text(),
                    Math.max(0, candidate.excerptStart() - 48), candidate.excerptStart())
                    + normalizedExcerpt.substring(0, matcher.start()));
            String after = normalizeContext(normalizedExcerpt.substring(matcher.end())
                    + codePointSlice(segment.text(), candidate.excerptEnd(),
                            Math.min(codePointLength(segment.text()), candidate.excerptEnd() + 24)));
            if (hasNegativeSign(before) || startsWithAny(after, "%", "‰")) continue;
            if (candidate.factType() == FactType.LINE_ITEM_QUANTITY
                    && startsWithAny(after, "元", "圆", "万元", "万圆")) continue;
            if (candidate.factType() == FactType.CONTRACT_AMOUNT
                    && !contractAmountContext(before)) continue;
            return true;
        }
        return false;
    }

    private static boolean contractAmountContext(String before) {
        String value = before.toLowerCase(java.util.Locale.ROOT);
        int contract = lastIndexOfAny(value, "合同总金额", "合同金额", "合同总价", "合同价款",
                "contract amount", "total");
        int unrelated = lastIndexOfAny(value, "预算金额", "项目预算", "最高限价", "控制价",
                "投标报价", "调整比例", "费率", "比例", "budget");
        return contract >= 0 && contract > unrelated;
    }

    private static int lastIndexOfAny(String value, String... tokens) {
        int result = -1;
        for (String token : tokens) result = Math.max(result, value.lastIndexOf(token));
        return result;
    }

    private static boolean hasNegativeSign(String before) {
        String trimmed = before.stripTrailing();
        return !trimmed.isEmpty() && "-−﹣－".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
    }

    private static boolean startsWithAny(String value, String... tokens) {
        String trimmed = value.stripLeading();
        for (String token : tokens) if (trimmed.startsWith(token)) return true;
        return false;
    }

    private static String normalizeContext(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", " ");
    }

    private static boolean awardFinalContextProven(Segment segment, Candidate candidate) {
        int start = Math.max(0, candidate.excerptStart() - 80);
        int end = Math.min(codePointLength(segment.text()), candidate.excerptEnd() + 80);
        String context = normalizeText(codePointSlice(segment.text(), start, end));
        if (context == null || context.matches(".*(候选|预算|最高限价|控制价|投标报价).*")) return false;
        return switch (candidate.factType()) {
            case AWARD_AMOUNT -> containsAny(context, "中标金额", "成交金额", "中标价", "成交价");
            case AWARD_SUPPLIER -> containsAny(context, "中标供应商", "成交供应商", "中标人", "成交人");
            case AWARD_SUPPLIER_CREDIT_CODE -> containsAny(context, "中标供应商", "成交供应商", "中标人", "成交人")
                    && containsAny(context, "统一社会信用代码", "信用代码");
            default -> false;
        };
    }

    private static boolean containsAny(String value, String... labels) {
        for (String label : labels) if (value.contains(label)) return true;
        return false;
    }

    private static String normalizeText(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static Decision rejected(Candidate candidate, Reason reason) {
        return new Decision(candidate, Status.REJECTED, reason, null);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String codePointSlice(String value, int start, int end) {
        return value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public enum Status { ACCEPTED, REJECTED }
    public enum Reason {
        ACCEPTED, INPUT_AUTHORITY_MISMATCH, SOURCE_NOT_EXACT, CONTEXT_INVALID,
        ROLE_NOT_AUTHORIZED, PROJECT_IDENTITY_NOT_CONFIRMED, TYPED_VALUE_NOT_GROUNDED
    }

    public record CurrentInput(UUID projectId, UUID fileId, String fileSha256,
            UUID parseResultId, int parseVersion, String canonicalTextSha256,
            String canonicalText, DocumentRole effectiveRole, boolean sameProjectConfirmed) { }
    public record Decision(Candidate candidate, Status status, Reason reason,
            ExtractionResult publication) { }
    public record Validation(List<Decision> decisions, List<ExtractionResult> publications) {
        public Validation {
            decisions = List.copyOf(decisions);
            publications = List.copyOf(publications);
        }
    }
    private record Normalized(String valueText, String normalizedValue, BigDecimal valueAmount) { }
    private record Focus(int start, int end) { }
    private record PublicationEvidence(String text, int startOffset, int endOffset) { }
}
