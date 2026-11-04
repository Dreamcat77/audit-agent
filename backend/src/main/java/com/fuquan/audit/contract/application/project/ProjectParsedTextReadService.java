package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectParsedTextReadService {
    private final FileStoragePort storage;

    public ProjectParsedTextReadService(FileStoragePort storage) {
        this.storage = storage;
    }

    public String read(ProjectFile file) {
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE
                || file.parseStatus() != ProjectFileParseStatus.PARSED) {
            return null;
        }
        StorageKey key = new StorageKey(file.storageKey());
        StoredFileInspection inspection = storage.inspect(key);
        if (inspection.status() != StoredFileInspection.Status.PRESENT
                || inspection.file().size() != file.sizeBytes()
                || !inspection.file().sha256().equals(file.sha256())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "project file storage integrity is unproven");
        }
        if (file.parsedText() == null) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "persisted project text is unavailable");
        }
        return file.parsedText();
    }
}
