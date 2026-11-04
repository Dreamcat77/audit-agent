package com.fuquan.audit.contract.application.project.archive;

import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

@Component
public class SelectedProjectArchiveReader implements ProjectArchiveReader {
    private final ProjectIntakeProperties limits;

    public SelectedProjectArchiveReader(ProjectIntakeProperties limits) { this.limits = limits; }

    @Override
    public void read(InputStream input, EntryConsumer consumer) {
        Path spool = null;
        try {
            spool = Files.createTempFile("project-intake-archive-", ".zip");
            copyBounded(input, Files.newOutputStream(spool), limits.maxUploadSize().toBytes());
            scan(spool, consumer);
        } catch (ProjectArchiveSecurityException failure) {
            throw failure;
        } catch (IOException | ArithmeticException failure) {
            throw new ProjectArchiveSecurityException("MALFORMED_OR_TRUNCATED", failure);
        } finally {
            if (spool != null) {
                try { Files.deleteIfExists(spool); }
                catch (IOException ignored) { spool.toFile().deleteOnExit(); }
            }
        }
    }

    private void scan(Path spool, EntryConsumer consumer) throws IOException {
        Set<String> collisionKeys = new HashSet<>();
        long expandedTotal = 0;
        int acceptedFiles = 0;
        try (ZipFile zip = ZipFile.builder().setPath(spool).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntriesInPhysicalOrder();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > limits.maxEntries()) reject("ENTRY_COUNT_LIMIT");
                String path = normalize(entry.getName(), entry.isDirectory());
                if (!collisionKeys.add(path.toLowerCase(Locale.ROOT))) reject("PATH_COLLISION");
                if (entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry)) reject("ENCRYPTED_OR_UNSUPPORTED");
                if (entry.isUnixSymlink()) reject("SYMLINK_ENTRY");
                int mode = entry.getUnixMode();
                int fileType = mode & 0170000;
                if (!entry.isDirectory() && fileType != 0 && fileType != 0100000) reject("NON_REGULAR_ENTRY");
                if (entry.isDirectory()) continue;
                acceptedFiles++;
                validateDeclaredMetadata(entry);
                try (InputStream raw = zip.getInputStream(entry)) {
                    VerifiedEntryInput content = new VerifiedEntryInput(raw, limits.maxEntrySize().toBytes());
                    consumer.accept(new Entry(path, content, entry.getSize(), entry.getCompressedSize(), entry.getCrc()));
                    content.transferTo(OutputStream.nullOutputStream());
                    expandedTotal = Math.addExact(expandedTotal, content.count());
                    if (expandedTotal > limits.maxExpandedArchiveSize().toBytes()) reject("EXPANDED_SIZE_LIMIT");
                    if (entry.getSize() >= 0 && content.count() != entry.getSize()) reject("ENTRY_SIZE_MISMATCH");
                    if (entry.getCompressedSize() > 0
                            && ((double) content.count() / entry.getCompressedSize()) > limits.maxCompressionRatio()) {
                        reject("COMPRESSION_RATIO_LIMIT");
                    }
                    if (content.crc() != entry.getCrc()) reject("CRC_MISMATCH");
                }
            }
        }
        if (acceptedFiles == 0) reject("EMPTY_ARCHIVE");
    }

    private void validateDeclaredMetadata(ZipArchiveEntry entry) {
        if (entry.getSize() > limits.maxEntrySize().toBytes()) reject("ENTRY_SIZE_LIMIT");
        if (entry.getSize() > 0 && entry.getCompressedSize() <= 0) reject("INVALID_COMPRESSED_SIZE");
        if (entry.getSize() > 0 && entry.getCompressedSize() > 0
                && ((double) entry.getSize() / entry.getCompressedSize()) > limits.maxCompressionRatio()) {
            reject("COMPRESSION_RATIO_LIMIT");
        }
        if (entry.getCrc() < 0) reject("CRC_MISMATCH");
    }

    private String normalize(String raw, boolean directory) {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0) reject("INVALID_PATH");
        String replaced = Normalizer.normalize(raw.replace('\\', '/'), Normalizer.Form.NFC);
        if (directory && replaced.endsWith("/")) replaced = replaced.substring(0, replaced.length() - 1);
        if (replaced.startsWith("/") || replaced.startsWith("//") || replaced.matches("^[A-Za-z]:.*")) reject("ABSOLUTE_PATH");
        if (replaced.length() > limits.maxRelativePathLength()) reject("PATH_LENGTH_LIMIT");
        String[] segments = replaced.split("/", -1);
        if (segments.length > limits.maxDepth()) reject("PATH_DEPTH_LIMIT");
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) reject("PATH_TRAVERSAL");
            if (segment.chars().anyMatch(Character::isISOControl)) reject("INVALID_PATH");
        }
        return replaced;
    }

    private static void copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        try (OutputStream target = output) {
            byte[] buffer = new byte[8192];
            long count = 0;
            for (int read; (read = input.read(buffer)) >= 0; ) {
                count += read;
                if (count > maximum) reject("ARCHIVE_TOO_LARGE");
                target.write(buffer, 0, read);
            }
        }
    }

    private static void reject(String reason) { throw new ProjectArchiveSecurityException(reason); }

    private static final class VerifiedEntryInput extends FilterInputStream {
        private final long maximum;
        private final CRC32 checksum = new CRC32();
        private long count;

        private VerifiedEntryInput(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) update(new byte[]{(byte) value}, 0, 1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) update(bytes, offset, read);
            return read;
        }
        private void update(byte[] bytes, int offset, int length) {
            count += length;
            if (count > maximum) reject("ENTRY_SIZE_LIMIT");
            checksum.update(bytes, offset, length);
        }
        long count() { return count; }
        long crc() { return checksum.getValue(); }
    }
}
