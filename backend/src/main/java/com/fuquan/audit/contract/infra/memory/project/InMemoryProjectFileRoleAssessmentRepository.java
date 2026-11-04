package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ProjectFileRoleAssessmentRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProjectFileRoleAssessmentRepository implements ProjectFileRoleAssessmentRepository {
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());

    @Override
    public ProjectFileRoleAssessment insertIfAbsent(ProjectFileRoleAssessment assessment) {
        return insert(assessment, false);
    }

    @Override
    public ProjectFileRoleAssessment stagePendingInsertIfAbsent(ProjectFileRoleAssessment assessment) {
        if (assessment.executionStatus()
                != com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus.PENDING) {
            throw new IllegalArgumentException("only a PENDING assessment can be staged");
        }
        return insert(assessment, true);
    }

    private ProjectFileRoleAssessment insert(ProjectFileRoleAssessment assessment, boolean stagedInsert) {
        Fence fence = new Fence(assessment.fileId(), assessment.inputFingerprint());
        while (true) {
            State current = state.get();
            ProjectFileRoleAssessment existing = current.byId().get(assessment.assessmentId());
            if (existing != null) return requireSameInput(existing, assessment);
            UUID activeId = isActive(assessment) ? current.activeByFence().get(fence) : null;
            if (activeId != null) {
                ProjectFileRoleAssessment winner = current.byId().get(activeId);
                if (winner == null) throw new IllegalStateException("role assessment fence is inconsistent");
                return requireSameInput(winner, assessment);
            }
            Map<UUID, ProjectFileRoleAssessment> byId = new HashMap<>(current.byId());
            byId.put(assessment.assessmentId(), assessment);
            Map<Fence, UUID> active = new HashMap<>(current.activeByFence());
            if (isActive(assessment)) active.put(fence, assessment.assessmentId());
            Set<UUID> staged = new java.util.HashSet<>(current.staged());
            if (stagedInsert) staged.add(assessment.assessmentId());
            if (state.compareAndSet(current,
                    new State(Map.copyOf(byId), Map.copyOf(active), Set.copyOf(staged)))) return assessment;
        }
    }

    @Override
    public void commitStagedInsert(ProjectFileRoleAssessment assessment) {
        while (true) {
            State current = state.get();
            ProjectFileRoleAssessment stored = current.byId().get(assessment.assessmentId());
            if (!assessment.equals(stored)) {
                throw new IllegalStateException("staged role assessment identity is inconsistent");
            }
            if (!current.staged().contains(assessment.assessmentId())) return;
            Set<UUID> staged = new java.util.HashSet<>(current.staged());
            staged.remove(assessment.assessmentId());
            if (state.compareAndSet(current,
                    new State(current.byId(), current.activeByFence(), Set.copyOf(staged)))) return;
        }
    }

    @Override
    public boolean compareAndSet(ProjectFileRoleAssessment expected, ProjectFileRoleAssessment updated) {
        expected.requireLegalSuccessor(updated);
        Fence fence = new Fence(expected.fileId(), expected.inputFingerprint());
        while (true) {
            State current = state.get();
            ProjectFileRoleAssessment stored = current.byId().get(expected.assessmentId());
            if (!expected.equals(stored)) return false;
            if (current.staged().contains(expected.assessmentId())) return false;
            Map<UUID, ProjectFileRoleAssessment> byId = new HashMap<>(current.byId());
            byId.put(updated.assessmentId(), updated);
            Map<Fence, UUID> active = new HashMap<>(current.activeByFence());
            if (isActive(expected)) {
                UUID owner = active.get(fence);
                if (!expected.assessmentId().equals(owner)) {
                    throw new IllegalStateException("role assessment fence is inconsistent");
                }
                if (!isActive(updated)) active.remove(fence);
            } else if (isActive(updated)) {
                UUID owner = active.putIfAbsent(fence, updated.assessmentId());
                if (owner != null && !owner.equals(updated.assessmentId())) return false;
            }
            if (state.compareAndSet(current,
                    new State(Map.copyOf(byId), Map.copyOf(active), current.staged()))) return true;
        }
    }

    @Override
    public Optional<ProjectFileRoleAssessment> findById(UUID assessmentId) {
        return Optional.ofNullable(state.get().byId().get(assessmentId));
    }

    @Override
    public Optional<ProjectFileRoleAssessment> findLatestByFileId(UUID fileId) {
        return state.get().byId().values().stream().filter(value -> value.fileId().equals(fileId))
                .max(Comparator.comparingInt(ProjectFileRoleAssessment::parseVersion)
                        .thenComparing(ProjectFileRoleAssessment::createdAt)
                        .thenComparingInt(value -> value.assessmentSource()
                                == com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessmentSource.MACHINE_AI
                                ? 1 : 0)
                        .thenComparing(value -> value.assessmentId().toString()));
    }

    @Override
    public void rollbackUncommittedInsert(ProjectFileRoleAssessment assessment) {
        Fence fence = new Fence(assessment.fileId(), assessment.inputFingerprint());
        while (true) {
            State current = state.get();
            ProjectFileRoleAssessment stored = current.byId().get(assessment.assessmentId());
            if (!assessment.equals(stored) || !current.staged().contains(assessment.assessmentId())
                    || !isActive(assessment)
                    || assessment.executionStatus()
                    != com.fuquan.audit.contract.domain.project.RoleAssessmentExecutionStatus.PENDING) {
                return;
            }
            if (!assessment.assessmentId().equals(current.activeByFence().get(fence))) return;
            Map<UUID, ProjectFileRoleAssessment> byId = new HashMap<>(current.byId());
            byId.remove(assessment.assessmentId());
            Map<Fence, UUID> active = new HashMap<>(current.activeByFence());
            active.remove(fence);
            Set<UUID> staged = new java.util.HashSet<>(current.staged());
            staged.remove(assessment.assessmentId());
            if (state.compareAndSet(current,
                    new State(Map.copyOf(byId), Map.copyOf(active), Set.copyOf(staged)))) return;
        }
    }

    private static ProjectFileRoleAssessment requireSameInput(ProjectFileRoleAssessment winner,
                                                               ProjectFileRoleAssessment attempted) {
        if (!winner.sameInputIdentity(attempted)) {
            throw new IllegalStateException("role assessment input conflict");
        }
        return winner;
    }

    private static boolean isActive(ProjectFileRoleAssessment value) {
        return switch (value.executionStatus()) {
            case PENDING, RUNNING, UNKNOWN -> true;
            case COMPLETED, FAILED -> false;
        };
    }

    private record Fence(UUID fileId, String inputFingerprint) { }
    private record State(Map<UUID, ProjectFileRoleAssessment> byId,
                         Map<Fence, UUID> activeByFence,
                         Set<UUID> staged) {
        private static State empty() {
            return new State(Map.of(), Map.of(), Set.of());
        }
    }

}
