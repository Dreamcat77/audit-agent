package com.fuquan.audit.budget.infra.file;

import com.fuquan.audit.budget.application.port.BudgetMaterialStoragePort;
import com.fuquan.audit.integration.file.FileStorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * F01 本地文件系统材料存储 Adapter。
 *
 * <p>仅此 Adapter 接触 Path/Files；任务中保存的字符串只是重启后可恢复的 opaque 引用。</p>
 */
@Component
public class FileSystemBudgetMaterialStorageAdapter implements BudgetMaterialStoragePort {

    private static final int COPY_BUFFER_SIZE = 8192;

    private final Path root;

    public FileSystemBudgetMaterialStorageAdapter() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "audit-f01-materials"));
    }

    @Autowired
    public FileSystemBudgetMaterialStorageAdapter(FileStorageProperties properties) {
        this(Path.of(properties.getRoot(), "budget-materials"));
    }

    FileSystemBudgetMaterialStorageAdapter(Path root) {
        this.root = Objects.requireNonNull(root, "root must not be null")
                .toAbsolutePath().normalize();
        initialize();
    }

    @Override
    public StoredMaterial store(InputStream inputStream, String fileName, long sizeLimit)
            throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("input stream must not be null");
        }
        if (sizeLimit <= 0) {
            throw new IllegalArgumentException("size limit must be positive");
        }
        String suffix = safeFileSuffix(fileName);
        String reference = UUID.randomUUID() + suffix;
        Path target = root.resolve(reference).normalize();
        if (!target.getParent().equals(root)) {
            throw new IOException("budget material path escaped storage root");
        }
        try {
            try (var output = Files.newOutputStream(
                    target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                long total = 0;
                while (true) {
                    int read = inputStream.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        int single = inputStream.read();
                        if (single < 0) {
                            break;
                        }
                        total++;
                        if (total > sizeLimit) {
                            throw new IOException("file exceeds configured size limit");
                        }
                        output.write(single);
                        continue;
                    }
                    total += read;
                    if (total > sizeLimit) {
                        throw new IOException("file exceeds configured size limit");
                    }
                    output.write(buffer, 0, read);
                }
                if (total == 0) {
                    throw new IOException("file is empty");
                }
            }
            return new StoredMaterial(reference);
        } catch (IOException exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
    }

    @Override
    public InputStream open(String reference) throws IOException {
        Path path = resolve(reference);
        if (!Files.isRegularFile(path)) {
            throw new IOException("stored budget material is missing");
        }
        return Files.newInputStream(path);
    }

    @Override
    public boolean exists(String reference) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(resolve(reference));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Path resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("material reference must not be blank");
        }
        if (reference.contains("..")
                || reference.contains("/")
                || reference.contains("\\")
                || Path.of(reference).isAbsolute()
                || reference.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("material reference escaped storage root");
        }
        Path path = root.resolve(reference).normalize();
        if (!path.getParent().equals(root)) {
            throw new IllegalArgumentException("material reference escaped storage root");
        }
        return path;
    }

    @Override public void delete(String reference) throws IOException {
        Path path=resolve(reference);
        if(Files.isSymbolicLink(path)||!root.toRealPath().equals(root))throw new IOException("unsafe budget cleanup path");
        Files.deleteIfExists(path);
    }

    private void initialize() {
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot initialize budget material storage", exception);
        }
    }

    private static String safeFileSuffix(String fileName) {
        String value = fileName == null ? "" : fileName;
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1) {
            return ".bin";
        }
        String suffix = value.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        return suffix.matches("\\.[A-Za-z0-9]{1,10}") ? suffix : ".bin";
    }
}
