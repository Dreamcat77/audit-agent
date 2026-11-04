package com.fuquan.audit.contract.application.analysis;

import java.time.Instant;
import java.util.*;

/** Durable case snapshots; file bytes and canonical text remain owned by Project Intake. */
public interface ContractAnalysisRepository {
    Optional<ContractAnalysisCase> find(UUID id);
    List<ContractAnalysisCase> recent(String owner, int offset, int limit);
    default List<ContractAnalysisCase> recent(String owner, int offset, int limit,
                                              String projectName, Instant from, Instant to) {
        return recent(owner, offset, limit).stream()
                .filter(value -> from == null || !value.createdAt().isBefore(from))
                .filter(value -> to == null || value.createdAt().isBefore(to))
                .toList();
    }
    List<UUID> pending(Instant now, int limit);
    void insert(ContractAnalysisCase value);
    /** Atomic CAS with exact prior version, lease token and live publication lease. */
    boolean replace(ContractAnalysisCase previous, ContractAnalysisCase next, Instant now);
}
