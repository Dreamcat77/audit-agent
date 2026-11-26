package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.infra.memory.analysis.InMemoryContractAnalysisRepository;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisProcessorTest {
    @Test void longerScanningBudgetDoesNotKeepAnUnuploadedBatchWaitingForSixHours(){
        var p=UUID.randomUUID();var c=new ContractAnalysisCase(identity("owner",p),p,"owner",sha256("unuploaded"),State.QUEUED,0,null,null,0,
            clock.instant(),clock.instant().plusSeconds(21600),List.of(),List.of(),"WAITING_FOR_UPLOAD",1);repo.insert(c);
        when(sources.acceptedManifest(any())).thenReturn(Optional.empty());
        clock.now=c.createdAt().plusSeconds(1981);processor.process(c.id(),actor);
        assertThat(repo.find(c.id()).orElseThrow().reasonCode()).isEqualTo("UPLOAD_TOTAL_DEADLINE");verifyNoInteractions(ai);
    }
    @Test void completeUploadedManifestIsNotDiscardedBecauseItsWorkerWaitExceededUploadGrace(){
        var ready=caseFor("验收合格后付款");
        var p=ready.projectId();var pending=new ContractAnalysisCase(UUID.randomUUID(),p,"owner",sha256("late-schedule"),State.QUEUED,800,null,null,400,
                clock.instant(),clock.instant().plusSeconds(21600),List.of(),List.of(),"WAITING_FOR_UPLOAD",1);repo.insert(pending);
        when(sources.acceptedManifest(any())).thenReturn(Optional.of(ready.sources()));
        clock.now=pending.createdAt().plusSeconds(2400);
        processor.process(pending.id(),actor);
        var resumed=repo.find(pending.id()).orElseThrow();
        assertThat(resumed.sources()).containsExactlyElementsOf(ready.sources());
        assertThat(resumed.reasonCode()).isNotEqualTo("UPLOAD_TOTAL_DEADLINE");
        assertThat(resumed.status()).isEqualTo(State.QUEUED);
    }
    static class MutableClock extends Clock {
        Instant now=ContractAnalysisCaseTest.NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    final MutableClock clock=new MutableClock();
    final InMemoryContractAnalysisRepository repo=new InMemoryContractAnalysisRepository();
    final ContractAnalysisSources sources=mock(ContractAnalysisSources.class);final ContractAnalysisAiPort ai=mock(ContractAnalysisAiPort.class);
    final ContractAnalysisEvidence evidence=new ContractAnalysisEvidence();
    final ContractAnalysisTransactions tx=new ContractAnalysisTransactions(repo,mock(OperationAuditService.class),clock);
    final ContractAnalysisRules rules=new ContractAnalysisRules(new ProjectFactExtractor(clock),evidence);
    final ContractAnalysisRelations relations=new ContractAnalysisRelations();
    final ContractAnalysisProcessor processor=new ContractAnalysisProcessor(repo,tx,sources,new ContractAnalysisPlan(),rules,ai,evidence,relations,clock);
    final CurrentUser actor=new CurrentUser("owner","test-user","测试用户",Set.of("AUDITOR"));
    @Test void bidHistoryDoesNotTriggerContractTotalFocusedCall(){
        var c=caseFor("合同编号：OLD-2023\n验收合格后付款","投标文件.pdf");
        when(ai.analyze(any())).thenAnswer(i->clauses(i.getArgument(0)));
        var done=run(c.id());assertThat(done.steps()).noneMatch(step->step.kind().equals("FOCUSED"));
        verify(ai,times(1)).analyze(any());
    }
    ContractAnalysisCase caseFor(String text){
        return caseFor(text,"合成合同.docx");
    }
    ContractAnalysisCase caseFor(String text,String name){
        var original=ContractAnalysisEvidenceTest.source(text);
        var s=new Source(original.fileId(),name,original.sha256(),original.size(),original.status(),original.parseResultId(),original.parseVersion(),original.textSha256(),original.reasonCode());var p=UUID.randomUUID();
        var c=new ContractAnalysisCase(identity("owner",p),p,"owner",sha256("manifest/"+p),State.QUEUED,0,null,null,0,clock.instant(),clock.instant().plusSeconds(1800),List.of(s),List.of(),null);
        when(sources.freeze(p,s)).thenReturn(s);
        var parse=new ProjectFileParseResult(s.parseResultId(),p,s.fileId(),s.sha256(),1,ProjectFileParseOrigin.CURRENT_PIPELINE,ProjectFileParseStatus.PARSED,ProjectFileParseMethod.DOCX_TEXT,"synthetic",null,text,s.textSha256(),1,1L,null,null,clock.instant(),clock.instant(),0);
        when(sources.load(p,s)).thenReturn(parse);when(sources.file(p,s)).thenReturn(new ContractAnalysisAdmissionTest().file(p,s.fileId()));repo.insert(c);return c;
    }
    ContractAnalysisCase run(UUID id){for(int i=0;i<150;i++){var c=repo.find(id).orElseThrow();if(c.terminal())return c;processor.process(id,actor);}throw new AssertionError("case failed to converge");}
    ContractAnalysisAiPort.Result clauses(ContractAnalysisAiPort.Request request){
        var doc=request.documents().get(0);return ContractAnalysisEvidenceTest.result(List.of(),List.of(new ContractAnalysisAiPort.Clause("ACCEPTANCE","以验收合格为付款前提，其他独立条款照常审查",List.of(new ContractAnalysisAiPort.Citation(doc.id(),"验收合格后付款")))),List.of());
    }
    @Test void missingFormalFieldsStillProducesMeaningfulNoRiskReport(){
        var c=caseFor("这是合成服务合同\n验收合格后付款\n争议由合同履行地法院处理");when(ai.analyze(any())).thenAnswer(i->clauses(i.getArgument(0)));
        var done=run(c.id());assertThat(done.status()).isEqualTo(State.COMPLETED);verify(ai,times(1)).analyze(any());
        var reports=new ContractAnalysisReport(mock(ContractAnalysisService.class),sources,relations);var report=reports.project(done);
        assertThat(report.documents().get(0).clauses()).isNotEmpty();assertThat(report.findings()).isEmpty();
        assertThat(report.checks()).noneMatch(ch->ch.status().equals("PASS"));assertThat(report.limitations()).isEmpty();
    }
    @Test void interruptedProviderCallIsUnknownAndNeverAutomaticallyResent(){
        var c=caseFor("合同名称：合成服务合同\n验收合格后付款");when(ai.analyze(any())).thenThrow(new org.springframework.web.client.ResourceAccessException("synthetic timeout"));
        var done=run(c.id());assertThat(done.status()).isIn(State.PARTIAL,State.UNKNOWN);
        assertThat(done.steps()).anyMatch(s->s.status()==StepState.UNKNOWN);verify(ai,times(1)).analyze(any());
        processor.process(c.id(),actor);verify(ai,times(1)).analyze(any());
    }
    @Test void recoveredRunningStepIsNotResentAndIndependentRulesContinue(){
        var c=caseFor("验收合格后付款");var source=c.sources().get(0);var claim=c.claim("old",clock.instant());repo.replace(c,claim,clock.instant());
        var planned=new ContractAnalysisPlan().prepare(claim,Map.of(source.fileId(),"验收合格后付款"));
        var work=planned.stream().map(s->s.kind().equals("FILE")?s.running(clock.instant()):s).toList();
        var running=claim.checkpoint(claim.sources(),work);repo.replace(claim,running,clock.instant());clock.now=clock.now.plusSeconds(241);
        var done=run(c.id());assertThat(done.terminal()).isTrue();assertThat(done.steps()).anyMatch(s->s.kind().equals("RULES"));
        verifyNoInteractions(ai);
    }
    @Test void lateExecutorCannotOverwriteNewOwnerOrPublishExpiredResult(){
        var c=caseFor("验收合格后付款");processor.process(c.id(),actor);
        when(ai.analyze(any())).thenAnswer(i->{clock.now=clock.now.plusSeconds(241);return clauses(i.getArgument(0));});
        processor.process(c.id(),actor);var durable=repo.find(c.id()).orElseThrow();
        assertThat(durable.status()).isEqualTo(State.RUNNING);assertThat(durable.steps()).anyMatch(s->s.kind().equals("FILE")&&s.status()==StepState.RUNNING&&s.output()==null);
        var done=run(c.id());verify(ai,times(1)).analyze(any());assertThat(done.steps()).anyMatch(s->s.kind().equals("FILE")&&s.status()==StepState.UNKNOWN);
    }
    @Test void queuedCaseHasDeadlineAndDoesNotCallModelAfterIt(){
        var c=caseFor("验收合格后付款");clock.now=c.deadline().plusSeconds(1);var done=run(c.id());
        assertThat(done.terminal()).isTrue();assertThat(done.reasonCode()).isEqualTo("CASE_TOTAL_DEADLINE");verifyNoInteractions(ai);
    }
    @Test void previouslyCreatedBatchIsRediscoveredWithoutBrowserCompletingUploads(){
        var p=UUID.randomUUID();var c=new ContractAnalysisCase(identity("owner",p),p,"owner",sha256("admission"),State.QUEUED,0,null,null,0,clock.instant(),clock.instant().plusSeconds(1800),List.of(),List.of(),"WAITING_FOR_UPLOAD",2);repo.insert(c);
        when(sources.acceptedManifest(any())).thenReturn(Optional.empty());processor.process(c.id(),actor);
        assertThat(repo.find(c.id()).orElseThrow().reasonCode()).isEqualTo("WAITING_FOR_UPLOAD");
        clock.now=c.deadline().plusSeconds(1);processor.process(c.id(),actor);
        assertThat(repo.find(c.id()).orElseThrow().status()).isEqualTo(State.DATA_INSUFFICIENT);verifyNoInteractions(ai);
    }
    @Test void frozenParseDisappearanceCannotPublishDerivedOutput(){
        var c=caseFor("验收合格后付款");processor.process(c.id(),actor);
        when(ai.analyze(any())).thenAnswer(i->{when(sources.load(c.projectId(),c.sources().get(0))).thenThrow(new IllegalStateException("synthetic parse lost"));return clauses(i.getArgument(0));});
        processor.process(c.id(),actor);var latest=repo.find(c.id()).orElseThrow();
        assertThat(latest.steps()).anyMatch(s->s.kind().equals("FILE")&&s.status()==StepState.UNKNOWN&&s.output()==null);
    }
    @Test void provenOutputLimitSplitsTheRangeAndConvergesWithoutReplayingOriginalRequest(){
        String text="验收合格后付款。".repeat(250);var c=caseFor(text);
        when(ai.analyze(any())).thenAnswer(i->{
            var request=(ContractAnalysisAiPort.Request)i.getArgument(0);
            if(request.documents().get(0).text().length()>1400)throw new ContractAnalysisOutputLimitException();
            return new ContractAnalysisAiPort.Result("synthetic","contract-case-v1","CONTRACT","GENERAL",true,List.of(),
                List.of(new ContractAnalysisAiPort.Clause("ACCEPTANCE","按原文验收后付款",List.of(new ContractAnalysisAiPort.Citation(request.documents().get(0).id(),request.documents().get(0).text())))),List.of());
        });
        var done=run(c.id());assertThat(done.status()).isEqualTo(State.COMPLETED);
        assertThat(done.steps()).anyMatch(s->"MODEL_OUTPUT_LIMIT".equals(s.reasonCode()));
        assertThat(done.steps().stream().filter(s->"MODEL_OUTPUT_LIMIT".equals(s.reasonCode())))
            .allMatch(s->ContractAnalysisPlan.recoveredOutputLimit(s,done.steps()));
        var captor=org.mockito.ArgumentCaptor.forClass(ContractAnalysisAiPort.Request.class);
        verify(ai,atLeast(3)).analyze(captor.capture());
        assertThat(captor.getAllValues().stream().map(ContractAnalysisAiPort.Request::inputDigest)).doesNotHaveDuplicates();
    }
    @Test void semanticContextComesFromFrozenSourceNotMutableProjectDisplayName(){
        String text="项目名称：青禾档案整理项目\n验收合格后付款";
        var c=caseFor(text);when(sources.projectName(any())).thenReturn("后来修改的其他项目");
        when(ai.analyze(any())).thenAnswer(i->{var request=(ContractAnalysisAiPort.Request)i.getArgument(0);var document=request.documents().get(0);
            assertThat(document.header()).isEqualTo(text);assertThat(document.projectName()).isEqualTo("青禾档案整理项目");
            assertThat(document.name()).isEqualTo(c.sources().get(0).name());return clauses(request);});
        run(c.id());verify(ai,atLeastOnce()).analyze(any());verify(sources,never()).projectName(any());
    }
    @Test void longFileScopesDoNotRepeatUnseenHeaderClausesAsCitableMaterial(){
        String text="项目名称：青禾档案整理项目\n合同总价10000元。验收合格后付款。\n"+"运维记录格式，不调整合同约定。\n".repeat(220)+"特别约定：验收前付清全部价款。";
        var c=caseFor(text);var requests=new ArrayList<ContractAnalysisAiPort.Request>();
        when(ai.analyze(any())).thenAnswer(i->{var request=(ContractAnalysisAiPort.Request)i.getArgument(0);requests.add(request);
            var document=request.documents().get(0);assertThat(document.header()).isNull();
            assertThat(text).contains(document.text());assertThat(document.projectName()).isEqualTo("青禾档案整理项目");
            return ContractAnalysisEvidenceTest.result(List.of(),List.of(),List.of());});
        var done=run(c.id());assertThat(done.terminal()).isTrue();
        assertThat(requests).hasSizeGreaterThan(1);
        var fragments=requests.stream().filter(r->r.scope().equals("FRAGMENT")).toList();
        assertThat(fragments.get(fragments.size()-1).documents().get(0).text()).contains("验收前付清全部价款").doesNotContain("验收合格后付款");
    }

}
