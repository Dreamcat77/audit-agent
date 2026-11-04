package com.fuquan.audit.procurement.domain;

import java.time.Instant;
import java.util.UUID;

public record ProcurementNotification(UUID id, String recipientUserId, UUID projectId, String projectName,
                                      String type, String title, String content, String operatorName,
                                      String opinion, boolean read, Instant createdTime, Instant readTime) {}
