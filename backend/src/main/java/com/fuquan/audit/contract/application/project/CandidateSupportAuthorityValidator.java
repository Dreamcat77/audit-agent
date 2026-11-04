package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Bounded read-only support validation. Callers own primary validation, locks and transactions. */
@Component
public class CandidateSupportAuthorityValidator {
    private static final Set<String> AWARD_FACTS = Set.of(
            "AWARD_AMOUNT", "AWARD_SUPPLIER", "AWARD_SUPPLIER_CREDIT_CODE");
    private static final Comparator<ExtractionResult> FACT_ORDER = Comparator
            .comparing(ExtractionResult::factType)
            .thenComparing(ExtractionResult::groupKey, Comparator.nullsFirst(String::compareTo))
            .thenComparing(ExtractionResult::sourceLocator)
            .thenComparing(ExtractionResult::extractionKey)
            .thenComparing(fact -> fact.id().toString());

    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileRoleDecisionRepository roles;
    private final ProjectFileIdentityDecisionRepository identities;
    private final EffectiveDocumentRoleResolver effectiveRoles;
    private final EffectiveProjectIdentityResolver effectiveIdentities;
    private final ExtractionResultRepository extractions;
    private final ContractCandidateSupportAuthorityRepository supports;

    public CandidateSupportAuthorityValidator(ProjectRepository projects, ProjectFileRepository files,
            ProjectFileParseResultRepository parses, ProjectFileRoleDecisionRepository roles,
            ProjectFileIdentityDecisionRepository identities, EffectiveDocumentRoleResolver effectiveRoles,
            EffectiveProjectIdentityResolver effectiveIdentities, ExtractionResultRepository extractions,
            ContractCandidateSupportAuthorityRepository supports) {
        this.projects = projects;
        this.files = files;
        this.parses = parses;
        this.roles = roles;
        this.identities = identities;
        this.effectiveRoles = effectiveRoles;
        this.effectiveIdentities = effectiveIdentities;
        this.extractions = extractions;
        this.supports = supports;
    }

    /** Resolves a fresh complete set under the caller's already-held File -> Project -> Candidate locks. */
    public ResolvedCandidateSupportAuthoritySet resolveRequested(UUID projectId, List<UUID> supportFileIds) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(supportFileIds, "supportFileIds must not be null");
        Project project = projects.findById(projectId).orElseThrow(() ->
                new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        require(project.id().equals(projectId), "support project ownership mismatch");
        List<UUID> canonicalIds = supportFileIds.stream().map(Objects::requireNonNull)
                .distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        return resolvedSet(canonicalIds.stream().map(id -> resolveSource(project, id)).toList());
    }

    /** Validates every current row and the complete cohort; this is not successful-command replay. */
    public ResolvedCandidateSupportAuthoritySet validateCurrent(ContractCandidate candidate) {
        List<ContractCandidateSupportAuthority> rows = supports.findCurrentByProjectIdAndCandidateId(
                candidate.projectId(), candidate.id());
        require(rows != null, "support cohort unavailable");
        if (candidate.supportAuthorityVersionNo() == null) {
            require(candidate.supportAuthorityFingerprint() == null && rows.isEmpty(),
                    "support cohort metadata mismatch");
            return resolvedSet(List.of());
        }
        Set<UUID> seen = new HashSet<>();
        for (ContractCandidateSupportAuthority row : rows) {
            require(row != null && row.projectId().equals(candidate.projectId())
                    && row.candidateId().equals(candidate.id())
                    && row.authorityVersion() == candidate.supportAuthorityVersionNo()
                    && seen.add(row.sourceFileId()), "support cohort ownership or version mismatch");
        }
        ResolvedCandidateSupportAuthoritySet current = rows.isEmpty() ? resolvedSet(List.of())
                : resolveRequested(candidate.projectId(), rows.stream()
                        .map(ContractCandidateSupportAuthority::sourceFileId).toList());
        List<ContractCandidateSupportAuthority> orderedRows = rows.stream()
                .sorted(Comparator.comparing(row -> row.sourceFileId().toString())).toList();
        for (int index = 0; index < orderedRows.size(); index++) {
            require(matches(orderedRows.get(index), current.supports().get(index)),
                    "support source authority is stale");
        }
        require(current.supportAuthorityFingerprint().equals(candidate.supportAuthorityFingerprint()),
                "support cohort fingerprint mismatch");
        return current;
    }

