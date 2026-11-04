package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegacyProjectFileParseResultMaterializer {
    private static final int LEGACY_PARSE_VERSION = 1;
    private static final Set<String> OCR_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg");
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parseResults;

    public LegacyProjectFileParseResultMaterializer(
            ProjectFileRepository files, ProjectFileParseResultRepository parseResults) {
        this.files = files;
        this.parseResults = parseResults;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProjectFileParseResult materialize(UUID projectId, UUID fileId) {
        ProjectFile file = files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "project file not found"));
        requireMaterializable(file);

        return parseResults.findByFileIdAndParseVersion(fileId, LEGACY_PARSE_VERSION)
                .map(existing -> requireConsistent(existing, file))
                .orElseGet(() -> insert(file));
    }

    private ProjectFileParseResult insert(ProjectFile file) {
        ProjectFileParseResult proposed = ProjectFileParseResultFactory.legacyImported(
                file, LEGACY_PARSE_VERSION, null, file.createdAt(), file.updatedAt());
        try {
            return requireConsistent(parseResults.insertIfAbsent(proposed), file);
        } catch (IllegalStateException conflict) {
            throw inconsistentResult(conflict);
        }
    }

    private static void requireMaterializable(ProjectFile file) {
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE
                || file.parseStatus() != ProjectFileParseStatus.PARSED) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "project file parse result is unavailable");
        }
        if (file.parsedText() == null || file.parsedText().isBlank()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "persisted project text is unavailable");
        }
        ProjectFileParseProvenance provenance = file.parseProvenance();
        if (!provenance.present() || !supports(provenance.method(), extension(file.originalFilename()))) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "persisted parse provenance is inconsistent");
        }
    }

    private static ProjectFileParseResult requireConsistent(
            ProjectFileParseResult result, ProjectFile file) {
        ProjectFileParseProvenance provenance = file.parseProvenance();
        if (!result.projectId().equals(file.projectId())
                || !result.fileId().equals(file.id())
                || !result.fileSha256().equals(file.sha256())
                || result.parseVersion() != LEGACY_PARSE_VERSION
                || result.parseStatus() != ProjectFileParseStatus.PARSED
                || !Objects.equals(result.canonicalText(), file.parsedText())
                || !Objects.equals(result.canonicalTextSha256(), sha256(file.parsedText()))
                || result.parseMethod() != provenance.method()
                || !Objects.equals(result.engine(), provenance.engine())
                || !Objects.equals(result.pageCount(), provenance.pageCount())
                || !Objects.equals(result.durationMs(), provenance.durationMs())
                || !sameDecimal(result.confidence(), provenance.confidence())) {
            throw inconsistentResult(null);
        }
        return result;
    }

    private static boolean supports(ProjectFileParseMethod method, String extension) {
        return switch (method) {
            case PDF_TEXT -> "pdf".equals(extension);
            case DOCX_TEXT -> "docx".equals(extension);
            case DOC_TEXT -> "doc".equals(extension);
            case OCR -> OCR_EXTENSIONS.contains(extension);
        };
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int separator = filename.lastIndexOf('.');
        return separator < 0 ? "" : filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean sameDecimal(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static BusinessException inconsistentResult(Throwable cause) {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "immutable parse result is inconsistent", cause);
    }
}
