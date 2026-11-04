package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Exact Candidate-owned provenance. Reading a stored association never reauthorizes it. */
@Service
public class CandidateSupportAuthorityReadService {
    private static final Set<String> AWARD_FACTS = Set.of(
            "AWARD_AMOUNT", "AWARD_SUPPLIER", "AWARD_SUPPLIER_CREDIT_CODE");
    private static final Comparator<ExtractionResult> EVIDENCE_ORDER = Comparator
            .comparing(ExtractionResult::factType)
            .thenComparing(ExtractionResult::groupKey, Comparator.nullsFirst(String::compareTo))
            .thenComparing(ExtractionResult::sourceLocator)
            .thenComparing(ExtractionResult::extractionKey)
            .thenComparing(fact -> fact.id().toString());
    private final ContractCandidateSupportAuthorityRepository supports;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ExtractionResultRepository extractions;
    private final CandidateSupportAuthorityValidator validator;

    public CandidateSupportAuthorityReadService(ContractCandidateSupportAuthorityRepository supports,
            ProjectFileRepository files, ProjectFileParseResultRepository parses,
            ExtractionResultRepository extractions, CandidateSupportAuthorityValidator validator) {
        this.supports = supports;
        this.files = files;
        this.parses = parses;
        this.extractions = extractions;
        this.validator = validator;
    }

    public SupportDetail read(ContractCandidate candidate) {
        List<ContractCandidateSupportAuthority> rows = supports.findCurrentByProjectIdAndCandidateId(
                candidate.projectId(), candidate.id());
        if (rows == null || rows.stream().anyMatch(row -> row == null
                || !candidate.projectId().equals(row.projectId()) || !candidate.id().equals(row.candidateId()))) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate support ownership is unproven");
        }
        if (rows.isEmpty()) {
            // Historical null metadata needs no backfill; an explicit clear must still prove its empty fingerprint.
            if (candidate.supportAuthorityVersionNo() != null) validator.validateCurrent(candidate);
            return SupportDetail.primaryOnly();
        }

        List<ResolvedCandidateSupportAuthority> currentSources = currentSources(candidate);
        List<SupportAuthorityProjection> projections = new ArrayList<>();
        List<ExtractionResult> evidence = new ArrayList<>();
        for (ContractCandidateSupportAuthority row : rows.stream()
                .sorted(Comparator.comparing(value -> value.sourceFileId().toString())).toList()) {
            ProjectFile file = files.findByProjectIdAndId(candidate.projectId(), row.sourceFileId())
                    .filter(value -> value.projectId().equals(candidate.projectId())
                            && value.id().equals(row.sourceFileId())).orElse(null);
            boolean exactParse = exactParseAvailable(row, file);
            List<ExtractionResult> exactEvidence = exactParse ? exactEvidence(row) : List.of();
            evidence.addAll(exactEvidence);
            List<ExtractionResult> awardEvidence = exactEvidence.stream()
                    .filter(fact -> AWARD_FACTS.contains(fact.factType())).toList();
            // Only the exact immutable fact IDs proven by validation may contribute after the evidence reload.
            boolean current = exactParse && java.util.Objects.equals(candidate.supportAuthorityVersionNo(), row.authorityVersion())
                    && currentSources.stream().anyMatch(source -> source.sourceFileId().equals(row.sourceFileId())
                            && source.sourceFactFingerprint().equals(row.sourceFactFingerprint())
                            && source.eligibleFactIds().equals(awardEvidence.stream().map(ExtractionResult::id).toList()));
            List<ExtractionResult> eligible = current ? awardEvidence : List.of();
            projections.add(new SupportAuthorityProjection(row.supportAuthorityId(), row.sourceFileId(),
                    row.fileSha256(), row.parseResultId(), row.parseVersion(), row.projectVersionNo(),
                    row.effectiveRole(), row.roleDecisionId(), row.identityResolution(), row.identityDecisionId(),
                    row.sourceFactFingerprint(), row.authorityVersion(), file == null ? null : file.originalFilename(),
                    current, current ? null : "SUPPORT_AUTHORITY_NOT_CURRENT", eligible));
        }
        if (projections.stream().anyMatch(row -> !row.current())) {
            // A second-read failure invalidates the complete accepted cohort, never just one conflict participant.
            projections = projections.stream().map(row -> new SupportAuthorityProjection(row.supportAuthorityId(),
                    row.sourceFileId(), row.fileSha256(), row.parseResultId(), row.parseVersion(), row.projectVersionNo(),
                    row.effectiveRole(), row.roleDecisionId(), row.identityResolution(), row.identityDecisionId(),
                    row.sourceFactFingerprint(), row.authorityVersion(), row.originalFilename(),
                    false, "SUPPORT_AUTHORITY_NOT_CURRENT", List.of())).toList();
        }
        return new SupportDetail(CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY, projections, evidence);
    }

    private List<ResolvedCandidateSupportAuthority> currentSources(ContractCandidate candidate) {
        try {
            return validator.validateCurrent(candidate).supports();
        } catch (BusinessException failure) {
            // A stale/missing source must remain readable for repair; unknown infrastructure failures still fail closed.
            if (failure.errorCode() == BusinessErrorCode.CONFLICT || failure.errorCode() == BusinessErrorCode.NOT_FOUND) {
                return List.of();
            }
            throw failure;
        }
    }

    private boolean exactParseAvailable(ContractCandidateSupportAuthority row, ProjectFile file) {
        return file != null && parses.findById(row.parseResultId()).filter(parse ->
                parse.parseResultId().equals(row.parseResultId()) && parse.projectId().equals(row.projectId())
                        && parse.fileId().equals(row.sourceFileId()) && parse.fileSha256().equals(row.fileSha256())
                        && parse.parseVersion() == row.parseVersion()).isPresent();
    }

    private List<ExtractionResult> exactEvidence(ContractCandidateSupportAuthority row) {
        return extractions.findBySourceFileIdAndParseResultId(row.projectId(), row.sourceFileId(), row.parseResultId())
                .stream().filter(fact -> row.projectId().equals(fact.projectId())
                        && row.sourceFileId().equals(fact.sourceFileId()) && row.parseResultId().equals(fact.parseResultId())
                        && fact.candidateId() == null)
                .sorted(EVIDENCE_ORDER).toList();
    }

    public record SupportDetail(CandidateAuthorityMode authorityMode,
            List<SupportAuthorityProjection> supportAuthorities, List<ExtractionResult> evidence) {
        public SupportDetail {
            supportAuthorities = List.copyOf(supportAuthorities);
            evidence = List.copyOf(evidence);
        }

        static SupportDetail primaryOnly() {
            return new SupportDetail(CandidateAuthorityMode.PRIMARY_ONLY_COMPAT, List.of(), List.of());
        }
    }

    public record SupportAuthorityProjection(UUID supportAuthorityId, UUID sourceFileId, String fileSha256,
            UUID parseResultId, int parseVersion, long projectVersionNo, DocumentRole effectiveRole,
            UUID roleDecisionId, ProjectIdentityResolution identityResolution, UUID identityDecisionId,
            String sourceFactFingerprint, long authorityVersion, String originalFilename,
            boolean current, String safeReasonCode, List<ExtractionResult> eligibleAwardFacts) {
        public SupportAuthorityProjection {
            eligibleAwardFacts = List.copyOf(eligibleAwardFacts);
        }
    }
}
