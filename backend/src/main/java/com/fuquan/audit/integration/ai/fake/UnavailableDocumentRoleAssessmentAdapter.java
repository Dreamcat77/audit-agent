package com.fuquan.audit.integration.ai.fake;

import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentException;
import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentPort;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleModelProvenance;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "local-dev & !local-ai"})
public class UnavailableDocumentRoleAssessmentAdapter implements DocumentRoleAssessmentPort {
    private static final ProjectFileRoleModelProvenance PROVENANCE =
            new ProjectFileRoleModelProvenance("DISABLED", "not-configured", "not-configured",
                    "document-role-prompt-v1", "document-role-v1");

    @Override
    public ProjectFileRoleModelProvenance configuredProvenance() {
        return PROVENANCE;
    }

    @Override
    public Result assess(Request request) {
        throw new DocumentRoleAssessmentException(
                DocumentRoleAssessmentException.Reason.UNAVAILABLE, null);
    }
}
