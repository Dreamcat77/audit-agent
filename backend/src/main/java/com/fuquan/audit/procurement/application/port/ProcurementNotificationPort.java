package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementNotification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProcurementNotificationPort {
    void save(ProcurementNotification notification);
    List<ProcurementNotification> findByRecipient(String recipientUserId, boolean unreadOnly);
    void markRead(UUID id, String recipientUserId, Instant readTime);
}
