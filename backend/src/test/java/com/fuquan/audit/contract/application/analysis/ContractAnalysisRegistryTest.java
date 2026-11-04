package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.Project;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractAnalysisRegistryTest {
    final ContractAnalysisRepository cases=mock(ContractAnalysisRepository.class);final ProjectRepository projects=mock(ProjectRepository.class);
    @Test void disabledFeatureDoesNotQueryNewSchema(){
        assertThat(new ContractAnalysisRegistry(cases,projects,false,false).contains(UUID.randomUUID())).isFalse();verifyNoInteractions(cases,projects);
    }
    @Test void featureCannotStartWithoutDurableParser(){assertThatThrownBy(()->new ContractAnalysisRegistry(cases,projects,true,false)).hasMessage("CONTRACT_ANALYSIS_REQUIRES_DURABLE_FILE_WORKER");}
    @Test void onlyAnExistingIndependentBatchBypassesFormalPipeline(){
        var c=ContractAnalysisCaseTest.sample();when(projects.findById(c.projectId())).thenReturn(Optional.of(Project.blank(c.projectId(),c.owner(),c.createdAt())));
        when(cases.find(c.id())).thenReturn(Optional.of(c));var registry=new ContractAnalysisRegistry(cases,projects,true,true);
        assertThat(registry.contains(c.projectId())).isTrue();when(cases.find(c.id())).thenReturn(Optional.empty());assertThat(registry.contains(c.projectId())).isFalse();
    }
}
