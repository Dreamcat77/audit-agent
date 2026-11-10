package com.fuquan.audit.budget.application;

import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact;
import java.math.BigDecimal;
import java.util.*;

/** Project totals have their own roles; none is injected into a subject amount. */
final class BudgetBatchAmounts {
    static final Set<String> ROLES=Set.of("projectBudgetAmount","declaredTotalAmount","approvedTotalAmount","ceilingAmount");
    static final Map<String,String> LABELS=Map.of("projectBudgetAmount","项目预算","declaredTotalAmount","申报合计","approvedTotalAmount","批复总额","ceilingAmount","最高限价");
    private BudgetBatchAmounts() {}
    static List<Total> totals(List<Input> inputs) {
        var found=new LinkedHashMap<String,Total>();
        for(var input:inputs)if(input.snapshot()!=null) {
            var years=input.snapshot().rows().stream().flatMap(r->r.facts().stream()).filter(f->f.field().equals("budgetYear"))
                    .map(Fact::value).map(v->v.replaceAll("年(?:度)?$","").strip()).distinct().toList();
            String year=years.size()==1?years.get(0):null;
            for(var row:input.snapshot().rows())for(var fact:row.facts()) {
                if(!ROLES.contains(fact.field())||Set.of("REFERENCE","GENERAL").contains(Objects.toString(fact.subjectScope(),"")))continue;
                var amount=BudgetUploadedMaterialAnalyzer.money(fact.value());if(amount==null||amount.signum()<0)continue;
                String scope=scope(fact,context(input.snapshot(),fact));
                // Package names and annual labels are preserved in the scope, never mixed with whole-project totals.
                var rowYears=row.facts().stream().filter(f->f.field().equals("budgetYear")).map(Fact::value).map(v->v.replaceAll("年(?:度)?$","").strip()).distinct().toList();
                String effectiveYear=rowYears.size()==1?rowYears.get(0):year;
                String key=fact.field()+"/"+scope+"/"+effectiveYear+"/"+amount.stripTrailingZeros().toPlainString();
                var evidence=BudgetSourceText.locate(new Evidence(input.taskId(),input.fileName(),fact.field(),fact.value(),fact.locator(),fact.excerpt()),input.snapshot());
                var old=found.get(key);var refs=new LinkedHashSet<Evidence>();if(old!=null)refs.addAll(old.sources());refs.add(evidence);
                found.put(key,new Total(fact.field(),scope,effectiveYear,amount,List.copyOf(refs)));
            }
        }
        return List.copyOf(found.values());
    }
    private static String context(Snapshot snapshot,Fact fact) {
        for(var segment:snapshot.segments())if(fact.locator().equals(segment.locator())||fact.locator().startsWith(segment.locator()+"/")) {
            int at=segment.text().indexOf(fact.excerpt());
            if(at>=0&&at==segment.text().lastIndexOf(fact.excerpt())) {
                int start=at;
                while(start>0&&at-start<100&&"\n。；;".indexOf(segment.text().charAt(start-1))<0)start--;
                return segment.text().substring(start,at+fact.excerpt().length());
            }
        }
        return fact.excerpt();
    }
    private static String scope(Fact fact,String text) {
        if(text.matches("(?s).*(?:每年|每年度|本年度|当年度|该年度|年度预算|年度金额|年度批复|/年|／年|元每年).*"))return "ANNUAL";
        if(text.matches("(?s).*(?:第[一二三四五六七八九十0-9]+包|分包|采购包).*"))return "PACKAGE:"+text;
        if("ANNUAL".equals(fact.amountScope()))return "ANNUAL";
        if("PACKAGE".equals(fact.amountScope())||"UNIT".equals(fact.amountScope()))return "UNKNOWN:"+text;
        if("TOTAL".equals(fact.amountScope()))return "TOTAL";
        if(Set.of("declaredTotalAmount","approvedTotalAmount").contains(fact.field()))return "TOTAL";
        if(text.matches("(?s).*(?:项目|本次|预算总额|申报合计|申请总额|申报总额|批复总额|批复控制数|最高限价|预算金额).*"))return "TOTAL";
        return "UNKNOWN:"+text;
    }
    static List<Assessment> checks(List<Total> totals) {
        var out=new ArrayList<Assessment>();
        for(String scope:List.of("TOTAL","ANNUAL")) {
            var years=totals.stream().filter(t->scope.equals(t.scope())).map(t->Objects.toString(t.year(),"")).distinct().toList();
            for(String year:years) {
                var same=totals.stream().filter(t->scope.equals(t.scope())&&Objects.toString(t.year(),"").equals(year)).toList();
                for(String role:ROLES) {
                    var values=same.stream().filter(t->role.equals(t.role())).toList();
                    if(values.size()>1)out.add(new Assessment("TOTAL_VERSION_REVIEW","INFO",LABELS.get(role)+"存在不同记载",
                            "材料中列有不同"+LABELS.get(role)+"，已保留各自金额及原文；需结合调整或版本依据理解。", "金额不同不直接认定超支或版本冲突。",refs(values)));
                }
                var declared=one(same,"declaredTotalAmount");var approved=one(same,"approvedTotalAmount");
                if(declared!=null&&approved!=null) {
                    BigDecimal delta=declared.amount().subtract(approved.amount());boolean over=delta.signum()>0;
                    out.add(new Assessment("TOTAL_VS_APPROVAL",over?"WARNING":"INFO",over?"申报合计超过批复总额":"申报合计与批复总额核对",
                            "申报合计 "+declared.amount().toPlainString()+" 元，批复总额 "+approved.amount().toPlainString()+" 元，"
                                    +(over?"超出 "+delta.toPlainString()+" 元。":"已核对金额未超过该批复总额。"),
                            "依据同一金额口径、年度的申报合计与批复总额精确核算。",refs(List.of(declared,approved))));
                }
                // A single explicit project total triggers the agreed threshold too, without needing artificial detail rows.
                var threshold=declared!=null?declared:one(same,"projectBudgetAmount");
                if(scope.equals("TOTAL")&&threshold!=null&&threshold.amount().compareTo(BudgetMaterialSplitAnalysis.ADDITIONAL_APPROVAL_THRESHOLD)>=0)
                    out.add(new Assessment("TOTAL_APPROVAL_THRESHOLD","WARNING","项目金额达到100万元审批门槛",
                            LABELS.get(threshold.role())+"为 "+threshold.amount().toPlainString()+" 元，已达到100万元的额外审批门槛。",
                            "按当前确认口径，达到或超过100万元提示额外审批；不据此认定实际漏审或拆分违规。",threshold.sources()));
            }
        }
        return out;
    }
    private static Total one(List<Total> totals,String role){var values=totals.stream().filter(t->role.equals(t.role())).toList();return values.size()==1?values.get(0):null;}
    private static List<Evidence> refs(List<Total> totals){return totals.stream().flatMap(t->t.sources().stream()).distinct().toList();}
}
