package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryContractCandidateSupportAuthorityRepository
        implements ContractCandidateSupportAuthorityRepository {
    private final Map<CandidateFileKey, ContractCandidateSupportAuthority> current = new HashMap<>();
    private final Map<UUID, ContractCandidateSupportAuthority> byId = new HashMap<>();
    private final Map<UUID, UUID> candidateProjects = new HashMap<>();
    private final Map<UUID, UUID> sourceFileProjects = new HashMap<>();

    @Override
    public synchronized List<ContractCandidateSupportAuthority> findCurrentByProjectIdAndCandidateId(
            UUID projectId, UUID candidateId) {
        requireOwnershipParameters(projectId, candidateId);
        return sorted(current.values().stream().filter(value -> value.projectId().equals(projectId)
                && value.candidateId().equals(candidateId)).toList());
    }

    @Override
    public synchronized List<ContractCandidateSupportAuthority> findCurrentByProjectIdAndCandidateIdForUpdate(
            UUID projectId, UUID candidateId) {
        return findCurrentByProjectIdAndCandidateId(projectId, candidateId);
    }

    @Override
    public synchronized Optional<ContractCandidateSupportAuthority> findByProjectIdAndCandidateIdAndSourceFileId(
            UUID projectId, UUID candidateId, UUID sourceFileId) {
        requireOwnershipParameters(projectId, candidateId);
        Objects.requireNonNull(sourceFileId, "sourceFileId must not be null");
        return Optional.ofNullable(current.get(new CandidateFileKey(projectId, candidateId, sourceFileId)));
    }

    @Override
    public synchronized void replaceCurrentCohort(UUID projectId, UUID candidateId,
            List<ContractCandidateSupportAuthority> cohort) {
        requireOwnershipParameters(projectId, candidateId);
        List<ContractCandidateSupportAuthority> ownershipChecked = List.copyOf(
                Objects.requireNonNull(cohort, "cohort must not be null"));
        for (ContractCandidateSupportAuthority authority : ownershipChecked) {
            if (!authority.projectId().equals(projectId) || !authority.candidateId().equals(candidateId)) {
                throw new IllegalArgumentException("support authority cohort ownership mismatch");
            }
        }
        List<ContractCandidateSupportAuthority> copy = validatedCopy(ownershipChecked);
        validateInsertable(copy, true);
        removeCurrent(projectId, candidateId);
        addAll(copy);
    }

    @Override
    public synchronized void deleteCurrentByProjectIdAndCandidateId(UUID projectId, UUID candidateId) {
        requireOwnershipParameters(projectId, candidateId);
        removeCurrent(projectId, candidateId);
    }

    @Override
    public synchronized void insertAll(List<ContractCandidateSupportAuthority> cohort) {
        List<ContractCandidateSupportAuthority> copy = validatedCopy(cohort);
        validateInsertable(copy, false);
        addAll(copy);
    }

    private List<ContractCandidateSupportAuthority> validatedCopy(List<ContractCandidateSupportAuthority> cohort) {
        Objects.requireNonNull(cohort, "cohort must not be null");
        List<ContractCandidateSupportAuthority> copy = List.copyOf(cohort);
        Map<CandidateKey, Long> versions = new HashMap<>();
        Map<UUID, UUID> pendingCandidateProjects = new HashMap<>(candidateProjects);
        Map<UUID, UUID> pendingSourceFileProjects = new HashMap<>(sourceFileProjects);
        Set<CandidateFileKey> keys = new HashSet<>();
        Set<UUID> authorityIds = new HashSet<>();
        for (ContractCandidateSupportAuthority authority : copy) {
            Objects.requireNonNull(authority, "cohort must not contain null");
            requireSameProject(pendingCandidateProjects, authority.candidateId(), authority.projectId(),
                    "candidate project ownership conflict");
            requireSameProject(pendingSourceFileProjects, authority.sourceFileId(), authority.projectId(),
                    "source file project ownership conflict");
            CandidateKey candidateKey = new CandidateKey(authority.projectId(), authority.candidateId());
            Long existingVersion = versions.putIfAbsent(candidateKey, authority.authorityVersion());
            if (existingVersion != null && existingVersion.longValue() != authority.authorityVersion()) {
                throw new IllegalArgumentException("support authority cohort must have one authorityVersion");
            }
            if (!keys.add(CandidateFileKey.of(authority))) {
                throw new IllegalStateException("duplicate Candidate/sourceFile support authority");
            }
            if (!authorityIds.add(authority.supportAuthorityId())) {
                throw new IllegalStateException("duplicate support authority identity");
            }
        }
        return copy;
    }

    private void validateInsertable(List<ContractCandidateSupportAuthority> cohort, boolean replacingCurrentCandidate) {
        for (ContractCandidateSupportAuthority authority : cohort) {
            UUID candidateProject = candidateProjects.get(authority.candidateId());
            if (candidateProject != null && !candidateProject.equals(authority.projectId())) {
                throw new IllegalStateException("candidate project ownership conflict");
            }
            UUID fileProject = sourceFileProjects.get(authority.sourceFileId());
            if (fileProject != null && !fileProject.equals(authority.projectId())) {
                throw new IllegalStateException("source file project ownership conflict");
            }
            CandidateFileKey key = CandidateFileKey.of(authority);
            ContractCandidateSupportAuthority existing = current.get(key);
            if (existing != null && !replacingCurrentCandidate) {
                throw new IllegalStateException("duplicate Candidate/sourceFile support authority");
            }
            ContractCandidateSupportAuthority idExisting = byId.get(authority.supportAuthorityId());
            if (idExisting != null && (!replacingCurrentCandidate
                    || !idExisting.projectId().equals(authority.projectId())
                    || !idExisting.candidateId().equals(authority.candidateId())
                    || !idExisting.sourceFileId().equals(authority.sourceFileId()))) {
                throw new IllegalStateException("support authority identity conflict");
            }
        }
    }

    private void removeCurrent(UUID projectId, UUID candidateId) {
        List<CandidateFileKey> keys = current.keySet().stream().filter(key -> key.projectId().equals(projectId)
                && key.candidateId().equals(candidateId)).toList();
        for (CandidateFileKey key : keys) {
            ContractCandidateSupportAuthority removed = current.remove(key);
            byId.remove(removed.supportAuthorityId());
        }
    }

    private void addAll(List<ContractCandidateSupportAuthority> cohort) {
        for (ContractCandidateSupportAuthority authority : cohort) {
            current.put(CandidateFileKey.of(authority), authority);
            byId.put(authority.supportAuthorityId(), authority);
            candidateProjects.putIfAbsent(authority.candidateId(), authority.projectId());
            sourceFileProjects.putIfAbsent(authority.sourceFileId(), authority.projectId());
        }
    }

    private static List<ContractCandidateSupportAuthority> sorted(List<ContractCandidateSupportAuthority> authorities) {
        return authorities.stream().sorted(Comparator.comparing(value -> value.sourceFileId().toString())).toList();
    }

    private static void requireOwnershipParameters(UUID projectId, UUID candidateId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
    }

    private static void requireSameProject(Map<UUID, UUID> ownerships, UUID resourceId, UUID projectId,
            String error) {
        UUID existingProject = ownerships.putIfAbsent(resourceId, projectId);
        if (existingProject != null && !existingProject.equals(projectId)) {
            throw new IllegalStateException(error);
        }
    }

    private record CandidateKey(UUID projectId, UUID candidateId) { }

    private record CandidateFileKey(UUID projectId, UUID candidateId, UUID sourceFileId) {
        private static CandidateFileKey of(ContractCandidateSupportAuthority authority) {
            return new CandidateFileKey(authority.projectId(), authority.candidateId(), authority.sourceFileId());
        }
    }
}
