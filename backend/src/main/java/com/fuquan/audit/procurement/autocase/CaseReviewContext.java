package com.fuquan.audit.procurement.autocase;

import java.util.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** Bounded goal-directed context, including technical documents with zero structured facts. */
public final class CaseReviewContext {
    public static final String VERSION="case-cross-file-v2";
    public static final List<String> MODULES=List.of("AGENT_PRECHECK","TENDER_COMPLIANCE");
    private static final int PER_FILE=12000,TOTAL=54000;
    private CaseReviewContext() {}
    public record Context(List<SourceEvidence> evidence,List<ReviewScope> scope,List<String> gaps,List<Document> documents) {}
    static String role(Document doc) {
        String title=doc.segments().isEmpty()?"":doc.segments().get(0).text().strip().split("[\\r\\n]",2)[0];
        // Cover/title first; mentions inside body text are not document classifications.
        for(String text:List.of(title,Objects.toString(CaseFactPolicy.scalar(doc,"documentType"),""))) {
            if(text.contains("技术需求"))return "TECHNICAL";
            if(text.contains("澄清")||text.contains("答复"))return "CLARIFICATION";
            if(text.contains("评审办法")||text.contains("评分办法"))return "EVALUATION";
            if(text.contains("招标文件")||text.contains("采购文件"))return "TENDER";
            if(text.contains("需求申请"))return "DEMAND";
            if(text.contains("预算")&&text.contains("批复"))return "BUDGET";
            if(text.contains("报价单"))return "QUOTATION";
            if(text.contains("中标")||text.contains("成交通知"))return "AWARD";
            if(text.contains("验收方案"))return "ACCEPTANCE_PLAN";
            if(text.contains("计划"))return "PLAN";
        }
        return "OTHER";
    }
    public static Context select(String module,List<Document> all) {
        var evidence=new ArrayList<SourceEvidence>();var scope=new ArrayList<ReviewScope>();var gaps=new ArrayList<String>();
        var selected=new ArrayList<Document>();int total=0;
        for(var doc:all) {
            String role=role(doc);
            boolean relevant=module.equals("AGENT_PRECHECK")||Set.of("TECHNICAL","TENDER","EVALUATION","CLARIFICATION","DEMAND").contains(role);
            if(!relevant)continue;
            selected.add(doc);
            if(doc.parseMethod()!=null&&doc.parseMethod().contains("PARTIAL"))gaps.add(doc.id()+":DOCUMENT_PARTIALLY_READ");
            if(!doc.status().equals("EXTRACTED"))gaps.add(doc.id()+":FILE_"+doc.status());
            int source=doc.segments().stream().mapToInt(s->s.text().length()).sum(),supplied=0;
            // Preserve the cover, then source paragraphs containing verified
            // facts, then remaining body. A tail-page budget must not disappear
            // merely because an introduction used the per-file context budget.
            var ordered=new LinkedHashSet<com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment>();
            if(!doc.segments().isEmpty())ordered.add(doc.segments().get(0));
            var factLocators=doc.facts().stream().filter(f->f.verification().equals("SOURCE_VERIFIED"))
                .map(Fact::locator).collect(java.util.stream.Collectors.toSet());
            doc.segments().stream().filter(s->factLocators.contains(s.locator())).forEach(ordered::add);
            ordered.addAll(doc.segments());
            for(var segment:ordered)for(int start=0;start<segment.text().length();) {
                int end=Math.min(start+1600,segment.text().length());
                if(end<segment.text().length()){int line=segment.text().lastIndexOf('\n',end);if(line>start)end=line+1;}
                String excerpt=segment.text().substring(start,end);int offset=start;start=end;
                if(supplied+excerpt.length()>PER_FILE||total+excerpt.length()>TOTAL)continue;
                String id="E-"+AuditCaseService.hash(doc.id()+"/"+doc.sha256()+"/"+segment.locator()+"/"+offset+"/"+excerpt).substring(0,24);
                evidence.add(new SourceEvidence(id,doc.id(),doc.name(),doc.sha256(),segment.locator(),excerpt));
                supplied+=excerpt.length();total+=excerpt.length();
            }
            scope.add(new ReviewScope(doc.id(),doc.name(),role,source,supplied));
            if(source==0)gaps.add(doc.id()+":NO_READABLE_TEXT");
            else if(source!=supplied)gaps.add(doc.id()+":CONTEXT_TRUNCATED");
        }
        var required=module.equals("TENDER_COMPLIANCE")?List.of("TECHNICAL","TENDER","EVALUATION","CLARIFICATION"):List.of("DEMAND","BUDGET","QUOTATION","AWARD");
        for(String role:required)if(scope.stream().noneMatch(s->s.role().equals(role)))gaps.add("UPLOADED_SCOPE_MISSING_"+role);
        return new Context(List.copyOf(evidence),List.copyOf(scope),List.copyOf(gaps),List.copyOf(selected));
    }
    static List<SourceEvidence> factEvidence(Context context,Document doc,Fact fact) {
        return context.evidence().stream().filter(e->e.documentId().equals(doc.id())&&e.locator().equals(fact.locator())&&e.excerpt().contains(fact.excerpt())).toList();
    }
}
