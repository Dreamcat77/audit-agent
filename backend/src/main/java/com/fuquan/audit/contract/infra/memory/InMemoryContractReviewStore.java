package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.port.ContractReviewConcurrencyException;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.contract.domain.MaterialBinding;
import com.fuquan.audit.contract.domain.ReviewDocument;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** local profile 中模拟数据库唯一约束和显式 CAS 的共享内存适配器。 */
@Component
@Profile("local")
public class InMemoryContractReviewStore {

    private final Map<UUID, ContractReviewSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, ReviewDocument> documents = new ConcurrentHashMap<>();
    private final Map<UUID, MaterialBinding> bindings = new ConcurrentHashMap<>();

    public synchronized void createSession(ContractReviewSession session) {
        if (sessions.putIfAbsent(session.id(), session) != null) {
            throw new ContractReviewConcurrencyException("review session already exists");
        }
    }

    public Optional<ContractReviewSession> findSession(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public List<ContractReviewSession> findSessionsByContract(UUID contractId) {
        return sessions.values().stream()
                .filter(session -> session.contractId().equals(contractId))
                .filter(session -> !session.deleted())
                .sorted(Comparator.comparing(ContractReviewSession::createdAt).reversed()
                        .thenComparing(ContractReviewSession::id, Comparator.reverseOrder()))
                .toList();
    }

    public synchronized ContractReviewSession advancePreparingVersion(
            UUID id, long expectedVersion, Instant updatedAt) {
        ContractReviewSession current = requireSession(id);
        if (current.versionNo() != expectedVersion) {
            throw conflict("review session version conflict");
        }
        ContractReviewSession updated;
        try {
            updated = current.advanceVersion(updatedAt);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        sessions.put(id, updated);
        return updated;
    }

    public synchronized ContractReviewSession confirmMaterials(
            UUID id, long expectedVersion, Instant confirmedAt) {
        ContractReviewSession current = requireSession(id);
        if (current.versionNo() != expectedVersion) {
            throw conflict("review session version conflict");
        }
        ContractReviewSession updated;
        try {
            updated = current.confirmMaterials(confirmedAt);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        sessions.put(id, updated);
        return updated;
    }

    public synchronized ContractReviewSession softDelete(
            UUID id, long expectedVersion, String deletedBy, Instant deletedAt) {
        ContractReviewSession current = requireSession(id);
        if (current.versionNo() != expectedVersion) {
            throw conflict("review session version conflict");
        }
        ContractReviewSession updated;
        try {
            updated = current.softDelete(deletedBy, deletedAt);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        sessions.put(id, updated);
        return updated;
    }

    public List<ContractReviewSession> activeSessions() {
        return sessions.values().stream().filter(session -> !session.deleted()).toList();
    }

    public synchronized void createDocument(ReviewDocument document) {
        if (documents.containsKey(document.id())
                || documents.values().stream().anyMatch(existing ->
                        existing.storageKey().equals(document.storageKey()))) {
            throw conflict("review document identity already exists");
        }
        documents.put(document.id(), document);
    }

    public Optional<ReviewDocument> findDocument(UUID id) {
        return Optional.ofNullable(documents.get(id));
    }

    public Optional<ReviewDocument> findDocumentBySessionAndId(UUID sessionId, UUID id) {
        return findDocument(id).filter(document -> document.sessionId().equals(sessionId));
    }

    public List<ReviewDocument> findDocumentsBySession(UUID sessionId) {
        return documents.values().stream()
                .filter(document -> document.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(ReviewDocument::createdAt)
                        .thenComparing(ReviewDocument::id))
                .toList();
    }

    public List<ReviewDocument> findPendingBySessionId(UUID sessionId) {
        return findDocumentsBySession(sessionId).stream()
                .filter(document -> document.status()
                        == com.fuquan.audit.contract.domain.ReviewDocumentStatus.UPLOAD_PENDING)
                .toList();
    }

    public synchronized ReviewDocument markAvailable(UUID id, long expectedVersion, Instant storedAt) {
        ReviewDocument current = requireDocument(id);
        if (current.versionNo() != expectedVersion) {
            throw conflict("review document version conflict");
        }
        ReviewDocument updated;
        try {
            updated = current.markAvailable(storedAt);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        documents.put(id, updated);
        return updated;
    }

    public synchronized ReviewDocument markFailed(UUID id, long expectedVersion, Instant failedAt) {
        ReviewDocument current = requireDocument(id);
        if (current.versionNo() != expectedVersion) {
            throw conflict("review document version conflict");
        }
        ReviewDocument updated;
        try {
            updated = current.markFailed(failedAt);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        documents.put(id, updated);
        return updated;
    }

    public synchronized void createBinding(MaterialBinding binding) {
        boolean duplicateSlot = bindings.values().stream().anyMatch(existing ->
                existing.sessionId().equals(binding.sessionId())
                        && existing.slotKey().equals(binding.slotKey()));
        if (bindings.containsKey(binding.id()) || duplicateSlot) {
            throw conflict("material binding slot already exists");
        }
        bindings.put(binding.id(), binding);
    }

    public Optional<MaterialBinding> findBySessionAndSlot(UUID sessionId, String slotKey) {
        return bindings.values().stream()
                .filter(binding -> binding.sessionId().equals(sessionId)
                        && binding.slotKey().equals(slotKey))
                .findFirst();
    }

    public List<MaterialBinding> findBindingsBySession(UUID sessionId) {
        return bindings.values().stream()
                .filter(binding -> binding.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(MaterialBinding::createdAt)
                        .thenComparing(MaterialBinding::id))
                .toList();
    }

    public synchronized MaterialBinding bind(
            UUID id,
            UUID expectedDocumentId,
            boolean expectedActive,
            long expectedVersion,
            UUID newDocumentId,
            Instant updatedAt) {
        MaterialBinding current = requireBinding(id);
        if (!current.boundDocumentId().equals(expectedDocumentId)
                || current.active() != expectedActive
                || current.versionNo() != expectedVersion) {
            throw conflict("material binding version conflict");
        }
        ReviewDocument target = requireDocument(newDocumentId);
        MaterialBinding updated = current.bind(target, updatedAt);
        bindings.put(id, updated);
        return updated;
    }

    public synchronized MaterialBinding deactivate(
            UUID id, UUID expectedDocumentId, long expectedVersion, Instant updatedAt) {
        MaterialBinding current = requireBinding(id);
        if (!current.boundDocumentId().equals(expectedDocumentId)
                || !current.active()
                || current.versionNo() != expectedVersion) {
            throw conflict("material binding version conflict");
        }
        MaterialBinding updated;
        try {
            updated = current.deactivate(updatedAt);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        bindings.put(id, updated);
        return updated;
    }

    /** 仅供 local contract tests 清理隔离状态。 */
    public synchronized void clear() {
        sessions.clear();
        documents.clear();
        bindings.clear();
    }

    private ContractReviewSession requireSession(UUID id) {
        ContractReviewSession value = sessions.get(id);
        if (value == null) {
            throw conflict("review session is missing");
        }
        return value;
    }

    private ReviewDocument requireDocument(UUID id) {
        ReviewDocument value = documents.get(id);
        if (value == null) {
            throw conflict("review document is missing");
        }
        return value;
    }

    private MaterialBinding requireBinding(UUID id) {
        MaterialBinding value = bindings.get(id);
        if (value == null) {
            throw conflict("material binding is missing");
        }
        return value;
    }

    private ContractReviewConcurrencyException conflict(String message) {
        return new ContractReviewConcurrencyException(message);
    }
}
