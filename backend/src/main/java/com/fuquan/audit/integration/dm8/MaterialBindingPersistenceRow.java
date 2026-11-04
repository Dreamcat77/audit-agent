package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class MaterialBindingPersistenceRow {
    private String id;
    private String sessionId;
    private String slotKey;
    private String documentKind;
    private String boundDocumentId;
    private int activeFlag;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long versionNo;

    public MaterialBindingPersistenceRow() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSlotKey() { return slotKey; }
    public void setSlotKey(String slotKey) { this.slotKey = slotKey; }
    public String getDocumentKind() { return documentKind; }
    public void setDocumentKind(String documentKind) { this.documentKind = documentKind; }
    public String getBoundDocumentId() { return boundDocumentId; }
    public void setBoundDocumentId(String boundDocumentId) { this.boundDocumentId = boundDocumentId; }
    public int getActiveFlag() { return activeFlag; }
    public void setActiveFlag(int activeFlag) { this.activeFlag = activeFlag; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public long getVersionNo() { return versionNo; }
    public void setVersionNo(long versionNo) { this.versionNo = versionNo; }
}
