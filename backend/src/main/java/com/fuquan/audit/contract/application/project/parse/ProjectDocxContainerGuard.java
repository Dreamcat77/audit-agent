package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveReader;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveSecurityException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ProjectDocxContainerGuard {
    private final ProjectArchiveReader reader;
    private final long maxBytes;

    public ProjectDocxContainerGuard(ProjectArchiveReader reader, ProjectIntakeProperties properties) {
        this.reader = reader;
        this.maxBytes = properties.maxUploadSize().toBytes();
    }

    public byte[] validate(InputStream input) {
        byte[] bytes = bounded(input);
        try {
            Set<String> names = new java.util.HashSet<>();
            reader.read(new ByteArrayInputStream(bytes), entry -> names.add(entry.normalizedPath()));
            if (!names.contains("[Content_Types].xml") || !names.contains("word/document.xml")) {
                throw new ProjectParseException("DOCX_REQUIRED_PART_MISSING");
            }
            return bytes;
        } catch (ProjectArchiveSecurityException exception) {
            throw new ProjectParseException("DOCX_" + exception.reasonCode(), exception);
        }
    }

    private byte[] bounded(InputStream input) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; long count = 0;
            for (int read; (read = input.read(buffer)) >= 0; ) {
                count += read; if (count > maxBytes) throw new ProjectParseException("DOCX_SIZE_LIMIT");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) { throw new ProjectParseException("DOCX_READ_FAILED", exception); }
    }
}
