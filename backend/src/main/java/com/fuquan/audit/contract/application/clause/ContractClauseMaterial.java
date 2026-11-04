package com.fuquan.audit.contract.application.clause;

import java.util.Objects;
import java.util.UUID;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;

public record ContractClauseMaterial(UUID fileId, String fileName, ProjectFileCategory category, String text) {
    public ContractClauseMaterial(UUID fileId, String fileName, String text) {
        this(fileId, fileName, ProjectFileCategory.CONTRACT_BODY, text);
    }

    public ContractClauseMaterial {
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }
}
