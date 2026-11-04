package com.fuquan.audit.contract.application.port;

import com.fuquan.audit.contract.domain.MaterialBinding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 当前材料 Slot 绑定的 CAS persistence boundary. */
public interface MaterialBindingRepository {
    void create(MaterialBinding binding);

    Optional<MaterialBinding> findBySessionAndSlot(UUID sessionId, String slotKey);

    List<MaterialBinding> findBySessionId(UUID sessionId);

    MaterialBinding bind(
            UUID id,
            UUID expectedDocumentId,
            boolean expectedActive,
            long expectedVersion,
            UUID newDocumentId,
            Instant updatedAt);

    MaterialBinding deactivate(
            UUID id,
            UUID expectedDocumentId,
            long expectedVersion,
            Instant updatedAt);
}
