package com.fuquan.audit.project.archive;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

final class ArchiveCapabilityProbe {

    enum Result { PROVEN, UNPROVABLE }

    Map<String, Result> probe(String reader) {
        return switch (reader) {
            case "jdk" -> jdk();
            case "commons-compress" -> commonsCompress();
            default -> throw new IllegalArgumentException("unsupported archive.reader: " + reader);
        };
    }

    private Map<String, Result> jdk() {
        Map<String, Result> results = commonResults();
        results.put("symlink metadata", Result.UNPROVABLE);
        results.put("non-regular metadata", Result.UNPROVABLE);
        results.put("encrypted metadata", Result.UNPROVABLE);
        return results;
    }

    private Map<String, Result> commonsCompress() {
        Map<String, Result> results = commonResults();
        results.put("ordinary archive", readsNamedEntryWithCommons("document.txt",
                ArchiveFixtureFactory.ordinary("document.txt", new byte[]{1})));
        results.put("path traversal visibility", readsNamedEntryWithCommons("../escape.txt",
                ArchiveFixtureFactory.ordinary("../escape.txt", new byte[]{1})));
        results.put("entry count visibility", readsNamedEntryWithCommons("entry.txt",
                ArchiveFixtureFactory.ordinary("entry.txt", new byte[]{1})));
        results.put("size visibility", readsNamedEntryWithCommons("size.txt",
                ArchiveFixtureFactory.ordinary("size.txt", new byte[]{1})));
        results.put("compression ratio visibility", readsNamedEntryWithCommons("large.txt",
                ArchiveFixtureResources.fixed("high-ratio.zip")));
        results.put("symlink metadata", proveUnixMode(ArchiveFixtureResources.fixed("symlink.zip"), true));
        results.put("non-regular metadata", proveUnixMode(ArchiveFixtureResources.fixed("non-regular.zip"), false));
        results.put("encrypted metadata", proveEncryptedMetadata());
        results.put("CRC mismatch rejection", detectsCrcMismatch(ArchiveFixtureResources.fixed("crc-mismatch.zip")));
        results.put("truncation rejection", rejects(ArchiveFixtureResources.fixed("truncated.zip")));
        return results;
    }

    private Result readsNamedEntryWithCommons(String name, byte[] bytes) {
        try (ZipFile zip = new ZipFile(new SeekableInMemoryByteChannel(bytes))) {
            ZipArchiveEntry entry = zip.getEntries().nextElement();
            try (InputStream input = zip.getInputStream(entry)) {
                input.transferTo(OutputSink.INSTANCE);
            }
            return name.equals(entry.getName()) && entry.getSize() >= 0 && entry.getCompressedSize() >= 0
                    ? Result.PROVEN : Result.UNPROVABLE;
        } catch (IOException exception) {
            return Result.UNPROVABLE;
        }
    }

    private Map<String, Result> commonResults() {
        Map<String, Result> results = new LinkedHashMap<>();
        results.put("ordinary archive", readsWithJdk(ArchiveFixtureFactory.ordinary("document.txt", new byte[]{1})));
        results.put("path traversal visibility", readsNamedEntry("../escape.txt"));
        results.put("entry count visibility", readsNamedEntry("entry.txt"));
        results.put("size visibility", readsNamedEntry("size.txt"));
        results.put("compression ratio visibility", readsNamedEntry("large.txt",
                ArchiveFixtureResources.fixed("high-ratio.zip")));
        results.put("CRC mismatch rejection", rejectsWithJdk(ArchiveFixtureResources.fixed("crc-mismatch.zip")));
        results.put("truncation rejection", rejectsWithJdk(ArchiveFixtureResources.fixed("truncated.zip")));
        return results;
    }

    private Result readsNamedEntry(String name) {
        return readsNamedEntry(name, ArchiveFixtureFactory.ordinary(name, new byte[]{1}));
    }

    private Result readsNamedEntry(String name, byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry = zip.getNextEntry();
            return entry != null && name.equals(entry.getName()) ? Result.PROVEN : Result.UNPROVABLE;
        } catch (IOException exception) {
            return Result.UNPROVABLE;
        }
    }

    private Result readsWithJdk(byte[] bytes) {
        return readsNamedEntry("document.txt", bytes);
    }

    private Result proveUnixMode(byte[] bytes, boolean symlink) {
        try (ZipFile zip = new ZipFile(new SeekableInMemoryByteChannel(bytes))) {
            ZipArchiveEntry entry = zip.getEntries().nextElement();
            boolean regular = (entry.getUnixMode() & 0170000) == 0100000;
            return (symlink ? entry.isUnixSymlink() : !regular && !entry.isDirectory())
                    ? Result.PROVEN : Result.UNPROVABLE;
        } catch (IOException exception) {
            return Result.UNPROVABLE;
        }
    }

    private Result proveEncryptedMetadata() {
        try (ZipFile zip = new ZipFile(new SeekableInMemoryByteChannel(ArchiveFixtureResources.fixed("encrypted.zip")))) {
            ZipArchiveEntry entry = zip.getEntries().nextElement();
            return entry.getGeneralPurposeBit().usesEncryption() && !zip.canReadEntryData(entry)
                    ? Result.PROVEN : Result.UNPROVABLE;
        } catch (IOException exception) {
            return Result.UNPROVABLE;
        }
    }

    private Result rejectsWithJdk(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            if (zip.getNextEntry() == null) {
                return Result.PROVEN;
            }
            zip.transferTo(OutputSink.INSTANCE);
            return Result.UNPROVABLE;
        } catch (IOException exception) {
            return Result.PROVEN;
        }
    }

    private Result rejects(byte[] bytes) {
        try (ZipFile zip = new ZipFile(new SeekableInMemoryByteChannel(bytes))) {
            if (!zip.getEntries().hasMoreElements()) {
                return Result.PROVEN;
            }
            ZipArchiveEntry entry = zip.getEntries().nextElement();
            try (InputStream input = zip.getInputStream(entry)) {
                input.transferTo(OutputSink.INSTANCE);
            }
            return Result.UNPROVABLE;
        } catch (IOException exception) {
            return Result.PROVEN;
        }
    }

    private Result detectsCrcMismatch(byte[] bytes) {
        try (ZipFile zip = new ZipFile(new SeekableInMemoryByteChannel(bytes))) {
            ZipArchiveEntry entry = zip.getEntries().nextElement();
            java.util.zip.CRC32 actual = new java.util.zip.CRC32();
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    actual.update(buffer, 0, read);
                }
            }
            return entry.getCrc() != actual.getValue() ? Result.PROVEN : Result.UNPROVABLE;
        } catch (IOException exception) {
            return Result.PROVEN;
        }
    }

    private static final class OutputSink extends java.io.OutputStream {
        private static final OutputSink INSTANCE = new OutputSink();

        @Override
        public void write(int value) {
            // Deliberately discard bytes while forcing the reader to verify the full entry.
        }
    }
}
