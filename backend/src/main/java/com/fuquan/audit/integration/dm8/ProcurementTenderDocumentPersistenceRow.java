package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ProcurementTenderDocumentPersistenceRow {
    private String id;
    private String projectId;
    private String fileName;
    private String fileExtension;
    private String contentType;
    private Long fileSize;
    private String sha256;
    private String storageKey;
    private String status;
    private String parseMethod;
    private String parseEngine;
    private Double parseConfidence;
    private Integer pageCount;
    private String parsedContentJson;
    private String parseError;
    private String createdBy;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { projectId = value; }
    public String getFileName() { return fileName; }
    public void setFileName(String value) { fileName = value; }
    public String getFileExtension() { return fileExtension; }
    public void setFileExtension(String value) { fileExtension = value; }
    public String getContentType() { return contentType; }
    public void setContentType(String value) { contentType = value; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long value) { fileSize = value; }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256 = value; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String value) { storageKey = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getParseMethod() { return parseMethod; }
    public void setParseMethod(String value) { parseMethod = value; }
    public String getParseEngine() { return parseEngine; }
    public void setParseEngine(String value) { parseEngine = value; }
    public Double getParseConfidence() { return parseConfidence; }
    public void setParseConfidence(Double value) { parseConfidence = value; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer value) { pageCount = value; }
    public String getParsedContentJson() { return parsedContentJson; }
    public void setParsedContentJson(String value) { parsedContentJson = value; }
    public String getParseError() { return parseError; }
    public void setParseError(String value) { parseError = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime value) { createdTime = value; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime value) { updatedTime = value; }
}
