package com.fuquan.audit.budget.application;

import static com.fuquan.audit.budget.application.BudgetMaterialRisk.*;

import com.fuquan.audit.budget.domain.BudgetSplitRiskRule;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;

/** Threshold arithmetic on uploaded evidence. Upload time is never an application date. */
public final class BudgetMaterialSplitAnalysis {
    private BudgetMaterialSplitAnalysis() {}
    static final BigDecimal ADDITIONAL_APPROVAL_THRESHOLD = new BigDecimal("1000000.00");

    public record Rule(String code, String name, int version, BigDecimal individualLimit,
            BigDecimal threshold, int minimumCount, int windowDays) {}
    public record Finding(Rule rule, String year, String department, BigDecimal totalAmount,
            String riskLevel, String explanation, List<Candidate> projects) {
        public Finding { projects = List.copyOf(projects); }
    }
    public record Exclusion(Candidate project, String reasonCode, String reason) {}
    public record Analysis(String version, String riskLevel, BigDecimal totalAmount, int countedItems,
            String summary, List<Rule> rules, List<Finding> findings, List<Exclusion> exclusions,
            String bandCode, String bandLabel, boolean complete) {
        public Analysis { rules=List.copyOf(rules);findings=List.copyOf(findings);exclusions=List.copyOf(exclusions); }
        public Analysis(String version,String riskLevel,BigDecimal totalAmount,int countedItems,String summary,
                List<Rule> rules,List<Finding> findings,List<Exclusion> exclusions) {
            this(version,riskLevel,totalAmount,countedItems,summary,rules,findings,exclusions,null,null,false);
        }
    }

    /** Each explicitly selected row is counted once, without identity/version/scope filtering. */
    public static Analysis analyzeSelected(List<Candidate> candidates) {
        BigDecimal total=new BigDecimal("0.00");
        var missing=new ArrayList<Exclusion>();
        for(var candidate:candidates) {
            BigDecimal amount=candidate.line().declaredAmount();
            if(amount==null||amount.signum()<0)missing.add(new Exclusion(candidate,"MISSING_AMOUNT","未识别有效申报金额"));
            else total=total.add(amount);
        }
        boolean complete=missing.isEmpty();
        boolean requiresAdditionalApproval=total.compareTo(ADDITIONAL_APPROVAL_THRESHOLD)>=0;
        String band=!complete?null:requiresAdditionalApproval?"AT_OR_ABOVE_1M":"BELOW_1M";
        String label=!complete?null:requiresAdditionalApproval?"达到或超过 100 万元":"低于 100 万元";
        String summary=complete?"已合计所选 "+candidates.size()+" 项申报金额，所属金额区间："+label+"。"
                +(requiresAdditionalApproval?"需履行额外审批。":"未触发 100 万元的额外审批门槛。")
                :"所选 "+candidates.size()+" 项中有 "+missing.size()+" 项未识别有效申报金额，暂不能确定全部申报总额及金额区间。";
        return new Analysis("SELECTED_SUM_V3",complete?(requiresAdditionalApproval?"WARNING":"INFO"):"UNKNOWN",total,candidates.size()-missing.size(),summary,
                List.of(),List.of(),missing,band,label,complete);
    }

