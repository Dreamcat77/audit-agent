package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class BlacklistRulePersistenceRow {
    private String id;
    private String ruleName;
    private String ruleType;
    private String blacklistTypes;
    private String alertLevel;
    private Boolean autoResolve;
    private Boolean active;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getBlacklistTypes() { return blacklistTypes; }
    public void setBlacklistTypes(String blacklistTypes) { this.blacklistTypes = blacklistTypes; }
    public String getAlertLevel() { return alertLevel; }
    public void setAlertLevel(String alertLevel) { this.alertLevel = alertLevel; }
    public Boolean getAutoResolve() { return autoResolve; }
    public void setAutoResolve(Boolean autoResolve) { this.autoResolve = autoResolve; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}
