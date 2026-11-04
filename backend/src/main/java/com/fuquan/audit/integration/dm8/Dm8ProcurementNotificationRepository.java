package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementNotificationPort;
import com.fuquan.audit.procurement.domain.ProcurementNotification;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementNotificationRepository implements ProcurementNotificationPort {
    private final ProcurementNotificationMapper mapper;
    public Dm8ProcurementNotificationRepository(ProcurementNotificationMapper mapper) { this.mapper = mapper; }
    public void save(ProcurementNotification n) {
        var row = new ProcurementNotificationPersistenceRow(); row.id=n.id().toString(); row.recipientUserId=n.recipientUserId();
        row.projectId=n.projectId().toString(); row.projectName=n.projectName(); row.type=n.type(); row.title=n.title();
        row.content=n.content(); row.operatorName=n.operatorName(); row.opinion=n.opinion(); row.readFlag=n.read()?1:0;
        row.createdTime=local(n.createdTime()); row.readTime=local(n.readTime()); mapper.insert(row);
    }
    public List<ProcurementNotification> findByRecipient(String userId, boolean unreadOnly) {
        return mapper.findByRecipient(userId, unreadOnly).stream().map(r -> new ProcurementNotification(UUID.fromString(r.id),
                r.recipientUserId, UUID.fromString(r.projectId), r.projectName, r.type, r.title, r.content,
                r.operatorName, r.opinion, r.readFlag != null && r.readFlag != 0, instant(r.createdTime), instant(r.readTime))).toList();
    }
    public void markRead(UUID id, String userId, Instant time) { mapper.markRead(id.toString(), userId, local(time)); }
    private static LocalDateTime local(Instant v) { return v == null ? null : LocalDateTime.ofInstant(v, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime v) { return v == null ? null : v.toInstant(ZoneOffset.UTC); }
}
