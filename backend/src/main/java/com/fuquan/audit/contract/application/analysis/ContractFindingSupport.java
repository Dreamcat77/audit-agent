package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.clause.*;
import java.util.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** A literal quotation must also support the date/term conclusion, including its adjacent source text. */
final class ContractFindingSupport {
    private ContractFindingSupport() {}
    private static final Set<String> TEMPORAL=Set.of("DATE_FORMAT_COMPLETENESS","SIGN_DATE_COMPLETENESS","DATE_SEQUENCE","TERMINATION_COMPLETENESS");
    static boolean temporal(Finding finding) {
        String claim=ContractAnalysisEvidence.compact(finding.title()+finding.explanation());
        return Set.of("TERMINATION_AMBIGUITY","TERMINATION_COMPLETENESS").contains(finding.code())
                ||claim.matches("(?s).*(?:(?:签署|签订|签约).{0,12}日期|(?:终止|结束).{0,12}(?:日期|条件|节点)).*");
    }
    static Output documentProjection(Output output,Source source,String text) {
        if(!"document-rules-v1".equals(output.version())||source.reasonCode()!=null)return output;
        var checks=new ArrayList<>(output.checks().stream().filter(c->!TEMPORAL.contains(c.code())).toList());
        var findings=new ArrayList<>(output.findings().stream().filter(f->!TEMPORAL.contains(f.code())).toList());
        var scope=new ContractAnalysisEvidence.Scope(source,text,0,text.length());
        var grounding=new ContractAnalysisEvidence();
        var review=new ContractTermClauseAnalyzer().analyze(List.of(new ContractClauseMaterial(source.fileId(),source.name(),text)));
        for(var check:review.deterministicChecks())if(TEMPORAL.contains(check.type())){
            var evidence=check.sources().stream().map(s->grounding.locate(scope,s.excerpt())).flatMap(Optional::stream).distinct().toList();
            if(evidence.isEmpty())continue;
            boolean risk=Set.of("REVIEW","ATTENTION").contains(check.status());
            checks.add(new Check(check.type(),risk?"FAIL":check.status().equals("NORMAL")?"PASS":"NOT_EVALUATED",check.summary(),evidence));
            if(risk)findings.add(new Finding(check.type(),"WARNING",title(check.type()),check.summary(),check.recommendation(),evidence,"DETERMINISTIC_DOCUMENT"));
        }
        return new Output(output.role(),output.profile(),output.complete(),output.facts(),output.clauses(),checks,findings,output.rejectedCitations(),output.model(),output.version());
    }
    static boolean supports(Finding finding,Source source,String text) {
        String claim=ContractAnalysisEvidence.compact(finding.title()+finding.explanation());
        boolean sign=claim.matches("(?s).*(?:签署|签订|签约).{0,12}日期.*");
        boolean incomplete=claim.matches("(?s).*(?:不完整|未完整|缺少|缺失|未填写|留空|未列明|没有|未见).*");
        boolean term=Set.of("TERMINATION_AMBIGUITY","TERMINATION_COMPLETENESS").contains(finding.code())
                ||!TEMPORAL.contains(finding.code())&&claim.matches("(?s).*(?:终止|结束).{0,12}(?:日期|条件|节点).*");
        if(!sign&&!term)return true;
        if(finding.evidence().isEmpty())return false;
        var own=finding.evidence().stream().filter(e->e.fileId().equals(source.fileId())).toList();
        if(own.isEmpty())return false;
        if(sign&&own.stream().noneMatch(e->ContractAnalysisEvidence.compact(e.excerpt()).matches("(?s).*(?:签署|签订|签约|[0-9〇零一二三四五六七八九]{4}年).*")))return false;
        if(term&&own.stream().noneMatch(e->ContractAnalysisEvidence.compact(e.excerpt()).matches("(?s).*(?:终止|解除|废除|有效期|合同期|协议期|履行完毕|执行完毕|履约完毕|到期|届满).*")))return false;
        if(term&&claim.matches("(?s).*(?:未列明|未载明|未约定|未见|没有|缺少|缺失).{0,20}(?:终止|结束).{0,12}(?:条件|节点).*")){
            var review=new ContractTermClauseAnalyzer().analyze(List.of(new ContractClauseMaterial(source.fileId(),source.name(),text)));
            if(review.conditions().stream().anyMatch(c->c.type().equals("TERMINATION")&&!"不适用".equals(c.description())))return false;
        }
        if(sign&&incomplete){
            // The model can quote an exact but truncated date at the edge of its
            // fragment. Check the unchanged full source, never invent a missing day.
            var review=new ContractTermClauseAnalyzer().analyze(List.of(new ContractClauseMaterial(source.fileId(),source.name(),text)));
            for(var date:review.dates())if(date.type().equals("SIGNING_DATE")&&date.normalizedDate()==null){
                int at=text.indexOf(date.excerpt());
                while(at>=0){final int from=at,to=at+date.excerpt().length();
                    if(own.stream().anyMatch(e->e.start()<=from&&e.end()>=to))return true;
                    at=text.indexOf(date.excerpt(),at+1);
                }
            }
            for(var date:review.dates())if(date.type().equals("SIGNING_DATE")&&date.normalizedDate()!=null){
                int at=text.indexOf(date.excerpt());
                while(at>=0){
                    final int from=at,to=at+date.excerpt().length();
                    if(own.stream().anyMatch(e->e.start()<to&&e.end()>from))return false;
                    at=text.indexOf(date.excerpt(),at+1);
                }
            }
        }
        return true;
    }
    static Finding narrow(Finding finding,Source source,String text){
        String claim=finding.title()+finding.explanation();
        if(!temporal(finding)&&!TEMPORAL.contains(finding.code()))return finding;
        var review=new ContractTermClauseAnalyzer().analyze(List.of(new ContractClauseMaterial(source.fileId(),source.name(),text)));
        var anchors=new ArrayList<String>();
        boolean sign=claim.matches("(?s).*(?:签署|签订|签约).{0,12}日期.*");
        boolean term=claim.matches("(?s).*(?:终止|结束).{0,12}(?:日期|条件|节点).*");
        boolean invalid=claim.matches("(?s).*(?:未填|留空|空白|缺失|缺少|无效|不完整|不规范|格式错误).*" );
        if(sign||finding.code().equals("DATE_FORMAT_COMPLETENESS"))review.dates().stream()
                .filter(d->!sign||d.type().equals("SIGNING_DATE")).filter(d->!invalid||d.normalizedDate()==null).map(d->d.excerpt()).forEach(anchors::add);
        if(term)review.conditions().stream().filter(c->c.type().equals("TERMINATION")).map(c->c.excerpt()).forEach(anchors::add);
        var evidence=new ArrayList<Evidence>();
        for(var e:finding.evidence()){
            if(!e.fileId().equals(source.fileId())){evidence.add(e);continue;}
            var own=new ArrayList<Evidence>();
            for(String anchor:anchors){
                int at=text.indexOf(anchor);
                while(at>=0){int end=at+anchor.length();
                    if(at>=e.start()&&end<=e.end())own.add(new Evidence(e.fileId(),e.fileSha256(),e.parseResultId(),e.parseVersion(),e.textSha256(),at,end,anchor));
                    at=text.indexOf(anchor,at+1);
                }
            }
            if(own.isEmpty())evidence.add(e);else evidence.addAll(own);
        }
        return new Finding(finding.code(),finding.level(),finding.title(),finding.explanation(),finding.suggestion(),evidence.stream().distinct().toList(),finding.origin());
    }
    static String title(String code){return switch(code){
        case "DATE_FORMAT_COMPLETENESS"->"合同日期存在空白或格式问题";
        case "SIGN_DATE_COMPLETENESS"->"合同签订日期需要关注";
        case "DATE_SEQUENCE"->"合同日期先后顺序需要关注";
        default->"合同终止条件需要关注";
    };}
}