    private ResolvedCandidateSupportAuthority resolveSource(Project project, UUID fileId) {
        ProjectFile file = files.findByProjectIdAndId(project.id(), fileId).orElseThrow(() ->
                new BusinessException(BusinessErrorCode.NOT_FOUND, "support file not found"));
        require(file.id().equals(fileId) && file.projectId().equals(project.id()),
                "support file ownership mismatch");
        require(file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE
                && file.parseStatus() == ProjectFileParseStatus.PARSED, "support file is not parsed and available");
        ProjectFileParseResult parse = parses.findLatestByFileId(fileId).orElseThrow(() ->
                conflict("support immutable parse is required"));
        require(parse.projectId().equals(project.id()) && parse.fileId().equals(fileId)
                && parse.fileSha256().equals(file.sha256())
                && parse.parseStatus() == ProjectFileParseStatus.PARSED
                && parse.canonicalText() != null && !parse.canonicalText().isBlank()
                && parse.canonicalText().equals(file.parsedText()), "support parse authority mismatch");

        var effectiveRole = effectiveRoles.resolve(file);
        require(effectiveRole.effectiveRole() == DocumentRole.AWARD_NOTICE
                && effectiveRole.source().isAuthoritativeDecision()
                && parse.parseResultId().equals(effectiveRole.parseResultId())
                && Objects.equals(parse.parseVersion(), effectiveRole.parseVersion()),
                "support role is not authoritative");
        var role = roles.findById(effectiveRole.roleDecisionId()).orElseThrow(() ->
                conflict("support role decision is missing"));
        require(role.decisionId().equals(effectiveRole.roleDecisionId())
                && role.projectId().equals(project.id()) && role.fileId().equals(fileId)
                && role.fileSha256().equals(file.sha256()) && role.parseResultId().equals(parse.parseResultId())
                && role.parseVersion() == parse.parseVersion() && role.selectedRole() == DocumentRole.AWARD_NOTICE
                && Objects.equals(role.versionNo(), effectiveRole.decisionVersionNo())
                && roles.findLatestByFileId(fileId).filter(role::equals).isPresent(),
                "support role decision is stale");

        var effectiveIdentity = effectiveIdentities.resolve(project, file);
        var currentSame = effectiveIdentity.currentAuthorizedSameDecision();
        require(project.metadataConfirmed() && effectiveIdentity.status() == EffectiveProjectIdentityStatus.SAME_PROJECT
                && effectiveIdentity.identityCurrent() && !effectiveIdentity.excludedFromProjectUse()
                && currentSame != null, "support project identity is not authoritative");
        var identity = identities.findById(currentSame.decisionId()).orElseThrow(() ->
                conflict("support identity decision is missing"));
        require(identity.equals(currentSame) && identity.decisionId().equals(effectiveIdentity.decisionId())
                && Objects.equals(identity.versionNo(), effectiveIdentity.decisionVersion())
                && identity.projectId().equals(project.id()) && identity.projectVersionNo() == project.versionNo()
                && identity.fileId().equals(fileId) && identity.fileSha256().equals(file.sha256())
                && identity.parseResultId().equals(parse.parseResultId()) && identity.parseVersion() == parse.parseVersion()
                && identity.resolution() == ProjectIdentityResolution.CONFIRM_SAME_PROJECT
                && identities.findLatestByFileId(fileId).filter(identity::equals).isPresent(),
                "support identity decision is stale");

        List<ExtractionResult> facts = extractions.findBySourceFileIdAndParseResultId(
                project.id(), fileId, parse.parseResultId());
        require(facts != null, "support facts unavailable");
        Set<UUID> factIds = new HashSet<>();
        Set<String> factKeys = new HashSet<>();
        for (ExtractionResult fact : facts) {
            require(fact != null && fact.projectId().equals(project.id()) && fact.sourceFileId().equals(fileId)
                    && parse.parseResultId().equals(fact.parseResultId()) && fact.candidateId() == null
                    && factIds.add(fact.id()) && factKeys.add(fact.extractionKey()), "support fact tuple mismatch");
        }
        List<ExtractionResult> eligible = facts.stream().filter(fact -> AWARD_FACTS.contains(fact.factType()))
                .sorted(FACT_ORDER).toList();
        MessageDigest digest = digest();
        add(digest, "contract-candidate-support-source-v1");
        add(digest, project.id(), fileId, file.sha256(), parse.parseResultId(), parse.parseVersion(),
                parse.canonicalTextSha256(), project.versionNo(), DocumentRole.AWARD_NOTICE, role.decisionId(),
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT, identity.decisionId(), eligible.size());
        for (ExtractionResult fact : eligible) {
            add(digest, fact.id(), fact.extractionKey(), fact.extractionVersion(), fact.factType(), fact.groupKey(),
                    fact.valueText(), fact.normalizedValue(),
                    fact.valueAmount() == null ? null : fact.valueAmount().stripTrailingZeros().toPlainString(),
                    fact.sourceText(), fact.pageNumber(), fact.paragraphIndex(), fact.sourceLocator());
        }
        return new ResolvedCandidateSupportAuthority(project.id(), fileId, file.sha256(), parse.parseResultId(),
                parse.parseVersion(), project.versionNo(), DocumentRole.AWARD_NOTICE, role.decisionId(),
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT, identity.decisionId(),
                eligible.stream().map(ExtractionResult::id).toList(), HexFormat.of().formatHex(digest.digest()));
    }

