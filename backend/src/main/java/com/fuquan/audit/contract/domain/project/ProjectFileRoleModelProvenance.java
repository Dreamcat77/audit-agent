package com.fuquan.audit.contract.domain.project;

public record ProjectFileRoleModelProvenance(
        String provider,
        String modelName,
        String modelRevision,
        String promptVersion,
        String schemaVersion) {

    public ProjectFileRoleModelProvenance {
        provider = required(provider, "provider");
        modelName = required(modelName, "modelName");
        modelRevision = required(modelRevision, "modelRevision");
        promptVersion = required(promptVersion, "promptVersion");
        schemaVersion = required(schemaVersion, "schemaVersion");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
