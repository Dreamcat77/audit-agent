package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ProjectSessionBindingPersistenceRow {
    private String id;
    private String sessionId;
    private String projectId;
    private String projectFileId;
    private String fileSha256;
    private String parseResultId;
    private int parseVersion;
    private String canonicalTextSha256;
    private String effectiveRole;
    private String roleDecisionId;
    private String identityDecisionId;
    private String slotKey;
    private int ordinal;
    private int activeFlag;
    private String boundBy;
    private LocalDateTime boundAt;
    private long selectionVersion;
    private long versionNo;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { projectId = value; }
    public String getProjectFileId() { return projectFileId; }
    public void setProjectFileId(String value) { projectFileId = value; }
    public String getFileSha256() { return fileSha256; }
    public void setFileSha256(String value) { fileSha256 = value; }
    public String getParseResultId() { return parseResultId; }
    public void setParseResultId(String value) { parseResultId = value; }
    public int getParseVersion() { return parseVersion; }
    public void setParseVersion(int value) { parseVersion = value; }
    public String getCanonicalTextSha256() { return canonicalTextSha256; }
    public void setCanonicalTextSha256(String value) { canonicalTextSha256 = value; }
    public String getEffectiveRole() { return effectiveRole; }
    public void setEffectiveRole(String value) { effectiveRole = value; }
    public String getRoleDecisionId() { return roleDecisionId; }
    public void setRoleDecisionId(String value) { roleDecisionId = value; }
    public String getIdentityDecisionId() { return identityDecisionId; }
    public void setIdentityDecisionId(String value) { identityDecisionId = value; }
    public String getSlotKey() { return slotKey; }
    public void setSlotKey(String value) { slotKey = value; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int value) { ordinal = value; }
    public int getActiveFlag() { return activeFlag; }
    public void setActiveFlag(int value) { activeFlag = value; }
    public String getBoundBy() { return boundBy; }
    public void setBoundBy(String value) { boundBy = value; }
    public LocalDateTime getBoundAt() { return boundAt; }
    public void setBoundAt(LocalDateTime value) { boundAt = value; }
    public long getSelectionVersion() { return selectionVersion; }
    public void setSelectionVersion(long value) { selectionVersion = value; }
    public long getVersionNo() { return versionNo; }
    public void setVersionNo(long value) { versionNo = value; }
}
