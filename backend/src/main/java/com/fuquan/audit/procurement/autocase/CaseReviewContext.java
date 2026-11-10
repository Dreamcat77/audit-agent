package com.fuquan.audit.procurement.autocase;

import java.util.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** Bounded goal-directed context, including technical documents with zero structured facts. */
public final class CaseReviewContext {
    public static final String VERSION="case-cross-file-v7";
    public static final List<String> MODULES=List.of("AGENT_PRECHECK","TENDER_COMPLIANCE");
    private static final int PER_FILE=12000,TOTAL=54000;
    private CaseReviewContext() {}
    public record Context(List<SourceEvidence> evidence,List<ReviewScope> scope,List<String> gaps,List<Document> documents) {}
    private record Slice(String locator,int start,String text) {}
    static String role(Document doc) {
        return ProcurementMaterialSemantics.role(doc);

    }
    public static Context select(String module,List<Document> all) {
        var evidence=new ArrayList<SourceEvidence>();var scope=new ArrayList<ReviewScope>();var gaps=new ArrayList<String>();
        var selected=all.stream().filter(d->module.equals("AGENT_PRECHECK")||Set.of("TECHNICAL","TENDER","EVALUATION","CLARIFICATION","DEMAND","BID","QUOTATION","EVALUATION_REPORT").contains(role(d))).toList();
        int total=0,remaining=selected.size();
        for(var doc:selected) {
            String role=role(doc);
            int allowance=Math.min(PER_FILE,(TOTAL-total)/Math.max(1,remaining--));
            if(doc.parseMethod()!=null&&doc.parseMethod().contains("PARTIAL"))gaps.add(doc.id()+":DOCUMENT_PARTIALLY_READ");
            if(!doc.status().equals("EXTRACTED"))gaps.add(doc.id()+":FILE_"+doc.status());
            int source=doc.segments().stream().mapToInt(s->s.text().length()).sum(),supplied=0,eligible=0;
            var index=new ProcurementMaterialSemantics.Index(doc);
            for(var segment:doc.segments())for(var range:index.ranges(segment))eligible+=range[1]-range[0];
            var slices=new ArrayList<Slice>();
            int size=Math.min(1600,Math.max(128,allowance/8));
            for(var segment:doc.segments())for(var range:index.ranges(segment))for(int start=range[0];start<range[1];){
                int end=Math.min(start+size,range[1]);
                if(end<range[1]){int line=segment.text().lastIndexOf('\n',end);if(line>start)end=line+1;}
                slices.add(new Slice(segment.locator(),start,segment.text().substring(start,end)));start=end;
            }
            // One representative per topic precedes extra identity/quote rows.
            // Slice within long paragraphs, so a first-page introduction cannot
            // consume the places reserved for tail-page scoring and payment.
            var ordered=new LinkedHashSet<Slice>();
            if(!slices.isEmpty())ordered.add(slices.get(0));
            var factLocators=doc.facts().stream().filter(f->f.verification().equals("SOURCE_VERIFIED"))
                .map(Fact::locator).collect(java.util.stream.Collectors.toSet());
            // Give each business topic a place before consuming the remaining
            // context on introductions. Historical appendices never supply current facts.
            var topics=module.equals("TENDER_COMPLIANCE")?List.of("采购需求|技术要求|技术参数","评分|评审|评标","付款|支付|结算","资格|品牌|授权|注册|业绩","澄清|更正|答复")
                    :List.of("预算|最高限价|采购资金","投标总价|报价|中标|成交","项目名称|项目编号|采购人|采购代理","交付|服务期限|验收|日期");
            for(String topic:topics)slices.stream().filter(s->!indexOnly(s.text())&&java.util.regex.Pattern.compile(topic).matcher(s.text()).find())
                    .sorted(Comparator.comparingInt((Slice s)->topicScore(s.text(),topic)).reversed()).limit(2).forEach(ordered::add);
            slices.stream().filter(s->factLocators.contains(s.locator())).forEach(ordered::add);
            slices.stream().filter(s->!indexOnly(s.text())&&s.text().codePoints().filter(Character::isLetter).count()>=12)
                    .sorted(Comparator.comparingInt((Slice s)->topics.stream().mapToInt(t->topicScore(s.text(),t)).max().orElse(0)).reversed()).forEach(ordered::add);
            for(var slice:ordered) {
                String excerpt=slice.text();
                if(supplied+excerpt.length()>allowance||total+excerpt.length()>TOTAL)continue;
                String id="E-"+AuditCaseService.hash(doc.id()+"/"+doc.sha256()+"/"+slice.locator()+"/"+slice.start()+"/"+excerpt).substring(0,24);
                evidence.add(new SourceEvidence(id,doc.id(),doc.name(),doc.sha256(),slice.locator(),excerpt));
                supplied+=excerpt.length();total+=excerpt.length();
            }
            scope.add(new ReviewScope(doc.id(),doc.name(),role,source,supplied));
            if(source==0)gaps.add(doc.id()+":NO_READABLE_TEXT");
            else if(eligible!=supplied)gaps.add(doc.id()+":CONTEXT_TRUNCATED");
        }
        // A procurement stage does not require every later-stage document to exist.
        // The uploaded inventory and model observations explain what can be checked.
        return new Context(List.copyOf(evidence),List.copyOf(scope),List.copyOf(gaps),List.copyOf(selected));
    }
    private static boolean indexOnly(String text){return text.stripLeading().startsWith("目录")||text.matches("(?s).*[.．…·]{2,}\\s*\\d{1,4}\\s*.*");}
    private static int topicScore(String text,String topic) {
        var matches=java.util.regex.Pattern.compile(topic).matcher(text);int score=0;
        while(matches.find())score+=2;
        if(score==0)return 0;
        String compact=text.replaceAll("\\s+","");
        if(topic.matches(".*(?:付款|支付|结算).*")) {
            if(compact.matches("(?s).*(?:[0-9一二三四五六七八九十]+(?:%|％)|[0-9一二三四五六七八九十]+(?:个工作日|日内|天内)).*"))score+=24;
        } else if(topic.matches(".*(?:交付|服务期限|验收|日期).*")) {
            if(compact.matches("(?s).*(?:建设期|交付|签订后|生效后|运维期|维护期|质保期|服务期限).{0,25}[0-9一二三四五六七八九十]+(?:年|月|日|天).*"))score+=24;
        } else if(topic.matches(".*(?:报价|中标|预算|限价).*")) {
            if(compact.matches("(?s).*(?:[0-9]+[,.0-9]*|[壹贰叁肆伍陆柒捌玖拾佰仟万]+)(?:元|万元|亿元).*"))score+=12;
            if(compact.matches("(?s).*(?:开标一览表|报价明细|分项报价|投标报价[（(]元).*"))score+=8;
        } else if(topic.matches(".*(?:评分|评审|评标).*")) {
            if(compact.matches("(?s).*(?:评分标准|评分指标|评分细则|自由酌情|满分|分值构成|[0-9]+分).*"))score+=12;
        }
        if(text.matches("(?s).*(?:索引表|资格条件及符合性要求响应表|按招标文件要求|符合招标文件要求).*"))score-=8;
        return score;
    }
    static List<SourceEvidence> factEvidence(Context context,Document doc,Fact fact) {
        return context.evidence().stream().filter(e->e.documentId().equals(doc.id())&&e.locator().equals(fact.locator())&&e.excerpt().contains(fact.excerpt())).toList();
    }
}