    public static Analysis analyze(List<Candidate> candidates, List<BudgetSplitRiskRule> configured) {
        List<Rule> rules=configured.stream()
                .sorted(Comparator.comparing(BudgetSplitRiskRule::aggregateAmountThreshold)
                        .thenComparing(BudgetSplitRiskRule::ruleCode))
                .map(r->new Rule(r.ruleCode(),r.ruleName(),r.versionNo(),r.individualAmountLimit(),
                        r.aggregateAmountThreshold(),r.minimumRecordCount(),r.windowDays())).toList();
        var exclusions=new ArrayList<Exclusion>();
        var identities=new LinkedHashMap<List<String>,List<Candidate>>();
        for (Candidate candidate:candidates) {
            var line=candidate.line();
            if (!Set.of("WITHIN_APPROVAL","OVER_APPROVAL","INSUFFICIENT").contains(Objects.toString(line.status(),""))) {
                exclusions.add(new Exclusion(candidate,"UNRELIABLE_AMOUNT","材料版本或金额存在冲突，未计入累计金额"));
            } else if (line.declaredAmount()==null || line.declaredAmount().signum()<=0) {
                exclusions.add(new Exclusion(candidate,"MISSING_AMOUNT","未识别有效申报金额，未计入累计金额"));
            } else if (key(line.project()).isEmpty() || key(line.subjectCode()).isEmpty() && key(line.subject()).isEmpty()) {
                exclusions.add(new Exclusion(candidate,"MISSING_IDENTITY","项目或科目不明确，未计入累计金额"));
            } else {
                String project=projectKey(candidate,candidates),subject=subjectKey(candidate,candidates);
                if(project==null||subject==null) exclusions.add(new Exclusion(candidate,"AMBIGUOUS_IDENTITY",
                        "同名项目或科目对应多个编码，无法确认是否重复，未计入累计金额"));
                else identities.computeIfAbsent(List.of(project,subject,key(line.year()),key(line.department())),k->new ArrayList<>()).add(candidate);
            }
        }
        var counted=new ArrayList<Candidate>();
        for (List<Candidate> versions:identities.values()) {
            boolean different=versions.stream().map(c->c.line().declaredAmount()).distinct()
                    .anyMatch(amount->amount.compareTo(versions.get(0).line().declaredAmount())!=0);
            if (different) {
                for (Candidate c:versions) exclusions.add(new Exclusion(c,"CONFLICTING_VERSIONS",
                        "同一项目、科目的申报金额存在不同版本，未重复累加，请核对适用版本"));
            } else {
                counted.add(versions.get(0));
                for (int i=1;i<versions.size();i++) exclusions.add(new Exclusion(versions.get(i),"DUPLICATE",
                        "与已计入的项目、科目和金额重复，仅计入一次"));
            }
        }
        // Missing scope stays separate: an unknown year/department cannot bridge known contexts.
        var scopes=new LinkedHashMap<List<String>,List<Candidate>>();
        for (Candidate c:counted) scopes.computeIfAbsent(List.of(key(c.line().year()),key(c.line().department())),
                k->new ArrayList<>()).add(c);
        var findings=new ArrayList<Finding>();
        for (List<Candidate> group:scopes.values()) for (Rule rule:rules) {
            List<Candidate> small=group.stream().filter(c->c.line().declaredAmount().compareTo(rule.individualLimit())<0).toList();
            BigDecimal total=total(small);
            if (small.size()<rule.minimumCount() || total.compareTo(rule.threshold())<0) continue;
            var first=small.get(0).line();
            String explanation="以下 "+small.size()+" 项申报金额分别低于 "+money(rule.individualLimit())
                    +" 元，合计 "+money(total)+" 元，已达到“"+rule.name()+"”的 "+money(rule.threshold())
                    +" 元门槛。若属于同一项目或应合并申报的内容，存在拆分申报风险，请核对是否需履行相应审批、报告程序。"
                    +"本次仅核对金额门槛；材料未确认完整申报日期，尚未验证规则要求的 "+rule.windowDays()
                    +" 天范围。项目关联关系和适用制度仍需核实。";
            findings.add(new Finding(rule,first.year(),first.department(),total,"WARNING",explanation,small));
        }
        boolean partial=exclusions.stream().anyMatch(e->!e.reasonCode().equals("DUPLICATE"));
        String level=!findings.isEmpty()?"WARNING":"UNKNOWN";
        String summary=rules.isEmpty()?"未配置有效拆分风险规则，暂不能判断累计金额是否触及审批或报告门槛。"
                :!findings.isEmpty()?"发现 "+findings.size()+" 项累计金额门槛提示，请结合涉及项目核对是否存在拆分申报。"
                :"所选材料中可确认的金额暂未触及已配置的拆分门槛；这不代表已排除拆分风险。";
        if (partial) summary+="部分记录依据不足，未计入累计金额。";
        if (scopes.size()>1) summary+="不同年度、部门及范围不明的记录分别核算，未混合累计。";
        return new Analysis("UPLOADED_SPLIT_V1",level,total(counted),counted.size(),summary,rules,findings,exclusions);
    }

    // Prefer stable codes; a name-only duplicate can join a coded project only if the name
    // identifies one code in the same scope. Explicitly different codes never collapse.
    private static String projectKey(Candidate candidate,List<Candidate> candidates) {
        var line=candidate.line();
        if (!key(line.projectCode()).isEmpty()) return "code:"+key(line.projectCode());
        String name=projectName(candidate);
        var codes=new HashSet<String>();
        for (Candidate other:candidates) if (!key(other.line().projectCode()).isEmpty()
                && name.equals(projectName(other)) && key(line.year()).equals(key(other.line().year()))
                && key(line.department()).equals(key(other.line().department()))) codes.add(key(other.line().projectCode()));
        return codes.size()>1?null:codes.size()==1?"code:"+codes.iterator().next():"name:"+name;
    }
    private static String projectName(Candidate candidate) {
        var names=candidate.line().sources().stream().filter(e->e.field().equals("projectName"))
                .map(e->key(e.value())).filter(s->!s.isEmpty()).distinct().toList();
        return names.size()==1?names.get(0):key(candidate.line().project());
    }
    private static String subjectKey(Candidate candidate,List<Candidate> candidates) {
        var line=candidate.line();
        if(!key(line.subjectCode()).isEmpty())return "code:"+key(line.subjectCode());
        var codes=new HashSet<String>();
        for(Candidate other:candidates)if(!key(other.line().subjectCode()).isEmpty()
                &&Objects.equals(projectKey(candidate,candidates),projectKey(other,candidates))
                &&key(line.subject()).equals(key(other.line().subject()))&&key(line.year()).equals(key(other.line().year()))
                &&key(line.department()).equals(key(other.line().department()))) codes.add(key(other.line().subjectCode()));
        return codes.size()>1?null:codes.size()==1?"code:"+codes.iterator().next():"name:"+key(line.subject());
    }
    private static BigDecimal total(List<Candidate> candidates) {
        return candidates.stream().map(c->c.line().declaredAmount()).reduce(new BigDecimal("0.00"),BigDecimal::add);
    }
    private static String money(BigDecimal amount) { return amount.stripTrailingZeros().toPlainString(); }
    private static String key(String value) {
        return value==null?"":Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+","").toLowerCase(Locale.ROOT);
    }
}
