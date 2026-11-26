package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.domain.ChineseNumber;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;
import static com.fuquan.audit.procurement.autocase.CaseReviewAiPort.*;

/** Recognizes an explicitly scored upgrade beyond an already eligible annual inspection frequency. */
final class CaseInspectionScoring {
    private static final String COUNT="([1-9][0-9]?|[一二两三四五六七八九十]{1,3})";
    private static final Pattern BASE = Pattern.compile(
            "(?:须覆盖|至少|不少于|不低于|最低要求为?)[ \\t]*(?:每年|一年|年度)[ \\t]*"+COUNT+"[ \\t]*次(?:上门)?巡检");
    private static final Pattern SCORES = Pattern.compile(
            "巡检[ \\t]*每年[ \\t]*"+COUNT+"次[ \\t]*(?:得|计)([0-9]+)分[、，,；;][ \\t]*"
            +COUNT+"次[ \\t]*(?:得|计)([0-9]+)分");
    private static final Pattern RESTRICTION = Pattern.compile(
            "只能|仅可|仅限|固定|最高|最多|上限|不得|不允许|不接受|不超过|否则|不得分|淘汰|否决");
    private static final Pattern EXTRA_CONCERN = Pattern.compile(
            "排斥|排他|歧视|倾斜|门槛|加价|价格|付款|验收|地域|品牌|资质|权重|过高|过大|不合理");
    private static final Pattern CONFLICT_TITLE = Pattern.compile(
            "(?:巡检(?:频次|频率|次数)(?:要求)?与评分(?:标准|细则)?|采购需求与评分(?:标准|细则)中巡检(?:频次|频率|次数))(?:不一致|存在冲突|不符)");

    private CaseInspectionScoring() {}

    static ReviewCheck compatibleUpgrade(Candidate candidate, Request request, List<SourceEvidence> cited) {
        if (!"TENDER_COMPLIANCE".equals(request.module()) || !"TECHNICAL_AMBIGUITY".equals(candidate.code())
                || !"WARNING".equals(candidate.level()) || cited.isEmpty()
                || !CONFLICT_TITLE.matcher(candidate.title()).matches()
                || EXTRA_CONCERN.matcher(candidate.explanation()).find()) return null;
        var documentId = cited.get(0).documentId();
        if (cited.stream().anyMatch(source -> !source.documentId().equals(documentId))) return null;
        var scope = request.documents().stream().filter(doc -> documentId.equals(doc.documentId())).findFirst().orElse(null);
        if (scope == null || !"TENDER".equals(scope.role()) || scope.sourceChars() != scope.suppliedChars()) return null;
        // Any restriction close to a visit count leaves the model's dispute open.
        // Review other clauses independently, including any genuine excessive or restrictive criteria.
        for (var source : request.evidence()) {
            if (!source.documentId().equals(documentId)) continue;
            for (String clause : source.excerpt().split("[。；;\\r\\n]+")) {
                if ((clause.contains("巡检") || clause.matches(".*"+COUNT+"次.*"))
                        && RESTRICTION.matcher(clause).find()) return null;
            }
        }
        for (var baseSource : cited) {
            var base = BASE.matcher(baseSource.excerpt());
            if (!base.find()) continue;
            var required = count(base.group(1));
            for (var scoreSource : cited) {
                var scores = SCORES.matcher(scoreSource.excerpt());
                if (!scores.find()) continue;
                var upper = count(scores.group(1));
                var lower = count(scores.group(3));
                if (required == null || upper == null || lower == null || required.compareTo(lower) != 0
                        || upper.compareTo(lower) <= 0 || scores.group(4).equals("0")
                        || new BigDecimal(scores.group(2)).compareTo(new BigDecimal(scores.group(4))) <= 0) continue;
                return new ReviewCheck("SCORING_METHOD","SOURCE_GROUNDED_OBSERVATION",
                        "每年巡检"+required+"次满足材料载明的最低要求，"+upper+"次对应更高的加分档。",
                        baseSource.equals(scoreSource)?List.of(baseSource):List.of(baseSource,scoreSource));
            }
        }
        return null;
    }

    private static BigDecimal count(String text) {
        return ChineseNumber.parse(text.replace("两","二")).orElse(null);
    }
}
