package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentCommand;
import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentService;
import com.fuquan.audit.contract.application.project.identity.IdentityAssessmentResult;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentCommand;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentService;
import com.fuquan.audit.contract.application.project.role.AiRoleAssessmentService;
import com.fuquan.audit.contract.application.project.role.RoleAssessmentResult;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resumes server-owned intake stages after a durable upload outcome.
 *
 * <p>This service only creates machine assessments. Human role and project-identity
 * decisions remain the authority for candidate materialization.</p>
 */
@Service
public class ProjectIntakeProgressionService {
    private static final String ROLE_KEY_PREFIX = "project-intake-role:";
    private static final String IDENTITY_KEY_PREFIX = "project-intake-identity:";

    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final RoleAssessmentService roleAssessments;
    private final AiRoleAssessmentService aiRoleAssessments;
    private final IdentityAssessmentService identityAssessments;
    private final com.fuquan.audit.security.application.AuthContext auth;

    @Autowired
    public ProjectIntakeProgressionService(ProjectRepository projects, ProjectFileRepository files,
            ProjectFileParseResultRepository parses, RoleAssessmentService roleAssessments,
            AiRoleAssessmentService aiRoleAssessments, IdentityAssessmentService identityAssessments,
            com.fuquan.audit.security.application.AuthContext auth) {
        this.projects = projects;
        this.files = files;
        this.parses = parses;
        this.roleAssessments = roleAssessments;
        this.aiRoleAssessments = aiRoleAssessments;
        this.identityAssessments = identityAssessments;
        this.auth = auth;
    }

    public ProjectIntakeProgressionService(ProjectRepository projects, ProjectFileRepository files,
            ProjectFileParseResultRepository parses, RoleAssessmentService roleAssessments,
            AiRoleAssessmentService aiRoleAssessments, IdentityAssessmentService identityAssessments) {
        this(projects, files, parses, roleAssessments, aiRoleAssessments, identityAssessments, null);
    }

    /** Idempotently advances every currently parsed file, including archive children. */
    public void ensureRoleAssessments(UUID projectId, ProjectRequestMetadata metadata) {
        var actor = java.util.Objects.requireNonNullElseGet(
                authActor(), () -> { throw new BusinessException(BusinessErrorCode.UNAUTHENTICATED); });
        ensureRoleAssessments(projectId, metadata, actor);
    }

    public void ensureRoleAssessments(UUID projectId, ProjectRequestMetadata metadata,
            com.fuquan.audit.security.domain.CurrentUser actor) {
        files.findByProjectId(projectId).stream()
                .filter(file -> file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE)
                .filter(file -> file.parseStatus() == ProjectFileParseStatus.PARSED)
                .forEach(file -> parses.findLatestByFileId(file.id()).ifPresent(parse ->
                        ensureRoleAssessment(projectId, file.id(),
                                ROLE_KEY_PREFIX + parse.parseResultId(), metadata, actor)));
    }

    /** Advances exactly one file. Callers must supply an immutable operation identity. */
    public RoleAssessmentResult ensureRoleAssessment(UUID projectId, UUID fileId,
            String operationKey, ProjectRequestMetadata metadata) {
        var actor = authActor();
        if (actor == null) {
            var deterministic = roleAssessments.assess(new RoleAssessmentCommand(
                    projectId, fileId, operationKey, metadata));
            return aiRoleAssessments.assessIfNeeded(deterministic, metadata);
        }
        return ensureRoleAssessment(projectId, fileId, operationKey, metadata, actor);
    }

    public RoleAssessmentResult ensureRoleAssessment(UUID projectId, UUID fileId,
            String operationKey, ProjectRequestMetadata metadata,
            com.fuquan.audit.security.domain.CurrentUser actor) {
        var deterministic = roleAssessments.assess(new RoleAssessmentCommand(
                projectId, fileId, operationKey, metadata), actor);
        return aiRoleAssessments.assessIfNeeded(deterministic, metadata, actor);
    }

    /** Idempotently creates the assessment that a human identity decision must reference. */
    public IdentityAssessmentResult ensureIdentityAssessment(UUID projectId, UUID fileId, UUID roleDecisionId,
            ProjectRequestMetadata metadata) {
        var actor = authActor();
        if (actor == null) throw new BusinessException(BusinessErrorCode.UNAUTHENTICATED);
        return ensureIdentityAssessment(projectId, fileId, roleDecisionId, metadata, actor);
    }

    public IdentityAssessmentResult ensureIdentityAssessment(UUID projectId, UUID fileId, UUID roleDecisionId,
            ProjectRequestMetadata metadata, com.fuquan.audit.security.domain.CurrentUser actor) {
        long projectVersion = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"))
                .versionNo();
        String key = IDENTITY_KEY_PREFIX + roleDecisionId + ":p" + projectVersion;
        return ensureIdentityAssessment(projectId, fileId, key, metadata, actor);
    }

    /** Advances exactly one file using the caller's durable operation identity. */
    public IdentityAssessmentResult ensureIdentityAssessment(UUID projectId, UUID fileId,
            String operationKey, ProjectRequestMetadata metadata) {
        var actor = authActor();
        if (actor == null) {
            return identityAssessments.assess(new IdentityAssessmentCommand(
                    projectId, fileId, operationKey, metadata));
        }
        return ensureIdentityAssessment(projectId, fileId, operationKey, metadata, actor);
    }

    public IdentityAssessmentResult ensureIdentityAssessment(UUID projectId, UUID fileId,
            String operationKey, ProjectRequestMetadata metadata,
            com.fuquan.audit.security.domain.CurrentUser actor) {
        return identityAssessments.assess(new IdentityAssessmentCommand(
                projectId, fileId, operationKey, metadata), actor);
    }

    private com.fuquan.audit.security.domain.CurrentUser authActor() {
        return auth == null ? null : auth.currentUser().orElse(null);
    }
}
