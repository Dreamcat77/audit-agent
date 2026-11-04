package com.fuquan.audit.integration.file;

import com.fuquan.audit.contract.application.port.FileStorageContentConflictException;
import com.fuquan.audit.contract.application.port.FileStorageException;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.FileStorageUserRejectionException;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StorageFailureReason;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.port.StoredFileInfo;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Controlled-root streaming storage with same-FileStore create-only hard-link publication. */
@Component
public class FileSystemStorageAdapter implements FileStoragePort {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int HEADER_SIZE = 16;
    private static final Pattern STAGING_ID = Pattern.compile("^[0-9a-f-]{36}\\.part$");

    private final Path root;
    private final Path stagingRoot;
    private final Path objectsRoot;
    private final CreateOnlyPublisher publisher;
    private final StoredFileReader storedFileReader;
    private final FileStoreLookup fileStoreLookup;
    private final Duration stagingRetentionAge;

    @Autowired
    public FileSystemStorageAdapter(FileStorageProperties properties) {
        this(
                Path.of(properties.getRoot()),
                (source, target) -> Files.createLink(target, source),
                properties.getStagingRetentionAge(),
                FileSystemStorageAdapter::inspectRegularFile,
                Files::getFileStore);
    }

    /** Explicit test seam; production uses Files.createLink with the same capability probe. */
    public FileSystemStorageAdapter(Path configuredRoot, CreateOnlyPublisher publisher) {
        this(configuredRoot, publisher, Duration.ofHours(24));
    }

    /** Test and controlled-assembly seam; cleanup is restricted to private staging names. */
    public FileSystemStorageAdapter(
            Path configuredRoot, CreateOnlyPublisher publisher, Duration stagingRetentionAge) {
        this(
                configuredRoot,
                publisher,
                stagingRetentionAge,
                FileSystemStorageAdapter::inspectRegularFile,
                Files::getFileStore);
    }

    FileSystemStorageAdapter(
            Path configuredRoot,
            CreateOnlyPublisher publisher,
            Duration stagingRetentionAge,
            StoredFileReader storedFileReader,
            FileStoreLookup fileStoreLookup) {
        this.root = configuredRoot.toAbsolutePath().normalize();
        this.stagingRoot = confined(root.resolve(".staging"), root);
        this.objectsRoot = confined(root.resolve("objects"), root);
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.storedFileReader = Objects.requireNonNull(
                storedFileReader, "stored file reader must not be null");
        this.fileStoreLookup = Objects.requireNonNull(
                fileStoreLookup, "FileStore lookup must not be null");
        if (stagingRetentionAge == null
                || stagingRetentionAge.isZero()
                || stagingRetentionAge.isNegative()) {
            throw new FileStorageException("staging retention age must be positive");
        }
        this.stagingRetentionAge = stagingRetentionAge;
        initialize();
    }

