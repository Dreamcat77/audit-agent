package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.DocumentRoleSignal;
import com.fuquan.audit.contract.domain.project.DocumentRoleSignal.SignalType;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure deterministic signal extraction over one immutable canonical parse result. */
public final class DocumentRoleSignalExtractor {
    public static final String EXTRACTOR_VERSION = "document-role-signals-v2";
    private static final int TITLE_LIMIT = 240;
    private static final int FRONT_MATTER_LIMIT = 1_200;
    private static final int MAX_STRUCTURE_SPAN = 12_000;
    private static final int MAX_EXCERPT = 160;
    private static final int FLAGS = Pattern.MULTILINE | Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE;
    private static final String LINE_SPACE = "[\\p{Zs}\\t]*";
    private static final List<TextRule> TEXT_RULES = textRules();
    private static final List<StructureRule> STRUCTURE_RULES = structureRules();

    public DocumentRoleSignalExtractor() {
    }

    /**
     * Extracts evidence from server-owned canonical text. No file metadata is accepted by this API.
     */
    public List<DocumentRoleSignal> extract(ProjectFileParseResult parseResult) {
        if (parseResult == null
                || parseResult.parseStatus() != ProjectFileParseStatus.PARSED
                || parseResult.canonicalText() == null
                || parseResult.canonicalText().isBlank()
                || parseResult.canonicalTextSha256() == null) {
            throw new IllegalArgumentException("completed PARSED canonical input is required");
        }

        String text = parseResult.canonicalText();
        List<DocumentRoleSignal> signals = new ArrayList<>();
        for (TextRule rule : TEXT_RULES) {
            Match match = firstMatch(text, rule);
            if (match != null) {
                signals.add(signal(parseResult, text, rule.role(), rule.type(), rule.code(),
                        rule.weight(), match));
            }
        }
        for (StructureRule rule : STRUCTURE_RULES) {
            Match match = structureMatch(text, rule);
            if (match != null) {
                signals.add(signal(parseResult, text, rule.role(), SignalType.STRUCTURE, rule.code(),
                        rule.weight(), match));
            }
        }
        signals.sort(Comparator.comparing(DocumentRoleSignal::signalType)
                .thenComparing(DocumentRoleSignal::role)
                .thenComparing(DocumentRoleSignal::ruleCode)
                .thenComparingInt(DocumentRoleSignal::charStart)
                .thenComparingInt(DocumentRoleSignal::charEnd));
        return List.copyOf(signals);
    }

    private static Match firstMatch(String text, TextRule rule) {
        int regionEnd = switch (rule.scope()) {
            case TITLE -> utf16IndexAtMost(text, TITLE_LIMIT);
            case FRONT_MATTER -> utf16IndexAtMost(text, FRONT_MATTER_LIMIT);
            case WHOLE_TEXT -> text.length();
        };
        Matcher matcher = rule.pattern().matcher(text);
        matcher.region(0, regionEnd);
        if (!matcher.find()) {
            return null;
        }
        return new Match(matcher.start(), matcher.end(), normalize(matcher.group()));
    }

    private static Match structureMatch(String text, StructureRule rule) {
        int start = text.length();
        int end = -1;
        List<String> values = new ArrayList<>();
        for (Pattern requiredTerm : rule.requiredTerms()) {
            Matcher matcher = requiredTerm.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            start = Math.min(start, matcher.start());
            end = Math.max(end, matcher.end());
            values.add(normalize(matcher.group()));
        }
        int startCodePoint = text.codePointCount(0, start);
        int endCodePoint = text.codePointCount(0, end);
        if (endCodePoint - startCodePoint > MAX_STRUCTURE_SPAN) {
            return null;
        }
        return new Match(start, end, String.join(" | ", values));
    }

    private static DocumentRoleSignal signal(ProjectFileParseResult parseResult, String canonicalText,
            DocumentRole role, SignalType type, String ruleCode, BigDecimal weight, Match match) {
        String span = canonicalText.substring(match.start(), match.end());
        int charStart = canonicalText.codePointCount(0, match.start());
        int charEnd = canonicalText.codePointCount(0, match.end());
        String locator = "parse-result:%s:canonical-codepoint:%d-%d"
                .formatted(parseResult.parseResultId(), charStart, charEnd);
        return new DocumentRoleSignal(role, type, bound(match.normalizedValue()), ruleCode, weight,
                parseResult.parseResultId(), charStart, charEnd, locator, sha256(span), bound(span), EXTRACTOR_VERSION);
    }

