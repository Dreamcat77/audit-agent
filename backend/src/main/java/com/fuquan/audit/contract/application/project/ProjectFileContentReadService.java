package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.FileStorageException;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectFileContentReadService {
    private final FileStoragePort storage;

    public ProjectFileContentReadService(FileStoragePort storage) { this.storage = storage; }

    public StoredFileContent openVerified(ProjectFile file) {
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE) {
            throw conflict();
        }
        StorageKey key = new StorageKey(file.storageKey());
        try {
            StoredFileInspection inspection = storage.inspect(key);
            if (inspection.status() != StoredFileInspection.Status.PRESENT
                    || inspection.file().size() != file.sizeBytes()
                    || !inspection.file().sha256().equals(file.sha256())) {
                throw conflict();
            }
            StoredFileContent content = storage.open(key);
            if (content.size() != file.sizeBytes()) {
                try { content.inputStream().close(); } catch (java.io.IOException ignored) { }
                throw conflict();
            }
            return content;
        } catch (FileStorageException failure) {
            throw conflict();
        }
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "project file storage integrity is unproven");
    }
}
