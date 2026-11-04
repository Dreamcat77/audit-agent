package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleAssessmentRepository;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessmentSource;
import com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectFileRoleProjectionService {
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileRoleAssessmentRepository assessments;
    private final EffectiveDocumentRoleResolver effectiveRoles;

    public ProjectFileRoleProjectionService(ProjectFileParseResultRepository parses,
            ProjectFileRoleAssessmentRepository assessments, EffectiveDocumentRoleResolver effectiveRoles) {
        this.parses = parses;
        this.assessments = assessments;
        this.effectiveRoles = effectiveRoles;
    }

    @Transactional(readOnly = true)
    public ProjectFileRoleProjection project(ProjectFile file) {
        Optional<ProjectFileParseResult> parse = parses.findLatestByFileId(file.id());
        Optional<ProjectFileRoleAssessment> latest = parse.flatMap(current ->
                assessments.findLatestByFileId(file.id()).filter(value -> currentBinding(value, file, current)));
        ProjectFileRoleAssessment ai = latest
                .filter(value -> value.assessmentSource() == ProjectFileRoleAssessmentSource.MACHINE_AI)
                .orElse(null);
        Optional<ProjectFileRoleAssessment> assessment = latest.flatMap(value -> {
            if (value.assessmentSource() == ProjectFileRoleAssessmentSource.MACHINE_AI
                    && value.executionStatus() != RoleAssessmentExecutionStatus.COMPLETED
                    && value.precedingDeterministicAssessmentId() != null) {
                return assessments.findById(value.precedingDeterministicAssessmentId())
                        .filter(prior -> parse.filter(current -> currentBinding(prior, file, current)).isPresent());
            }
            return Optional.of(value);
        });
        EffectiveDocumentRoleResult effective = effectiveRoles.resolve(file);
        ProjectFileRoleAssessment role = assessment.orElse(null);
        String decisionStatus = effective.source().isAuthoritativeDecision()
                ? RoleDecisionStatus.CONFIRMED.name()
                : role == null ? null
                : role.executionStatus() == RoleAssessmentExecutionStatus.COMPLETED
                ? RoleDecisionStatus.NEEDS_CONFIRMATION.name() : null;
        ProjectFileParseResult current = parse.orElse(null);
        boolean completed = role != null && role.executionStatus() == RoleAssessmentExecutionStatus.COMPLETED;
        var model = role == null ? null : role.modelProvenance();
        return new ProjectFileRoleProjection(
                current == null ? null : current.parseResultId(),
                current == null ? null : current.parseVersion(),
                current == null ? null : current.parseOrigin().name(),
                role == null ? null : role.assessmentId(),
                completed ? role.proposedRole() : null,
                completed ? role.confidence() : null,
                role == null ? null : role.executionStatus().name(),
                role == null ? null : role.assessmentSource().name(),
                role == null ? null : role.safeReasonCode(),
                model == null ? null : model.provider(), model == null ? null : model.modelName(),
                model == null ? null : model.modelRevision(), model == null ? null : model.promptVersion(),
                model == null ? null : model.schemaVersion(), ai == null ? null : ai.assessmentId(),
                ai == null ? null : ai.executionStatus().name(), ai == null ? null : ai.safeReasonCode(),
                decisionStatus,
                effective.effectiveRole(), effective.source(), effective.roleDecisionId(),
                effective.decisionVersionNo(), role != null && role.conflict());
    }

    private static boolean currentBinding(ProjectFileRoleAssessment assessment, ProjectFile file,
                                          ProjectFileParseResult parse) {
        return assessment.projectId().equals(file.projectId()) && assessment.fileId().equals(file.id())
                && assessment.fileSha256().equals(file.sha256())
                && assessment.parseResultId().equals(parse.parseResultId())
                && assessment.parseVersion() == parse.parseVersion();
    }
}
