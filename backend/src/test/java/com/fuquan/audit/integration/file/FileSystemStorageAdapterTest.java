package com.fuquan.audit.integration.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fuquan.audit.contract.application.port.FileStorageContentConflictException;
import com.fuquan.audit.contract.application.port.FileStorageException;
import com.fuquan.audit.contract.application.port.FileStorageUserRejectionException;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StorageFailureReason;
import com.fuquan.audit.contract.application.port.StoredFileInfo;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemStorageAdapterTest {
    @TempDir
    Path tempDir;

    @Test
    void stagesFortyMiBAsStreamWithoutSourceSizedByteArray() {
        FileSystemStorageAdapter adapter = adapter(tempDir.resolve("large"));
        long size = 40L * 1024 * 1024;

        StagedFile staged = adapter.stage(new RepeatingInputStream(size), 50L * 1024 * 1024);

        assertThat(staged.size()).isEqualTo(size);
        assertThat(staged.sha256()).hasSize(64);
        assertThat(staged.header()).hasSize(16);
        adapter.discard(staged);
    }

    @Test
    void publishAbsentUsesHardLinkAndInspectionIsPresent() throws Exception {
        Path root = tempDir.resolve("absent");
        FileSystemStorageAdapter adapter = adapter(root);
        byte[] fixture = "hello".getBytes(StandardCharsets.UTF_8);
        StagedFile staged = adapter.stage(new ByteArrayInputStream(fixture), 100);
        Path stagingName = root.resolve(".staging").resolve(staged.stagingId());
        StorageKey key = new StorageKey("a".repeat(64));

        adapter.promote(staged, key);

        assertThat(stagingName).doesNotExist();
        assertThat(staged.sha256())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        StoredFileInspection inspection = adapter.inspect(key);
        assertThat(inspection.status()).isEqualTo(StoredFileInspection.Status.PRESENT);
        assertThat(inspection.file()).satisfies(info -> {
            assertThat(info.size()).isEqualTo(5);
            assertThat(info.sha256()).isEqualTo(staged.sha256());
        });
        try (InputStream input = adapter.open(key).inputStream()) {
            assertThat(input.readNBytes(5)).isEqualTo(fixture);
        }
    }

    @Test
    void deleteRemovesPromotedObjectForFailedMetadataCompensation() {
        FileSystemStorageAdapter adapter = adapter(tempDir.resolve("delete"));
        StorageKey key = new StorageKey("f".repeat(64));
        adapter.promote(adapter.stage(new ByteArrayInputStream(new byte[] {1, 2, 3}), 10), key);

        adapter.delete(key);

        assertThat(adapter.inspect(key).status())
                .isEqualTo(StoredFileInspection.Status.DEFINITELY_MISSING);
    }

    @Test
    void rejectsEmptyAndOversizedAsUserFileRejections() {
        FileSystemStorageAdapter adapter = adapter(tempDir.resolve("rejections"));

        assertThatThrownBy(() -> adapter.stage(new ByteArrayInputStream(new byte[0]), 10))
                .isInstanceOf(FileStorageUserRejectionException.class)
                .extracting(exception -> ((FileStorageUserRejectionException) exception).reason())
                .isEqualTo(FileStorageUserRejectionException.Reason.EMPTY);
        assertThatThrownBy(() -> adapter.stage(new ByteArrayInputStream(new byte[11]), 10))
                .isInstanceOf(FileStorageUserRejectionException.class)
                .extracting(exception -> ((FileStorageUserRejectionException) exception).reason())
                .isEqualTo(FileStorageUserRejectionException.Reason.SIZE_LIMIT);
    }

    @Test
    void existingSameBytesIsReplayAndDifferentBytesIsConflictWithoutMutation() throws Exception {
        FileSystemStorageAdapter adapter = adapter(tempDir.resolve("existing"));
        StorageKey key = new StorageKey("b".repeat(64));
        byte[] original = new byte[] {1, 2, 3};
        adapter.promote(adapter.stage(new ByteArrayInputStream(original), 10), key);

        StagedFile replay = adapter.stage(new ByteArrayInputStream(original), 10);
        adapter.promote(replay, key);
        assertThat(tempDir.resolve("existing/.staging").resolve(replay.stagingId())).doesNotExist();

        StagedFile conflict = adapter.stage(new ByteArrayInputStream(new byte[] {9, 8, 7}), 10);
        assertThatThrownBy(() -> adapter.promote(conflict, key))
                .isInstanceOf(FileStorageContentConflictException.class);
        adapter.discard(conflict);
        try (InputStream input = adapter.open(key).inputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(original);
        }
    }

    @Test
    void concurrentTargetCreationAfterPreliminaryChecksCannotBeOverwritten() throws Exception {
        byte[] competitor = "competitor-wins".getBytes(StandardCharsets.US_ASCII);
        FileSystemStorageAdapter.CreateOnlyPublisher racingPublisher = (source, target) -> {
            if (!target.getFileName().toString().startsWith(".create-only-probe-")) {
                Files.write(target, competitor);
            }
            Files.createLink(target, source);
        };
        FileSystemStorageAdapter adapter = new FileSystemStorageAdapter(
                tempDir.resolve("race"), racingPublisher);
        StorageKey key = new StorageKey("c".repeat(64));
        StagedFile staged = adapter.stage(
                new ByteArrayInputStream("request-bytes".getBytes(StandardCharsets.US_ASCII)), 100);

        assertThatThrownBy(() -> adapter.promote(staged, key))
                .isInstanceOf(FileStorageContentConflictException.class);

        try (InputStream input = adapter.open(key).inputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(competitor);
        }
        adapter.discard(staged);
    }

    @Test
    void unsupportedPublicationPrimitiveFailsClosedDuringCapabilityProbe() {
        assertThatThrownBy(() -> new FileSystemStorageAdapter(
                tempDir.resolve("unsupported"),
                (source, target) -> { throw new UnsupportedOperationException("hard links unavailable"); }))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("does not support create-only publication")
                .extracting(exception -> ((FileStorageException) exception).reason())
                .isEqualTo(StorageFailureReason.PUBLICATION_UNSUPPORTED);
    }

    @Test
    void overwriteCapablePrimitiveFailsClosedDuringCapabilityProbe() {
        assertThatThrownBy(() -> new FileSystemStorageAdapter(
                tempDir.resolve("overwrite"),
                (source, target) -> Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("cannot preserve an existing target");
    }

    @Test
    void differentFileStoresFailClosedBeforePublicationProbe() {
        FileStore stagingStore = mock(FileStore.class);
        FileStore objectsStore = mock(FileStore.class);

        assertThatThrownBy(() -> new FileSystemStorageAdapter(
                tempDir.resolve("different-store"),
                (source, target) -> Files.createLink(target, source),
                Duration.ofHours(1),
                path -> new StoredFileInfo(0, "0".repeat(64)),
                path -> path.getFileName().toString().equals(".staging")
                        ? stagingStore : objectsStore))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("one FileStore");
    }

    @Test
    void inspectionDistinguishesPresentDefiniteMissingAndUnknownFailures() {
        StorageKey key = new StorageKey("d".repeat(64));
        FileSystemStorageAdapter actual = adapter(tempDir.resolve("inspection-actual"));
        assertThat(actual.inspect(key).status())
                .isEqualTo(StoredFileInspection.Status.DEFINITELY_MISSING);
        actual.promote(actual.stage(new ByteArrayInputStream(new byte[] {1}), 10), key);
        assertThat(actual.inspect(key).status()).isEqualTo(StoredFileInspection.Status.PRESENT);

        assertThat(adapterWithReader(
                tempDir.resolve("inspection-missing"),
                path -> { throw new NoSuchFileException("opaque"); }).inspect(key).status())
                .isEqualTo(StoredFileInspection.Status.DEFINITELY_MISSING);
        StoredFileInspection denied = adapterWithReader(
                tempDir.resolve("inspection-denied"),
                path -> { throw new AccessDeniedException("opaque"); }).inspect(key);
        assertThat(denied.status()).isEqualTo(StoredFileInspection.Status.UNKNOWN);
        assertThat(denied.safeReason()).isEqualTo(StorageFailureReason.ACCESS_DENIED);

        StoredFileInspection filesystem = adapterWithReader(
                tempDir.resolve("inspection-filesystem"),
                path -> { throw new FileSystemException("opaque"); }).inspect(key);
        assertThat(filesystem.status()).isEqualTo(StoredFileInspection.Status.UNKNOWN);
        assertThat(filesystem.safeReason()).isEqualTo(StorageFailureReason.FILESYSTEM_ERROR);

        StoredFileInspection io = adapterWithReader(
                tempDir.resolve("inspection-io"),
                path -> { throw new IOException("opaque"); }).inspect(key);
        assertThat(io.status()).isEqualTo(StoredFileInspection.Status.UNKNOWN);
        assertThat(io.safeReason()).isEqualTo(StorageFailureReason.IO_ERROR);

        StoredFileInspection provider = adapterWithReader(
                tempDir.resolve("inspection-provider"),
                path -> { throw new IllegalStateException("opaque"); }).inspect(key);
        assertThat(provider.status()).isEqualTo(StoredFileInspection.Status.UNKNOWN);
        assertThat(provider.safeReason()).isEqualTo(StorageFailureReason.PROVIDER_ERROR);
    }

    @Test
    void startupCleanupRemovesOnlyExpiredStagingNameAndPreservesStableObject() throws Exception {
        Path root = tempDir.resolve("retention");
        FileSystemStorageAdapter first = new FileSystemStorageAdapter(
                root, (source, target) -> Files.createLink(target, source), Duration.ofHours(1));
        StorageKey stableKey = new StorageKey("e".repeat(64));
        first.promote(first.stage(new ByteArrayInputStream(new byte[] {7}), 10), stableKey);
        StagedFile expired = first.stage(new ByteArrayInputStream(new byte[] {1}), 10);
        StagedFile fresh = first.stage(new ByteArrayInputStream(new byte[] {2}), 10);
        Path expiredPath = root.resolve(".staging").resolve(expired.stagingId());
        Path freshPath = root.resolve(".staging").resolve(fresh.stagingId());
        Files.setLastModifiedTime(expiredPath, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        FileSystemStorageAdapter restarted = new FileSystemStorageAdapter(
                root, (source, target) -> Files.createLink(target, source), Duration.ofHours(1));

        assertThat(expiredPath).doesNotExist();
        assertThat(freshPath).exists();
        assertThat(restarted.inspect(stableKey).status()).isEqualTo(StoredFileInspection.Status.PRESENT);
    }

    @Test
    void opaqueHandlesRejectPathTraversal() {
        assertThatThrownBy(() -> new StorageKey("../../evil.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FileSystemStorageAdapter adapter(Path root) {
        return new FileSystemStorageAdapter(root, (source, target) -> Files.createLink(target, source));
    }

    private FileSystemStorageAdapter adapterWithReader(
            Path root, FileSystemStorageAdapter.StoredFileReader reader) {
        return new FileSystemStorageAdapter(
                root,
                (source, target) -> Files.createLink(target, source),
                Duration.ofHours(1),
                reader,
                Files::getFileStore);
    }

    private static final class RepeatingInputStream extends InputStream {
        private long remaining;

        private RepeatingInputStream(long remaining) {
            this.remaining = remaining;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 'x';
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(remaining, length);
            java.util.Arrays.fill(target, offset, offset + count, (byte) 'x');
            remaining -= count;
            return count;
        }
    }
}
