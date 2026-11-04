package com.fuquan.audit.contract.application.clause;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only explicit trigger, boundary operator and parties can establish a termination match. */
record ClauseTerminationIdentity(String triggerType, String thresholdKey,
        String breachedParty, String entitledParty) {
    static final Pattern THRESHOLD = Pattern.compile(
            "(?:延期|逾期)(?:交付|付款|交货|供货)?(?:解除(?:条件|阈值)\\s*[:：]?\\s*)?\\s*"
                    + "(超过|满|达到)\\s*([0-9一二两三四五六七八九十百]+)\\s*"
                    + "(个?工作日|个?自然日|个?日|天|周|个月|月|年)");
    private static final Pattern BREACHING_PARTY = Pattern.compile(
            "(甲方|乙方)(?=[^甲乙。！？；;\\r\\n]{0,16}(?:延期|逾期|不符合|不合格|重大缺陷))");
    private static final Pattern ENTITLED_PARTY = Pattern.compile(
            "(甲方|乙方)(?=[^甲乙延逾。！？；;\\r\\n]{0,16}(?:解除|终止))");

    static ClauseTerminationIdentity from(String clause) {
        String trigger = clause.contains("延期") || clause.contains("逾期") ? "DELAY"
                : clause.contains("不符合") || clause.contains("重大缺陷") ? "NON_CONFORMITY"
                : clause.contains("不可抗力") ? "FORCE_MAJEURE"
                : clause.contains("严重违约") ? "MATERIAL_BREACH"
                : clause.contains("协商一致") ? "MUTUAL_AGREEMENT" : null;
        return new ClauseTerminationIdentity(trigger, thresholdKey(clause),
                party(BREACHING_PARTY, clause), party(ENTITLED_PARTY, clause));
    }

    static String thresholdKey(String clause) {
        Matcher match = clause == null ? null : THRESHOLD.matcher(clause);
        if (match == null || !match.find()) return null;
        String operator = match.group(1).equals("超过") ? "GT" : "GE";
        return operator + ":" + ClauseDurationNormalizer.key(match.group(2), match.group(3));
    }

    boolean complete() {
        return "MUTUAL_AGREEMENT".equals(triggerType) || triggerType != null
                && breachedParty != null && entitledParty != null
                && (!"DELAY".equals(triggerType) || thresholdKey != null);
    }

    private static String party(Pattern syntax, String clause) {
        Matcher match = syntax.matcher(clause);
        return match.find() ? match.group(1) : null;
    }
}
