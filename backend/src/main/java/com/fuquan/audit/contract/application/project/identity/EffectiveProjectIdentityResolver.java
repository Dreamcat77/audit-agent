package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityDecisionStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Human-positive exact current tuple is the sole SAME_PROJECT authority. */
@Component
public class EffectiveProjectIdentityResolver {
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileIdentityAssessmentRepository assessments;
    private final ProjectFileIdentityDecisionRepository decisions;
    public EffectiveProjectIdentityResolver(ProjectFileParseResultRepository parses,
            ProjectFileIdentityAssessmentRepository assessments, ProjectFileIdentityDecisionRepository decisions) {
        this.parses = parses; this.assessments = assessments; this.decisions = decisions;
    }

    public EffectiveProjectIdentity resolve(Project project, ProjectFile file) {
        Optional<ProjectFileParseResult> parse = parses.findLatestByFileId(file.id())
                .filter(value -> value.projectId().equals(project.id()) && value.fileId().equals(file.id())
                        && value.fileSha256().equals(file.sha256())
                        && value.parseStatus() == ProjectFileParseStatus.PARSED
                        && file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE
                        && file.parseStatus() == ProjectFileParseStatus.PARSED);
        Optional<ProjectFileIdentityAssessment> latestAssessment = assessments.findLatestByFileId(file.id())
                .filter(value -> value.projectId().equals(project.id()) && value.fileId().equals(file.id()));
        Optional<ProjectFileIdentityDecision> latestDecision = decisions.findLatestByFileId(file.id());
        boolean assessmentCurrent = parse.isPresent() && latestAssessment.isPresent()
                && project.metadataConfirmed()
                && sameInput(project, file, parse.orElseThrow(), latestAssessment.orElseThrow());
        boolean decisionCurrent = parse.isPresent() && latestDecision.isPresent()
                && project.metadataConfirmed()
                && sameInput(project, file, parse.orElseThrow(), latestDecision.orElseThrow())
                && assessments.findById(latestDecision.orElseThrow().assessmentId())
                        .filter(value -> sameInput(project, file, parse.orElseThrow(), value)).isPresent();
        EffectiveProjectIdentityStatus status;
        ProjectFileIdentityDecision same = null;
        if (decisionCurrent) {
            var decision = latestDecision.orElseThrow();
            if (decision.resolution() == ProjectIdentityResolution.CONFIRM_SAME_PROJECT) {
                status = EffectiveProjectIdentityStatus.SAME_PROJECT;
                same = decision;
            } else status = EffectiveProjectIdentityStatus.NOT_SAME_PROJECT;
        } else if (assessmentCurrent) {
            status = switch (latestAssessment.orElseThrow().status()) {
                case LIKELY_SAME_PROJECT -> EffectiveProjectIdentityStatus.LIKELY_SAME_PROJECT;
                case CONFLICT -> EffectiveProjectIdentityStatus.CONFLICT;
                case INSUFFICIENT, SAME_PROJECT -> EffectiveProjectIdentityStatus.INSUFFICIENT;
            };
        } else status = EffectiveProjectIdentityStatus.INSUFFICIENT;
        EffectiveProjectIdentityRecoveryReason recoveryReason = null;
        if (!project.metadataConfirmed()) {
            recoveryReason = EffectiveProjectIdentityRecoveryReason.PROJECT_METADATA_CONFIRMATION_REQUIRED;
        } else if (!assessmentCurrent) {
            recoveryReason = latestAssessment.isPresent()
                    ? EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_STALE
                    : EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_PENDING;
        }
        return new EffectiveProjectIdentity(status,
                latestAssessment.map(ProjectFileIdentityAssessment::assessmentId).orElse(null),
                latestAssessment.map(ProjectFileIdentityAssessment::versionNo).orElse(null),
                latestAssessment.map(ProjectFileIdentityAssessment::status).orElse(null),
                decisionCurrent ? ProjectIdentityDecisionStatus.CONFIRMED
                        : assessmentCurrent ? ProjectIdentityDecisionStatus.NEEDS_CONFIRMATION : null,
                latestDecision.map(ProjectFileIdentityDecision::decisionId).orElse(null),
                latestDecision.map(ProjectFileIdentityDecision::versionNo).orElse(null),
                decisionCurrent, status == EffectiveProjectIdentityStatus.NOT_SAME_PROJECT, same,
                recoveryReason);
    }

    private static boolean sameInput(Project project, ProjectFile file, ProjectFileParseResult parse,
            ProjectFileIdentityAssessment value) {
        return value.projectId().equals(project.id()) && value.projectVersionNo() == project.versionNo()
                && value.fileId().equals(file.id()) && value.fileSha256().equals(file.sha256())
                && value.parseResultId().equals(parse.parseResultId())
                && value.parseVersion() == parse.parseVersion();
    }
    private static boolean sameInput(Project project, ProjectFile file, ProjectFileParseResult parse,
            ProjectFileIdentityDecision value) {
        return value.projectId().equals(project.id()) && value.projectVersionNo() == project.versionNo()
                && value.fileId().equals(file.id()) && value.fileSha256().equals(file.sha256())
                && value.parseResultId().equals(parse.parseResultId())
                && value.parseVersion() == parse.parseVersion();
    }
}
