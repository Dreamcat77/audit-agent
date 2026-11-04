package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveReader;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveStorageKeyFactory;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ProjectArchiveProcessingService {
    private final ProjectArchiveReader reader;
    private final ProjectFileClassifier classifier;
    private final ProjectFilePolicy policy;
    private final ProjectArchiveStorageKeyFactory keys;
    private final FileStoragePort storage;

    public ProjectArchiveProcessingService(ProjectArchiveReader reader, ProjectFileClassifier classifier,
                                           ProjectFilePolicy policy, ProjectArchiveStorageKeyFactory keys,
                                           FileStoragePort storage) {
        this.reader = reader; this.classifier = classifier; this.policy = policy; this.keys = keys; this.storage = storage;
    }

    public ArchiveOutcome stageChildren(ProjectFile root, InputStream archiveInput) {
        List<StagedChild> staged = new ArrayList<>();
        try {
            reader.read(archiveInput, entry -> {
                if (entry.declaredSize() == 0) return;
                String mediaType = mediaType(entry.normalizedPath());
                StagedFile stagedFile = storage.stage(entry.content(),
                        policy.properties().maxEntrySize().toBytes());
                staged.add(new StagedChild(entry.normalizedPath(), classifier.classify(entry.normalizedPath()),
                        mediaType, stagedFile, keys.create(root.id(), entry.normalizedPath(), stagedFile.sha256())));
                if (policy.decide(entry.normalizedPath(), mediaType, true, stagedFile.size(), stagedFile.header())
                        == ProjectFilePolicy.Decision.REJECTED) {
                    throw new IllegalArgumentException("ARCHIVE_CHILD_REJECTED");
                }
            });
            if (staged.isEmpty()) throw new IllegalArgumentException("ARCHIVE_HAS_NO_FILES");
            return new ArchiveOutcome(staged);
        } catch (RuntimeException exception) {
            staged.forEach(child -> storage.discard(child.stagedFile()));
            throw exception;
        }
    }

    private static String mediaType(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        if (value.endsWith(".pdf")) return "application/pdf";
        if (value.endsWith(".doc")) return "application/msword";
        if (value.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (value.endsWith(".zip")) return "application/zip";
        if (value.endsWith(".png")) return "image/png";
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    public record ArchiveOutcome(List<StagedChild> children) {
        public ArchiveOutcome { children = List.copyOf(children); }
    }
    public record StagedChild(String normalizedPath, ProjectFileCategory category, String mediaType,
                              StagedFile stagedFile, StorageKey storageKey) {
    }
}
