package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ProcurementNotificationPersistenceRow {
    public String id, recipientUserId, projectId, projectName, type, title, content, operatorName, opinion;
    public Integer readFlag;
    public LocalDateTime createdTime, readTime;
}
