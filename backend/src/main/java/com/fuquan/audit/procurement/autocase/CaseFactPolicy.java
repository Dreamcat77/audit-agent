package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.domain.RmbAmountParser;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import java.math.*;
import java.util.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** AI supplies cited candidates; Java alone validates, associates and calculates. */
public final class CaseFactPolicy {
    private CaseFactPolicy() {}
    public static String projectName(AuditCase value) {
        if(value.report()!=null&&value.report().projectName()!=null)return value.report().projectName();
        return com.fuquan.audit.common.material.ProjectNameResolver.resolve(value.documents().stream()
                .flatMap(d->facts(d,"projectName").stream()).map(Fact::value).toList(),
                value.documents().stream().flatMap(d->d.segments().stream()).map(MaterialFieldAiPort.Segment::text).toList(),
                "采购材料分析 " + value.createdAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate());
    }
    static final Set<String> TYPES=Set.of("documentType","projectName","projectNo","purchaserName","agencyName","packageName",
            "budgetAmount","ceilingAmount","supplierName","quoteAmount","awardAmount","productDescription",
            "itemName","quantity","unitPrice","lineAmount");
    public static List<Fact> validate(Document doc,MaterialFieldAiPort.Result reply) {
        if(reply==null||reply.rows()==null||reply.model()==null||reply.promptVersion()==null)throw new IllegalArgumentException("invalid extraction");
        var out=new LinkedHashSet<Fact>();
        var index=new ProcurementMaterialSemantics.Index(doc);
        if(reply.rows().size()>10000)throw new IllegalArgumentException("extraction limit");
        for(var row:reply.rows())for(var f:row.facts()) {
            if(f==null||!TYPES.contains(f.field())||f.value()==null||f.value().isBlank()||f.value().length()>2000
                    ||f.excerpt()==null||f.excerpt().isBlank()||f.excerpt().length()>2400||f.locator()==null)continue;
            boolean grounded=f.excerpt().contains(f.value())&&doc.segments().stream().anyMatch(s->s.locator().equals(f.locator())&&s.text().contains(f.excerpt()));
            boolean amount=f.field().endsWith("Amount")||f.field().equals("unitPrice");
            boolean typed=!amount||ProcurementMaterialSemantics.money(doc,f.value(),f.excerpt(),f.locator()).isPresent();
            boolean role=ProcurementMaterialSemantics.amountRole(f.field(),f.value(),f.excerpt(),f.subjectScope(),f.amountScope())
                    &&ProcurementMaterialSemantics.compatibleTableRole(doc,f.field(),f.excerpt(),f.locator());
            String excerpt=amount&&grounded&&typed?ProcurementMaterialSemantics.amountExcerpt(doc,f.excerpt(),f.locator()):f.excerpt();
            var candidate=new Fact(row.rowKey(),f.field(),f.value(),amount?"MONEY":f.field().equals("quantity")?"DECIMAL":"TEXT",
                    f.locator(),excerpt,grounded&&typed&&role?"SOURCE_VERIFIED":"UNVERIFIED",reply.model(),reply.promptVersion(),f.subjectScope(),f.amountScope());
            ProcurementMaterialSemantics.project(doc,candidate,index).ifPresent(out::add);
            if(out.size()>10000)throw new IllegalArgumentException("fact limit");
        }
        return List.copyOf(out);
    }
    static List<Fact> facts(Document d,String type){
        var index=new ProcurementMaterialSemantics.Index(d);
        return d.facts().stream().filter(f->f.verification().equals("SOURCE_VERIFIED"))
                .map(f->ProcurementMaterialSemantics.project(d,f,index)).flatMap(Optional::stream).filter(f->f.type().equals(type)).distinct().toList();
    }
    static List<Fact> totals(Document d,String type){return facts(d,type).stream().filter(ProcurementMaterialSemantics::total).toList();}
    static String scalar(Document d,String type){var values=facts(d,type).stream().map(Fact::value)
        .map(v->Set.of("supplierName","purchaserName","agencyName").contains(type)?ProcurementMaterialSemantics.key(v):v.strip()).distinct().toList();return values.size()==1?values.get(0):null;}
    static BigDecimal amount(Document d,String type) {
        var values=new TreeSet<BigDecimal>();totals(d,type).forEach(f->ProcurementMaterialSemantics.money(d,f).ifPresent(values::add));
        return values.size()==1?values.first():null;
    }
    static boolean quoteDocument(Document d) {
        var type=scalar(d,"documentType");
        if(type!=null&&(type.contains("中标通知")||type.contains("成交通知")))return false;
        return scalar(d,"supplierName")!=null&&amount(d,"quoteAmount")!=null
                ||type!=null&&(type.contains("报价单")||type.contains("询价报价"));
    }
    static boolean needsSupplement(Document d) {
        String type=scalar(d,"documentType");
        return type==null || quoteDocument(d)&&(scalar(d,"supplierName")==null||amount(d,"quoteAmount")==null||facts(d,"itemName").isEmpty())
                ||type.contains("预算")&&type.contains("批复")&&amount(d,"budgetAmount")==null
                ||(type.contains("中标")||type.contains("成交"))&&amount(d,"awardAmount")==null;
    }
    public static Report report(UUID caseId,List<Document> docs) {
        var notices=new ArrayList<String>();
        var budgets=new TreeSet<BigDecimal>();var awards=new TreeSet<BigDecimal>();var ceilings=new TreeSet<BigDecimal>();
        var identities=new HashSet<String>();var offers=new ArrayList<Offer>();
        var projectNames=new HashSet<String>();
        for(var d:docs) {
            facts(d,"projectNo").forEach(f->identities.add(f.value().replaceAll("\\s+","")));
            facts(d,"projectName").forEach(f->projectNames.add(f.value().replaceAll("\\s+","")));
            var role=scalar(d,"documentType");
            // The source may be a tender, demand or approval document. The fact's
            // verbatim budget role is validated above; a filename is not authority.
            totals(d,"budgetAmount").forEach(f->ProcurementMaterialSemantics.money(d,f).ifPresent(budgets::add));
            totals(d,"ceilingAmount").forEach(f->ProcurementMaterialSemantics.money(d,f).ifPresent(ceilings::add));
            if(role!=null&&(role.contains("中标")||role.contains("成交")))totals(d,"awardAmount").forEach(f->ProcurementMaterialSemantics.money(d,f).ifPresent(awards::add));
            if(!quoteDocument(d))continue;
            String supplier=scalar(d,"supplierName");BigDecimal total=amount(d,"quoteAmount");
            if(supplier==null||total==null||total.signum()<=0){notices.add(d.name()+"：供应商或报价总额缺失/冲突，未参与计算");continue;}
            var lines=new ArrayList<Line>();
            var grouped=new LinkedHashMap<String,List<Fact>>();
            for(String type:List.of("itemName","quantity","unitPrice","lineAmount"))for(var f:facts(d,type))grouped.computeIfAbsent(f.row(),x->new ArrayList<>()).add(f);
            boolean valid=true,complete=true;
            for(var row:grouped.values()) {
                String item=one(row,"itemName"),q=one(row,"quantity"),price=one(row,"unitPrice"),sum=one(row,"lineAmount");
                if(item==null||q==null||price==null||sum==null){complete=false;continue;}
                try {
                    var qty=com.fuquan.audit.common.domain.ChineseNumber.parse(q.strip().replaceFirst("(?:台|套|项|个|份|件|批)$","")).orElseThrow().setScale(4,RoundingMode.UNNECESSARY);
                    var unit=row.stream().filter(f->f.type().equals("unitPrice")).map(f->ProcurementMaterialSemantics.money(d,f)).flatMap(Optional::stream).findFirst().orElseThrow();
                    var line=row.stream().filter(f->f.type().equals("lineAmount")).map(f->ProcurementMaterialSemantics.money(d,f)).flatMap(Optional::stream).findFirst().orElseThrow();
                    if(qty.signum()<=0||qty.precision()>18) {complete=false;continue;}
                    if(qty.multiply(unit).compareTo(line)!=0)valid=false;
                    lines.add(new Line(item,qty,unit,line,row.get(0).locator()));
                }catch(RuntimeException e){complete=false;}
            }
            String lineStatus=!valid?"CONFLICT":lines.isEmpty()||!complete?"DATA_INSUFFICIENT":lines.stream().map(Line::amount).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(total)==0?"VERIFIED":"CONFLICT";
            if(!lineStatus.equals("VERIFIED"))notices.add(d.name()+"：明细核算"+lineStatus+"，总价来自原文，未用明细填造总价");
            offers.add(new Offer(supplier,total,d.id(),lines,lineStatus));
        }
        boolean duplicate=offers.stream().map(Offer::supplier).distinct().count()!=offers.size();
        if(duplicate)notices.add("同一供应商存在多份报价，未擅自选择有效版本");
        BigDecimal budget=budgets.size()==1?budgets.first():null;
        BigDecimal ceiling=ceilings.size()==1?ceilings.first():null;
        if(budget==null)notices.add("材料中的预算金额不足或冲突，超预算检查未执行；已识别报价仍保留");
        if(offers.size()<3)notices.add("可靠供应商报价少于三家，三方询比价数据不足");
        ProcurementQuoteCheck calculation=null;
        if(!duplicate&&offers.size()>=3&&budget!=null) {
            var input=new ArrayList<ProcurementQuoteCheck.QuoteInput>();
            for(var o:offers)input.add(new ProcurementQuoteCheck.QuoteInput(input.size()+1,o.supplier(),o.amount(),null));
            calculation=ProcurementQuoteCheck.analyze(caseId.toString(),budget,input);
            notices.add("本结果为前期询价总价比较；报价集中是统计线索，不构成围串标或正式中标推荐。");
        }
        if(docs.stream().anyMatch(d->!d.status().equals("EXTRACTED")||d.parseMethod()!=null&&d.parseMethod().contains("PARTIAL")))notices.add("部分文件未完成读取/提取，报告覆盖不完整，不能声称全部审查通过");
        String state=calculation==null?"DATA_INSUFFICIENT":docs.stream().allMatch(d->d.status().equals("EXTRACTED")&&(d.parseMethod()==null||!d.parseMethod().contains("PARTIAL")))&&offers.stream().allMatch(o->o.lineValidation().equals("VERIFIED"))?"COMPLETED":"PARTIAL";
        String projectName=com.fuquan.audit.common.material.ProjectNameResolver.resolve(docs.stream().flatMap(d->facts(d,"projectName").stream()).map(Fact::value).toList(),
                docs.stream().flatMap(d->d.segments().stream()).map(MaterialFieldAiPort.Segment::text).toList(),"采购材料分析");
        return new Report(state,budget,awards.size()==1?awards.first():null,offers,calculation,notices,
                Map.of("QUOTE",state,"AGENT_PRECHECK","NOT_EXECUTED","BLACKLIST","NOT_EXECUTED","TENDER_COMPLIANCE","NOT_EXECUTED","COLLUSION","NOT_EXECUTED","SPLIT_PURCHASE","NOT_EXECUTED"),Map.of(),projectName,ceiling);
    }
    private static String one(List<Fact> row,String field){var v=row.stream().filter(f->f.type().equals(field)).map(Fact::value).distinct().toList();return v.size()==1?v.get(0):null;}
}
