package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class BudgetCategoryRulePersistenceRow {
    private String id;
    private String categoryCode;
    private String categoryName;
    private String allowedKeywords;
    private String forbiddenKeywords;
    private String suggestCategory;
    private String status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getAllowedKeywords() { return allowedKeywords; }
    public void setAllowedKeywords(String allowedKeywords) { this.allowedKeywords = allowedKeywords; }
    public String getForbiddenKeywords() { return forbiddenKeywords; }
    public void setForbiddenKeywords(String forbiddenKeywords) { this.forbiddenKeywords = forbiddenKeywords; }
    public String getSuggestCategory() { return suggestCategory; }
    public void setSuggestCategory(String suggestCategory) { this.suggestCategory = suggestCategory; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}
