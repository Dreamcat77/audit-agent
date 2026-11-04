package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class CandidateSourceAuthorityValidator {
    private static final String NOT_AUTHORITATIVE = "candidate source role not authoritative";

    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileRoleDecisionRepository decisions;
    private final EffectiveDocumentRoleResolver effectiveRoles;
    private final ProjectRepository projects;
    private final ProjectFileIdentityDecisionRepository identityDecisions;
    private final EffectiveProjectIdentityResolver effectiveIdentities;
    private final CandidateSupportAuthorityValidator supportAuthority;
    public CandidateSourceAuthorityValidator(ProjectFileRepository files,
            ProjectFileParseResultRepository parses, ProjectFileRoleDecisionRepository decisions,
            EffectiveDocumentRoleResolver effectiveRoles, ProjectRepository projects,
            ProjectFileIdentityDecisionRepository identityDecisions,
            EffectiveProjectIdentityResolver effectiveIdentities,
            CandidateSupportAuthorityValidator supportAuthority) {
        this.files = files;
        this.parses = parses;
        this.decisions = decisions;
        this.effectiveRoles = effectiveRoles;
        this.projects = projects;
        this.identityDecisions = identityDecisions;
        this.effectiveIdentities = effectiveIdentities;
        this.supportAuthority = supportAuthority;
    }

    /** Primary-only validation also permits repair/replacement of a stale support cohort. */
    public CandidateSourceAuthority snapshot(ContractCandidate candidate) {
        ProjectFile file = files.findByProjectIdAndId(candidate.projectId(), candidate.sourceContractFileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "candidate source file not found"));
        Project project = projects.findById(candidate.projectId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        boolean legacy = candidate.sourceAuthoritySource() == null
                || candidate.sourceAuthoritySource() == ContractCandidateAuthoritySource.LEGACY_COMPAT;
        ProjectFileParseResult parse = parses.findLatestByFileId(file.id()).orElseGet(
                CandidateSourceAuthorityValidator::missingParse);
        var effective = effectiveRoles.resolve(file);
        var identity = effectiveIdentities.resolve(project, file);

        boolean commonBinding = parse.projectId().equals(file.projectId())
                && parse.fileId().equals(file.id())
                && parse.fileSha256().equals(file.sha256())
                && Objects.equals(parse.canonicalText(), file.parsedText())
                && Objects.equals(effective.parseResultId(), parse.parseResultId());
        boolean rolePromotable;
        if (!commonBinding) {
            rolePromotable = false;
        } else if (legacy) {
            rolePromotable = effective.effectiveRole() == DocumentRole.CONTRACT_BODY
                    && (effective.source() == EffectiveDocumentRoleSource.LEGACY_EFFECTIVE
                    || effective.source().isAuthoritativeDecision());
        } else {
            ProjectFileRoleDecision sourceDecision = decisions.findById(candidate.sourceRoleDecisionId())
                    .orElse(null);
            boolean expectedAuto = candidate.sourceAuthoritySource() == ContractCandidateAuthoritySource.SYSTEM_AUTO;
            rolePromotable = (expectedAuto
                    ? sourceDecision != null && com.fuquan.audit.contract.application.project.AutomaticDecisionProvenance
                            .isAutomatic(sourceDecision.reason())
                    : candidate.sourceAuthoritySource() == ContractCandidateAuthoritySource.HUMAN_CONFIRMATION)
                    && candidate.sourceParseResultId().equals(parse.parseResultId())
                    && sourceDecision != null
                    && sourceDecision.projectId().equals(file.projectId())
                    && sourceDecision.fileId().equals(file.id())
                    && sourceDecision.fileSha256().equals(file.sha256())
                    && sourceDecision.parseResultId().equals(parse.parseResultId())
                    && sourceDecision.parseVersion() == parse.parseVersion()
                    && sourceDecision.selectedRole() == DocumentRole.CONTRACT_BODY
                    && effective.effectiveRole() == DocumentRole.CONTRACT_BODY
                    && effective.source().isAuthoritativeDecision()
                    && (expectedAuto == (effective.source() == EffectiveDocumentRoleSource.SYSTEM_AUTO_DECISION))
                    && candidate.sourceRoleDecisionId().equals(effective.roleDecisionId());
        }
        var currentSame = identity.currentAuthorizedSameDecision();
        var boundDecision = candidate.sourceIdentityDecisionId() == null ? null
                : identityDecisions.findById(candidate.sourceIdentityDecisionId()).orElse(null);
        boolean identityPromotable = project.metadataConfirmed()
                && identity.status() == EffectiveProjectIdentityStatus.SAME_PROJECT
                && currentSame != null && boundDecision != null
                && boundDecision.decisionId().equals(currentSame.decisionId())
                && boundDecision.projectId().equals(project.id())
                && boundDecision.projectVersionNo() == project.versionNo()
                && boundDecision.fileId().equals(file.id())
                && boundDecision.fileSha256().equals(file.sha256())
                && boundDecision.parseResultId().equals(parse.parseResultId())
                && boundDecision.parseVersion() == parse.parseVersion()
                && (candidate.sourceAuthoritySource() == ContractCandidateAuthoritySource.SYSTEM_AUTO)
                    == com.fuquan.audit.contract.application.project.AutomaticDecisionProvenance
                            .isAutomatic(boundDecision.reason());
        boolean promotable = rolePromotable && identityPromotable;
        String reason = promotable ? null : !rolePromotable ? NOT_AUTHORITATIVE
                : "candidate source project identity not authoritative";
        return new CandidateSourceAuthority(file.id(), file.sha256(), parse.parseResultId(),
                project.versionNo(), effective.effectiveRole(), effective.source(), effective.roleDecisionId(),
                identity.status(), currentSame == null ? null : currentSame.decisionId(),
                candidate.sourceFactFingerprint(), promotable, reason);
    }

    /** Fresh confirmation/promotion callers must hold the complete authority file set through commit. */
    public CandidateSourceAuthority snapshotWithCurrentSupport(ContractCandidate candidate) {
        CandidateSourceAuthority primary = snapshot(candidate);
        if (!primary.promotable()) return primary;
        try {
            supportAuthority.validateCurrent(candidate);
            return primary;
        } catch (BusinessException invalid) {
            if (invalid.errorCode() != BusinessErrorCode.CONFLICT
                    && invalid.errorCode() != BusinessErrorCode.NOT_FOUND) throw invalid;
            return new CandidateSourceAuthority(primary.sourceFileId(), primary.fileSha256(), primary.parseResultId(),
                    primary.projectVersionNo(), primary.effectiveRole(), primary.effectiveRoleSource(),
                    primary.currentRoleDecisionId(), primary.effectiveIdentityStatus(), primary.currentIdentityDecisionId(),
                    primary.sourceFactFingerprint(), false, "candidate support authority is not current");
        }
    }

    private static ProjectFileParseResult missingParse() {
        throw new BusinessException(BusinessErrorCode.CONFLICT, "immutable parse result is required");
    }
}
