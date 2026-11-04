package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Publish only same-file facts from the immutable analysis tuple into existing evidence storage. */
@Service
public class ContractAnalysisFactPublisher {
    private final ContractAnalysisSources sources;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ExtractionResultRepository facts;
    private final ProjectFactExtractor extractor;
    private final OperationAuditService audit;
    private final Clock clock;
    public ContractAnalysisFactPublisher(ContractAnalysisSources sources,ProjectFileRepository files,
            ProjectFileParseResultRepository parses,ExtractionResultRepository facts,ProjectFactExtractor extractor,
            OperationAuditService audit,Clock clock){
        this.sources=sources;this.files=files;this.parses=parses;this.facts=facts;this.extractor=extractor;this.audit=audit;this.clock=clock;
    }
    @Transactional
    public void publish(ContractAnalysisCase c,Source s,List<Step> work,CurrentUser actor){
        if(!c.owner().equals(actor.userId()))throw new IllegalStateException("ANALYSIS_OWNER_UNPROVEN");
        files.withFileAuthorityFence(c.projectId(),s.fileId(),()->{
            var parse=sources.load(c.projectId(),s);var file=sources.file(c.projectId(),s);
            if(!parses.findLatestByFileId(s.fileId()).map(p->p.parseResultId().equals(s.parseResultId())).orElse(false))
                throw new IllegalStateException("ANALYSIS_PARSE_SUPERSEDED");
            var segments=new ArrayList<ParsedProjectDocument.Segment>();int offset=0;
            for(String line:parse.canonicalText().split("\n",-1)){
                segments.add(new ParsedProjectDocument.Segment("analysis/offset:"+offset,line));offset+=line.length()+1;
            }
            for(var fact:extractor.extractPotential(file,new ParsedProjectDocument(segments),s.parseResultId()))facts.insertIfAbsent(fact);
            int published=0;
            for(var step:work)if(s.fileId().equals(step.fileId())&&step.output()!=null&&!step.kind().startsWith("CROSS")){
                for(var fact:step.output().facts()){
                    String type=switch(fact.type()){
                        case "TOTAL_AMOUNT"->"AMOUNT_NUMERIC";case "AMOUNT_WORDS"->"AMOUNT_UPPERCASE";
                        case "CONTRACT_NO","CONTRACT_NAME","CONTRACT_TYPE","PROJECT_NAME","PROJECT_NO","PARTY_A","PARTY_B","AWARD_AMOUNT","AWARD_SUPPLIER"->fact.type();
                        default->null;
                    };
                    if(type==null||fact.value().length()>200)continue;
                    // Evidence cannot migrate across a source or be clipped into a new meaning.
                    var evidence=fact.evidence().stream().filter(e->e.fileId().equals(s.fileId())
                        &&e.parseResultId().equals(s.parseResultId())&&e.parseVersion()==s.parseVersion()
                        &&e.fileSha256().equals(s.sha256())&&e.textSha256().equals(s.textSha256())
                        &&e.end()<=parse.canonicalText().length()
                        &&parse.canonicalText().substring(e.start(),e.end()).equals(e.excerpt())
                        &&ContractAnalysisEvidence.compact(e.excerpt()).contains(ContractAnalysisEvidence.compact(fact.value())))
                        .map(e->shortEvidence(e,fact.value())).filter(Objects::nonNull).findFirst();
                    if(evidence.isEmpty())continue;
                    var e=evidence.get();
                    var amount=Set.of("AMOUNT_NUMERIC","AWARD_AMOUNT").contains(type)?ContractAnalysisRules.money(fact.value()).orElse(null):null;
                    if(Set.of("AMOUNT_NUMERIC","AWARD_AMOUNT").contains(type)&&(amount==null||amount.signum()<0||amount.scale()>2))continue;
                    String key=sha256("analysis-fact-v2/"+s.fileId()+"/"+s.parseResultId()+"/"+type+"/"+fact.value()+"/"+e.start()+"/"+e.end());
                    int start=parse.canonicalText().codePointCount(0,e.start()),end=parse.canonicalText().codePointCount(0,e.end());
                    facts.insertIfAbsent(new ExtractionResult(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        c.projectId(),s.fileId(),s.parseResultId(),null,key,"contract-analysis-v2",type,null,
                        fact.value(),fact.value().strip(),amount,e.excerpt(),null,null,
                        "parse-result:"+s.parseResultId()+":canonical-codepoint:"+start+"-"+end,clock.instant()));published++;
                }
            }
            audit.success("CONTRACT_ANALYSIS_FACTS_PUBLISHED",actor,"CONTRACT_ANALYSIS_CASE",c.id().toString(),null,null,
                Map.of("fileId",s.fileId(),"parseResultId",s.parseResultId(),"facts",published),"保存有同份原文依据的合同信息");
            return null;
        });
    }
    /** Retain a real contiguous source window, including nearby role labels. */
    static Evidence shortEvidence(Evidence e,String value) {
        if(e.excerpt().length()<=240)return e;
        String needle=ContractAnalysisEvidence.compact(value);
        var text=new StringBuilder();var offsets=new ArrayList<Integer>();
        for(int i=0;i<e.excerpt().length();i++) {
            char c=e.excerpt().charAt(i);
            if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c)){text.append(c);offsets.add(i);}
        }
        int at=text.indexOf(needle);
        if(at<0||needle.isEmpty()||text.indexOf(needle,at+1)>=0)return null;
        int first=offsets.get(at),last=offsets.get(at+needle.length()-1)+1;
        if(last-first>240)return null;
        int from=Math.max(0,first-Math.min(80,240-(last-first)));
        int to=Math.min(e.excerpt().length(),Math.max(last,from+240));
        from=Math.max(0,to-240);
        if(from>first)return null;
        if(from>0&&Character.isLowSurrogate(e.excerpt().charAt(from)))from++;
        if(to<e.excerpt().length()&&Character.isLowSurrogate(e.excerpt().charAt(to)))to--;
        return new Evidence(e.fileId(),e.fileSha256(),e.parseResultId(),e.parseVersion(),e.textSha256(),
                e.start()+from,e.start()+to,e.excerpt().substring(from,to));
    }

}