    @Override
    public StagedFile stage(InputStream inputStream, long sizeLimit) {
        if (inputStream == null || sizeLimit <= 0) {
            throw new IllegalArgumentException("input stream and positive size limit are required");
        }
        String stagingId = UUID.randomUUID() + ".part";
        Path target = stagingPath(stagingId);
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        byte[] header = new byte[HEADER_SIZE];
        int headerLength = 0;
        long size = 0;
        try (OutputStream output = Files.newOutputStream(
                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                if (size > sizeLimit - read) {
                    throw new FileStorageUserRejectionException(
                            FileStorageUserRejectionException.Reason.SIZE_LIMIT,
                            "file exceeds configured size limit");
                }
                int copy = Math.min(read, HEADER_SIZE - headerLength);
                if (copy > 0) {
                    System.arraycopy(buffer, 0, header, headerLength, copy);
                    headerLength += copy;
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (FileStorageUserRejectionException exception) {
            deleteQuietly(target);
            throw exception;
        } catch (FileStorageException exception) {
            deleteQuietly(target);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(target);
            throw storageFailure("file staging failed", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw providerFailure("file staging failed", exception);
        }
        if (size == 0) {
            deleteQuietly(target);
            throw new FileStorageUserRejectionException(
                    FileStorageUserRejectionException.Reason.EMPTY,
                    "empty file is not allowed");
        }
        return new StagedFile(
                stagingId,
                size,
                HexFormat.of().formatHex(digest.digest()),
                Arrays.copyOf(header, headerLength));
    }

    @Override
    public void promote(StagedFile stagedFile, StorageKey storageKey) {
        Path source = stagingPath(stagedFile.stagingId());
        Path target = objectPath(storageKey);
        try {
            Files.createDirectories(target.getParent());
            assertConfined(target.getParent(), objectsRoot);
            try {
                publisher.publish(source, target);
            } catch (FileAlreadyExistsException race) {
                verifyExisting(target, stagedFile);
                discard(stagedFile);
                return;
            }
            // Publication succeeded. A private-name cleanup failure is recoverable by retention
            // cleanup and must not turn an immutable stable object into a failed operation.
            deleteQuietly(source);
        } catch (UnsupportedOperationException exception) {
            throw new FileStorageException(
                    StorageFailureReason.PUBLICATION_UNSUPPORTED,
                    "create-only publication is not supported",
                    exception);
        } catch (FileStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageFailure("create-only file publication failed", exception);
        } catch (RuntimeException exception) {
            throw providerFailure("create-only file publication failed", exception);
        }
    }

    @Override
    public StoredFileContent open(StorageKey storageKey) {
        Path path = objectPath(storageKey);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileStorageException("stored object is missing");
            }
            return new StoredFileContent(
                    Files.newInputStream(path, StandardOpenOption.READ), Files.size(path));
        } catch (FileStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageFailure("stored object cannot be opened", exception);
        } catch (RuntimeException exception) {
            throw providerFailure("stored object cannot be opened", exception);
        }
    }

    @Override
    public void delete(StorageKey storageKey) {
        Path path = objectPath(storageKey);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw storageFailure("stored object cannot be deleted", exception);
        } catch (RuntimeException exception) {
            throw providerFailure("stored object cannot be deleted", exception);
        }
    }

    @Override
    public void discard(StagedFile stagedFile) {
        deleteQuietly(stagingPath(stagedFile.stagingId()));
    }

    @Override
    public StoredFileInspection inspect(StorageKey storageKey) {
        Path path = objectPath(storageKey);
        try {
            return StoredFileInspection.present(storedFileReader.inspect(path));
        } catch (NoSuchFileException exception) {
            return StoredFileInspection.definitelyMissing();
        } catch (AccessDeniedException exception) {
            return StoredFileInspection.unknown(StorageFailureReason.ACCESS_DENIED);
        } catch (FileSystemException exception) {
            return StoredFileInspection.unknown(StorageFailureReason.FILESYSTEM_ERROR);
        } catch (IOException exception) {
            return StoredFileInspection.unknown(StorageFailureReason.IO_ERROR);
        } catch (RuntimeException exception) {
            return StoredFileInspection.unknown(StorageFailureReason.PROVIDER_ERROR);
        }
    }

    private void initialize() {
        try {
            Files.createDirectories(stagingRoot);
            Files.createDirectories(objectsRoot);
            if (!root.isAbsolute() || !Files.isDirectory(root) || !Files.isWritable(root)) {
                throw new FileStorageException(
                        StorageFailureReason.ACCESS_DENIED,
                        "configured storage root is not writable");
            }
            FileStore stagingStore = fileStoreLookup.get(stagingRoot);
            FileStore objectStore = fileStoreLookup.get(objectsRoot);
            if (!stagingStore.equals(objectStore)) {
                throw new FileStorageException(
                        StorageFailureReason.PUBLICATION_UNSUPPORTED,
                        "staging and object roots must share one FileStore");
            }
            verifyCreateOnlyPublicationCapability();
            cleanupStagingOlderThan(Instant.now().minus(stagingRetentionAge));
        } catch (FileStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageFailure("file storage initialization failed", exception);
        } catch (RuntimeException exception) {
            throw providerFailure("file storage initialization failed", exception);
        }
    }

    void cleanupStagingOlderThan(Instant cutoff) throws IOException {
        try (var paths = Files.list(stagingRoot)) {
            for (Path candidate : paths.toList()) {
                Path confined = candidate.toAbsolutePath().normalize();
                assertConfined(confined, stagingRoot);
                String name = confined.getFileName().toString();
                if (STAGING_ID.matcher(name).matches()
                        && Files.isRegularFile(confined, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(confined, LinkOption.NOFOLLOW_LINKS)
                                .toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(confined);
                }
            }
        }
    }

    private void verifyCreateOnlyPublicationCapability() throws IOException {
        byte[] original = "create-only-original".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] replacement = "create-only-replacement".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Path firstSource = Files.createTempFile(stagingRoot, "create-only-probe-", ".tmp");
        Path secondSource = Files.createTempFile(stagingRoot, "create-only-probe-", ".tmp");
        Path target = objectsRoot.resolve(".create-only-probe-" + UUID.randomUUID()).normalize();
        assertConfined(target, objectsRoot);
        try {
            Files.write(firstSource, original, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(secondSource, replacement, StandardOpenOption.TRUNCATE_EXISTING);
            publisher.publish(firstSource, target);
            if (!Arrays.equals(original, Files.readAllBytes(target))) {
                throw new FileStorageException(
                        StorageFailureReason.PUBLICATION_UNSUPPORTED,
                        "create-only publication capability verification failed");
            }
            boolean rejectedExisting = false;
            try {
                publisher.publish(secondSource, target);
            } catch (FileAlreadyExistsException expected) {
                rejectedExisting = true;
            }
            if (!rejectedExisting || !Arrays.equals(original, Files.readAllBytes(target))) {
                throw new FileStorageException(
                        StorageFailureReason.PUBLICATION_UNSUPPORTED,
                        "create-only publication cannot preserve an existing target");
            }
        } catch (UnsupportedOperationException exception) {
            throw new FileStorageException(
                    StorageFailureReason.PUBLICATION_UNSUPPORTED,
                    "configured storage does not support create-only publication",
                    exception);
        } catch (FileStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageFailure(
                    "create-only publication capability verification failed", exception);
        } catch (RuntimeException exception) {
            throw providerFailure(
                    "create-only publication capability verification failed", exception);
        } finally {
            deleteQuietly(firstSource);
            deleteQuietly(secondSource);
            deleteQuietly(target);
        }
    }

    private void verifyExisting(Path target, StagedFile expected) throws IOException {
        StoredFileInfo actual;
        try {
            actual = storedFileReader.inspect(target);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FileStorageException(
                    StorageFailureReason.PROVIDER_ERROR,
                    "stored object inspection failed",
                    exception);
        }
        if (actual.size() != expected.size() || !actual.sha256().equals(expected.sha256())) {
            throw new FileStorageContentConflictException(
                    "stored object conflicts with pending upload");
        }
    }

    private static StoredFileInfo inspectRegularFile(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new FileSystemException("stored object is not a regular file");
        }
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0;
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
        }
        return new StoredFileInfo(size, HexFormat.of().formatHex(digest.digest()));
    }

