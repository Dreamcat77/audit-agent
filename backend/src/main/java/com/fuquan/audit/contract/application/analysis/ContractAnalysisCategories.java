package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Eight business views over already persisted and grounded analysis; no model calls on GET. */
public final class ContractAnalysisCategories {
    private ContractAnalysisCategories() {}
    public record Item(String title,String summary,String level,List<Evidence> evidence) {
        public Item { evidence=List.copyOf(evidence); }
    }
    public record Category(String id,int order,String title,String status,String summary,List<Item> items) {
        public Category {items=List.copyOf(items);}
    }
    private static final String[][] DEFINITIONS={
        {"QUANTITATIVE","合同条款量化一致性审查"},{"DRAFT_STRUCTURE","合同草本结构对标审查"},
        {"BREACH","违约责任条款量化审查"},{"TERM","合同期限与终止条件格式审查"},
        {"PARTY_QUALIFICATION","合同主体资格审查"},{"ATTACHMENT_COMPLETENESS","附件清单完整性审查"},
        {"EFFECTIVENESS_COPIES","合同生效条件与份数审查"},{"CLAUSE_RISK","合同条款风险提示"}};
    public static List<Category> project(List<ContractAnalysisReport.Document> docs,List<Check> checks,List<Finding> findings) {
        var groups=new LinkedHashMap<String,List<Item>>();
        for(var definition:DEFINITIONS)groups.put(definition[0],new ArrayList<>());
        for(var check:checks)if(!check.status().equals("NOT_EVALUATED")&&!check.evidence().isEmpty()
                &&findings.stream().noneMatch(f->f.code().equals(check.code())&&f.explanation().equals(check.explanation())))
            groups.get(checkGroup(check.code())).add(new Item("核对结果",check.explanation(),check.status().equals("FAIL")?"WARNING":"INFO",check.evidence()));
        for(var check:checks)if(check.status().equals("NOT_EVALUATED")&&!check.evidence().isEmpty()&&check.code().equals("BREACH_AMOUNT_CAP"))
            groups.get("BREACH").add(new Item("违约金计算",check.explanation(),"INFO",check.evidence()));
        for(var doc:docs) {
            // Keep the file identity on each item; shared headings do not authorize
            // merging facts from two contracts or unsupported cross-file comparisons.
            for(var fact:doc.facts()) {
                String label=switch(fact.type()){
                    case "TOTAL_AMOUNT"->"合同金额";case "AMOUNT_WORDS"->"大写金额";
                    case "PARTY_A"->"甲方";case "PARTY_B"->"乙方";
                    case "CONTRACT_NO"->"合同编号";case "CONTRACT_NAME"->"CONTRACT".equals(doc.role())?"合同名称":"材料名称";
                    case "SIGN_DATE"->"签订日期";default->null;};
                if(label==null)continue;
                String group=switch(fact.type()){
                    case "TOTAL_AMOUNT","AMOUNT_WORDS"->"QUANTITATIVE";
                    case "PARTY_A","PARTY_B"->"PARTY_QUALIFICATION";
                    case "SIGN_DATE"->"TERM";default->"DRAFT_STRUCTURE";};
                groups.get(group).add(new Item(label,label+"："+fact.value(),"INFO",fact.evidence()));
            }
            for(var clause:doc.clauses()) {
                String group=switch(clause.topic()){
                    case "BREACH"->"BREACH";case "TERM","TERMINATION","DELIVERY"->"TERM";
                    case "QUALIFICATION"->"PARTY_QUALIFICATION";
                    case "SUBJECT"->"DRAFT_STRUCTURE";
                    case "ATTACHMENT"->"ATTACHMENT_COMPLETENESS";
                    case "EFFECTIVENESS"->"EFFECTIVENESS_COPIES";
                    case "STRUCTURE"->"DRAFT_STRUCTURE";default->"CLAUSE_RISK";};
                groups.get(group).add(new Item("条款内容",clause.summary(),"INFO",clause.evidence()));
            }
        }
        for(var finding:findings) {
            String group=switch(finding.code()) {
                case "BREACH_AMBIGUITY","BREACH_AMOUNT_CAP"->"BREACH";
                case "TERM_CONFLICT","TERMINATION_AMBIGUITY","DATE_FORMAT_COMPLETENESS","SIGN_DATE_COMPLETENESS","DATE_SEQUENCE","TERMINATION_COMPLETENESS"->"TERM";
                case "TEMPLATE_STRUCTURE_COMPARISON"->"DRAFT_STRUCTURE";
                case "COPY_COUNT_REQUIREMENT_COMPARISON"->"EFFECTIVENESS_COPIES";
                case "ATTACHMENT_REFERENCE_MATCH"->"ATTACHMENT_COMPLETENESS";
                case "F02_AMOUNT_WORDS_MATCH","F02_LINE_ITEM_ARITHMETIC","F02_LINE_SUM_MATCH","F02_AWARD_AMOUNT_MATCH","F02_AWARD_SUPPLIER_MATCH"->"QUANTITATIVE";
                default->"CLAUSE_RISK";};
            groups.get(group).add(new Item(finding.title(),finding.explanation()
                    +(finding.suggestion()==null||finding.suggestion().isBlank()?"":" 建议："+finding.suggestion()),finding.level(),finding.evidence()));
        }
        var result=new ArrayList<Category>();int order=0;
        for(var definition:DEFINITIONS) {
            var items=groups.get(definition[0]).stream().distinct().sorted(Comparator.comparingInt(i->i.level().equals("WARNING")?0:1)).toList();
            boolean warning=items.stream().anyMatch(i->i.level().equals("WARNING"));
            String summary=items.isEmpty()?"材料不足，未生成":warning?"请优先查看以下问题及涉及材料":"已整理材料中的相关约定，供审查参考";
            if(definition[0].equals("PARTY_QUALIFICATION")&&!items.isEmpty()&&!warning)summary="已整理合同主体信息；资质结论以材料中的证明为依据";
            result.add(new Category(definition[0],++order,definition[1],items.isEmpty()?"DATA_INSUFFICIENT":warning?"ATTENTION":"AVAILABLE",summary,items));
        }
        return List.copyOf(result);
    }
    private static String checkGroup(String code) { return switch(code) {
        case "TEMPLATE_STRUCTURE_COMPARISON" -> "DRAFT_STRUCTURE";
        case "BREACH_AMOUNT_CAP" -> "BREACH";
        case "COPY_COUNT_REQUIREMENT_COMPARISON" -> "EFFECTIVENESS_COPIES";
        case "ATTACHMENT_REFERENCE_MATCH" -> "ATTACHMENT_COMPLETENESS";
        case "DATE_FORMAT_COMPLETENESS","SIGN_DATE_COMPLETENESS","DATE_SEQUENCE","TERMINATION_COMPLETENESS" -> "TERM";
        default -> "QUANTITATIVE";
    }; }
}
