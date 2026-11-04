package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateDerivedFacts;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFactIdentity;
import com.fuquan.audit.contract.domain.project.ProjectFactIdentityConflictException;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class InMemoryProjectRepositories {

    private final ConcurrentMap<UUID, Project> projects = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProjectFile> files = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ReentrantLock> authorityLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ExtractionResult> extractions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ContractCandidate> candidates = new ConcurrentHashMap<>();

    @Bean
    public ProjectRepository projectRepository() {
        return new ProjectRepository() {
            @Override public void insert(Project project) { insertProject(project); }
            @Override public Optional<Project> findById(UUID id) { return findProjectById(id); }
            @Override public List<Project> findPage(int offset, int limit, String keyword) { return findProjects(offset, limit, keyword); }
            @Override public long count(String keyword) { return countProjects(keyword); }
            @Override public List<Project> findIntakeDraftPage(int offset, int limit) { return findIntakeDrafts(offset, limit); }
            @Override public long countIntakeDrafts() { return countIntakeDraftProjects(); }
            @Override public List<ProjectRepository.PreparationDraft> findPreparationDraftPage(int offset, int limit) {
                return findPreparationDrafts(offset, limit);
            }
            @Override public long countPreparationDrafts() { return findPreparationDrafts(0, Integer.MAX_VALUE).size(); }
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
                return lockFile(projectId, id);
            }
            @Override public <T> T withFileAuthorityFence(UUID projectId, UUID id,
                    java.util.function.Supplier<T> command) {
                ReentrantLock lock = authorityLocks.computeIfAbsent(id, ignored -> new ReentrantLock());
                lock.lock();
                try {
                    if (findFile(projectId, id).isEmpty()) {
                        throw new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found");
                    }
                    return command.get();
                } finally {
                    lock.unlock();
                }
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
            @Override public List<ExtractionResult> findByProjectId(UUID projectId) { return orderedExtractions(projectId); }
        };
    }

    @Bean
    public ContractCandidateRepository contractCandidateRepository() {
        return new ContractCandidateRepository() {
            @Override public ContractCandidate insertIfAbsent(ContractCandidate candidate) { return insertCandidate(candidate); }
            @Override public Optional<ContractCandidate> findByProjectIdAndId(UUID projectId, UUID id) { return findCandidate(projectId, id); }
            @Override public List<ContractCandidate> findByProjectId(UUID projectId) { return findCandidates(projectId); }
            @Override public Optional<ContractCandidate> findBySourceContractFileId(UUID projectId, UUID sourceId) { return findCandidateBySource(projectId, sourceId); }
            @Override public ContractCandidate reauthorizeDraftIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, UUID parseResultId, UUID roleDecisionId, String factFingerprint,
                    Instant updatedAt) { return reauthorizeCandidate(projectId, candidateId, expectedVersion,
                            parseResultId, roleDecisionId, null, factFingerprint, updatedAt); }
            @Override public ContractCandidate reauthorizeDraftIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
                    String factFingerprint, Instant updatedAt) {
                return reauthorizeCandidate(projectId, candidateId, expectedVersion, parseResultId,
                        roleDecisionId, identityDecisionId, factFingerprint, updatedAt);
            }
            @Override public ContractCandidate reauthorizeDraftIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
                    com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource authoritySource,
                    String factFingerprint, Instant updatedAt) {
                return reauthorizeCandidate(projectId, candidateId, expectedVersion, parseResultId,
                        roleDecisionId, identityDecisionId, authoritySource, factFingerprint, updatedAt);
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
            @Override public synchronized ContractCandidate confirmDraftIfVersionMatches(UUID projectId, UUID candidateId,
                    long expectedVersion, Instant confirmedAt) {
                ContractCandidate current = findCandidate(projectId, candidateId)
                        .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                                "contract candidate not found"));
                if (current.promotionState() != CandidatePromotionState.DRAFT
                        || current.versionNo() != expectedVersion) {
                    throw new BusinessException(BusinessErrorCode.CONFLICT,
                            "candidate confirmation version conflict");
                }
                ContractCandidate updated = current.confirmed(confirmedAt);
                candidates.put(candidateId, updated);
                return updated;
            }
        };
    }

    private void insertProject(Project project) {
        if (projects.putIfAbsent(project.id(), project) != null) {
            throw new IllegalStateException("project identity conflict");
        }
    }

    private Optional<Project> findProjectById(UUID id) {
        return Optional.ofNullable(projects.get(id));
    }

    private List<Project> findProjects(int offset, int limit, String keyword) {
        String term = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return projects.values().stream()
                .filter(Project::metadataConfirmed)
                .filter(project -> term.isEmpty() || contains(project.name(), term) || contains(project.projectNo(), term))
                .sorted(Comparator.comparing(Project::createdAt).reversed()
                        .thenComparing(Project::id, Comparator.reverseOrder()))
                .skip(offset).limit(limit).toList();
    }

    private long countProjects(String keyword) {
        return findProjects(0, Integer.MAX_VALUE, keyword).size();
    }

    private List<Project> findIntakeDrafts(int offset, int limit) {
        return projects.values().stream()
                .filter(project -> !project.metadataConfirmed())
                .filter(project -> files.values().stream().anyMatch(file -> file.projectId().equals(project.id())
                        && file.category() == com.fuquan.audit.contract.domain.project.ProjectFileCategory.PACKAGE))
                .sorted(Comparator.comparing(Project::createdAt).reversed()
                        .thenComparing(Project::id, Comparator.reverseOrder()))
                .skip(offset).limit(limit).toList();
    }

    private long countIntakeDraftProjects() {
        return findIntakeDrafts(0, Integer.MAX_VALUE).size();
    }

    private List<ProjectRepository.PreparationDraft> findPreparationDrafts(int offset, int limit) {
        return projects.values().stream()
                .filter(project -> !project.metadataConfirmed())
                .filter(project -> files.values().stream().anyMatch(file -> file.projectId().equals(project.id())))
                .filter(project -> files.values().stream().noneMatch(file -> file.projectId().equals(project.id())
                        && file.category() == com.fuquan.audit.contract.domain.project.ProjectFileCategory.PACKAGE))
                .sorted(Comparator.comparing(Project::createdAt).reversed()
                        .thenComparing(Project::id, Comparator.reverseOrder()))
                .skip(offset).limit(limit)
                .map(project -> new ProjectRepository.PreparationDraft(project.id(), project.createdAt(),
                        files.values().stream().filter(file -> file.projectId().equals(project.id())).count()))
                .toList();
    }

    private boolean replaceProject(Project expected, Project updated) {
        return projects.replace(expected.id(), expected, updated);
    }

    private void insertFile(ProjectFile file) {
        if (files.putIfAbsent(file.id(), file) != null) {
            throw new IllegalStateException("project file identity conflict");
        }
        if (files.values().stream().anyMatch(other -> !other.id().equals(file.id())
                && other.storageKey().equals(file.storageKey()))) {
            files.remove(file.id(), file);
            throw new IllegalStateException("project file storage identity conflict");
        }
    }

    private void updateFile(ProjectFile file) {
        ProjectFile current = files.get(file.id());
        if (current == null || !current.projectId().equals(file.projectId())) {
            throw new IllegalStateException("project file missing");
        }
        files.put(file.id(), file);
    }

    private Optional<ProjectFile> findFile(UUID projectId, UUID id) {
        return Optional.ofNullable(files.get(id)).filter(file -> file.projectId().equals(projectId));
    }

    private Optional<ProjectFile> lockFile(UUID projectId, UUID id) {
        ReentrantLock lock = authorityLocks.computeIfAbsent(id, ignored -> new ReentrantLock());
        lock.lock();
        boolean handedToTransaction = false;
        try {
            Optional<ProjectFile> found = findFile(projectId, id);
            if (found.isPresent() && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { lock.unlock(); }
                });
                handedToTransaction = true;
            }
            return found;
        } finally {
            if (!handedToTransaction) lock.unlock();
        }
    }

    private Optional<ProjectFile> findChild(
            UUID projectId, UUID parentArchiveFileId, String normalizedRelativePath) {
        return files.values().stream().filter(file -> file.projectId().equals(projectId)
                        && parentArchiveFileId.equals(file.parentArchiveFileId())
                        && normalizedRelativePath.equals(file.normalizedRelativePath()))
                .findFirst();
    }

    private List<ProjectFile> findFiles(UUID projectId) {
        return files.values().stream().filter(file -> file.projectId().equals(projectId))
                .sorted(Comparator.comparing(ProjectFile::createdAt).thenComparing(ProjectFile::id)).toList();
    }

    private ExtractionResult insertExtraction(ExtractionResult result) {
        ExtractionResult existing = extractions.putIfAbsent(result.extractionKey(), result);
        if (existing != null && !ProjectFactIdentity.sameExtraction(existing, result)) {
            throw ProjectFactIdentityConflictException.extraction();
        }
        return existing == null ? result : existing;
    }

    private Optional<ExtractionResult> findExtraction(String extractionKey) {
        return Optional.ofNullable(extractions.get(extractionKey));
    }

    private List<ExtractionResult> findExtractionsBySource(UUID projectId, UUID sourceFileId) {
        return orderedExtractions(projectId).stream().filter(value -> value.sourceFileId().equals(sourceFileId)).toList();
    }

    private List<ExtractionResult> findExtractionsBySourceAndParse(
            UUID projectId, UUID sourceFileId, UUID parseResultId) {
        return findExtractionsBySource(projectId, sourceFileId).stream()
                .filter(value -> parseResultId.equals(value.parseResultId())).toList();
    }

    private ExtractionResult bindExtraction(UUID projectId, UUID sourceFileId, UUID parseResultId,
            String extractionKey, UUID candidateId) {
        synchronized (extractions) {
            ExtractionResult current = extractions.get(extractionKey);
            if (current == null || !current.projectId().equals(projectId)
                    || !current.sourceFileId().equals(sourceFileId)
                    || !parseResultId.equals(current.parseResultId())) {
                throw ProjectFactIdentityConflictException.extractionCandidateBinding();
            }
            ExtractionResult updated = current.bindCandidate(parseResultId, candidateId);
            extractions.put(extractionKey, updated);
            return updated;
        }
    }

    private List<ExtractionResult> findExtractionsByCandidate(UUID projectId, UUID candidateId) {
        return orderedExtractions(projectId).stream().filter(value -> candidateId.equals(value.candidateId())).toList();
    }

    private synchronized ContractCandidate insertCandidate(ContractCandidate candidate) {
        Optional<ContractCandidate> existing = findCandidateBySource(candidate.projectId(), candidate.sourceContractFileId());
        if (existing.isPresent()) {
            if (!ProjectFactIdentity.sameCandidate(existing.get(), candidate)) {
                throw ProjectFactIdentityConflictException.candidate();
            }
            return existing.get();
        }
        ContractCandidate raced = candidates.putIfAbsent(candidate.id(), candidate);
        if (raced != null && !raced.equals(candidate)) {
            throw ProjectFactIdentityConflictException.candidate();
        }
        return raced == null ? candidate : raced;
    }

    private Optional<ContractCandidate> findCandidate(UUID projectId, UUID id) {
        return Optional.ofNullable(candidates.get(id)).filter(value -> value.projectId().equals(projectId));
    }

    private List<ContractCandidate> findCandidates(UUID projectId) {
        return candidates.values().stream().filter(value -> value.projectId().equals(projectId))
                .sorted(Comparator.comparing(ContractCandidate::createdAt).thenComparing(ContractCandidate::id)).toList();
    }

    private Optional<ContractCandidate> findCandidateBySource(UUID projectId, UUID sourceFileId) {
        return candidates.values().stream().filter(value -> value.projectId().equals(projectId)
                && value.sourceContractFileId().equals(sourceFileId)).findFirst();
    }

    private synchronized ContractCandidate promoteCandidate(UUID projectId, UUID candidateId,
            long expectedVersion, UUID contractId, UUID confirmationId,
            String promotedBy, Instant promotedAt) {
        ContractCandidate current = findCandidate(projectId, candidateId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "contract candidate not found"));
        if (current.promotionState() != CandidatePromotionState.DRAFT || current.versionNo() != expectedVersion) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate promotion conflict");
        }
        ContractCandidate promoted = current.promote(contractId, confirmationId, promotedBy, promotedAt);
        candidates.put(candidateId, promoted);
        return promoted;
    }

    private synchronized ContractCandidate reauthorizeCandidate(UUID projectId, UUID candidateId,
            long expectedVersion, UUID parseResultId, UUID roleDecisionId,
            UUID identityDecisionId, String factFingerprint, Instant updatedAt) {
        return reauthorizeCandidate(projectId, candidateId, expectedVersion, parseResultId, roleDecisionId,
                identityDecisionId, com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                factFingerprint, updatedAt);
    }

    private synchronized ContractCandidate reauthorizeCandidate(UUID projectId, UUID candidateId,
            long expectedVersion, UUID parseResultId, UUID roleDecisionId,
            UUID identityDecisionId, com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource authoritySource,
            String factFingerprint, Instant updatedAt) {
        ContractCandidate current = findCandidate(projectId, candidateId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "contract candidate not found"));
        if (current.promotionState() != CandidatePromotionState.DRAFT
                || current.versionNo() != expectedVersion) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate authority conflict");
        }
        ContractCandidate updated = current.reauthorize(parseResultId, roleDecisionId,
                identityDecisionId, authoritySource, factFingerprint, updatedAt);
        candidates.put(candidateId, updated);
        return updated;
    }

    private synchronized ContractCandidate replaceDraftDerivedFacts(UUID projectId, UUID candidateId,
            long expectedVersion, ContractCandidateDerivedFacts facts, long supportAuthorityVersionNo,
            String supportAuthorityFingerprint, Instant updatedAt) {
        validateDerivedFactLineOwnership(projectId, candidateId, facts);
        ContractCandidate current = findCandidate(projectId, candidateId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "contract candidate not found"));
        if (current.promotionState() != CandidatePromotionState.DRAFT
                || current.versionNo() != expectedVersion) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "candidate derived facts conflict");
        }
        ContractCandidate updated = new ContractCandidate(current.id(), current.projectId(),
                current.sourceContractFileId(), current.sourceParseResultId(), current.sourceRoleDecisionId(),
                current.sourceIdentityDecisionId(), current.sourceAuthoritySource(), current.sourceFactFingerprint(),
                facts.contractNo(), facts.contractName(), facts.contractType(), facts.partyA(), facts.partyB(),
                facts.partyBCreditCode(), facts.contractAmount(), facts.amountInWords(), facts.awardAmount(),
                facts.awardSupplier(), facts.awardSupplierCreditCode(), facts.lineItems(), current.promotionState(),
                current.versionNo() + 1, current.promotedContractId(), current.promotedConfirmationId(),
                current.promotedBy(), current.promotedAt(), current.createdAt(), updatedAt,
                supportAuthorityVersionNo, supportAuthorityFingerprint);
        candidates.put(candidateId, updated);
        return updated;
    }

    private static void validateDerivedFactLineOwnership(UUID projectId, UUID candidateId,
            ContractCandidateDerivedFacts facts) {
        for (var line : facts.lineItems()) {
            if (!projectId.equals(line.projectId()) || !candidateId.equals(line.candidateId())) {
                throw new IllegalArgumentException("candidate derived fact line ownership mismatch");
            }
        }
    }

    private List<ExtractionResult> orderedExtractions(UUID projectId) {
        List<ExtractionResult> values = new ArrayList<>(extractions.values());
        return values.stream().filter(value -> value.projectId().equals(projectId))
                .sorted(Comparator.comparing(ExtractionResult::sourceFileId)
                        .thenComparing(value -> value.candidateId() == null ? "" : value.candidateId().toString())
                        .thenComparing(ExtractionResult::factType)
                        .thenComparing(value -> value.groupKey() == null ? "" : value.groupKey())
                        .thenComparing(ExtractionResult::sourceLocator)
                        .thenComparing(ExtractionResult::id)).toList();
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }
}
