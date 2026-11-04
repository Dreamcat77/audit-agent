package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.infra.memory.analysis.InMemoryContractAnalysisRepository;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisAdmissionTest {
    final Instant now=ContractAnalysisCaseTest.NOW;
    final CurrentUser actor=new CurrentUser("owner","test-user","测试用户",Set.of("AUDITOR"));
    final ProjectRepository projects=mock(ProjectRepository.class);
    final ProjectFileRepository files=mock(ProjectFileRepository.class);
    final ProjectFileParseResultRepository parses=mock(ProjectFileParseResultRepository.class);
    final ProjectFileJobRepository jobs=mock(ProjectFileJobRepository.class);
    final ContractAnalysisSources sources=new ContractAnalysisSources(projects,files,parses,jobs);
    final InMemoryContractAnalysisRepository repo=new InMemoryContractAnalysisRepository();
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    final OperationAuditService audit=mock(OperationAuditService.class);
    final ContractAnalysisService service=new ContractAnalysisService(repo,new ContractAnalysisTransactions(repo,audit,clock),sources,clock);

    ProjectFile file(UUID project, UUID id){return new ProjectFile(id,project,null,"合同.docx","合同.docx","application/octet-stream",ProjectFileCategory.UNKNOWN,
        ProjectFileIngestionStatus.AVAILABLE,ProjectFileParseStatus.PARSED,"a".repeat(64),"a".repeat(64),100,"合同内容",null,now,now);}
    @Test void repeatedAdmissionIsStableAndReadDoesNotPerformWork(){
        UUID p=UUID.randomUUID();var f=file(p,UUID.randomUUID());
        when(projects.findById(p)).thenReturn(Optional.of(Project.blank(p,actor.userId(),now)));
        when(files.findByProjectId(p)).thenReturn(List.of(f));
        var first=service.admit(p,actor);assertThat(service.admit(p,actor)).isEqualTo(first);
        assertThat(service.forProject(p,actor)).isEqualTo(first);assertThat(service.recent(actor,1,20)).containsExactly(first);
        verifyNoInteractions(parses,jobs);verify(audit,times(1)).success(anyString(),eq(actor),anyString(),anyString(),isNull(),isNull(),anyMap(),anyString());
    }
    @Test void otherOwnersCannotAdmitOrReadCase(){
        var c=ContractAnalysisCaseTest.sample();repo.insert(c);
        var other=new CurrentUser("other","other","另一用户",Set.of("AUDITOR"));
        assertThatThrownBy(()->service.owned(c.id(),other)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        when(projects.findById(c.projectId())).thenReturn(Optional.of(Project.blank(c.projectId(),"owner",now)));
        assertThatThrownBy(()->sources.manifest(c.projectId(),other)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        verifyNoInteractions(files,parses,jobs);
    }
    @Test void materialChangesNeverPolluteOldCase(){
        UUID p=UUID.randomUUID();var f=file(p,UUID.randomUUID());
        when(projects.findById(p)).thenReturn(Optional.of(Project.blank(p,actor.userId(),now)));
        when(files.findByProjectId(p)).thenReturn(List.of(f));var first=service.admit(p,actor);
        when(files.findByProjectId(p)).thenReturn(List.of(f,file(p,UUID.randomUUID())));
        assertThatThrownBy(()->service.admit(p,actor)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        assertThat(repo.find(first.id())).contains(first);
    }
    @Test void historyFiltersByProjectNameAndCreatedDateBeforePaging(){
        var value=ContractAnalysisCaseTest.sample();
        var projectRepository=mock(ProjectRepository.class);
        when(projectRepository.findById(value.projectId())).thenReturn(Optional.of(new Project(value.projectId(),"办公设备采购项目",null,null,"owner",1,now,now)));
        var filtered=new InMemoryContractAnalysisRepository(projectRepository);filtered.insert(value);
        assertThat(filtered.recent(value.owner(),0,20,"采购",now.minusSeconds(1),now.plusSeconds(1))).containsExactly(value);
        assertThat(filtered.recent(value.owner(),0,20,"采购",now.plusSeconds(1),now.plusSeconds(2))).isEmpty();
        assertThat(filtered.recent(value.owner(),0,20,"工程",now.minusSeconds(1),now.plusSeconds(1))).isEmpty();
    }
    @Test void frozenParseReadsExactVersionAndRejectsForeignSource(){
        UUID p=UUID.randomUUID();var f=file(p,UUID.randomUUID());var parse=new ProjectFileParseResult(UUID.randomUUID(),p,f.id(),f.sha256(),1,
            ProjectFileParseOrigin.CURRENT_PIPELINE,ProjectFileParseStatus.PARSED,ProjectFileParseMethod.DOCX_TEXT,"test",null,"合同内容",sha256("合同内容"),1,1L,null,null,now,now,0);
        var s=new Source(f.id(),f.originalFilename(),f.sha256(),100,SourceState.WAITING,null,null,null,null);
        when(files.findByProjectIdAndId(p,f.id())).thenReturn(Optional.of(f));when(parses.findLatestByFileId(f.id())).thenReturn(Optional.of(parse));
        var frozen=sources.freeze(p,s);when(parses.findById(parse.parseResultId())).thenReturn(Optional.of(parse));
        assertThat(sources.load(p,frozen)).isEqualTo(parse);
        assertThatThrownBy(()->sources.load(UUID.randomUUID(),frozen)).hasMessage("FROZEN_PARSE_IDENTITY_UNPROVEN");
        verify(parses,times(1)).findLatestByFileId(f.id());
    }
    @Test void unknownJobIsNotMisrepresentedAsFailedOrRetried(){
        var c=ContractAnalysisCaseTest.sample();var s=c.sources().get(0);
        when(files.findByProjectIdAndId(c.projectId(),s.fileId())).thenReturn(Optional.empty());
        var unknown=sources.freeze(c.projectId(),s);assertThat(unknown.status()).isEqualTo(SourceState.UNKNOWN);
        assertThat(sources.freeze(c.projectId(),unknown)).isEqualTo(unknown);verifyNoInteractions(parses,jobs);
    }
}
