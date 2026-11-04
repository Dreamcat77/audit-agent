package com.fuquan.audit.procurement.autocase;

import java.time.Instant;
import java.util.*;

/** Bounded history listing never returns facts, source text or complete reports. */
public record AuditCaseHistoryEntry(UUID id, Instant createdAt, String status, List<String> fileNames,String projectName) {
    public AuditCaseHistoryEntry(UUID id,Instant createdAt,String status,List<String> fileNames){this(id,createdAt,status,fileNames,null);}
    public static AuditCaseHistoryEntry from(AuditCase value) {
        return new AuditCaseHistoryEntry(value.id(),value.createdAt(),value.status(),
                value.documents().stream().map(AuditCase.Document::name).toList(),CaseFactPolicy.projectName(value));
    }
}
