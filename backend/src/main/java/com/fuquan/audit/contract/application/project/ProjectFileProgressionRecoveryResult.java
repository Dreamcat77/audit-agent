package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.util.Objects;

public record ProjectFileProgressionRecoveryResult(ProjectFile file, boolean replayed) {
    public ProjectFileProgressionRecoveryResult {
        Objects.requireNonNull(file, "file must not be null");
    }
}
