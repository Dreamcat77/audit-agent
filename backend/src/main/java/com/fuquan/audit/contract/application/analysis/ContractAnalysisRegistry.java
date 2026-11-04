package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** New independent batches must not also enter the old role/identity/promotion model pipeline. */
@Component
public class ContractAnalysisRegistry {
    private final ContractAnalysisRepository repository;private final ProjectRepository projects;private final boolean enabled;
    public ContractAnalysisRegistry(ContractAnalysisRepository repository,ProjectRepository projects,@Value("${audit.contract.analysis.enabled:false}") boolean enabled,
            @Value("${audit.contract.file-processing.worker-enabled:false}") boolean parsingEnabled){
        this.repository=repository;this.projects=projects;this.enabled=enabled;
        if(enabled&&!parsingEnabled)throw new IllegalStateException("CONTRACT_ANALYSIS_REQUIRES_DURABLE_FILE_WORKER");
    }
    public boolean contains(UUID projectId){
        if(!enabled)return false;
        var project=projects.findById(projectId).orElseThrow(()->new IllegalStateException("PROJECT_AUTHORITY_UNPROVEN"));
        return repository.find(ContractAnalysisCase.identity(project.createdBy(),projectId)).filter(c->c.projectId().equals(projectId)&&c.owner().equals(project.createdBy())).isPresent();
    }
}
