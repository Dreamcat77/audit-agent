package com.fuquan.audit.procurement.autocase;

import java.util.*;
import java.util.regex.Pattern;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** Read-only presentation of proven facts. Missing comparison inputs are not defects. */
final class CaseAvailableResults {
    private CaseAvailableResults() {}
    record Detail(String label,String value,String amountScope,List<SourceEvidence> evidence) {}
    static List<Detail> details(AuditCase value) {
        var groups=new LinkedHashMap<List<String>,List<SourceEvidence>>();
        var labels=new LinkedHashMap<String,String>();
        labels.put("projectName","材料中的项目名称");labels.put("procurementMethod","采购方式");
        labels.put("projectNo","项目编号");labels.put("purchaserName","采购单位");labels.put("agencyName","采购代理机构");
        labels.put("contractNo","合同编号");labels.put("tenderNo","招标编号");labels.put("procurementNo","采购编号");labels.put("applicationNo","申报编号");
        labels.put("supplierName","供应商");labels.put("packageName","采购包");
        labels.put("budgetAmount","预算金额");labels.put("ceilingAmount","最高限价");labels.put("quoteAmount","报价");labels.put("awardAmount","中标金额");
        labels.put("treasuryFundAmount","国库资金");labels.put("selfFundAmount","自筹资金");
        labels.put("contractPerformancePeriod","合同履行期限");labels.put("qualificationRequirements","供应商资格要求");
        labels.put("supplierInstructions","供应商须知");labels.put("negotiationInstructions","磋商须知");
        labels.put("scoringCriteria","评分细则");labels.put("contractTermsSummary","合同条款要点");labels.put("procurementRulesSummary","采购规则要点");
        var current=new HashMap<UUID,List<Fact>>();
        for(var doc:value.documents()) {
            var index=new ProcurementMaterialSemantics.Index(doc);
            current.put(doc.id(),doc.facts().stream().filter(f->f.verification().equals("SOURCE_VERIFIED"))
                    .map(f->ProcurementMaterialSemantics.project(doc,f,index)).flatMap(Optional::stream).distinct().toList());
        }
        var aliases=com.fuquan.audit.common.material.SourceIdentityPolicy.aliases(current.values().stream().flatMap(Collection::stream).map(Fact::excerpt).toList());
        var display=new LinkedHashMap<List<String>,String>();
        for(var type:labels.entrySet())for(var doc:value.documents())for(var fact:current.get(doc.id())) {
            if(!fact.type().equals(type.getKey()))continue;
            String scope=Objects.toString(fact.amountScope(),"");
            String label=type.getValue()+switch(scope){case "ANNUAL"->"（每年）";case "PACKAGE"->"（分包）";case "UNIT"->"（单价）";default->"";};
            String normalized=com.fuquan.audit.common.material.SourceIdentityPolicy.identityKey(fact.value());
            if(Set.of("purchaserName","supplierName","agencyName").contains(fact.type()))normalized=aliases.getOrDefault(normalized,normalized);
            if(fact.type().endsWith("Amount"))normalized=ProcurementMaterialSemantics.money(doc,fact).map(n->n.stripTrailingZeros().toPlainString()).orElse(normalized);
            // A package amount and another supplier's price are not the same fact.
            String context=scope.equals("PACKAGE")?Objects.toString(CaseFactPolicy.scalar(doc,"packageName"),doc.id().toString()):"";
            if(fact.type().equals("quoteAmount"))context+="/"+Objects.toString(CaseFactPolicy.scalar(doc,"supplierName"),doc.id().toString());
            var key=List.of(label,normalized,scope,context);
            display.putIfAbsent(key,fact.value());
            if(com.fuquan.audit.common.material.SourceIdentityPolicy.identityKey(fact.value()).equals(normalized)
                    &&(!com.fuquan.audit.common.material.SourceIdentityPolicy.identityKey(display.get(key)).equals(normalized)
                    ||fact.value().length()<display.get(key).length()))display.put(key,fact.value());
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(new SourceEvidence(
                    AuditCaseService.hash(doc.id()+"/"+fact.locator()+"/"+fact.excerpt()).substring(0,24),doc.id(),doc.name(),doc.sha256(),fact.locator(),fact.excerpt()));
        }
        return groups.entrySet().stream().map(e->new Detail(e.getKey().get(0),display.get(e.getKey()),e.getKey().get(2),e.getValue().stream().distinct().toList())).toList();
    }
    // Scope statements about what was uploaded cannot prove a clause defect.
    // Contents-level deficiencies (e.g. a subjective scoring clause) remain reviewable.
    private static final Pattern SCOPE=Pattern.compile("仅(?:上传|提供|有|见)|只(?:上传|提供)|(?:未上传|没有上传)|(?:补充|补齐|提供|上传)(?:完整|相应|相关|对应|本项目|的|全套|关键|\\s)*(?:招标文件|采购文件|中标通知|评标报告|评分办法|评分细则|技术需求|招标公告)|(?:缺少|缺失|未见|未提供)(?:本项目|相关|对应|完整|的|\\s)*(?:招标文件|采购文件|招标公告|中标通知书|评标报告)");
    static boolean scopeOnly(String text,List<SourceEvidence> evidence) {
        if(!SCOPE.matcher(text).find())return false;
        // An explicit source requirement may support an actual omitted attachment
        // or submission. A cover/price page alone does not establish that duty.
        return evidence.stream().noneMatch(e->e.excerpt().matches("(?s).*(?:必须|应当|应随|须随|应提交|须提交|须提供|应提供|不得缺少).*(?:附件|文件|证明|材料).*"));
    }
    static boolean display(ReviewCheck c){
        return !Set.of("MATERIAL_COVERAGE").contains(c.code())&&!Set.of("RULE_CANDIDATE","UNKNOWN","NOT_EVALUATED").contains(c.status());
    }
}
