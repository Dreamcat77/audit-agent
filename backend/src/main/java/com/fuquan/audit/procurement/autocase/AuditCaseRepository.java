package com.fuquan.audit.procurement.autocase;

import java.time.Instant;
import java.util.*;

public interface AuditCaseRepository {
    Optional<AuditCase> find(UUID id);
    List<AuditCase> recent(String owner);
    List<AuditCaseHistoryEntry> history(String owner,UUID beforeId,int limit);
    List<AuditCase> pending(Instant now);
    void insert(AuditCase value);
    /** Compare version and previous fencing token. False means the caller lost ownership. */
    boolean replace(AuditCase previous,AuditCase next);
}
