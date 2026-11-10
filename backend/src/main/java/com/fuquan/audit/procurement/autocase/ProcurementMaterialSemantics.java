package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import java.text.Normalizer;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** Current procurement facts are separate from historical evidence, actor roles and price bases. */
final class ProcurementMaterialSemantics {
    private ProcurementMaterialSemantics() {}
    static String key(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+","");}
    private static final Pattern REFERENCE=Pattern.compile(".*(?:类似项目业绩|同类项目业绩|历史业绩|历史合同|业绩证明|业绩合同|业绩情况表|主要业绩|财务报表|财务审计报告|资产负债表|利润表|现金流量表|人员履历|个人简历|项目经验).*" );
    private static final Pattern CURRENT=Pattern.compile("(?:第[一二三四五六七八九十百0-9]+[章部分节].*|(?:招标文件|投标文件|投标函|投标报价表|开标一览表|报价一览表|分项报价表|采购需求|采购要求|评审办法|评分办法|澄清公告|中标通知书))");
    private static boolean referenceHeading(String value){
        // A sentence mentioning historical evidence is not a chapter boundary.
        // In particular, prohibitions must not hide the rest of a tender from review.
        return REFERENCE.matcher(value).matches()&&!value.matches(".*[。；;，,：:].*")
                &&!value.matches(".*(?:不得|不应|禁止|无需|应当|应提供|须提供|需提供|不能|作为|不属于).*" );
    }
    record Section(int start,boolean reference,String title) {}
    static final class Index {
        private final Document doc;
        private final Map<String,List<Section>> sections=new HashMap<>();
        private final Map<String,String> texts=new HashMap<>();
        Index(Document doc){
            this.doc=doc;boolean reference=false;String title="";
            for(var segment:doc.segments()){
                texts.put(segment.locator(),segment.text());
                var entries=new ArrayList<Section>();entries.add(new Section(0,reference,title));int offset=0;
                for(String line:segment.text().split("\n",-1)){
                    String heading=line.strip();
                    if(heading.length()<=100&&!heading.matches(".*(?:[.．…·]{2,}|\\s{2,})\\s*\\d{1,4}\\s*$")){
                        if(referenceHeading(heading)){reference=true;title=heading;}
                        else if(CURRENT.matcher(heading).matches()){reference=false;title=heading;}
                    }
                    entries.add(new Section(offset,reference,title));offset+=line.length()+1;
                }
                sections.put(segment.locator(),List.copyOf(entries));
            }
        }
        Section at(String locator,int offset){
            Section result=new Section(0,false,"");
            for(var entry:sections.getOrDefault(locator,List.of())){if(entry.start()>offset)break;result=entry;}
            return result;
        }
        boolean current(String locator,String excerpt,String value,String subjectScope){
            if(Set.of("REFERENCE","HISTORICAL","OTHER_PROJECT").contains(Objects.toString(subjectScope,"")))return false;
            if(subjectScope!=null&&!Set.of("CURRENT_PROJECT","GENERAL","UNKNOWN").contains(subjectScope))return false;
            String text=texts.get(locator);
            if(text!=null){
                int from=0,quote;
                while((quote=text.indexOf(excerpt,from))>=0){
                    int valueAt=excerpt.indexOf(value);
                    if(at(locator,quote+Math.max(0,valueAt)).reference())return false;
                    from=quote+Math.max(1,excerpt.length());
                }
            }
            return !excerpt.matches("(?s).*(?:历史业绩|业绩项目|原项目|往年项目)[：:].*"+Pattern.quote(value)+".*");
        }
        boolean currentSegment(String locator,int offset){return !at(locator,offset).reference();}
        List<int[]> ranges(MaterialFieldAiPort.Segment segment){
            var result=new ArrayList<int[]>();int start=0;boolean current=currentSegment(segment.locator(),0);
            for(var entry:sections.getOrDefault(segment.locator(),List.of())){
                if(current!=!entry.reference()){
                    if(current&&entry.start()>start)result.add(new int[]{start,entry.start()});
                    start=entry.start();current=!entry.reference();
                }
            }
            if(current&&start<segment.text().length())result.add(new int[]{start,segment.text().length()});
            return result;
        }
    }
    static Optional<Fact> project(Document doc,Fact fact,Index index){
        if(!index.current(fact.locator(),fact.excerpt(),fact.value(),fact.subjectScope()))return Optional.empty();
        String type=fact.type();String local=local(fact.excerpt(),fact.value());
        if(type.equals("purchaserName")){
            int valueAt=fact.excerpt().indexOf(fact.value());
            String before=valueAt<0?"":fact.excerpt().substring(Math.max(0,valueAt-100),valueAt);
            var roles=Pattern.compile("采购代理(?:机构)?|招标代理(?:机构)?|代理机构|受托代理|投标人|报价人|响应供应商|投标单位|报价单位|采购人|采购单位|招标人|委托方").matcher(before);
            String last="";while(roles.find())last=roles.group();
            if(last.matches(".*(?:代理).*"))type="agencyName";
            else if(last.matches(".*(?:投标|报价|供应商).*"))type="supplierName";
        }
        if(type.equals("projectNo")&&local.matches("(?s).*(?:统一社会信用代码|营业执照|合同编号).*"))return Optional.empty();
        String amountScope=amountScope(fact,local);
        return Optional.of(new Fact(fact.row(),type,fact.value(),fact.dataType(),fact.locator(),fact.excerpt(),fact.verification(),fact.model(),fact.extractionVersion(),fact.subjectScope(),amountScope));
    }
    private static String local(String excerpt,String value){
        int at=excerpt.indexOf(value);if(at<0)return excerpt;
        int start=Math.max(0,at-100),end=Math.min(excerpt.length(),at+value.length()+24);
        int line=excerpt.lastIndexOf('\n',at);if(line>=start)start=line+1;
        for(char separator:new char[]{'；',';','。'}){int p=excerpt.lastIndexOf(separator,at);if(p>=start)start=p+1;}
        return excerpt.substring(start,end);
    }
    private static String amountScope(Fact f,String context){
        if(!f.type().endsWith("Amount")&&!f.type().equals("unitPrice"))return f.amountScope();
        if(f.type().equals("unitPrice")||context.matches("(?s).*(?:单价|每台|每套|每人|单位价格).*"))return "UNIT";
        if(context.matches("(?s).*(?:每年|年度报价|年报价|[/／]年).*"))return "ANNUAL";
        if(context.matches("(?s).*(?:第[一二三四五六七八九十0-9]+(?:包|标段)|(?<![A-Za-z0-9])[A-Za-z0-9]+包|包[一二三四五六七八九十0-9]+|分包|采购包).*"))return "PACKAGE";
        // A one-year service can still have an explicitly all-inclusive total.
        // Duration alone is not a recurring annual price. Explicit per-year and
        // package wording above retains precedence over these total markers.
        if(context.matches("(?s).*(?:整体|全项目|本项目(?:含税)?总|(?:报价|中标|成交)(?:总价|总额)|涵盖全部|包含全部).*"))return "TOTAL";
        return f.amountScope();
    }
    static boolean total(Fact f){return (f.subjectScope()==null||f.subjectScope().equals("CURRENT_PROJECT"))&&(f.amountScope()==null||f.amountScope().equals("TOTAL"));}
    static String moneyText(String value){return value.replaceFirst("(?:[/／]年|每年|[/／]月|每月)$","").strip();}
    private static final Pattern MONEY_UNIT=Pattern.compile("(?:单位\\s*(?:[：:]|为)?\\s*|((?:预算金额|预算总额|最高限价|中标金额|成交金额|投标报价|响应报价|报价金额|报价|单价|金额|总价|价格))\\s*[（(]\\s*(?:人民币\\s*[：:]?\\s*)?)(亿[元圆]|万[元圆]|千[元圆]|[元圆])");
    private static Set<String> units(String text){var values=new HashSet<String>();var m=MONEY_UNIT.matcher(text);while(m.find())values.add(m.group(2));return values;}
    static Optional<BigDecimal> money(Document doc,Fact f){return money(doc,f.value(),f.excerpt(),f.locator());}
    static Optional<BigDecimal> money(Document doc,String raw,String excerpt){
        var locations=doc.segments().stream().filter(s->s.text().contains(excerpt)).map(MaterialFieldAiPort.Segment::locator).toList();
        if(locations.size()<=1)return money(doc,raw,excerpt,locations.isEmpty()?null:locations.get(0));
        var values=new TreeSet<BigDecimal>();
        for(String locator:locations){var value=money(doc,raw,excerpt,locator);if(value.isEmpty())return Optional.empty();values.add(value.get());}
        return values.size()==1?Optional.of(values.first()):Optional.empty();
    }
    static Optional<BigDecimal> money(Document doc,String raw,String excerpt,String locator){
        String value=moneyText(raw);
        var parsed=com.fuquan.audit.common.domain.RmbAmountParser.parse(value);
        if(parsed.isEmpty()||value.matches("(?s).*[元圆圓角分万萬亿億千仟币].*"))return parsed;
        int at=excerpt.indexOf(raw);if(at<0)return Optional.empty();
        // A partial value such as 22 in 22万元 must not acquire an implicit yuan unit.
        String suffix=excerpt.substring(at+raw.length());
        if(suffix.matches("(?s)^[元圆圓万萬亿億千仟].*"))return Optional.empty();
        var units=units(excerpt);
        String source=doc.segments().stream().filter(s->Objects.equals(s.locator(),locator)&&s.text().contains(excerpt)).map(MaterialFieldAiPort.Segment::text).findFirst().orElse(null);
        if(units.isEmpty()&&source!=null)units=units(source);
        if(units.size()>1)return Optional.empty();
        if(units.size()==1){
            String unit=units.iterator().next();
            BigDecimal factor=unit.startsWith("亿")?new BigDecimal("100000000"):unit.startsWith("万")?new BigDecimal("10000"):unit.startsWith("千")?new BigDecimal("1000"):BigDecimal.ONE;
            BigDecimal scaled=parsed.get().multiply(factor);
            return scaled.precision()<=18?Optional.of(scaled):Optional.empty();
        }
        // Units elsewhere in a long bid (registered capital, prior contracts,
        // financial statements) do not alter this price table's currency unit.
        return source==null||doc.segments().stream().anyMatch(s->s.text().matches("(?s).*(万元|萬元|亿元|億元|千元).*"))?Optional.empty():parsed;
    }
    static String amountExcerpt(Document doc,String excerpt,String locator){
        if(!units(excerpt).isEmpty())return excerpt;
        String text=doc.segments().stream().filter(s->s.locator().equals(locator)&&s.text().contains(excerpt)).map(MaterialFieldAiPort.Segment::text).findFirst().orElse(null);
        if(text==null||units(text).size()!=1)return excerpt;
        int at=text.indexOf(excerpt),bestStart=-1,bestEnd=-1,bestSize=Integer.MAX_VALUE;
        var header=MONEY_UNIT.matcher(text);
        while(header.find()){
            int start=Math.min(at,header.start()),end=Math.max(at+excerpt.length(),header.end());
            if(end-start<=1600&&end-start<bestSize){bestStart=start;bestEnd=end;bestSize=end-start;}
        }
        return bestStart<0?excerpt:text.substring(bestStart,bestEnd);
    }
    static boolean compatibleTableRole(Document doc,String type,String excerpt,String locator){
        if(!Set.of("budgetAmount","ceilingAmount","quoteAmount","awardAmount").contains(type)
                ||excerpt.matches("(?s).*(?:预算|限价|采购资金|财政资金|报价|投标总价|中标|成交|单价|合同金额|付款|保证金).*"))return true;
        String source=doc.segments().stream().filter(s->s.locator().equals(locator)&&s.text().contains(excerpt)).map(MaterialFieldAiPort.Segment::text).findFirst().orElse("");
        var roles=new HashSet<String>();var m=MONEY_UNIT.matcher(source);
        while(m.find())if(m.group(1)!=null){String label=m.group(1);if(label.contains("预算"))roles.add("budgetAmount");
            else if(label.contains("限价"))roles.add("ceilingAmount");else if(label.contains("报价"))roles.add("quoteAmount");
            else if(label.contains("中标")||label.contains("成交"))roles.add("awardAmount");}
        if(roles.isEmpty()&&source.stripLeading().matches("(?s)^(?:[0-9.]+\\s*[、.．]?\\s*)?(?:开标一览表|分项目明细报价表|报价分类明细表|报价明细表|投标报价表).*"))roles.add("quoteAmount");
        return roles.size()!=1||roles.contains(type);
    }
    static boolean amountRole(String type,String value,String excerpt,String subjectScope,String amountScope){
        String expected=switch(type){case "budgetAmount"->"BUDGET";case "ceilingAmount"->"CEILING";case "quoteAmount"->"QUOTE";case "awardAmount"->"AWARD";default->null;};
        if(expected==null)return true;
        var labels=Pattern.compile("最高(?:投标|响应|采购)?限价|投标最高限价|预算控制价|经费控制上限|采购控制价|预算|采购资金|财政资金|经费安排|批复金额|投标(?:总价|报价|价格|价)|报价(?:合计|总额|金额|总价)?|响应(?:总价|报价)|总报价|(?:中标|成交)(?:金额|价格|总价|价)|保证金|合同(?:金额|总价)|单价|付款金额|营业收入|利润");
        var roles=new HashSet<String>();int from=0,at;
        while((at=excerpt.indexOf(value,from))>=0){
            from=at+Math.max(1,value.length());
            // A model can quote a numeric substring of a different amount.
            if(at>0&&Character.isDigit(value.charAt(0))&&(Character.isDigit(excerpt.charAt(at-1))||".,，．".indexOf(excerpt.charAt(at-1))>=0))return false;
            int after=at+value.length();
            if(Character.isDigit(value.charAt(value.length()-1))&&after<excerpt.length()&&(Character.isDigit(excerpt.charAt(after))||".,，．".indexOf(excerpt.charAt(after))>=0))return false;
            var matches=labels.matcher(excerpt.substring(Math.max(0,at-180),at));String last=null;
            while(matches.find())last=matches.group();
            // Semantic extraction handles natural language and table headings.
            // Explicitly contradictory monetary roles still reject the candidate.
            if(last==null){
                if(!"CURRENT_PROJECT".equals(subjectScope)||!Set.of("TOTAL","ANNUAL","PACKAGE").contains(Objects.toString(amountScope,"")))return false;
                roles.add(expected);continue;
            }
            String role=last.matches(".*(?:限价|控制价|经费控制上限).*")?"CEILING":last.matches(".*(?:预算|采购资金|财政资金|经费安排|批复金额).*")?"BUDGET":last.matches(".*(?:报价|投标总价|投标价格|投标价|响应总价).*")?"QUOTE":last.matches(".*(?:中标|成交).*")?"AWARD":"OTHER";
            roles.add(role);
        }
        return roles.size()==1&&roles.contains(expected);
    }
    static MaterialFieldAiPort.Context context(Document doc,List<MaterialFieldAiPort.Segment> selected){
        var cover=new StringBuilder();
        for(var segment:doc.segments()){
            if(cover.length()>=800)break;
            if(!cover.isEmpty())cover.append('\n');
            cover.append(segment.text(),0,Math.min(segment.text().length(),800-cover.length()));
        }
        String header=cover.toString();
        String section=selected.isEmpty()?"":new Index(doc).at(selected.get(0).locator(),0).title();
        return new MaterialFieldAiPort.Context(header,section);
    }
    static String role(Document doc){
        var hints=new ArrayList<String>();
        doc.facts().stream().filter(f->f.type().equals("documentType")&&f.verification().equals("SOURCE_VERIFIED"))
                .map(Fact::value).forEach(hints::add);
        doc.segments().stream().limit(2).flatMap(s->s.text().lines().limit(3)).forEach(hints::add);
        hints.add(doc.name());
        for(String title:hints){
            if(title.length()>160||title.matches(".*(?:引用|参照|例如|包括|附件含|不改变|不得|应当|以下简称|依据).*")||title.contains("、")||title.contains("。"))continue;
            if(title.contains("投标文件")||title.contains("响应文件"))return "BID";
            if(title.contains("技术需求")||title.contains("需求标准")||title.contains("采购需求"))return "TECHNICAL";
            if(title.contains("澄清")||title.contains("答复"))return "CLARIFICATION";
            if(title.contains("评审办法")||title.contains("评分办法"))return "EVALUATION";
            if(title.contains("招标文件")||title.contains("采购文件"))return "TENDER";
            if(title.contains("需求申请")||title.contains("采购申请"))return "DEMAND";
            if(title.contains("预算")&&(title.contains("批复")||title.contains("通知")))return "BUDGET";
            if(title.contains("报价单"))return "QUOTATION";
            if(title.contains("中标")||title.contains("成交通知"))return "AWARD";
            if(title.contains("评标报告")||title.contains("评审报告"))return "EVALUATION_REPORT";
            if(title.contains("验收方案"))return "ACCEPTANCE_PLAN";
            if(title.contains("计划"))return "PLAN";
        }
        return "OTHER";
    }
}
