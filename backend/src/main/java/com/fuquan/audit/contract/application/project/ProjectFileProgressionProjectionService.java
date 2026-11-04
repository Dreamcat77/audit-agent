package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityRecoveryReason;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRepository;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjection;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityDecisionStatus;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-only server authority for UI workflow stage and available commands. */
@Service
public class ProjectFileProgressionProjectionService {
    private final ContractCandidateRepository candidates;
    private final ContractCandidateConfirmationRepository confirmations;
    private final ProjectContractQueryPort contracts;
    private final ProjectFileRecoveryInputFingerprint recoveryFingerprints;
    private final ContractFactExtractionRepository factExtractions;
    private final ContractFactCandidatePort factCandidates;
    private final boolean factExtractionEnabled;
    private final boolean automaticIntakeEnabled;

    @Autowired
    public ProjectFileProgressionProjectionService(ContractCandidateRepository candidates,
            ContractCandidateConfirmationRepository confirmations, ProjectContractQueryPort contracts,
            ProjectFileRecoveryInputFingerprint recoveryFingerprints,
            ContractFactExtractionRepository factExtractions,
            ContractFactCandidatePort factCandidates,
            @Value("${AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED:false}") boolean factExtractionEnabled,
            @Value("${audit.contract.auto-intake.enabled:true}") boolean automaticIntakeEnabled) {
        this.candidates = candidates;
        this.confirmations = confirmations;
        this.contracts = contracts;
        this.recoveryFingerprints = recoveryFingerprints;
        this.factExtractions = factExtractions;
        this.factCandidates = factCandidates;
        this.factExtractionEnabled = factExtractionEnabled;
        this.automaticIntakeEnabled = automaticIntakeEnabled;
    }

    ProjectFileProgressionProjectionService(ContractCandidateRepository candidates,
            ContractCandidateConfirmationRepository confirmations, ProjectContractQueryPort contracts,
            ProjectFileRecoveryInputFingerprint recoveryFingerprints,
            ContractFactExtractionRepository factExtractions, ContractFactCandidatePort factCandidates) {
        this(candidates, confirmations, contracts, recoveryFingerprints, factExtractions,
                factCandidates, true, true);
    }

    /** Compatibility seam for focused tests that never enter a machine recovery state. */
    ProjectFileProgressionProjectionService(ContractCandidateRepository candidates,
            ContractCandidateConfirmationRepository confirmations, ProjectContractQueryPort contracts) {
        this(candidates, confirmations, contracts, null, null, null, false, false);
    }

    public ProjectFileProgressionProjection project(ProjectFile file, ProjectFileRoleProjection role,
            EffectiveProjectIdentity identity) {
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE) {
            return state("UPLOADED", "FILE_NOT_AVAILABLE");
        }
        if (file.parseStatus() == ProjectFileParseStatus.FAILED) {
            return state("PARSE_FAILED", "PARSE_FAILED");
        }
        if (file.parseStatus() != ProjectFileParseStatus.PARSED) {
            return state("UPLOADED", "PARSE_PENDING");
        }
        if (role.roleAssessmentId() == null) {
            return recovery("PARSED", "ROLE_ASSESSMENT_PENDING", "RESUME_ROLE_ASSESSMENT",
                    file, role, identity);
        }
        if (!"CONFIRMED".equals(role.roleDecisionStatus()) && !automaticIntakeEnabled) {
            return action("NEEDS_ROLE_CONFIRMATION", "NEEDS_ROLE_CONFIRMATION", "CONFIRM_ROLE");
        }
        if (role.effectiveRole() == DocumentRole.OTHER) {
            return state("OTHER_MATERIAL", "NOT_INCLUDED_IN_CONTRACT_ANALYSIS");
        }
        if (identity.recoveryReason()
                == EffectiveProjectIdentityRecoveryReason.PROJECT_METADATA_CONFIRMATION_REQUIRED) {
            return state("ROLE_CONFIRMED", "PROJECT_METADATA_CONFIRMATION_REQUIRED");
        }
        if (identity.recoveryReason()
                == EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_STALE) {
            return recovery("ROLE_CONFIRMED", "IDENTITY_ASSESSMENT_STALE",
                    "RESUME_IDENTITY_ASSESSMENT", file, role, identity);
        }
        if (identity.recoveryReason()
                == EffectiveProjectIdentityRecoveryReason.IDENTITY_ASSESSMENT_PENDING
                || identity.assessmentId() == null) {
            return recovery("ROLE_CONFIRMED", "IDENTITY_ASSESSMENT_PENDING",
                    "RESUME_IDENTITY_ASSESSMENT", file, role, identity);
        }
        if (identity.decisionStatus() == ProjectIdentityDecisionStatus.NEEDS_CONFIRMATION
                && !automaticIntakeEnabled) {
            return action("NEEDS_IDENTITY_CONFIRMATION", "NEEDS_IDENTITY_CONFIRMATION",
                    "CONFIRM_PROJECT_IDENTITY");
        }
        if (identity.status() != EffectiveProjectIdentityStatus.SAME_PROJECT) {
            return state("OTHER_MATERIAL", "NOT_INCLUDED_IN_PROJECT_ANALYSIS");
        }
        boolean factRole = role.effectiveRole() == DocumentRole.CONTRACT_BODY
                || role.effectiveRole() == DocumentRole.AWARD_NOTICE;
        var currentFactAttempt = factExtractionEnabled && factRole
                && role.parseResultId() != null && role.roleDecisionId() != null
                && identity.decisionId() != null
                ? factExtractions.findLatestForAuthority(file.projectId(), file.id(),
                        role.parseResultId(), role.roleDecisionId(), identity.decisionId(),
                        role.effectiveRole(), factCandidates.configuredProvenance())
                : java.util.Optional.<com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt>empty();
        boolean factAttemptPending = currentFactAttempt.isEmpty()
                || currentFactAttempt.get().status() == com.fuquan.audit.contract.application.project.factai
                        .ContractFactExtractionAttempt.Status.IN_PROGRESS;
        boolean factAttemptHasNoUsableFacts = currentFactAttempt.filter(attempt ->
                attempt.status() != com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt.Status.IN_PROGRESS
                        && (attempt.status() != com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt.Status.SUCCEEDED
                                || attempt.acceptedCount() == 0)).isPresent();
        if (factExtractionEnabled && factRole && factAttemptPending) {
            return recovery("IDENTITY_CONFIRMED", "FACT_EXTRACTION_PENDING",
                    "RESUME_FACT_EXTRACTION", file, role, identity);
        }
        if (role.effectiveRole() != DocumentRole.CONTRACT_BODY) {
            return state("MATERIAL_CLASSIFIED", "NO_CONTRACT_CANDIDATE_REQUIRED");
        }