    private static boolean matches(ContractCandidateSupportAuthority row, ResolvedCandidateSupportAuthority value) {
        return row.projectId().equals(value.projectId()) && row.sourceFileId().equals(value.sourceFileId())
                && row.fileSha256().equals(value.fileSha256()) && row.parseResultId().equals(value.parseResultId())
                && row.parseVersion() == value.parseVersion() && row.projectVersionNo() == value.projectVersionNo()
                && row.effectiveRole() == value.effectiveRole() && row.roleDecisionId().equals(value.roleDecisionId())
                && row.identityResolution() == value.identityResolution()
                && row.identityDecisionId().equals(value.identityDecisionId())
                && row.sourceFactFingerprint().equals(value.sourceFactFingerprint());
    }

    private static ResolvedCandidateSupportAuthoritySet resolvedSet(List<ResolvedCandidateSupportAuthority> sources) {
        CandidateAuthorityMode mode = sources.isEmpty() ? CandidateAuthorityMode.PRIMARY_ONLY_COMPAT
                : CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY;
        MessageDigest digest = digest();
        add(digest, "contract-candidate-support-authority-v1", mode, sources.size());
        if (sources.isEmpty()) add(digest, "EMPTY");
        for (ResolvedCandidateSupportAuthority source : sources) {
            add(digest, source.projectId(), source.sourceFileId(), source.fileSha256(), source.parseResultId(),
                    source.parseVersion(), source.projectVersionNo(), source.effectiveRole(), source.roleDecisionId(),
                    source.identityResolution(), source.identityDecisionId(), source.sourceFactFingerprint());
        }
        return new ResolvedCandidateSupportAuthoritySet(mode, sources, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is required by Java 17", failure); }
    }

    /** Length-prefix every field; a null is distinct from an empty string and from textual delimiters. */
    private static void add(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw conflict(reason);
    }

    private static BusinessException conflict(String reason) {
        return new BusinessException(BusinessErrorCode.CONFLICT, reason);
    }
}
