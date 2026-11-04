package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveSecurityException;
import com.fuquan.audit.contract.application.project.archive.ProjectArchiveStorageKeyFactory;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectArchiveStreamingTest {
    @Test
    void validChineseTextChildWithTruncatedUtf8HeaderIsAllowed() throws Exception {
        TrackingStorage storage = new TrackingStorage();
        ProjectIntakeProperties limits = ProjectIntakeProperties.defaults();
        ProjectArchiveProcessingService service = new ProjectArchiveProcessingService(
                new SelectedProjectArchiveReader(limits), new ProjectFileClassifier(),
                new ProjectFilePolicy(limits, Duration.ofMinutes(15)),
                new ProjectArchiveStorageKeyFactory(), storage);
        byte[] content = "123456789012345会议纪要".getBytes(StandardCharsets.UTF_8);

        ProjectArchiveProcessingService.ArchiveOutcome outcome = service.stageChildren(root(),
                new ByteArrayInputStream(zip("05_补充材料/会议纪要.txt", content)));

        assertThat(storage.staged).singleElement().satisfies(file -> {
            assertThat(file.header()).hasSize(16);
            assertThat(file.header()[15] & 0xff).isEqualTo(0xe4);
        });
        assertThat(outcome.children()).singleElement()
                .extracting(ProjectArchiveProcessingService.StagedChild::normalizedPath)
                .isEqualTo("05_补充材料/会议纪要.txt");
    }

    @Test
    void lateMaliciousEntryDiscardsEveryPreviouslyStagedChild() throws Exception {
        TrackingStorage storage = new TrackingStorage();
        ProjectIntakeProperties limits = ProjectIntakeProperties.defaults();
        ProjectArchiveProcessingService service = new ProjectArchiveProcessingService(
                new SelectedProjectArchiveReader(limits), new ProjectFileClassifier(),
                new ProjectFilePolicy(limits, Duration.ofMinutes(15)),
                new ProjectArchiveStorageKeyFactory(), storage);

        assertThatThrownBy(() -> service.stageChildren(root(), new ByteArrayInputStream(
                zip(List.of("safe.txt", "../late-malicious.txt")))))
                .isInstanceOf(ProjectArchiveSecurityException.class)
                .hasMessageContaining("PATH_TRAVERSAL");

        assertThat(storage.staged).hasSize(1);
        assertThat(storage.discarded).containsExactlyElementsOf(storage.staged);
    }

    private static ProjectFile root() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        return new ProjectFile(UUID.randomUUID(), UUID.randomUUID(), null, null, "intake.zip", "application/zip",
                ProjectFileCategory.PACKAGE, ProjectFileIngestionStatus.UPLOAD_PENDING,
                ProjectFileParseStatus.NOT_APPLICABLE, "1".repeat(64), "2".repeat(64), 100,
                null, null, now, now);
    }

    private static byte[] zip(List<String> names) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("safe".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] zip(String name, byte[] content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static final class TrackingStorage implements FileStoragePort {
        private final List<StagedFile> staged = new ArrayList<>();
        private final List<StagedFile> discarded = new ArrayList<>();
        @Override public StagedFile stage(InputStream input, long limit) {
            try {
                byte[] bytes = input.readAllBytes();
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                StagedFile file = new StagedFile("stage-" + staged.size(), bytes.length, hash,
                        java.util.Arrays.copyOf(bytes, Math.min(16, bytes.length)));
                staged.add(file);
                return file;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public void promote(StagedFile staged, StorageKey key) { throw new UnsupportedOperationException(); }
        @Override public StoredFileContent open(StorageKey key) { throw new UnsupportedOperationException(); }
        @Override public void discard(StagedFile staged) { discarded.add(staged); }
        @Override public StoredFileInspection inspect(StorageKey key) { throw new UnsupportedOperationException(); }
    }
}