        var candidate = candidates.findBySourceContractFileId(file.projectId(), file.id()).orElse(null);
        if (candidate == null) {
            if (factAttemptHasNoUsableFacts) {
                return new ProjectFileProgressionProjection("CONTRACT_INFORMATION_INSUFFICIENT",
                        "FACTS_NOT_VERIFIED", List.of(), null, null);
            }
            return recovery("IDENTITY_CONFIRMED", "CANDIDATE_MATERIALIZATION_PENDING",
                    "RESUME_CANDIDATE_MATERIALIZATION", file, role, identity);
        }
        if (candidate.promotionState() == CandidatePromotionState.PROMOTED) {
            var association = contracts.findByCandidateId(candidate.id()).orElse(null);
            if (association == null
                    || !association.projectId().equals(file.projectId())
                    || !association.projectId().equals(candidate.projectId())
                    || !association.candidateId().equals(candidate.id())
                    || !association.contractId().equals(candidate.promotedContractId())) {
                return new ProjectFileProgressionProjection("IDENTITY_CONFIRMED",
                        "PROJECT_CONTRACT_ASSOCIATION_UNPROVEN", List.of(), candidate.id(), null);
            }
            return new ProjectFileProgressionProjection("REVIEW_READY", null,
                    List.of("START_REVIEW"), candidate.id(), association.contractId());
        }
        var latestConfirmation = confirmations.findLatestByProjectAndCandidate(
                file.projectId(), candidate.id()).orElse(null);
        if (latestConfirmation != null
                && latestConfirmation.resultCandidateVersionNo() == candidate.versionNo()) {
            return new ProjectFileProgressionProjection("CANDIDATE_READY", null,
                    List.of("PROMOTE_CONTRACT"), candidate.id(), null);
        }
        if (AutomaticProjectFileClassificationService.autoPayload(candidate) == null) {
            return new ProjectFileProgressionProjection("CONTRACT_INFORMATION_INSUFFICIENT",
                    "CONTRACT_EVIDENCE_INCOMPLETE", List.of(),
                    candidate.id(), null);
        }
        return new ProjectFileProgressionProjection("AUTOMATIC_PROCESSING",
                "AUTOMATIC_RESULT_NOT_READY", List.of(), candidate.id(), null);
    }

    private static ProjectFileProgressionProjection state(String stage, String blocker) {
        return new ProjectFileProgressionProjection(stage, blocker, List.of(), null, null);
    }

    private static ProjectFileProgressionProjection action(String stage, String blocker, String action) {
        return new ProjectFileProgressionProjection(stage, blocker, List.of(action), null, null);
    }

    private ProjectFileProgressionProjection recovery(String stage, String blocker, String action,
            ProjectFile file, ProjectFileRoleProjection role, EffectiveProjectIdentity identity) {
        if (recoveryFingerprints == null) {
            throw new IllegalStateException("recovery input fingerprint service is required");
        }
        String fingerprint = recoveryFingerprints.createIfCurrentParseAvailable(
                file, role, identity, blocker, action).orElse(null);
        if (fingerprint == null) {
            return new ProjectFileProgressionProjection(stage, blocker, List.of(), null, null, null);
        }
        return new ProjectFileProgressionProjection(stage, blocker, List.of(action), null, null,
                fingerprint);
    }
}