    private Path stagingPath(String stagingId) {
        if (stagingId == null || !STAGING_ID.matcher(stagingId).matches()) {
            throw new FileStorageException("invalid staging handle");
        }
        Path path = stagingRoot.resolve(stagingId).normalize();
        assertConfined(path, stagingRoot);
        return path;
    }

    private Path objectPath(StorageKey key) {
        String value = key.value();
        Path path = objectsRoot.resolve(value.substring(0, 2)).resolve(value + ".bin").normalize();
        assertConfined(path, objectsRoot);
        return path;
    }

    private static Path confined(Path candidate, Path allowedRoot) {
        Path normalized = candidate.toAbsolutePath().normalize();
        assertConfined(normalized, allowedRoot.toAbsolutePath().normalize());
        return normalized;
    }

    private static void assertConfined(Path candidate, Path allowedRoot) {
        if (!candidate.normalize().startsWith(allowedRoot.normalize())) {
            throw new FileStorageException("storage path escaped the configured root");
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java 17", exception);
        }
    }

    private static FileStorageException storageFailure(String safeMessage, IOException cause) {
        StorageFailureReason reason;
        if (cause instanceof AccessDeniedException) {
            reason = StorageFailureReason.ACCESS_DENIED;
        } else if (cause instanceof FileSystemException) {
            reason = StorageFailureReason.FILESYSTEM_ERROR;
        } else {
            reason = StorageFailureReason.IO_ERROR;
        }
        return new FileStorageException(reason, safeMessage, cause);
    }

    private static FileStorageException providerFailure(String safeMessage, RuntimeException cause) {
        return new FileStorageException(StorageFailureReason.PROVIDER_ERROR, safeMessage, cause);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Retention/reconciliation can safely retry cleanup; never expose a server path.
        }
    }

    @FunctionalInterface
    public interface CreateOnlyPublisher {
        void publish(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface StoredFileReader {
        StoredFileInfo inspect(Path path) throws IOException;
    }

    @FunctionalInterface
    interface FileStoreLookup {
        FileStore get(Path path) throws IOException;
    }
}
