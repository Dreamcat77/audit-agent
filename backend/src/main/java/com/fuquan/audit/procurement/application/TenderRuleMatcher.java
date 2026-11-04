package com.fuquan.audit.procurement.application;

import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.util.regex.*;

/** Shared F10 text matching; a hit is a review signal, not a legal determination. */
public final class TenderRuleMatcher {
    private static final Pattern FORBIDDEN_EXAMPLE = Pattern.compile(
            "(?:禁止|不得|不应|严禁)采用的(?:反面|错误)?示例[：:\\s]*$");
    private TenderRuleMatcher() {}
    public static Matcher matcher(ProcurementTenderRule rule,String text) {
        if (rule.matchType().equals("REGEX")) return Pattern.compile(rule.matchContent(),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(text);
        String alternatives=java.util.Arrays.stream(rule.matchContent().split("\\|"))
                .map(String::trim).filter(s->!s.isEmpty()).map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        return Pattern.compile(alternatives,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(text);
    }
    public static boolean nextAffirmative(Matcher matcher,String text) {
        while(matcher.find()) {
            if (insideForbiddenExample(text, matcher.start(), matcher.end())) continue;
            int boundary=0;
            for(int i=matcher.end()-1;i>=0;i--)if("。；;\n，,".indexOf(text.charAt(i))>=0){boundary=i+1;break;}
            String clause=text.substring(boundary,matcher.end());
            if(clause.contains("不得不")||clause.contains("不能不"))return true;
            // Explicit prohibitions of restrictive conditions are counterevidence, not rule hits.
            if(Pattern.compile("(?:不得|不应|禁止|严禁)(?:以|要求|指定|限定|限制)|(?:不|未)(?:指定|限定|限制|要求)|无需|不设|不限|并非|不存在").matcher(clause).find())continue;
            return true;
        }
        return false;
    }
    private static boolean insideForbiddenExample(String text, int start, int end) {
        // Exclude only the explicitly prohibited quotation, never later operative conditions.
        int opening = Math.max(text.lastIndexOf('“', start), text.lastIndexOf('"', start));
        if (opening < 0 || start - opening > 600) return false;
        char closing = text.charAt(opening) == '“' ? '”' : '"';
        int close = text.indexOf(closing, opening + 1);
        if (close < end || close - opening > 600) return false;
        return FORBIDDEN_EXAMPLE.matcher(text.substring(Math.max(0, opening - 120), opening)).find();
    }
    public static boolean hasAffirmativeMatch(ProcurementTenderRule rule,String text) {
        return nextAffirmative(matcher(rule,text),text);
    }
}
