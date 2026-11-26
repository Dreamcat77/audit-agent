package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.common.application.TaskCancellationRegistry;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.infra.memory.analysis.InMemoryContractAnalysisRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ContractCancelledWorkCleanupTest {
    @Test void cleanupWaitsForActualWorkerAndRetriesStorageFailure() {
        var cases=new InMemoryContractAnalysisRepository();var files=mock(ProjectFileRepository.class);
        var parses=mock(ProjectFileParseResultRepository.class);var jobs=mock(ProjectFileJobRepository.class);
        var storage=mock(FileStoragePort.class);var running=new TaskCancellationRegistry();
        var now=Instant.now();var id=UUID.randomUUID();var project=UUID.randomUUID();
        cases.insert(new ContractAnalysisCase(id,project,"owner","a".repeat(64),ContractAnalysisCase.State.FAILED,1,null,null,1,now,now.plusSeconds(60),List.of(),List.of(),"USER_CANCELLED",1));
        var file=new ProjectFile(UUID.randomUUID(),project,null,null,"test.pdf","application/pdf",ProjectFileCategory.OTHER,
                ProjectFileIngestionStatus.AVAILABLE,ProjectFileParseStatus.PENDING,"a".repeat(64),"b".repeat(64),10,null,null,now,now);
        when(files.findByProjectId(project)).thenReturn(List.of(file));when(jobs.byProject(project)).thenReturn(List.of());
        var service=new ContractCancelledWorkCleanup(cases,files,parses,jobs,storage,running);
        try(var ignored=running.register(id)){service.cleanup();verifyNoInteractions(storage);}
        doThrow(new IllegalStateException("synthetic storage unavailable")).doNothing().when(storage).delete(any());
        service.cleanup();verify(files,never()).update(any());assertThat(cases.cancelledForCleanup(50)).contains(id);
        service.cleanup();verify(storage,times(2)).delete(any());
        verify(files).update(argThat(value->"USER_CANCELLED_CLEANED".equals(value.safeReasonCode())));
        assertThat(cases.cancelledForCleanup(50)).isEmpty();
    }
}
