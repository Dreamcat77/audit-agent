package com.fuquan.audit.contract.application.debug;

import java.util.*;

/** Explicit run scope, optionally narrowed to one step and one historical attempt. */
public record DebugScope(UUID projectId, UUID contractId, UUID runId, UUID stepId, UUID attemptId) {
    public DebugScope {
        if (projectId == null || contractId == null || runId == null || (attemptId != null && stepId == null))
            throw new IllegalArgumentException("invalid debug scope");
    }
    public Map<String,Object> metadata() {
        Map<String,Object> value = new TreeMap<>();
        value.put("projectId",projectId.toString()); value.put("contractId",contractId.toString());
        value.put("runId",runId.toString());
        if (stepId != null) value.put("stepId",stepId.toString());
        if (attemptId != null) value.put("attemptId",attemptId.toString());
        return Collections.unmodifiableMap(value);
    }
}
