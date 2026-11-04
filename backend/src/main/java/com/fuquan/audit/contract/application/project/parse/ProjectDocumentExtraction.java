package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import java.util.Objects;

public record ProjectDocumentExtraction(
        ParsedProjectDocument document,
        ProjectFileParseProvenance provenance) {

    public ProjectDocumentExtraction {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
        if (!provenance.present()) {
            throw new IllegalArgumentException("parse provenance must be present");
        }
    }
}
