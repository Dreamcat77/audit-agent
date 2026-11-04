package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.project.ProjectCommandTransactionCoordinator;
import com.fuquan.audit.contract.domain.project.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisIntakeTest extends ContractAnalysisAdmissionTest {
    final ProjectCommandTransactionCoordinator commands=mock(ProjectCommandTransactionCoordinator.class);
    final ContractAnalysisIntake intake=new ContractAnalysisIntake(commands,repo,audit,clock);
    @Test void newBatchReservesScanningTimeAndReplayCannotResetDeadline(){
        UUID p=UUID.randomUUID();when(commands.create(eq("scan-key"),any(),any(),any())).thenReturn(Project.blank(p,actor.userId(),now));
        var receipt=intake.start("scan-key",1,actor,null,null,null);var first=repo.find(receipt.caseId()).orElseThrow();
        assertThat(first.deadline()).isEqualTo(first.createdAt().plusSeconds(21600));
        assertThat(intake.start("scan-key",1,actor,null,null,null)).isEqualTo(receipt);
        assertThat(repo.find(receipt.caseId()).orElseThrow().deadline()).isEqualTo(first.deadline());
    }
    @Test void intakeIsIdempotentAndCountCannotChange(){
        UUID p=UUID.randomUUID();when(commands.create(eq("same-key"),any(),any(),any())).thenReturn(Project.blank(p,actor.userId(),now));
        var first=intake.start("same-key",2,actor,null,null,null);
        assertThat(intake.start("same-key",2,actor,null,null,null)).isEqualTo(first);
        assertThatThrownBy(()->intake.start("same-key",3,actor,null,null,null)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        var c=repo.find(first.caseId()).orElseThrow();assertThat(c.sources()).isEmpty();assertThat(c.expectedUploads()).isEqualTo(2);
        verify(audit,times(1)).success(eq("CONTRACT_ANALYSIS_CREATED"),eq(actor),anyString(),anyString(),isNull(),isNull(),anyMap(),anyString());
    }
    @Test void invalidAdmissionDoesNotCreateAnyProject(){
        assertThatThrownBy(()->intake.start("x",201,actor,null,null,null)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        verifyNoInteractions(commands);
    }
    @Test void anotherOwnersReplayedProjectDoesNotCreateAnalysis(){
        UUID p=UUID.randomUUID();when(commands.create(any(),any(),any(),any())).thenReturn(Project.blank(p,"foreign",now));
        assertThatThrownBy(()->intake.start("key",1,actor,null,null,null)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        assertThat(repo.recent(actor.userId(),0,20)).isEmpty();verifyNoInteractions(audit);
    }
    ContractAnalysisCase pending(UUID p,int count){return new ContractAnalysisCase(identity("owner",p),p,"owner",sha256("pending"),State.QUEUED,0,null,null,0,now,now.plusSeconds(1800),List.of(),List.of(),null,count);}
    ProjectFile archiveFile(UUID p,UUID id,UUID parent,ProjectFileCategory category,ProjectFileIngestionStatus status){
        return new ProjectFile(id,p,parent,"synthetic",category==ProjectFileCategory.PACKAGE?"材料.zip":"材料.docx","application/octet-stream",category,status,ProjectFileParseStatus.PENDING,"a".repeat(64),"a".repeat(64),10,null,null,now,now);
    }
    @Test void incompleteUploadsCannotSealPartialManifest(){
        UUID p=UUID.randomUUID();when(projects.findById(p)).thenReturn(Optional.of(Project.blank(p,"owner",now)));
        var first=archiveFile(p,UUID.randomUUID(),null,ProjectFileCategory.UNKNOWN,ProjectFileIngestionStatus.AVAILABLE);
        when(files.findByProjectId(p)).thenReturn(List.of(first));assertThat(sources.acceptedManifest(pending(p,2))).isEmpty();
        var uploading=archiveFile(p,UUID.randomUUID(),null,ProjectFileCategory.UNKNOWN,ProjectFileIngestionStatus.UPLOAD_PENDING);
        when(files.findByProjectId(p)).thenReturn(List.of(first,uploading));assertThat(sources.acceptedManifest(pending(p,2))).isEmpty();
    }
    @Test void zipWaitsForDurableJobBeforeFreezingMembers(){
        UUID p=UUID.randomUUID();when(projects.findById(p)).thenReturn(Optional.of(Project.blank(p,"owner",now)));
        var root=archiveFile(p,UUID.randomUUID(),null,ProjectFileCategory.PACKAGE,ProjectFileIngestionStatus.AVAILABLE);
        var member=archiveFile(p,UUID.randomUUID(),root.id(),ProjectFileCategory.UNKNOWN,ProjectFileIngestionStatus.AVAILABLE);
        when(files.findByProjectId(p)).thenReturn(List.of(root,member));var job=mock(ProjectFileJob.class);
        when(jobs.byFile(p,root.id())).thenReturn(Optional.of(job));when(job.status()).thenReturn("PROCESSING");
        assertThat(sources.acceptedManifest(pending(p,1))).isEmpty();when(job.status()).thenReturn("COMPLETED");
        assertThat(sources.acceptedManifest(pending(p,1)).orElseThrow()).extracting(Source::fileId).containsExactly(member.id());
    }
    @Test void unknownAdditionalRootCannotBeSilentlyAdded(){
        UUID p=UUID.randomUUID();when(projects.findById(p)).thenReturn(Optional.of(Project.blank(p,"owner",now)));
        when(files.findByProjectId(p)).thenReturn(List.of(file(p,UUID.randomUUID()),file(p,UUID.randomUUID())));
        assertThatThrownBy(()->sources.acceptedManifest(pending(p,1))).hasMessage("INTAKE_MANIFEST_CHANGED");
    }
}
