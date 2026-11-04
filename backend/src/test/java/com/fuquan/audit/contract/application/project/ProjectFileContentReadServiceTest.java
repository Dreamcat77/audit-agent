package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.FileStorageException;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.port.StoredFileInfo;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectFileContentReadServiceTest {

    @Test
    void verifiedMetadataFollowedByAnOpenFailureStillReturnsStableConflict() {
        ProjectFile file = availableFile();
        FileStoragePort storage = new FileStoragePort() {
            @Override public StagedFile stage(java.io.InputStream input, long limit) { throw new UnsupportedOperationException(); }
            @Override public void promote(StagedFile staged, StorageKey key) { throw new UnsupportedOperationException(); }
            @Override public StoredFileContent open(StorageKey key) { throw new FileStorageException("open failed"); }
            @Override public void discard(StagedFile staged) { throw new UnsupportedOperationException(); }
            @Override public StoredFileInspection inspect(StorageKey key) {
                return StoredFileInspection.present(new StoredFileInfo(file.sizeBytes(), file.sha256()));
            }
        };

        assertThatThrownBy(() -> new ProjectFileContentReadService(storage).openVerified(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("integrity is unproven");
    }

    private static ProjectFile availableFile() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        return new ProjectFile(UUID.randomUUID(), UUID.randomUUID(), null, null,
                "contract.pdf", "application/pdf", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                "1".repeat(64), "2".repeat(64), 10, null, null, now, now);
    }
}
