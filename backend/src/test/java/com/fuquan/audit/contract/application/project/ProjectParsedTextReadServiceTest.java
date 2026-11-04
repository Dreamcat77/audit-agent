package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.port.StoredFileInfo;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectParsedTextReadServiceTest {

    @Test
    void refreshReturnsPersistedTextAfterIntegrityCheckWithoutRerunningParserOrOcr() {
        ProjectFile file = parsedFile();
        FileStoragePort storage = new FileStoragePort() {
            @Override public StagedFile stage(java.io.InputStream input, long limit) { throw new AssertionError(); }
            @Override public void promote(StagedFile staged, StorageKey key) { throw new AssertionError(); }
            @Override public StoredFileContent open(StorageKey key) { throw new AssertionError("must not reparse"); }
            @Override public void discard(StagedFile staged) { throw new AssertionError(); }
            @Override public StoredFileInspection inspect(StorageKey key) {
                return StoredFileInspection.present(new StoredFileInfo(file.sizeBytes(), file.sha256()));
            }
        };

        assertThat(new ProjectParsedTextReadService(storage).read(file)).isEqualTo("persisted OCR text");
    }

    private static ProjectFile parsedFile() {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new ProjectFile(UUID.randomUUID(), UUID.randomUUID(), null, null,
                "scan.pdf", "application/pdf", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                "1".repeat(64), "2".repeat(64), 10, "persisted OCR text", null, now, now,
                new ProjectFileParseProvenance(ProjectFileParseMethod.OCR,
                        "PADDLEOCR_3.7.0", null, 1, 10L));
    }
}
