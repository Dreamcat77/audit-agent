package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ReviewDocumentPersistenceRow {
    private String id;
    private String sessionId;
    private String documentKind;
    private String slotKey;
    private String originalFileName;
    private String fileExtension;
    private String contentType;
    private long fileSize;
    private String sha256;
    private String storageKey;
    private String status;
    private String replacesDocumentId;
    private long requestedSessionVersion;
    private long reservedSessionVersion;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime storedAt;
    private LocalDateTime failedAt;
    private long versionNo;

    public ReviewDocumentPersistenceRow() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getDocumentKind() { return documentKind; }
    public void setDocumentKind(String documentKind) { this.documentKind = documentKind; }
    public String getSlotKey() { return slotKey; }
    public void setSlotKey(String slotKey) { this.slotKey = slotKey; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
    public String getFileExtension() { return fileExtension; }
    public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReplacesDocumentId() { return replacesDocumentId; }
    public void setReplacesDocumentId(String replacesDocumentId) { this.replacesDocumentId = replacesDocumentId; }
    public long getRequestedSessionVersion() { return requestedSessionVersion; }
    public void setRequestedSessionVersion(long requestedSessionVersion) { this.requestedSessionVersion = requestedSessionVersion; }
    public long getReservedSessionVersion() { return reservedSessionVersion; }
    public void setReservedSessionVersion(long reservedSessionVersion) { this.reservedSessionVersion = reservedSessionVersion; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getStoredAt() { return storedAt; }
    public void setStoredAt(LocalDateTime storedAt) { this.storedAt = storedAt; }
    public LocalDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(LocalDateTime failedAt) { this.failedAt = failedAt; }
    public long getVersionNo() { return versionNo; }
    public void setVersionNo(long versionNo) { this.versionNo = versionNo; }
}
