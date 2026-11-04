package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateFactPolicy;
import com.fuquan.audit.contract.application.project.CandidateSupportAuthorityReadService.SupportAuthorityProjection;
import com.fuquan.audit.contract.application.project.CandidateSupportAuthorityReadService.SupportDetail;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectVersionConflictException;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.domain.Contract;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectApplicationService {
    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ContractCandidateRepository candidates;
    private final ExtractionResultRepository extractions;
    private final ProjectContractQueryPort associations;
    private final ContractRepository contracts;
    private final CandidateSupportAuthorityReadService supportAuthority;
    private final Clock clock;

    public ProjectApplicationService(ProjectRepository projects, ProjectFileRepository files,
                                     ContractCandidateRepository candidates,
                                     ExtractionResultRepository extractions, Clock clock) {
        this(projects, files, candidates, extractions, new EmptyProjectContractQueryPort(),
                id -> java.util.Optional.empty(), clock);
    }

    public ProjectApplicationService(ProjectRepository projects, ProjectFileRepository files,
                                     ContractCandidateRepository candidates,
                                     ExtractionResultRepository extractions,
                                     ProjectContractQueryPort associations, Clock clock) {
        this(projects, files, candidates, extractions, associations,
                id -> java.util.Optional.empty(), clock);
    }

    public ProjectApplicationService(ProjectRepository projects, ProjectFileRepository files,
                                     ContractCandidateRepository candidates,
                                     ExtractionResultRepository extractions,
                                     ProjectContractQueryPort associations,
                                     ContractRepository contracts, Clock clock) {
        this(projects, files, candidates, extractions, associations, contracts, null, clock);
    }

    @Autowired
    public ProjectApplicationService(ProjectRepository projects, ProjectFileRepository files,
                                     ContractCandidateRepository candidates,
                                     ExtractionResultRepository extractions,
                                     ProjectContractQueryPort associations, ContractRepository contracts,
                                     CandidateSupportAuthorityReadService supportAuthority, Clock clock) {
        this.projects = projects;
        this.files = files;
        this.candidates = candidates;
        this.extractions = extractions;
        this.associations = associations;
        this.contracts = contracts;
        this.supportAuthority = supportAuthority;
        this.clock = clock;
    }

    @Transactional
    public Project createBlank(String actor) {
        return createBlank(UUID.randomUUID(), actor);
    }

    @Transactional
    public Project createBlank(UUID id, String actor) {
        Project project = Project.blank(id, actor, clock.instant());
        projects.insert(project);
        return project;
    }

    @Transactional(readOnly = true)
    public ProjectPage list(int page, int size, String keyword) {
        requirePageBounds(page, size);
        return new ProjectPage(projects.findPage((page - 1) * size, size, keyword), page, size,
                projects.count(keyword));
    }

    @Transactional(readOnly = true)
    public IntakeDraftPage listIntakeDrafts(int page, int size) {
        requirePageBounds(page, size);
        List<IntakeDraft> items = projects.findIntakeDraftPage((page - 1) * size, size).stream()
                .map(this::intakeDraft)
                .toList();
        return new IntakeDraftPage(items, page, size, projects.countIntakeDrafts());
    }

    @Transactional(readOnly = true)
    public PreparationDraftPage listPreparationDrafts(int page, int size) {
        requirePageBounds(page, size);
        List<PreparationDraft> items = projects.findPreparationDraftPage((page - 1) * size, size).stream()
                .map(draft -> new PreparationDraft(
                        draft.projectId(), draft.createdAt(), draft.uploadedFileCount()))
                .toList();
        return new PreparationDraftPage(items, page, size, projects.countPreparationDrafts());
    }

    @Transactional(readOnly = true)
    public ProjectDetail detail(UUID projectId) {
        Project project = get(projectId);
        List<ProjectFile> projectFiles = files.findByProjectId(projectId);
        List<ContractCandidate> projectCandidates = candidates.findByProjectId(projectId);
        List<ExtractionResult> projectExtractions = extractions.findByProjectId(projectId);
        List<AssociatedContractSummary> projectContracts = associations.findByProjectId(projectId).stream()
                .map(this::associatedContractSummary)
                .toList();
        long failed = projectFiles.stream().filter(file -> file.ingestionStatus() == ProjectFileIngestionStatus.UPLOAD_FAILED
                || file.parseStatus() == ProjectFileParseStatus.FAILED).count();
        long unsupported = projectFiles.stream().filter(file -> file.parseStatus() == ProjectFileParseStatus.UNSUPPORTED).count();
        return new ProjectDetail(project, intakeStatus(projectFiles, projectCandidates), projectFiles, projectCandidates,
                projectExtractions, projectContracts, failed, unsupported);
    }

    private AssociatedContractSummary associatedContractSummary(ProjectContract association) {
        Contract contract = contracts.findById(association.contractId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "associated formal contract is missing"));
        return new AssociatedContractSummary(association.projectId(), association.candidateId(), contract.id(),
                contract.contractNo(), contract.contractName(), contract.contractType(), contract.partyA(),
                contract.partyB(), contract.partyBCreditCode(), contract.contractAmount().amount(),
                contract.amountInWords(), contract.status(), contract.versionNo(),
                association.createdBy(), association.createdAt());
    }

    @Transactional
    public Project confirmMetadata(UUID projectId, long expectedVersion, String name,
                                   String projectNo, String category) {
        Project current = get(projectId);
        Project updated = current.confirmMetadata(expectedVersion, name, projectNo, category, clock.instant());
        if (!projects.compareAndSet(current, updated)) {
            throw new ProjectVersionConflictException();
        }
        return updated;
    }

    public Project get(UUID projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
    }

    private IntakeDraft intakeDraft(Project project) {
        if (project.metadataConfirmed()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "confirmed project cannot be an intake draft");
        }
        List<ProjectFile> projectFiles = files.findByProjectId(project.id());
        ProjectFile packageFile = projectFiles.stream()
                .filter(file -> file.category() == ProjectFileCategory.PACKAGE)
                .findFirst()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "intake draft package is missing"));
        List<ContractCandidate> projectCandidates = candidates.findByProjectId(project.id());
        List<ExtractionResult> projectExtractions = extractions.findByProjectId(project.id());
        long parsed = projectFiles.stream().filter(file -> file.category() != ProjectFileCategory.PACKAGE
                && file.parseStatus() == ProjectFileParseStatus.PARSED).count();
        long ocr = projectFiles.stream().filter(file -> file.category() != ProjectFileCategory.PACKAGE
                && file.parseStatus() == ProjectFileParseStatus.PARSED
                && file.parseProvenance().method() == ProjectFileParseMethod.OCR).count();
        long failed = projectFiles.stream().filter(file -> file.ingestionStatus() == ProjectFileIngestionStatus.UPLOAD_FAILED
                || file.parseStatus() == ProjectFileParseStatus.FAILED).count();
        return new IntakeDraft(project.id(), project.versionNo(), packageFile.originalFilename(),
                intakeStatus(projectFiles, projectCandidates), parsed, ocr, failed,
                uniqueFact(projectExtractions, "PROJECT_NAME"), uniqueFact(projectExtractions, "PROJECT_NO"),
                project.createdAt());
    }

    private static String uniqueFact(List<ExtractionResult> values, String factType) {
        List<String> candidates = values.stream()
                .filter(value -> factType.equals(value.factType()))
                .map(value -> value.normalizedValue() == null ? value.valueText() : value.normalizedValue())
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static String intakeStatus(List<ProjectFile> projectFiles, List<ContractCandidate> projectCandidates) {
        long available = projectFiles.stream()
                .filter(file -> file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE).count();
        long parsed = projectFiles.stream().filter(file -> file.parseStatus() == ProjectFileParseStatus.PARSED).count();
        return available == 0 ? "AWAITING_UPLOAD"
                : !projectCandidates.isEmpty() ? "CANDIDATES_AVAILABLE"
                : parsed > 0 ? "PARSED" : "UPLOADED";
    }

    private static void requirePageBounds(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid page bounds");
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectFile> files(UUID projectId) {
        get(projectId);
        return files.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public ProjectFile file(UUID projectId, UUID fileId) {
        get(projectId);
        return files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
    }

    @Transactional(readOnly = true)
    public List<ContractCandidate> candidates(UUID projectId) {
        get(projectId);
        return candidates.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public CandidateDetail candidate(UUID projectId, UUID candidateId) {
        get(projectId);
        ContractCandidate candidate = candidates.findByProjectIdAndId(projectId, candidateId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "contract candidate not found"));
        ProjectFile source = files.findByProjectIdAndId(projectId, candidate.sourceContractFileId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT, "candidate source file is missing"));
        List<ExtractionResult> primaryEvidence = extractions.findByCandidateId(projectId, candidateId);
        if (supportAuthority == null && candidate.supportAuthorityVersionNo() != null) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate support reader is required");
        }
        SupportDetail support = supportAuthority == null ? SupportDetail.primaryOnly() : supportAuthority.read(candidate);
        List<ExtractionResult> evidence = primaryEvidence;
        List<ExtractionResult> authorizedEvidence = primaryEvidence;
        if (support.authorityMode() == CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY) {
            primaryEvidence = primaryEvidence.stream().filter(fact -> projectId.equals(fact.projectId())
                    && candidateId.equals(fact.candidateId()) && source.id().equals(fact.sourceFileId())
                    && java.util.Objects.equals(candidate.sourceParseResultId(), fact.parseResultId())).toList();
            evidence = java.util.stream.Stream.concat(primaryEvidence.stream(), support.evidence().stream()).toList();
            authorizedEvidence = new ContractCandidateFactPolicy().authorizedFacts(source, primaryEvidence,
                    support.supportAuthorities().stream().filter(SupportAuthorityProjection::current)
                            .map(row -> new ContractCandidateFactPolicy.FactSource(row.effectiveRole(),
                                    row.sourceFileId(), row.parseVersion(), row.eligibleAwardFacts())).toList(),
                    support.authorityMode());
        }
        ContractCandidateAuthoritySource authority = candidate.sourceAuthoritySource() == null
                ? ContractCandidateAuthoritySource.LEGACY_COMPAT : candidate.sourceAuthoritySource();
        return new CandidateDetail(candidate, source, evidence, fieldStates(authorizedEvidence), lineItemFieldStates(primaryEvidence),
                candidate.sourceParseResultId(), candidate.sourceRoleDecisionId(),
                candidate.sourceIdentityDecisionId(), authority,
                candidate.sourceFactFingerprint(), support.authorityMode(), candidate.supportAuthorityVersionNo(),
                candidate.supportAuthorityFingerprint(), support.supportAuthorities());
    }

    private static Map<String, String> fieldStates(List<ExtractionResult> evidence) {
        Map<String, String> states = new LinkedHashMap<>();
        for (String type : List.of("PROJECT_NAME", "PROJECT_NO", "CONTRACT_NO", "CONTRACT_NAME", "CONTRACT_TYPE",
                "PARTY_A", "PARTY_B", "SUPPLIER_NAME", "CREDIT_CODE", "AMOUNT_NUMERIC",
                "AMOUNT_UPPERCASE", "AWARD_AMOUNT", "AWARD_SUPPLIER", "AWARD_SUPPLIER_CREDIT_CODE")) {
            states.put(type, fieldState(evidence.stream().filter(value -> type.equals(value.factType())).toList()));
        }
        return java.util.Collections.unmodifiableMap(states);
    }

    private static Map<String, Map<String, String>> lineItemFieldStates(List<ExtractionResult> evidence) {
        Map<String, Map<String, String>> states = new LinkedHashMap<>();
        evidence.stream().map(ExtractionResult::groupKey)
                .filter(value -> value != null && !value.isBlank()).distinct()
                .forEach(groupKey -> {
                    Map<String, String> groupStates = new LinkedHashMap<>();
                    for (String type : List.of("LINE_ITEM_KEY", "LINE_ITEM_NAME", "LINE_ITEM_QUANTITY",
                            "LINE_ITEM_UNIT_PRICE", "LINE_ITEM_DECLARED_AMOUNT")) {
                        groupStates.put(type, fieldState(evidence.stream()
                                .filter(value -> groupKey.equals(value.groupKey()) && type.equals(value.factType()))
                                .toList()));
                    }
                    states.put(groupKey, java.util.Collections.unmodifiableMap(groupStates));
                });
        return java.util.Collections.unmodifiableMap(states);
    }

    private static String fieldState(List<ExtractionResult> facts) {
        List<String> values = facts.stream()
                .map(value -> value.valueAmount() == null ? value.normalizedValue()
                        : value.valueAmount().stripTrailingZeros().toPlainString())
                .filter(java.util.Objects::nonNull).distinct().toList();
        return values.isEmpty() ? "MISSING" : values.size() == 1 ? "CONFIRMED" : "CONFLICT";
    }

    public record ProjectPage(List<Project> items, int page, int size, long total) {
        public ProjectPage {
            items = List.copyOf(items);
        }
    }

    public record IntakeDraftPage(List<IntakeDraft> items, int page, int size, long total) {
        public IntakeDraftPage {
            items = List.copyOf(items);
        }
    }

    public record IntakeDraft(UUID projectId, long versionNo, String packageFilename, String intakeStatus,
                              long parsedFileCount, long ocrFileCount, long failedFileCount,
                              String candidateProjectName, String candidateProjectNo, Instant createdAt) {
    }

    public record PreparationDraftPage(List<PreparationDraft> items, int page, int size, long total) {
        public PreparationDraftPage {
            items = List.copyOf(items);
        }
    }

    public record PreparationDraft(UUID projectId, Instant createdAt, long uploadedFileCount) {
    }

    public record ProjectDetail(Project project, String intakeStatus, List<ProjectFile> files,
                                List<ContractCandidate> candidates, List<ExtractionResult> extractions,
                                List<AssociatedContractSummary> associatedContracts,
                                long failedFileCount, long unsupportedFileCount) {
        public ProjectDetail {
            files = List.copyOf(files);
            candidates = List.copyOf(candidates);
            extractions = List.copyOf(extractions);
            associatedContracts = List.copyOf(associatedContracts);
        }
    }

    public record AssociatedContractSummary(
            UUID projectId,
            UUID candidateId,
            UUID contractId,
            String contractNo,
            String contractName,
            String contractType,
            String partyA,
            String partyB,
            String partyBCreditCode,
            BigDecimal contractAmount,
            String amountInWords,
            String status,
            long versionNo,
            String createdBy,
            Instant createdAt) {
    }

    public record CandidateDetail(ContractCandidate candidate, ProjectFile sourceFile,
                                  List<ExtractionResult> evidence, Map<String, String> fieldStates,
                                  Map<String, Map<String, String>> lineItemFieldStates,
                                  UUID sourceParseResultId, UUID sourceRoleDecisionId,
                                  UUID sourceIdentityDecisionId,
                                  ContractCandidateAuthoritySource candidateAuthoritySource,
                                  String sourceFactFingerprint, CandidateAuthorityMode authorityMode,
                                  Long supportAuthorityVersionNo, String supportAuthorityFingerprint,
                                  List<SupportAuthorityProjection> supportAuthorities) {
        public CandidateDetail {
            evidence = List.copyOf(evidence);
            fieldStates = Map.copyOf(fieldStates);
            lineItemFieldStates = Map.copyOf(lineItemFieldStates);
            supportAuthorities = List.copyOf(supportAuthorities);
        }
    }

    private static final class EmptyProjectContractQueryPort implements ProjectContractQueryPort {
        @Override public List<ProjectContract> findByProjectId(UUID projectId) { return List.of(); }
        @Override public java.util.Optional<ProjectContract> findByContractId(UUID contractId) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<ProjectContract> findByCandidateId(UUID candidateId) { return java.util.Optional.empty(); }
    }
}
