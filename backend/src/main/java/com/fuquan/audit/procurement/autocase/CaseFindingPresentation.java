package com.fuquan.audit.procurement.autocase;

import java.util.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** Collapse repeated module opinions only when their issue and frozen sources match. */
final class CaseFindingPresentation {
    private CaseFindingPresentation() {}
    static Set<ReviewFinding> representatives(Map<String,ModuleReview> reviews) {
        var selected=new LinkedHashMap<String,ReviewFinding>();
        var modules=new ArrayList<>(reviews.keySet());
        var ungrounded=Collections.newSetFromMap(new IdentityHashMap<ReviewFinding,Boolean>());
        modules.sort(Comparator.comparingInt((String m)->m.equals("TENDER_COMPLIANCE")?0:m.equals("AGENT_PRECHECK")?1:2).thenComparing(m->m));
        for(String module:modules)for(var finding:reviews.get(module).findings()) {
            if(finding.evidence().isEmpty()){ungrounded.add(finding);continue;}
            String key=key(finding);var prior=selected.get(key);
            if(prior==null||rank(finding.level())<rank(prior.level()))selected.put(key,finding);
        }
        // Identity rather than record equality makes selection deterministic
        // even when two modules contain the exact same immutable record value.
        var result=Collections.newSetFromMap(new IdentityHashMap<ReviewFinding,Boolean>());result.addAll(selected.values());result.addAll(ungrounded);return result;
    }
    private static int rank(String level){return level.equals("CRITICAL")?0:level.equals("WARNING")?1:2;}
    private static String key(ReviewFinding f) {
        String title=f.title().replaceAll("[\\s，。；：:、（）()]","");
        if(f.code().equals("RESTRICTIVE_CONDITION"))title=title.replaceAll("(?:指定|限定|限制)(?:单一)?品牌","限定品牌");
        if(f.code().equals("QUOTE_SCOPE"))title=title.replace("采购要求","采购需求").replace("不符","不一致");
        var sources=f.evidence().stream().map(e->e.documentId()+"/"+e.sha256()+"/"+e.locator()+"/"+e.excerpt()).distinct().sorted().toList();
        return f.code()+"/"+title+"/"+sources;
    }
}
