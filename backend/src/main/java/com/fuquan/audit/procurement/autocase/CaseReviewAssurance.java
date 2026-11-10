package com.fuquan.audit.procurement.autocase;

import java.util.*;
import java.util.regex.Pattern;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** A citation proves what was printed, not that a financial calculation was executed. */
final class CaseReviewAssurance {
    private CaseReviewAssurance() {}
    private static final Pattern BUDGET=Pattern.compile("未(?:发现|见|显示)?[^。；;]{0,10}(?:超(?:出|过)?预算|预算异常)|(?:不|没有)超过预算|预算(?:核对|比对|校验)[^。；;]{0,8}(?:通过|一致|正常)");
    private static final Pattern CEILING=Pattern.compile("未(?:发现|见|显示)?[^。；;]{0,16}(?:超(?:出|过)?(?:最高)?限价|限价异常)|(?:不|没有)超过(?:最高)?限价");
    private static final Pattern SUM=Pattern.compile("(?:明细|分项|报价)[^。；;]{0,10}(?:加总|求和|累加)[^。；;]{0,16}(?<!不)(?:一致|相符|无误)|未(?:发现|见|显示)?[^。；;]{0,10}(?:加总|求和)[^。；;]{0,10}(?:不符|异常)|金额内部逻辑一致");

    static boolean supported(String text, Report report) {
        String claims=Arrays.stream(text.split("[。；;]"))
                .filter(s->!s.matches("(?s).*(?:不代表|不表明|不等于|不能证明|不能据此|未完成|尚未|无法确认|无法核算).*(?:预算|限价|加总|求和|金额内部逻辑).*"))
                .collect(java.util.stream.Collectors.joining("。"));
        if(BUDGET.matcher(claims).find()&&(report.budget()==null||report.offers().isEmpty()
                ||report.quoteComparisons().stream().anyMatch(c->!c.status().equals("WITHIN_BUDGET"))))return false;
        if(CEILING.matcher(claims).find()&&(report.ceilingAmount()==null||report.offers().isEmpty()
                ||report.ceilingComparisons().stream().anyMatch(c->!c.status().equals("WITHIN_CEILING"))))return false;
        return !SUM.matcher(claims).find()||!report.offers().isEmpty()&&report.offers().stream().allMatch(o->o.lineValidation().equals("VERIFIED"));
    }

    /** Historical reads and HTML use the same projection; persisted calls/results remain intact. */
    static Report project(Report report) {
        if(report==null)return null;
        var reviews=new LinkedHashMap<String,ModuleReview>();
        report.reviews().forEach((key,review)->{
            var checks=review.checks().stream().filter(c->!c.status().equals("SOURCE_GROUNDED_OBSERVATION")||supported(c.explanation(),report)).toList();
            int rejected=review.checks().size()-checks.size();
            reviews.put(key,rejected==0?review:new ModuleReview(review.status().equals("COMPLETED")?"PARTIAL":review.status(),review.inputDigest(),review.version(),review.model(),review.startedAt(),review.completedAt(),review.scope(),review.coverageGaps(),checks,review.findings(),review.rejectedCitations()+rejected,"UNSUPPORTED_FINANCIAL_ASSURANCE"));
        });
        var modules=new LinkedHashMap<>(report.modules());reviews.forEach((key,r)->modules.put(key,r.status()));
        return new Report(report.quoteStatus(),report.budget(),report.awardAmount(),report.offers(),report.calculation(),report.notices(),modules,reviews,report.projectName(),report.ceilingAmount());
    }
}
