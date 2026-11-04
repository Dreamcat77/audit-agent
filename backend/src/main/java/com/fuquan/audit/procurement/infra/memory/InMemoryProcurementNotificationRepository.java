package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.procurement.application.port.ProcurementNotificationPort;
import com.fuquan.audit.procurement.domain.ProcurementNotification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProcurementNotificationRepository implements ProcurementNotificationPort {
    private final CopyOnWriteArrayList<ProcurementNotification> items = new CopyOnWriteArrayList<>();
    public void save(ProcurementNotification value) { items.add(value); }
    public List<ProcurementNotification> findByRecipient(String userId, boolean unreadOnly) {
        return items.stream().filter(item -> item.recipientUserId().equals(userId))
                .filter(item -> !unreadOnly || !item.read()).sorted((a, b) -> b.createdTime().compareTo(a.createdTime())).toList();
    }
    public void markRead(UUID id, String userId, Instant time) {
        items.replaceAll(item -> item.id().equals(id) && item.recipientUserId().equals(userId)
                ? new ProcurementNotification(item.id(), item.recipientUserId(), item.projectId(), item.projectName(),
                item.type(), item.title(), item.content(), item.operatorName(), item.opinion(), true, item.createdTime(), time)
                : item);
    }
}