    private static int utf16IndexAtMost(String text, int codePointLimit) {
        int count = text.codePointCount(0, text.length());
        return count <= codePointLimit ? text.length() : text.offsetByCodePoints(0, codePointLimit);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String bound(String value) {
        int count = value.codePointCount(0, value.length());
        if (count <= MAX_EXCERPT) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_EXCERPT));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static List<TextRule> textRules() {
        List<TextRule> rules = new ArrayList<>();
        rules.add(title(DocumentRole.CONTRACT_BODY, "ROLE_CONTRACT_TITLE",
                "合同协议书|建设工程合同|采购合同|服务合同|买卖合同|技术合同|合同书|合同"));
        rules.add(title(DocumentRole.AWARD_NOTICE, "ROLE_AWARD_TITLE", "中标通知书|成交通知书"));
        rules.add(title(DocumentRole.TENDER_DOCUMENT, "ROLE_TENDER_TITLE",
                "公开招标文件|邀请招标文件|竞争性磋商采购文件|招标文件|采购文件"));
        rules.add(title(DocumentRole.BID_RESPONSE, "ROLE_BID_TITLE", "投标文件|响应文件"));
        rules.add(title(DocumentRole.SUPPLEMENT, "ROLE_SUPPLEMENT_TITLE",
                "澄清与补充说明|补充协议书|补充协议|变更协议"));

        rules.add(front(DocumentRole.CONTRACT_BODY, "ROLE_CONTRACT_PARTY_A", "甲方(?:（[^）\\r\\n]{1,20}）)?[：:]"));
        rules.add(front(DocumentRole.CONTRACT_BODY, "ROLE_CONTRACT_PARTY_B", "乙方(?:（[^）\\r\\n]{1,20}）)?[：:]"));
        rules.add(front(DocumentRole.AWARD_NOTICE, "ROLE_AWARD_RECIPIENT", "(?:中标人|成交供应商)[：:]"));
        rules.add(front(DocumentRole.TENDER_DOCUMENT, "ROLE_TENDER_ISSUER", "(?:招标人|采购人)[：:]"));
        rules.add(front(DocumentRole.BID_RESPONSE, "ROLE_BID_SUBMITTER", "(?:投标人|响应人)[：:]"));
        rules.add(front(DocumentRole.SUPPLEMENT, "ROLE_SUPPLEMENT_ORIGINAL_REFERENCE", "原合同(?:名称|编号)[：:]"));

        rules.add(key(DocumentRole.CONTRACT_BODY, "ROLE_CONTRACT_EFFECTIVE_PHRASE", "双方签字盖章后生效|本合同自"));
        rules.add(key(DocumentRole.AWARD_NOTICE, "ROLE_AWARD_DETERMINATION_PHRASE", "被确定为中标人|被确定为成交供应商"));
        rules.add(key(DocumentRole.TENDER_DOCUMENT, "ROLE_TENDER_INSTRUCTION_PHRASE", "投标人须知|供应商须知"));
        rules.add(key(DocumentRole.BID_RESPONSE, "ROLE_BID_COMMITMENT_PHRASE", "投标函|响应函"));
        rules.add(key(DocumentRole.SUPPLEMENT, "ROLE_SUPPLEMENT_CHANGE_PHRASE", "对原合同作如下变更|本协议与原合同"));

        rules.add(heading(DocumentRole.CONTRACT_BODY, "ROLE_CONTRACT_HEADING", "合同价款|违约责任|争议解决"));
        rules.add(heading(DocumentRole.AWARD_NOTICE, "ROLE_AWARD_HEADING", "中标内容|中标金额|成交金额"));
        rules.add(heading(DocumentRole.TENDER_DOCUMENT, "ROLE_TENDER_HEADING", "招标公告|评标办法|合同条款及格式"));
        rules.add(heading(DocumentRole.BID_RESPONSE, "ROLE_BID_HEADING", "投标报价|商务响应|技术响应"));
        rules.add(heading(DocumentRole.SUPPLEMENT, "ROLE_SUPPLEMENT_HEADING", "变更内容|补充条款"));
        return List.copyOf(rules);
    }

    private static List<StructureRule> structureRules() {
        return List.of(
                structure(DocumentRole.CONTRACT_BODY, "ROLE_CONTRACT_STRUCTURE", "甲方", "乙方", "违约责任", "签字|盖章"),
                structure(DocumentRole.AWARD_NOTICE, "ROLE_AWARD_STRUCTURE", "项目名称", "中标人|成交供应商", "中标金额|成交金额"),
                structure(DocumentRole.TENDER_DOCUMENT, "ROLE_TENDER_STRUCTURE", "投标人须知|供应商须知", "评标办法", "合同条款"),
                structure(DocumentRole.BID_RESPONSE, "ROLE_BID_STRUCTURE", "投标函|响应函", "投标报价|报价一览", "法定代表人"),
                structure(DocumentRole.SUPPLEMENT, "ROLE_SUPPLEMENT_STRUCTURE", "原合同", "变更", "本协议"));
    }

    private static TextRule title(DocumentRole role, String code, String expression) {
        return new TextRule(role, SignalType.TITLE, code, new BigDecimal("3.00"),
                Pattern.compile("^" + LINE_SPACE + "(?:" + expression + ")" + LINE_SPACE + "$", FLAGS), Scope.TITLE);
    }

    private static TextRule front(DocumentRole role, String code, String expression) {
        return new TextRule(role, SignalType.FRONT_MATTER, code, new BigDecimal("1.25"),
                Pattern.compile(expression, FLAGS), Scope.FRONT_MATTER);
    }

    private static TextRule key(DocumentRole role, String code, String expression) {
        return new TextRule(role, SignalType.KEY_PHRASE, code, new BigDecimal("1.50"),
                Pattern.compile(expression, FLAGS), Scope.WHOLE_TEXT);
    }

    private static TextRule heading(DocumentRole role, String code, String expression) {
        return new TextRule(role, SignalType.HEADING, code, new BigDecimal("1.00"),
                Pattern.compile(expression, FLAGS), Scope.WHOLE_TEXT);
    }

    private static StructureRule structure(DocumentRole role, String code, String... requiredTerms) {
        List<Pattern> patterns = java.util.Arrays.stream(requiredTerms)
                .map(expression -> Pattern.compile(expression, FLAGS))
                .toList();
        return new StructureRule(role, code, new BigDecimal("2.00"), patterns);
    }

    private enum Scope {
        TITLE,
        FRONT_MATTER,
        WHOLE_TEXT
    }

    private record TextRule(DocumentRole role, SignalType type, String code, BigDecimal weight,
                            Pattern pattern, Scope scope) {
    }

    private record StructureRule(DocumentRole role, String code, BigDecimal weight, List<Pattern> requiredTerms) {
    }

    private record Match(int start, int end, String normalizedValue) {
    }
}
