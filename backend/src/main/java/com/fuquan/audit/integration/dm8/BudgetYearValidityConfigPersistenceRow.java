package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class BudgetYearValidityConfigPersistenceRow {
    private String id;
    private String subjectCode;
    private Integer startYear;
    private Integer endYear;
    private String status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public Integer getStartYear() { return startYear; }
    public void setStartYear(Integer startYear) { this.startYear = startYear; }
    public Integer getEndYear() { return endYear; }
    public void setEndYear(Integer endYear) { this.endYear = endYear; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}
