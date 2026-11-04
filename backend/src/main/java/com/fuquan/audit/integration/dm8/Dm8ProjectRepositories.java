package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateDerivedFacts;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateLineItem;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFactIdentity;
import com.fuquan.audit.contract.domain.project.ProjectFactIdentityConflictException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.time.Instant;

@Configuration(proxyBeanMethods = false)
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProjectRepositories {
    private static final Logger LOGGER = LoggerFactory.getLogger(Dm8ProjectRepositories.class);
    private final ProjectMapper projectMapper;
    private final ProjectFileMapper fileMapper;
    private final ProjectExtractionResultMapper extractionMapper;
    private final ContractCandidateMapper candidateMapper;

    public Dm8ProjectRepositories(ProjectMapper projectMapper, ProjectFileMapper fileMapper,
                                  ProjectExtractionResultMapper extractionMapper,
                                  ContractCandidateMapper candidateMapper) {
        this.projectMapper = projectMapper;
        this.fileMapper = fileMapper;
        this.extractionMapper = extractionMapper;
        this.candidateMapper = candidateMapper;
    }

    @Bean
    public ProjectRepository projectRepository() {
        return new ProjectRepository() {
            @Override public void insert(Project project) { insertProject(project); }
            @Override public Optional<Project> findById(UUID id) { return findProject(id); }
            @Override public Optional<Project> findByIdForUpdate(UUID id) {
                return Optional.ofNullable(projectMapper.findByIdForUpdate(id.toString()))
                        .map(Dm8ProjectRepositories.this::domain);
            }
            @Override public List<Project> findPage(int offset, int limit, String keyword) { return findProjects(offset, limit, keyword); }
            @Override public long count(String keyword) { return countProjects(keyword); }
            @Override public List<Project> findIntakeDraftPage(int offset, int limit) { return findIntakeDrafts(offset, limit); }
            @Override public long countIntakeDrafts() { return countIntakeDraftProjects(); }
            @Override public List<ProjectRepository.PreparationDraft> findPreparationDraftPage(int offset, int limit) {
                return findPreparationDrafts(offset, limit);
            }
            @Override public long countPreparationDrafts() { return projectMapper.countPreparationDrafts(); }
            @Override public boolean compareAndSet(Project expected, Project updated) { return replaceProject(expected, updated); }
        };
    }

    @Bean
    public ProjectFileRepository projectFileRepository() {
        return new ProjectFileRepository() {
            @Override public void insert(ProjectFile file) { insertFile(file); }
            @Override public void update(ProjectFile file) { updateFile(file); }
            @Override public Optional<ProjectFile> findByProjectIdAndId(UUID projectId, UUID id) { return findFile(projectId, id); }
            @Override public Optional<ProjectFile> findByProjectIdAndIdForUpdate(UUID projectId, UUID id) {
                return Optional.ofNullable(fileMapper.findByProjectIdAndIdForUpdate(projectId.toString(), id.toString()))
                        .map(Dm8ProjectRepositories.this::domain);
            }
            @Override public <T> T withFileAuthorityFence(UUID projectId, UUID id,
                    java.util.function.Supplier<T> command) {
                // The coordinator selects this row FOR UPDATE inside its write transaction.
                return command.get();
            }
            @Override public Optional<ProjectFile> findByProjectIdAndParentArchiveFileIdAndRelativePath(UUID projectId, UUID parentId, String path) { return findChild(projectId, parentId, path); }
            @Override public List<ProjectFile> findByProjectId(UUID projectId) { return findFiles(projectId); }
        };
    }

    @Bean
    public ExtractionResultRepository extractionResultRepository() {
        return new ExtractionResultRepository() {
            @Override public ExtractionResult insertIfAbsent(ExtractionResult result) { return insertExtraction(result); }
            @Override public Optional<ExtractionResult> findByExtractionKey(String key) { return findExtraction(key); }
            @Override public List<ExtractionResult> findBySourceFileId(UUID projectId, UUID sourceId) { return findExtractionsBySource(projectId, sourceId); }
            @Override public List<ExtractionResult> findBySourceFileIdAndParseResultId(UUID projectId, UUID sourceId, UUID parseResultId) { return findExtractionsBySourceAndParse(projectId, sourceId, parseResultId); }
            @Override public ExtractionResult bindCandidateIfUnbound(UUID projectId, UUID sourceId, UUID parseResultId, String key, UUID candidateId) { return bindExtraction(projectId, sourceId, parseResultId, key, candidateId); }
            @Override public List<ExtractionResult> findByCandidateId(UUID projectId, UUID candidateId) { return findExtractionsByCandidate(projectId, candidateId); }
            @Override public List<ExtractionResult> findByProjectId(UUID projectId) { return findExtractions(projectId); }
        };
    }

    @Bean
    public ContractCandidateRepository contractCandidateRepository() {
        return new ContractCandidateRepository() {
            @Override public ContractCandidate insertIfAbsent(ContractCandidate candidate) { return insertCandidate(candidate); }
            @Override public Optional<ContractCandidate> findByProjectIdAndId(UUID projectId, UUID id) { return findCandidate(projectId, id); }
            @Override public List<ContractCandidate> findByProjectId(UUID projectId) { return findCandidates(projectId); }
            @Override public Optional<ContractCandidate> findBySourceContractFileId(UUID projectId, UUID sourceId) { return findCandidateBySource(projectId, sourceId); }
            @Override public Optional<ContractCandidate> findByProjectIdAndIdForUpdate(UUID projectId, UUID id) { return findCandidateForUpdate(projectId, id); }
            @Override public Optional<ContractCandidate> findBySourceContractFileIdForUpdate(UUID projectId, UUID sourceId) { return findCandidateBySourceForUpdate(projectId, sourceId); }
            @Override public ContractCandidate reauthorizeDraftIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, UUID parseResultId, UUID roleDecisionId, String factFingerprint,
                    Instant updatedAt) { return reauthorizeCandidate(projectId, candidateId, expectedVersion,
                            parseResultId, roleDecisionId, null,
                            com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                            factFingerprint, updatedAt); }
            @Override public ContractCandidate reauthorizeDraftIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
                    String factFingerprint, Instant updatedAt) {
                return reauthorizeCandidate(projectId, candidateId, expectedVersion,
                        parseResultId, roleDecisionId, identityDecisionId,
                        com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                        factFingerprint, updatedAt);
            }
            @Override public ContractCandidate reauthorizeDraftIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
                    com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource authoritySource,
                    String factFingerprint, Instant updatedAt) {
                return reauthorizeCandidate(projectId, candidateId, expectedVersion,
                        parseResultId, roleDecisionId, identityDecisionId, authoritySource,
                        factFingerprint, updatedAt);
            }
            @Override public ContractCandidate replaceDraftDerivedFactsIfVersionMatches(UUID projectId,
                    UUID candidateId, long expectedVersion, ContractCandidateDerivedFacts facts,
                    long supportAuthorityVersionNo, String supportAuthorityFingerprint, Instant updatedAt) {
                return replaceDraftDerivedFacts(projectId, candidateId, expectedVersion, facts,
                        supportAuthorityVersionNo, supportAuthorityFingerprint, updatedAt);
            }
            @Override public ContractCandidate promoteIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, UUID contractId, String promotedBy, Instant promotedAt) {
                return promoteCandidate(projectId, candidateId, expectedVersion, contractId,
                        null, promotedBy, promotedAt);
            }
            @Override public ContractCandidate promoteIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, UUID contractId, UUID confirmationId,
                    String promotedBy, Instant promotedAt) {
                return promoteCandidate(projectId, candidateId, expectedVersion, contractId,
                        confirmationId, promotedBy, promotedAt);
            }
            @Override public ContractCandidate confirmDraftIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, Instant confirmedAt) {
                return confirmCandidate(projectId, candidateId, expectedVersion, confirmedAt);
            }
        };
    }

    private void insertProject(Project project) { projectMapper.insert(row(project)); }
    private Optional<Project> findProject(UUID id) { return Optional.ofNullable(projectMapper.findById(id.toString())).map(this::domain); }
    private List<Project> findProjects(int offset, int limit, String keyword) {
        return projectMapper.findPage(keyword, offset, (long) offset + limit).stream().map(this::domain).toList();
    }
    private long countProjects(String keyword) { return projectMapper.count(keyword); }
    private List<Project> findIntakeDrafts(int offset, int limit) {
        return projectMapper.findIntakeDraftPage(offset, (long) offset + limit).stream().map(this::domain).toList();
    }
    private long countIntakeDraftProjects() { return projectMapper.countIntakeDrafts(); }
    private List<ProjectRepository.PreparationDraft> findPreparationDrafts(int offset, int limit) {
        return projectMapper.findPreparationDraftPage(offset, (long) offset + limit).stream()
                .map(row -> new ProjectRepository.PreparationDraft(
                        UUID.fromString(row.projectId()), row.createdAt(), row.uploadedFileCount()))
                .toList();
    }
    private boolean replaceProject(Project expected, Project updated) {
        return projectMapper.compareAndSet(row(updated), expected.versionNo()) == 1;
    }
    private void insertFile(ProjectFile file) { fileMapper.insert(row(file)); }
    private void updateFile(ProjectFile file) {
        if (fileMapper.update(row(file)) != 1) throw new IllegalStateException("project file update conflict");
    }
    private Optional<ProjectFile> findFile(UUID projectId, UUID id) {
        return Optional.ofNullable(fileMapper.findByProjectIdAndId(projectId.toString(), id.toString())).map(this::domain);
    }
    private Optional<ProjectFile> findChild(UUID projectId, UUID parentArchiveFileId, String path) {
        return Optional.ofNullable(fileMapper.findByParentAndPath(projectId.toString(), parentArchiveFileId.toString(), path)).map(this::domain);
    }
    private List<ProjectFile> findFiles(UUID projectId) {
        return fileMapper.findByProjectId(projectId.toString()).stream().map(this::domain).toList();
    }
    private ExtractionResult insertExtraction(ExtractionResult result) {
        ProjectExtractionPersistenceRow existing = extractionMapper.findByExtractionKey(result.extractionKey());
        if (existing != null) return requireSame(result, domain(existing));
        try {
            extractionMapper.insert(row(result));
            return result;
        }
        catch (DuplicateKeyException conflict) {
            ProjectExtractionPersistenceRow raced = extractionMapper.findByExtractionKey(result.extractionKey());
            if (raced == null) throw conflict;
            return requireSame(result, domain(raced));
        }
        catch (DataIntegrityViolationException failure) {
            ContractFactPersistenceDiagnostics.log(LOGGER, "PUBLICATION_INSERT", 0, failure);
            LOGGER.warn("contract fact publication shape factType={} groupKeyLength={} valueTextLength={} "
                            + "normalizedLength={} decimalPrecision={} decimalScale={} sourceTextLength={} "
                            + "sourceTextUtf8Bytes={} locatorLength={}",
                    safeFactType(result.factType()), length(result.groupKey()), length(result.valueText()),
                    length(result.normalizedValue()), precision(result.valueAmount()), scale(result.valueAmount()),
                    length(result.sourceText()), utf8Length(result.sourceText()), length(result.sourceLocator()));
            throw failure;
        }
    }
    private Optional<ExtractionResult> findExtraction(String key) {
        return Optional.ofNullable(extractionMapper.findByExtractionKey(key)).map(this::domain);
    }
    private List<ExtractionResult> findExtractionsBySource(UUID projectId, UUID sourceFileId) {
        return extractionMapper.findBySource(projectId.toString(), sourceFileId.toString()).stream().map(this::domain).toList();
    }
    private List<ExtractionResult> findExtractionsBySourceAndParse(UUID projectId, UUID sourceFileId,
            UUID parseResultId) {
        return extractionMapper.findBySourceAndParse(projectId.toString(), sourceFileId.toString(),
                parseResultId.toString()).stream().map(this::domain).toList();
    }
    private ExtractionResult bindExtraction(UUID projectId, UUID sourceFileId, UUID parseResultId,
            String extractionKey, UUID candidateId) {
        extractionMapper.bindCandidateIfUnbound(projectId.toString(), sourceFileId.toString(),
                parseResultId.toString(), extractionKey, candidateId.toString());
        ExtractionResult stored = findExtraction(extractionKey)
                .orElseThrow(ProjectFactIdentityConflictException::extractionCandidateBinding);
        if (!stored.projectId().equals(projectId) || !stored.sourceFileId().equals(sourceFileId)
                || !parseResultId.equals(stored.parseResultId())
                || !candidateId.equals(stored.candidateId())) {
            throw ProjectFactIdentityConflictException.extractionCandidateBinding();
        }
        return stored;
    }
    private List<ExtractionResult> findExtractionsByCandidate(UUID projectId, UUID candidateId) {
        return extractionMapper.findByCandidate(projectId.toString(), candidateId.toString()).stream().map(this::domain).toList();
    }
    private List<ExtractionResult> findExtractions(UUID projectId) {
        return extractionMapper.findByProject(projectId.toString()).stream().map(this::domain).toList();
    }
    private ContractCandidate insertCandidate(ContractCandidate candidate) {
        ContractCandidatePersistenceRow existing = candidateMapper.findBySource(candidate.projectId().toString(), candidate.sourceContractFileId().toString());
        if (existing != null) return requireSame(candidate, candidateDomain(existing));
        try {
            candidateMapper.insert(row(candidate));
            candidate.lineItems().forEach(line -> candidateMapper.insertLine(row(line)));
            return candidate;
        }
        catch (DuplicateKeyException conflict) {
            ContractCandidatePersistenceRow raced = candidateMapper.findBySource(candidate.projectId().toString(), candidate.sourceContractFileId().toString());
            if (raced == null) throw conflict;
            return requireSame(candidate, candidateDomain(raced));
        }
    }
    private Optional<ContractCandidate> findCandidate(UUID projectId, UUID id) {
        return Optional.ofNullable(candidateMapper.findByProjectAndId(projectId.toString(), id.toString())).map(this::candidateDomain);
    }
    private Optional<ContractCandidate> findCandidateForUpdate(UUID projectId, UUID id) {
        return Optional.ofNullable(candidateMapper.findByProjectAndIdForUpdate(
                projectId.toString(), id.toString())).map(this::candidateDomain);
    }
    private List<ContractCandidate> findCandidates(UUID projectId) {
        return candidateMapper.findByProject(projectId.toString()).stream().map(this::candidateDomain).toList();
    }
    private Optional<ContractCandidate> findCandidateBySource(UUID projectId, UUID sourceFileId) {
        return Optional.ofNullable(candidateMapper.findBySource(projectId.toString(), sourceFileId.toString())).map(this::candidateDomain);
    }
    private Optional<ContractCandidate> findCandidateBySourceForUpdate(UUID projectId, UUID sourceFileId) {
        return Optional.ofNullable(candidateMapper.findBySourceForUpdate(
                projectId.toString(), sourceFileId.toString())).map(this::candidateDomain);
    }
    private ContractCandidate reauthorizeCandidate(UUID projectId, UUID candidateId, long expectedVersion,
            UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
            com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource authoritySource,
            String factFingerprint, Instant updatedAt) {
        if (candidateMapper.reauthorizeDraftIfVersionMatches(projectId.toString(), candidateId.toString(),
                expectedVersion, parseResultId.toString(), roleDecisionId.toString(), text(identityDecisionId),
                authoritySource.name(), factFingerprint,
                dateTime(updatedAt)) != 1) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate authority conflict");
        }
        return findCandidate(projectId, candidateId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "candidate authority read failed"));
    }
    private ContractCandidate replaceDraftDerivedFacts(UUID projectId, UUID candidateId, long expectedVersion,
            ContractCandidateDerivedFacts facts, long supportAuthorityVersionNo,
            String supportAuthorityFingerprint, Instant updatedAt) {
        validateDerivedFactLineOwnership(projectId, candidateId, facts);
        int changed = candidateMapper.replaceDraftDerivedFactsIfVersionMatches(projectId.toString(),
                candidateId.toString(), expectedVersion, facts, supportAuthorityVersionNo,
                supportAuthorityFingerprint, dateTime(updatedAt));
        if (changed != 1) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate derived facts conflict");
        }
        candidateMapper.deleteLinesByCandidate(projectId.toString(), candidateId.toString());
        facts.lineItems().stream().sorted(Comparator.comparingInt(ContractCandidateLineItem::sortNo)
                        .thenComparing(ContractCandidateLineItem::id))
                .forEach(line -> candidateMapper.insertLine(row(line)));
        ContractCandidate reloaded = findCandidate(projectId, candidateId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "candidate derived facts read failed"));
        if (reloaded.versionNo() != expectedVersion + 1
                || !java.util.Objects.equals(reloaded.supportAuthorityVersionNo(), supportAuthorityVersionNo)
                || !java.util.Objects.equals(reloaded.supportAuthorityFingerprint(), supportAuthorityFingerprint)) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate derived facts read conflict");
        }
        return reloaded;
    }
    private static void validateDerivedFactLineOwnership(UUID projectId, UUID candidateId,
            ContractCandidateDerivedFacts facts) {
        for (var line : facts.lineItems()) {
            if (!projectId.equals(line.projectId()) || !candidateId.equals(line.candidateId())) {
                throw new IllegalArgumentException("candidate derived fact line ownership mismatch");
            }
        }
    }
    private ContractCandidate promoteCandidate(UUID projectId, UUID candidateId, long expectedVersion,
            UUID contractId, UUID confirmationId, String promotedBy, Instant promotedAt) {
        var timestamp = java.time.LocalDateTime.ofInstant(promotedAt, ZoneOffset.UTC);
        int changed = confirmationId == null
                ? candidateMapper.promoteIfVersionMatches(projectId.toString(), candidateId.toString(),
                        expectedVersion, contractId.toString(), promotedBy, timestamp, timestamp)
                : candidateMapper.promoteWithConfirmationIfVersionMatches(projectId.toString(), candidateId.toString(),
                        expectedVersion, contractId.toString(), confirmationId.toString(),
                        promotedBy, timestamp, timestamp);
        if (changed != 1) {
            if (candidateMapper.findByProjectAndId(projectId.toString(), candidateId.toString()) == null) {
                throw new BusinessException(BusinessErrorCode.NOT_FOUND, "contract candidate not found");
            }
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate promotion conflict");
        }
        return findCandidate(projectId, candidateId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT, "candidate promotion read failed"));
    }

    private ContractCandidate confirmCandidate(UUID projectId, UUID candidateId,
            long expectedVersion, Instant confirmedAt) {
        if (candidateMapper.confirmDraftIfVersionMatches(projectId.toString(), candidateId.toString(),
                expectedVersion, dateTime(confirmedAt)) != 1) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate confirmation version conflict");
        }
        return findCandidate(projectId, candidateId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "candidate confirmation read failed"));
    }

    private ProjectPersistenceRow row(Project v) { return new ProjectPersistenceRow(v.id().toString(), v.name(), v.projectNo(), v.category(), v.createdBy(), v.versionNo(), java.time.LocalDateTime.ofInstant(v.createdAt(), ZoneOffset.UTC), java.time.LocalDateTime.ofInstant(v.updatedAt(), ZoneOffset.UTC)); }
    private Project domain(ProjectPersistenceRow v) { return new Project(UUID.fromString(v.id()), v.name(), v.projectNo(), v.category(), v.createdBy(), v.versionNo(), v.createdAt().toInstant(ZoneOffset.UTC), v.updatedAt().toInstant(ZoneOffset.UTC)); }
    private ProjectFilePersistenceRow row(ProjectFile v) {
        ProjectFileParseProvenance provenance = v.parseProvenance();
        return new ProjectFilePersistenceRow(v.id().toString(), v.projectId().toString(),
                text(v.parentArchiveFileId()), v.normalizedRelativePath(), v.originalFilename(), v.mediaType(),
                v.category().name(), v.ingestionStatus().name(), v.parseStatus().name(), v.storageKey(),
                v.sha256(), v.sizeBytes(), v.parsedText(),
                provenance.method() == null ? null : provenance.method().name(), provenance.engine(),
                provenance.confidence(), provenance.pageCount(), provenance.durationMs(), v.safeReasonCode(),
                java.time.LocalDateTime.ofInstant(v.createdAt(), ZoneOffset.UTC),
                java.time.LocalDateTime.ofInstant(v.updatedAt(), ZoneOffset.UTC));
    }
    private ProjectFile domain(ProjectFilePersistenceRow v) {
        ProjectFileParseProvenance provenance = v.parseMethod() == null
                ? ProjectFileParseProvenance.none()
                : new ProjectFileParseProvenance(ProjectFileParseMethod.valueOf(v.parseMethod()),
                        v.parseEngine(), v.parseConfidence(), v.pageCount(), v.parseDurationMs());
        return new ProjectFile(UUID.fromString(v.id()), UUID.fromString(v.projectId()),
                uuid(v.parentArchiveFileId()), v.normalizedRelativePath(), v.originalFilename(), v.mediaType(),
                ProjectFileCategory.valueOf(v.category()), ProjectFileIngestionStatus.valueOf(v.ingestionStatus()),
                ProjectFileParseStatus.valueOf(v.parseStatus()), v.storageKey(), v.sha256(), v.sizeBytes(),
                v.parsedText(), v.safeReasonCode(), v.createdAt().toInstant(ZoneOffset.UTC),
                v.updatedAt().toInstant(ZoneOffset.UTC), provenance);
    }
    private ProjectExtractionPersistenceRow row(ExtractionResult v) { return new ProjectExtractionPersistenceRow(v.id().toString(), v.projectId().toString(), v.sourceFileId().toString(), text(v.parseResultId()), text(v.candidateId()), v.extractionKey(), v.extractionVersion(), v.factType(), v.groupKey(), v.valueText(), v.normalizedValue(), v.valueAmount(), v.sourceText(), v.pageNumber(), v.paragraphIndex(), v.sourceLocator(), java.time.LocalDateTime.ofInstant(v.createdAt(), ZoneOffset.UTC)); }
    private ExtractionResult domain(ProjectExtractionPersistenceRow v) { return new ExtractionResult(UUID.fromString(v.id()), UUID.fromString(v.projectId()), UUID.fromString(v.sourceFileId()), uuid(v.parseResultId()), uuid(v.candidateId()), v.extractionKey(), v.extractionVersion(), v.factType(), v.groupKey(), v.valueText(), v.normalizedValue(), v.valueAmount(), v.sourceText(), v.pageNumber(), v.paragraphIndex(), v.sourceLocator(), v.createdAt().toInstant(ZoneOffset.UTC)); }
    private ContractCandidatePersistenceRow row(ContractCandidate v) { return new ContractCandidatePersistenceRow(v.id().toString(), v.projectId().toString(), v.sourceContractFileId().toString(), text(v.sourceParseResultId()), text(v.sourceRoleDecisionId()), text(v.sourceIdentityDecisionId()), v.sourceAuthoritySource() == null ? null : v.sourceAuthoritySource().name(), v.sourceFactFingerprint(), v.contractNo(), v.contractName(), v.contractType(), v.partyA(), v.partyB(), v.partyBCreditCode(), v.contractAmount(), v.amountInWords(), v.awardAmount(), v.awardSupplier(), v.awardSupplierCreditCode(), v.promotionState().name(), v.versionNo(), text(v.promotedContractId()), text(v.promotedConfirmationId()), v.promotedBy(), dateTime(v.promotedAt()), java.time.LocalDateTime.ofInstant(v.createdAt(), ZoneOffset.UTC), java.time.LocalDateTime.ofInstant(v.updatedAt(), ZoneOffset.UTC), v.supportAuthorityVersionNo(), v.supportAuthorityFingerprint()); }
    private ContractCandidateLineItemPersistenceRow row(ContractCandidateLineItem v) { return new ContractCandidateLineItemPersistenceRow(v.id().toString(), v.projectId().toString(), v.candidateId().toString(), v.itemKey(), v.itemName(), v.quantity(), v.unitPrice(), v.declaredAmount(), v.sortNo()); }
    private ContractCandidate candidateDomain(ContractCandidatePersistenceRow v) {
        List<ContractCandidateLineItem> lines = candidateMapper.findLinesByCandidate(v.projectId(), v.id()).stream()
                .map(this::domain).toList();
        return new ContractCandidate(UUID.fromString(v.id()), UUID.fromString(v.projectId()), UUID.fromString(v.sourceContractFileId()), uuid(v.sourceParseResultId()), uuid(v.sourceRoleDecisionId()), uuid(v.sourceIdentityDecisionId()), v.sourceAuthoritySource() == null ? null : ContractCandidateAuthoritySource.valueOf(v.sourceAuthoritySource()), v.sourceFactFingerprint(), v.contractNo(), v.contractName(), v.contractType(), v.partyA(), v.partyB(), v.partyBCreditCode(), v.contractAmount(), v.amountInWords(), v.awardAmount(), v.awardSupplier(), v.awardSupplierCreditCode(), lines, CandidatePromotionState.valueOf(v.promotionState()), v.versionNo(), uuid(v.promotedContractId()), uuid(v.promotedConfirmationId()), v.promotedBy(), instant(v.promotedAt()), v.createdAt().toInstant(ZoneOffset.UTC), v.updatedAt().toInstant(ZoneOffset.UTC), v.supportAuthorityVersionNo(), v.supportAuthorityFingerprint());
    }
    private ContractCandidateLineItem domain(ContractCandidateLineItemPersistenceRow v) { return new ContractCandidateLineItem(UUID.fromString(v.id()), UUID.fromString(v.projectId()), UUID.fromString(v.candidateId()), v.itemKey(), v.itemName(), v.quantity(), v.unitPrice(), v.declaredAmount(), v.sortNo()); }
    private static String text(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static java.time.LocalDateTime dateTime(java.time.Instant value) { return value == null ? null : java.time.LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static java.time.Instant instant(java.time.LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static int length(String value) { return value == null ? -1 : value.length(); }
    private static int utf8Length(String value) {
        return value == null ? -1 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    private static int precision(java.math.BigDecimal value) { return value == null ? -1 : value.precision(); }
    private static int scale(java.math.BigDecimal value) { return value == null ? -1 : value.scale(); }
    private static String safeFactType(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : "UNKNOWN";
    }
    private static ExtractionResult requireSame(ExtractionResult expected, ExtractionResult existing) {
        if (!ProjectFactIdentity.sameExtraction(expected, existing)) throw ProjectFactIdentityConflictException.extraction(); return existing;
    }
    private static ContractCandidate requireSame(ContractCandidate expected, ContractCandidate existing) {
        if (!ProjectFactIdentity.sameCandidate(expected, existing)) throw ProjectFactIdentityConflictException.candidate(); return existing;
    }
}
