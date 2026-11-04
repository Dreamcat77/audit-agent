package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import org.springframework.stereotype.Service;

@Service
public class ProjectFileIdentityProjectionService {
    private final ProjectRepository projects;
    private final EffectiveProjectIdentityResolver resolver;
    public ProjectFileIdentityProjectionService(ProjectRepository projects, EffectiveProjectIdentityResolver resolver) {
        this.projects = projects; this.resolver = resolver;
    }
    public EffectiveProjectIdentity project(ProjectFile file) {
        var project = projects.findById(file.projectId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        return resolver.resolve(project, file);
    }
}
