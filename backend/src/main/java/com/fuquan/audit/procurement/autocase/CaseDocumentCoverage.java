package com.fuquan.audit.procurement.autocase;

import java.util.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** Read/field/review coverage are different measurements, never inferred from finding count. */
final class CaseDocumentCoverage {
    private CaseDocumentCoverage() {}
    record Coverage(int segmentCount,int textChars,int factCount,int extractionCalls,int reviewInputChars) {}
    static Map<UUID,Coverage> of(List<Document> docs,Report report){
        var supplied=new HashMap<UUID,Integer>();
        if(report!=null)for(var review:report.reviews().entrySet()){
            if(!CaseReviewContext.MODULES.contains(review.getKey()))continue;
            for(var scope:review.getValue().scope())supplied.merge(scope.documentId(),scope.suppliedChars(),Math::max);
        }
        var result=new LinkedHashMap<UUID,Coverage>();
        for(var d:docs){
            var index=new ProcurementMaterialSemantics.Index(d);
            int facts=(int)d.facts().stream().filter(f->f.verification().equals("SOURCE_VERIFIED"))
                    .map(f->ProcurementMaterialSemantics.project(d,f,index)).flatMap(Optional::stream).distinct().count();
            result.put(d.id(),new Coverage(d.segments().size(),d.segments().stream().mapToInt(s->s.text().length()).sum(),
                    facts,d.calls(),supplied.getOrDefault(d.id(),0)));
        }
        return result;
    }
}
