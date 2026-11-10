package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** One durable checkpoint per bounded action. External calls never hold database transactions. */
@Service
public class ContractAnalysisProcessor {
    private ContractVisualQuality visualQuality;
    @org.springframework.beans.factory.annotation.Autowired public void setVisualQuality(ContractVisualQuality value){this.visualQuality=value;}
    private final ContractAnalysisRepository repository;private final ContractAnalysisTransactions tx;
    private final ContractAnalysisSources sources;private final ContractAnalysisPlan planner;
    private final ContractAnalysisRules rules;private final ContractAnalysisAiPort ai;
    private final ContractAnalysisEvidence evidence;private final ContractAnalysisRelations relations;private final Clock clock;
    private ContractAnalysisFormalBridge formalBridge;
    private com.fuquan.audit.common.privacy.ClientReviewIdentity clientIdentity;
    @org.springframework.beans.factory.annotation.Autowired
    public void setClientIdentity(com.fuquan.audit.common.privacy.ClientReviewIdentity value){this.clientIdentity=value;}
    private Map<UUID,String> reviewParties(Map<UUID,String> texts,List<UUID> ids){
        // Local fake-model mode has no configured names and never sends material externally.
        return clientIdentity==null?Map.of():clientIdentity.parties(texts,ids);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public void setFormalBridge(ContractAnalysisFormalBridge value){this.formalBridge=value;}
    public ContractAnalysisProcessor(ContractAnalysisRepository repository,ContractAnalysisTransactions tx,ContractAnalysisSources sources,
            ContractAnalysisPlan planner,ContractAnalysisRules rules,ContractAnalysisAiPort ai,ContractAnalysisEvidence evidence,ContractAnalysisRelations relations,Clock clock){
        this.repository=repository;this.tx=tx;this.sources=sources;this.planner=planner;this.rules=rules;this.ai=ai;this.evidence=evidence;this.relations=relations;this.clock=clock;
    }
    public void process(UUID id,CurrentUser actor){
        var previous=repository.find(id).orElseThrow();if(!previous.owner().equals(actor.userId())||!previous.claimable(clock.instant()))return;
        if(Collections.disjoint(actor.roles(),Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR","LEGAL","PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER")))return;
        var c=previous.claim(UUID.randomUUID().toString(),clock.instant());if(!tx.save(previous,c,actor))return;
        if(c.sources().isEmpty()){
            if(!clock.instant().isBefore(c.deadline())||!clock.instant().isBefore(c.createdAt().plusSeconds(Math.min(7200,1800+180L*c.expectedUploads())))){
                tx.save(c,c.release(State.DATA_INSUFFICIENT,List.of(),List.of(),"UPLOAD_TOTAL_DEADLINE"),actor);return;
            }
            Optional<List<Source>> accepted;
            try{accepted=sources.acceptedManifest(c);}
            catch(IllegalStateException changed){tx.save(c,c.release(State.UNKNOWN,List.of(),List.of(),"INTAKE_MANIFEST_UNPROVEN"),actor);return;}
            if(accepted.isEmpty()){tx.save(c,c.release(State.QUEUED,List.of(),List.of(),"WAITING_FOR_UPLOAD"),actor);return;}
            c=save(c,c.checkpoint(accepted.get(),List.of()),actor);if(c==null)return;
        }
        var files=new ArrayList<Source>();var texts=new LinkedHashMap<UUID,String>();var work=new ArrayList<Step>();
        for(var step:c.steps())work.add(step.status()==StepState.RUNNING?step.finish(StepState.UNKNOWN,null,"EXTERNAL_OUTCOME_UNKNOWN_AFTER_RECOVERY",clock.instant()):step);
        for(var source:c.sources()){
            var s=source;
            try{s=sources.freeze(c.projectId(),s);if(s.status()==SourceState.READY)texts.put(s.fileId(),sources.load(c.projectId(),s).canonicalText());}
            catch(IllegalStateException identity){s=ContractAnalysisSources.state(s,SourceState.UNKNOWN,"FROZEN_SOURCE_UNPROVEN");}
            files.add(s);
        }
        work.replaceAll(s->s.status()==StepState.QUEUED&&s.fileId()!=null&&!texts.containsKey(s.fileId())?
            s.finish(StepState.UNKNOWN,null,"FROZEN_SOURCE_UNPROVEN",clock.instant()):s);
        if(!clock.instant().isBefore(c.deadline())){
            files.replaceAll(s->s.status()==SourceState.WAITING?ContractAnalysisSources.state(s,SourceState.FAILED,"CASE_PARSE_DEADLINE"):s);
            work.replaceAll(s->s.status()==StepState.QUEUED?s.finish(StepState.FAILED,null,"CASE_TOTAL_DEADLINE",clock.instant()):s);
            finish(c,files,work,texts,actor,"CASE_TOTAL_DEADLINE");return;
        }
        c=save(c,c.checkpoint(files,work),actor);if(c==null)return;
        // A slow or damaged file must not hold other readable files behind its parser.
        work=new ArrayList<>(planner.prepare(c,texts));
        var chosen=work.stream().filter(s->s.status()==StepState.QUEUED).findFirst();
        if(chosen.isEmpty()){
            // At most one focused supplement per file; uncertain/failed calls are never retried under another name.
            for(var source:files)if(source.status()==SourceState.READY&&modelCalls(work)<planner.callBudget(c,texts)&&!ContractAnalysisPlan.has(work,"focus/"+source.fileId())){
                var fileWork=work.stream().filter(s->source.fileId().equals(s.fileId())&&Set.of("FILE","FRAGMENT").contains(s.kind())).toList();
                var facts=fileWork.stream().filter(s->s.output()!=null).flatMap(s->s.output().facts().stream()).toList();
                if(fileWork.isEmpty()||fileWork.stream().anyMatch(s->s.status()!=StepState.COMPLETED)||ContractAnalysisRules.unique(facts,"TOTAL_AMOUNT").isPresent()&&ContractAnalysisRules.unique(facts,"CONTRACT_NO").isPresent())continue;
                String text=texts.get(source.fileId());var matcher=java.util.regex.Pattern.compile("合同(?:编号|总价|金额)|价税合计|总价款").matcher(text);int at=-1;while(matcher.find())at=matcher.start();
                if(at>=0){int start=Math.max(0,at-500),end=Math.min(text.length(),at+2500);work.add(ContractAnalysisPlan.step("focus/"+source.fileId(),source,"FOCUSED",start,end));}
            }
            chosen=work.stream().filter(s->s.status()==StepState.QUEUED).findFirst();
        }
        if(chosen.isEmpty()){
            for(var source:files)if(source.status()==SourceState.READY&&!ContractAnalysisPlan.has(work,"rules/"+source.fileId()))
                work.add(ContractAnalysisPlan.step("rules/"+source.fileId(),source,"RULES",0,texts.get(source.fileId()).length()));
            chosen=work.stream().filter(s->s.status()==StepState.QUEUED).findFirst();
        }
        if(chosen.isEmpty()&&formalBridge!=null){
            for(var source:files)if(source.status()==SourceState.READY&&!ContractAnalysisPlan.has(work,"formal/"+source.fileId()))
                work.add(ContractAnalysisPlan.step("formal/"+source.fileId(),source,"FORMAL_LINK",0,0));
            chosen=work.stream().filter(s->s.status()==StepState.QUEUED).findFirst();
        }
        if(chosen.isEmpty()){
            if(files.stream().anyMatch(s->s.status()==SourceState.WAITING)){
                tx.save(c,c.release(State.QUEUED,files,work,"WAITING_FOR_PARSE"),actor);return;
            }
            for(var pair:relations.pairs(files,work)){
                if(!ContractAnalysisPlan.has(work,pair.id()))work.add(ContractAnalysisPlan.step(pair.id(),pair.contract(),"CROSS_RULES",0,0));
                if(modelCalls(work)<planner.callBudget(c,texts)&&!ContractAnalysisPlan.has(work,"semantic/"+pair.id())&&relations.scopes(pair,work,texts).stream().map(s->s.source().fileId()).distinct().count()==2)
                    work.add(ContractAnalysisPlan.step("semantic/"+pair.id(),pair.contract(),"CROSS_FILE",0,0));
            }
            chosen=work.stream().filter(s->s.status()==StepState.QUEUED).findFirst();
        }
        if(chosen.isEmpty()){
            if(files.stream().noneMatch(s->s.status()==SourceState.WAITING))
                for(var source:files)if(source.status()==SourceState.READY && relations.role(source,work).equals("CONTRACT")
                        && !ContractAnalysisPlan.has(work,"document-rules/"+source.fileId()))
                    work.add(ContractAnalysisPlan.step("document-rules/"+source.fileId(),source,"DOCUMENT_RULES",0,0));
            chosen=work.stream().filter(s->s.status()==StepState.QUEUED).findFirst();
        }
        if(chosen.isEmpty()){
            finish(c,files,work,texts,actor,null);return;
        }
        var step=chosen.get();int position=work.indexOf(step);UUID sourceId=step.fileId();
        var source=files.stream().filter(s->s.fileId().equals(sourceId)).findFirst().orElseThrow();
        String text=texts.get(source.fileId());var output=(Output)null;StepState result;String reason=null;
        work.set(position,step.running(clock.instant()));
        c=save(c,c.checkpoint(files,work),actor);if(c==null)return;
        step=work.get(position);
        try {
            if(step.kind().equals("FORMAL_LINK")){
                formalBridge.link(c,source,List.copyOf(work),actor);
                output=new Output("OTHER","GENERAL",true,List.of(),List.of(),List.of(),List.of(),0,null,"formal-link-v1");
            }
            else if(step.kind().equals("RECALL"))output=rules.recall(sources.file(c.projectId(),source),source,text);
            else if(step.kind().equals("RULES"))output=rules.evaluate(source,work.stream().map(Step::output).filter(Objects::nonNull).toList(),text);
            else if(step.kind().equals("DOCUMENT_RULES"))output=ContractAnalysisDocumentRules.evaluate(source,files,work,texts,relations);
            else if(step.kind().startsWith("CROSS")){
                var stepKey=step.id().replaceFirst("^semantic/","");var frozenWork=List.copyOf(work);
                var pair=relations.pairs(files,frozenWork).stream().filter(p->p.id().equals(stepKey)).findFirst().orElseThrow(()->new IllegalStateException("RELATION_UNPROVEN"));
                if(step.kind().equals("CROSS_RULES"))output=relations.evaluate(pair,work);
                else {
                    if(clock.instant().plusSeconds(175).isAfter(c.deadline()))throw new CaseDeadline();
                    var scopes=relations.scopes(pair,work,texts);var documents=new ArrayList<ContractAnalysisAiPort.Document>();
                    for(var selected:List.of(pair.contract(),pair.support())){
                        String supplied=scopes.stream().filter(s->s.source().fileId().equals(selected.fileId())).map(ContractAnalysisEvidence.Scope::supplied).collect(java.util.stream.Collectors.joining("\n\n"));
                        documents.add(new ContractAnalysisAiPort.Document(selected.fileId(),supplied));
                    }
                    output=evidence.validate(ai.analyze(new ContractAnalysisAiPort.Request(c.id(),step.inputDigest(),"CROSS_FILE",documents,
                        reviewParties(texts,documents.stream().map(ContractAnalysisAiPort.Document::id).toList()))),scopes,"CROSS_FILE");
                }
                sources.load(c.projectId(),pair.support());
            }else {
                // Keep enough time for the configured single provider timeout and result transaction.
                if(clock.instant().plusSeconds(175).isAfter(c.deadline()))throw new CaseDeadline();
                var parsed=sources.load(c.projectId(),source);
                boolean ocr=(parsed.parseMethod().name()+"/"+parsed.engine()).toUpperCase(java.util.Locale.ROOT).contains("OCR");
                var scope=new ContractAnalysisEvidence.Scope(source,text,step.start(),step.end(),ocr);
                var request=new ContractAnalysisAiPort.Request(c.id(),step.inputDigest(),step.kind(),List.of(new ContractAnalysisAiPort.Document(source.fileId(),scope.supplied(),source.name(),relations.role(source,work),
                        bounded(com.fuquan.audit.common.material.ProjectNameResolver.resolve(List.of(),List.of(text.substring(0,Math.min(1200,text.length()))),null),120),text.substring(0,Math.min(800,text.length())),ContractAnalysisSemantics.section(text,step.start()))),
                    reviewParties(texts,List.of(source.fileId())));
                output=evidence.validate(ai.analyze(request),List.of(scope),step.kind());
            }
            // Verify the exact parse tuple still exists immediately before publication.
            if(visualQuality!=null)for(var selected:files)if(texts.containsKey(selected.fileId()))
                output=visualQuality.project(output,selected,texts.get(selected.fileId()));
            sources.load(c.projectId(),source);
            result=output.complete()?StepState.COMPLETED:StepState.PARTIAL;
            if(!output.useful()&&!step.kind().equals("FORMAL_LINK")){result=StepState.DATA_INSUFFICIENT;reason="NO_SUPPORTED_ANALYSIS_IN_SCOPE";}
        } catch(ContractAnalysisOutputLimitException limit){result=StepState.FAILED;reason="MODEL_OUTPUT_LIMIT";}
        catch(CaseDeadline deadline){result=StepState.FAILED;reason="CASE_TOTAL_DEADLINE";}
        catch(org.springframework.web.client.ResourceAccessException timeout){result=StepState.UNKNOWN;reason="MODEL_OUTCOME_UNKNOWN";}
        catch(org.springframework.web.client.RestClientResponseException http){result=http.getStatusCode().value()==504?StepState.UNKNOWN:StepState.FAILED;reason="MODEL_HTTP_"+http.getStatusCode().value();}
        catch(IllegalArgumentException invalid){output=null;result=StepState.FAILED;reason="MODEL_SCHEMA_OR_EVIDENCE_INVALID";}
        catch(RuntimeException failure){output=null;result=StepState.UNKNOWN;reason="STEP_OUTCOME_UNPROVEN";}
        work.set(position,step.finish(result,output,reason,clock.instant()));
        boolean saved=tx.save(c,c.release(State.QUEUED,files,work,null),actor);
        org.slf4j.LoggerFactory.getLogger(getClass()).info("CONTRACT_ANALYSIS_STEP caseId={} kind={} outcome={} reasonCode={} saved={} suppliedChars={} evidenceRejected={}",
            id,step.kind(),result,reason,saved,step.end()-step.start(),output==null?0:output.rejectedCitations());
    }
    private ContractAnalysisCase save(ContractAnalysisCase old,ContractAnalysisCase next,CurrentUser actor){return tx.save(old,next,actor)?next:null;}
    private static long modelCalls(List<Step> work){return work.stream().filter(s->Set.of("FILE","FRAGMENT","FOCUSED","CROSS_FILE").contains(s.kind())).count();}
    private void finish(ContractAnalysisCase c,List<Source> files,List<Step> work,Map<UUID,String> texts,CurrentUser actor,String reason){
        boolean useful=work.stream().map(Step::output).filter(Objects::nonNull).anyMatch(Output::useful);
        boolean uncertain=files.stream().anyMatch(s->s.status()==SourceState.UNKNOWN)||work.stream().anyMatch(s->s.status()==StepState.UNKNOWN);
        boolean complete=reason==null&&files.stream().allMatch(s->s.status()==SourceState.READY)
            &&work.stream().noneMatch(s->Set.of(StepState.RUNNING,StepState.QUEUED,StepState.FAILED,StepState.UNKNOWN,StepState.PARTIAL).contains(s.status())&&!ContractAnalysisPlan.recoveredOutputLimit(s,work))
            &&files.stream().allMatch(s->planner.fullCoverage(s,work,texts.getOrDefault(s.fileId(),"").length()));
        State state=useful?(complete?State.COMPLETED:State.PARTIAL):uncertain?State.UNKNOWN:
            files.stream().anyMatch(s->s.status()==SourceState.READY)&&work.stream().noneMatch(s->s.status()==StepState.FAILED)?State.DATA_INSUFFICIENT:State.FAILED;
        tx.save(c,c.release(state,files,work,reason!=null?reason:!complete?"ANALYSIS_COVERAGE_LIMITED":null),actor);
    }
    private static String bounded(String value,int max){return value==null?null:value.substring(0,Math.min(max,value.length()));}
    private static class CaseDeadline extends RuntimeException {}
}
