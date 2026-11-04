package com.fuquan.audit.common.material.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** DM8 owns acknowledged extraction checkpoints and cumulative call budgets. */
public interface MaterialExtractionLedger {
    record Entry(UUID id,String sourceHash,String policyHash,int unitCount,int nextUnit,int calls,long usedMillis,
                 String status,String token,Instant updatedAt,String resultsJson,String reasonCode,Instant deadlineAt) {
        public Entry(UUID id,String sourceHash,String policyHash,int unitCount,int nextUnit,int calls,long usedMillis,
                String status,String token,Instant updatedAt,String resultsJson,String reasonCode) {
            this(id,sourceHash,policyHash,unitCount,nextUnit,calls,usedMillis,status,token,updatedAt,resultsJson,reasonCode,null);
        }
    }
    Optional<Entry> find(UUID id);
    void insert(Entry entry);
    boolean begin(UUID id,int next,String token,Instant now);
    boolean acknowledge(UUID id,int next,String token,int calls,long elapsed,String results,Instant now);
    void unknown(UUID id,String token,String reason,Instant now);
    void startDeadline(UUID id,Instant deadline);
    /** Fences late callers and removes unpublished facts, while retaining minimal diagnostic metadata. */
    boolean terminate(UUID id,String token,String reason,Instant now);
}
