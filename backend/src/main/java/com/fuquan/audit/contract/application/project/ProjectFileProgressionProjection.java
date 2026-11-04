package com.fuquan.audit.contract.application.project;

import java.util.List;
import java.util.UUID;

public record ProjectFileProgressionProjection(
        String stage,
        String blocker,
        List<String> availableActions,
        UUID candidateId,
        UUID contractId,
        String recoveryInputFingerprint) {
    public ProjectFileProgressionProjection {
        availableActions = List.copyOf(availableActions);
    }

    public ProjectFileProgressionProjection(String stage, String blocker, List<String> availableActions,
            UUID candidateId, UUID contractId) {
        this(stage, blocker, availableActions, candidateId, contractId, null);
    }
}
