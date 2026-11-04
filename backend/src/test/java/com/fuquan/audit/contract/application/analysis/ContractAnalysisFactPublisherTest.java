package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisFactPublisherTest {
    final ContractAnalysisSources sources=mock(ContractAnalysisSources.class);
    final ProjectFileRepository files=mock(ProjectFileRepository.class);
    final ProjectFileParseResultRepository parses=mock(ProjectFileParseResultRepository.class);
    final ExtractionResultRepository facts=mock(ExtractionResultRepository.class);
    final Clock clock=Clock.fixed(ContractAnalysisCaseTest.NOW,ZoneOffset.UTC);
    final ContractAnalysisFactPublisher publisher=new ContractAnalysisFactPublisher(sources,files,parses,facts,
        new ProjectFactExtractor(clock),mock(OperationAuditService.class),clock);
    final ContractAnalysisCase c=ContractAnalysisCaseTest.sample();
    final CurrentUser actor=new CurrentUser(c.owner(),"test","测试",Set.of("AUDITOR"));
    final String text="合成条款唯一合同金额100元";
    final Source source=ContractAnalysisEvidenceTest.source(text);
    final ProjectFileParseResult parse=new ProjectFileParseResult(source.parseResultId(),c.projectId(),source.fileId(),source.sha256(),1,
        ProjectFileParseOrigin.CURRENT_PIPELINE,ProjectFileParseStatus.PARSED,ProjectFileParseMethod.DOCX_TEXT,"test",null,text,sha256(text),1,1L,null,null,clock.instant(),clock.instant(),0);
    void ready(){
        doAnswer(i->((Supplier<?>)i.getArgument(2)).get()).when(files).withFileAuthorityFence(eq(c.projectId()),eq(source.fileId()),any());
        when(sources.load(c.projectId(),source)).thenReturn(parse);
        when(sources.file(c.projectId(),source)).thenReturn(new ContractAnalysisAdmissionTest().file(c.projectId(),source.fileId()));
        when(parses.findLatestByFileId(source.fileId())).thenReturn(Optional.of(parse));
    }
    Step work(Evidence evidence){
        var output=new Output("CONTRACT","GENERAL",true,List.of(new Fact("TOTAL_AMOUNT","100元","AI_SOURCE_VERIFIED",List.of(evidence))),List.of(),List.of(),List.of(),0,"test","v1");
        return ContractAnalysisPlan.step("model",source,"FILE",0,text.length()).finish(StepState.COMPLETED,output,null,clock.instant());
    }
    Evidence evidence(Source s){return new Evidence(s.fileId(),s.sha256(),s.parseResultId(),1,s.textSha256(),0,text.length(),text);}
    @Test void sameFrozenEvidencePublishesStableKeysAndExactAmountsAcrossReplay(){
        ready();publisher.publish(c,source,List.of(work(evidence(source))),actor);publisher.publish(c,source,List.of(work(evidence(source))),actor);
        var captured=ArgumentCaptor.forClass(ExtractionResult.class);verify(facts,atLeast(2)).insertIfAbsent(captured.capture());
        var published=captured.getAllValues().stream().filter(f->f.extractionVersion().equals("contract-analysis-v2")).toList();
        assertThat(published).hasSize(2);assertThat(published.get(0).extractionKey()).isEqualTo(published.get(1).extractionKey());
        assertThat(published.get(0).valueAmount()).isEqualByComparingTo("100");
        assertThat(published).allMatch(f->f.parseResultId().equals(source.parseResultId())&&f.sourceFileId().equals(source.fileId()));
    }
    @Test void foreignSourceCannotEnterFormalFacts(){
        ready();publisher.publish(c,source,List.of(work(evidence(ContractAnalysisEvidenceTest.source(text)))),actor);
        var captured=ArgumentCaptor.forClass(ExtractionResult.class);verify(facts,atLeast(0)).insertIfAbsent(captured.capture());
        assertThat(captured.getAllValues()).noneMatch(f->f.extractionVersion().equals("contract-analysis-v2"));
    }
    @Test void replacedParseStopsBeforeAnyFormalFactWrite(){
        ready();when(parses.findLatestByFileId(source.fileId())).thenReturn(Optional.empty());
        assertThatThrownBy(()->publisher.publish(c,source,List.of(work(evidence(source))),actor)).hasMessage("ANALYSIS_PARSE_SUPERSEDED");
        verifyNoInteractions(facts);
    }
    @Test void foreignOwnerNeverAcquiresSourceFence(){
        var stranger=new CurrentUser("other","other","其他",Set.of("AUDITOR"));
        assertThatThrownBy(()->publisher.publish(c,source,List.of(),stranger)).hasMessage("ANALYSIS_OWNER_UNPROVEN");
        verifyNoInteractions(files,facts);
    }
}
