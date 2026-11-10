package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Read-only projection of immutable step outputs; GET never creates work or invokes a model. */
@Service
public class ContractAnalysisReport {
    private ContractVisualQuality visualQuality;
    @org.springframework.beans.factory.annotation.Autowired public void setVisualQuality(ContractVisualQuality value){this.visualQuality=value;}
    public record Summary(UUID id,String status,String statusLabel,String headline,int files,int risks,
                          Instant createdAt,UUID projectId,String projectName) {}
    public record Document(UUID fileId,String name,String status,String role,String profile,List<Fact> facts,List<Clause> clauses,List<Check> checks,List<Finding> findings,int sourceChars,int reviewedChars,boolean fullyReviewed) {}
    public record Report(UUID id,String status,String statusLabel,String headline,String explanation,Instant createdAt,List<Document> documents,List<Finding> findings,List<Check> checks,List<String> limitations,List<Clause> comparisons,List<ContractAnalysisCategories.Category> categories,UUID projectId,String projectName) {}
    public record Text(String text,int start,int totalChars) {}
    private final ContractAnalysisService service;private final ContractAnalysisSources sources;private final ContractAnalysisRelations relations;
    public ContractAnalysisReport(ContractAnalysisService service,ContractAnalysisSources sources,ContractAnalysisRelations relations){this.service=service;this.sources=sources;this.relations=relations;}
    public Summary summary(ContractAnalysisCase c){return summary(c,null);}
    public Summary summary(ContractAnalysisCase c,String projectName){
        String name=projectName(c,projectName,List.of());
        var texts=new HashMap<UUID,Optional<String>>();
        long risks=uniqueFindings(c.steps()).stream().map(f->ContractAnalysisEvidence.ancillaryPartyDifference(f,c.sources())).filter(f->f.level().equals("WARNING"))
            .filter(f->visualQuality==null||c.sources().stream().filter(s->s.status()==SourceState.READY).noneMatch(s->{
                var text=texts.computeIfAbsent(s.fileId(),key->{try{return Optional.of(sources.load(c.projectId(),s).canonicalText());}catch(IllegalStateException unavailable){return Optional.empty();}});
                return text.isPresent()&&visualQuality.uncertain(s,text.get(),f.evidence());
            }))
            .filter(f->!ContractFindingSupport.temporal(f)||c.sources().stream().filter(s->s.status()==SourceState.READY)
                .filter(s->f.evidence().stream().anyMatch(e->e.fileId().equals(s.fileId())))
                .allMatch(s->{
                    var text=texts.computeIfAbsent(s.fileId(),key->{
                        if(sources==null)return Optional.empty();
                        try{return Optional.of(sources.load(c.projectId(),s).canonicalText());}
                        catch(IllegalStateException unavailable){return Optional.empty();}
                    });
                    return text.isEmpty()||ContractFindingSupport.supports(f,s,text.get());
                })).count();
        return new Summary(c.id(),c.status().name(),label(c.status()),name,Math.max(c.expectedUploads(),c.sources().size()),(int)risks,c.createdAt(),c.projectId(),name);
    }
    public Report report(UUID id,CurrentUser actor){return project(service.owned(id,actor));}
    public Report project(ContractAnalysisCase c){
        List<Document> docs=new ArrayList<>();List<String> limits=new ArrayList<>();var checks=new ArrayList<Check>();var texts=new ArrayList<String>();
        var originals=new HashMap<UUID,String>();
        for(var source:c.sources()){
            var work=c.steps().stream().filter(s->source.fileId().equals(s.fileId())&&s.output()!=null&&!s.kind().startsWith("CROSS")).toList();
            int length=0;String canonical=null;boolean ocr=false;
            if(source.status()==SourceState.READY)try{var parsed=sources.load(c.projectId(),source);length=parsed.canonicalText().length();canonical=parsed.canonicalText();ocr=(parsed.parseMethod().name()+"/"+parsed.engine()).toUpperCase(Locale.ROOT).contains("OCR");texts.add(ContractAnalysisSemantics.currentNamingText(parsed.canonicalText()));if(parsed.engine().contains("PARTIAL"))limits.add("“"+source.name()+"”：部分页面未取得文字，结果仅覆盖已读取内容");}catch(IllegalStateException unavailable){limits.add("“"+source.name()+"”的原文暂时无法核对，不能将相关判断当作已核实结论。");}
            if(canonical!=null&&!canonical.isEmpty()){
                originals.put(source.fileId(),canonical);
                final String original=canonical;final boolean scanned=ocr;
                work=work.stream().map(s->new Step(s.id(),s.fileId(),s.kind(),s.start(),s.end(),s.inputDigest(),s.status(),
                    s.startedAt(),s.completedAt(),quality(ContractAnalysisSemantics.project(s.output(),source,original,scanned),source,original),s.reasonCode())).toList();
            }
            var out=work.stream().map(Step::output).toList();
            int reviewed=covered(work,length);boolean full=length>0&&reviewed==length&&limits.stream().noneMatch(v->v.startsWith("“"+source.name()+"”：部分页面"));
            String profile=out.stream().map(Output::profile).filter(p->!p.equals("GENERAL")).findFirst().orElse("GENERAL");
            var localChecks=out.stream().flatMap(o->o.checks().stream()).distinct().toList();checks.addAll(localChecks);
            docs.add(new Document(source.fileId(),source.name(),source.status().name(),relations.role(source,c.steps()),profile,
                ContractFactConsolidation.merge(out.stream().flatMap(o->o.facts().stream()).toList(),canonical==null?"":canonical),
                deduplicate(out.stream().flatMap(o->o.clauses().stream()).toList(),v->v.topic()+"/"+v.summary()+"/"+v.evidence()),
                localChecks,uniqueFindings(work),length,reviewed,full));
            if(source.status()!=SourceState.READY)limits.add("“"+source.name()+"”："+sourceReason(source));
            else if(c.terminal()&&!full)limits.add("“"+source.name()+"”：仅展示已取得依据的内容，部分原文未完成模型审查，不能据此认定整份合同无风险。");
        }
        var crossWork=c.steps().stream().filter(s->s.kind().startsWith("CROSS")&&s.output()!=null).map(s->{
            var output=s.output();for(var source:c.sources())if(originals.containsKey(source.fileId()))output=quality(output,source,originals.get(source.fileId()));
            return new Step(s.id(),s.fileId(),s.kind(),s.start(),s.end(),s.inputDigest(),s.status(),s.startedAt(),s.completedAt(),output,s.reasonCode());
        }).toList();
        crossWork.stream().filter(s->s.kind().equals("CROSS_RULES")).flatMap(s->s.output().checks().stream()).forEach(checks::add);
        if(c.terminal())for(String code:List.of(com.fuquan.audit.contract.domain.F02RuleCodes.AWARD_AMOUNT_MATCH,com.fuquan.audit.contract.domain.F02RuleCodes.AWARD_SUPPLIER_MATCH))
            if(checks.stream().noneMatch(check->check.code().equals(code)))checks.add(ContractAnalysisRules.missing(code,"现有材料尚不能证明合同与中标依据的明确对应关系，未作一致性结论"));
        if(c.steps().stream().anyMatch(s->s.status()==StepState.UNKNOWN))limits.add("部分模型请求的最终结果无法确认，系统已停止自动重发；已取得的有效结果仍保留。");
        if(c.steps().stream().anyMatch(s->s.status()==StepState.FAILED&&!ContractAnalysisPlan.recoveredOutputLimit(s,c.steps())))limits.add("部分处理步骤未成功，当前报告保留其他已完成的独立检查。");
        if(c.steps().stream().map(Step::output).filter(Objects::nonNull).anyMatch(o->o.rejectedCitations()>0))limits.add("个别模型描述未能通过原文核对，未纳入本报告。");
        if(c.sources().size()>1&&relations.pairs(c.sources(),c.steps()).isEmpty()&&c.terminal())limits.add("材料之间尚无足够明确的合同关联依据，分别展示结果，未将不同文件的主体或金额强行合并。");
        String explanation=switch(c.status()){
            case COMPLETED->"本次处理范围内的分析已完成。金额核算与条款分析分开展示；仍需核对的事项不会计为通过。";
            case PARTIAL->"已形成可参考的阶段结果，请优先查看有原文依据的关注事项；尚未完成的范围单独列出。";
            case DATA_INSUFFICIENT->"材料已处理，现有内容不足以支持合同审查结论。";
            case FAILED->"本次未取得可用的分析结果。请查看处理说明；原有历史结果不会被覆盖。";
            case UNKNOWN->"本次处理的部分结果无法确认，系统已安全停止，请联系维护人员核查。";
            default->"系统正在处理本批材料，可离开页面，稍后从历史记录查看结果。";
        };
        var comparisons=deduplicate(crossWork.stream().filter(s->s.kind().equals("CROSS_FILE"))
            .flatMap(s->s.output().clauses().stream()).toList(),v->v.topic()+"/"+v.summary()+"/"+v.evidence());
        var projectedFindings=new ArrayList<Finding>();docs.forEach(d->projectedFindings.addAll(d.findings()));
        projectedFindings.addAll(uniqueFindings(crossWork).stream()
                .map(f->ContractAnalysisEvidence.ancillaryPartyDifference(f,c.sources())).toList());
        String projectName=projectName(c,sources.projectName(c),texts);
        return new Report(c.id(),c.status().name(),label(c.status()),projectName,explanation,c.createdAt(),List.copyOf(docs),List.copyOf(projectedFindings),checks.stream().distinct().toList(),limits.stream().distinct().toList(),comparisons,ContractAnalysisCategories.project(docs,checks.stream().distinct().toList(),projectedFindings),c.projectId(),projectName);
    }
    private Output quality(Output output,Source source,String text){return visualQuality==null?output:visualQuality.project(output,source,text);}
    public Text text(UUID id,UUID fileId,int start,int size,CurrentUser actor){
        if(start<0||size<1||size>20000)throw new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.INVALID_ARGUMENT);
        var c=service.owned(id,actor);var s=c.sources().stream().filter(f->f.fileId().equals(fileId)).findFirst().orElseThrow(()->new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND));
        String text=sources.load(c.projectId(),s).canonicalText();if(start>text.length())throw new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.INVALID_ARGUMENT);
        return new Text(text.substring(start,Math.min(text.length(),start+size)),start,text.length());
    }
    static int covered(List<Step> work,int length){
        var ranges=work.stream().filter(ContractAnalysisPlan::reviewed).sorted(Comparator.comparingInt(Step::start)).toList();int end=0,total=0;
        for(var r:ranges){int start=Math.max(end,r.start());if(r.end()>start)total+=r.end()-start;end=Math.max(end,r.end());}return Math.min(length,total);
    }
    static List<Finding> uniqueFindings(List<Step> work){
        var findings=deduplicate(work.stream().map(Step::output).filter(Objects::nonNull).flatMap(o->o.findings().stream()).toList(),f->f.level()+"/"+f.code()+"/"+ContractAnalysisEvidence.compact(f.title())+"/"+ContractAnalysisEvidence.compact(f.explanation())+"/"+f.evidence().stream().map(e->e.fileId()+":"+e.start()+":"+e.end()).sorted().toList());
        // Consolidate only an arithmetical restatement proven by the exact F02
        // operands and frozen evidence. Other semantic concerns stay visible.
        var result=new ArrayList<Finding>();
        for(var finding:findings){
            var duplicate=findings.stream().filter(rule->sameLineSumIssue(finding,rule)||sameFormIssue(finding,rule)).findFirst();
            if(duplicate.isEmpty())result.add(finding);
        }
        return ContractResultConsolidation.findings(result);
    }
    private static boolean sameFormIssue(Finding ai,Finding rule){
        if(!"AI_REVIEW_SUGGESTION".equals(ai.origin())||!"DETERMINISTIC_FORM".equals(rule.origin())
                ||!Objects.equals(ai.level(),rule.level())||!ContractAnalysisEvidence.compact(ai.title()).equals(ContractAnalysisEvidence.compact(rule.title()))
                ||ai.evidence().isEmpty())return false;
        return ai.evidence().stream().allMatch(a->rule.evidence().stream().anyMatch(b->
                a.fileId().equals(b.fileId())&&a.fileSha256().equals(b.fileSha256())&&a.parseResultId().equals(b.parseResultId())
                &&a.parseVersion()==b.parseVersion()&&a.textSha256().equals(b.textSha256())&&a.start()>=b.start()&&a.end()<=b.end()));
    }
    private static boolean sameLineSumIssue(Finding ai,Finding rule){
        if(!"AI_REVIEW_SUGGESTION".equals(ai.origin())||!"OTHER_CONCRETE_RISK".equals(ai.code())||!"WARNING".equals(ai.level())
            ||!"DETERMINISTIC_F02".equals(rule.origin())||!"F02_LINE_SUM_MATCH".equals(rule.code())||!"WARNING".equals(rule.level()))return false;
        String text=ai.title()+ai.explanation();
        if(!text.matches("(?s).*(明细|合计).*")||!text.matches("(?s).*总(金额|价).*")||!text.matches("(?s).*(不一致|差额|不符).*")
            ||text.matches("(?s).*(税|币|付款|支付|变更|附件|生效|适用).*")||!numbers(text).containsAll(numbers(rule.explanation()))||numbers(rule.explanation()).size()<3)return false;
        return !ai.evidence().isEmpty()&&ai.evidence().stream().allMatch(a->rule.evidence().stream().anyMatch(b->
            a.fileId().equals(b.fileId())&&a.fileSha256().equals(b.fileSha256())&&a.parseResultId().equals(b.parseResultId())
                &&a.parseVersion()==b.parseVersion()&&a.textSha256().equals(b.textSha256())&&a.start()<b.end()&&b.start()<a.end()));
    }
    private static Set<java.math.BigDecimal> numbers(String value){
        var result=new HashSet<java.math.BigDecimal>();var matcher=java.util.regex.Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?").matcher(value);
        while(matcher.find())result.add(new java.math.BigDecimal(matcher.group().replace(",","")).stripTrailingZeros());return result;
    }
    private static List<Fact> mergedFacts(List<Fact> facts){return ContractFactConsolidation.merge(facts,"");}
    private static <T> List<T> deduplicate(List<T> values,java.util.function.Function<T,String> key){var map=new LinkedHashMap<String,T>();for(var v:values)map.putIfAbsent(key.apply(v),v);return List.copyOf(map.values());}
    static String label(State s){return switch(s){case QUEUED->"等待处理";case RUNNING->"正在分析";case COMPLETED->"分析完成";case PARTIAL->"已生成结果";case DATA_INSUFFICIENT->"材料不足以支持分析";case FAILED->"未能完成处理";case UNKNOWN->"处理已停止，结果待核查";};}
    static String projectName(ContractAnalysisCase c,String established,List<String> texts){
        return ContractAnalysisProjectNames.resolve(c,established,texts);
    }
    private static String sourceReason(Source s){return switch(s.status()){case WAITING->"正在等待文件解析";case FAILED->"文件未取得可读内容，其他材料仍独立分析";case UNKNOWN->"文件处理结果无法确认，未使用无法核对的内容";default->"已取得可读内容";};}
}
