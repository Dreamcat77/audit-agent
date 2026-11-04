package com.fuquan.audit.budget.application;

import java.math.BigDecimal;
import java.util.*;

/** Uploaded task/line references never masquerade as procurement IDs. */
public final class BudgetMaterialRisk {
    private BudgetMaterialRisk() {}
    public record Selection(UUID taskId,int lineIndex) {}
    public record Candidate(Selection source,String fileName,String uploadedAt,BudgetUploadedFacts.Line line) {}
    public record CandidatePage(List<Candidate> items, boolean hasMore) {}
    public record Source(UUID taskId,String fileName,java.time.Instant createdAt,String resultJson) {}
    public record Position(java.time.Instant createdAt,UUID taskId,int lineIndex) {}
    public record BrowsePage(List<Candidate> items,String nextCursor) {}
    public record HistoryItem(UUID id,String createdAt,int projectCount,String description,BigDecimal totalAmount,String bandLabel) {}
    public record HistoryPage(List<HistoryItem> items,int page,int size,long total) {}
    public record Result(UUID id,String actorId,String createdAt,List<Candidate> projects,List<String> opinions,
            BudgetMaterialSplitAnalysis.Analysis splitAnalysis) {
        public Result { projects=List.copyOf(projects);opinions=List.copyOf(opinions); }
        public Result(UUID id,String actorId,String createdAt,List<Candidate> projects,List<String> opinions) {
            this(id,actorId,createdAt,projects,opinions,null);
        }
    }
    public static List<String> opinions(List<Candidate> candidates) {
        var result=new ArrayList<String>();
        long uncertain=candidates.stream().filter(c->!Set.of("WITHIN_APPROVAL","OVER_APPROVAL").contains(c.line().status())).count();
        long over=candidates.stream().filter(c->c.line().status().equals("OVER_APPROVAL")).count();
        result.add("本次依据所选上传材料中的预算信息进行核对，仅供工作参考。");
        if(over>0)result.add("有 "+over+" 项申报金额超过材料中的对应批复，请核对申报依据与批复版本。");
        if(uncertain>0)result.add("有 "+uncertain+" 项存在信息缺失、金额异常或材料冲突，暂不能形成完整判断。");
        var unique=new LinkedHashMap<String,Set<BigDecimal>>();
        for(var c:candidates) {
            var l=c.line();
            if(l.project()==null||l.subject()==null&&l.subjectCode()==null)continue;
            String key=l.project()+"\u0000"+Objects.toString(l.subjectCode(),l.subject())+"\u0000"+l.year()+"\u0000"+l.department();
            if(l.declaredAmount()!=null)unique.computeIfAbsent(key,k->new TreeSet<>()).add(l.declaredAmount());
        }
        boolean conflictingVersions=unique.values().stream().anyMatch(v->v.size()>1);
        if(conflictingVersions)result.add("所选材料中有同一项目、科目的不同申报金额，不能直接相加，请核对是否为不同版本。");
        if(over==0&&uncertain==0&&!conflictingVersions)result.add("已识别的申报金额均未超过各自对应批复；这不等同于全部预算风险已排除。");
        result.add("科目分类仅用于整理项目。现有材料未提供完整申报时间和审批阈值，不能仅凭同科目下多个项目认定拆分申报。");
        return List.copyOf(result);
    }
}
