package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class ProjectFileParseResultFactory {
    private ProjectFileParseResultFactory() {}

    public static ProjectFileParseResult currentPipeline(ProjectFile file, ParsedProjectDocument document,
                                                          String engineVersion, int parseVersion,
                                                          UUID supersedesParseResultId, Instant createdAt,
                                                          Instant completedAt) {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(document, "document must not be null");
        ProjectFileParseProvenance p = file.parseProvenance();
        String text = document.fullText();
        return new ProjectFileParseResult(UUID.randomUUID(), file.projectId(), file.id(), file.sha256(), parseVersion,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED, p.method(), p.engine(),
                engineVersion, text, sha256(text), p.pageCount(), p.durationMs(), p.confidence(),
                supersedesParseResultId, createdAt, completedAt, 1);
    }

    public static ProjectFileParseResult legacyImported(ProjectFile file, int parseVersion,
                                                         UUID supersedesParseResultId, Instant createdAt,
                                                         Instant completedAt) {
        Objects.requireNonNull(file, "file must not be null");
        ProjectFileParseProvenance p = file.parseProvenance();
        String text = file.parsedText();
        return new ProjectFileParseResult(UUID.randomUUID(), file.projectId(), file.id(), file.sha256(), parseVersion,
                ProjectFileParseOrigin.LEGACY_IMPORTED, file.parseStatus(), p.method(), p.engine(), null,
                text, text == null ? null : sha256(text), p.pageCount(), p.durationMs(), p.confidence(),
                supersedesParseResultId, createdAt, completedAt, 1);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
