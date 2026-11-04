package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

public final class ProjectFilePolicy {
    public enum Decision { DIRECT_UPLOAD_ALLOWED, ARCHIVE_CHILD_ALLOWED, REJECTED }

    private static final Set<String> REJECTED_EXTENSIONS = Set.of(
            "exe", "dll", "bat", "cmd", "ps1", "sh", "jar", "lnk", "js", "vbs", "com", "msi");
    private static final Set<String> ARCHIVE_CHILD_ALLOWLIST = Set.of(
            "pdf", "docx", "doc", "xls", "xlsx", "png", "jpg", "jpeg", "txt", "zip");
    private final ProjectIntakeProperties properties;
    private final Duration pendingRecoveryAge;

    public ProjectFilePolicy(ProjectIntakeProperties properties, Duration pendingRecoveryAge) {
        this.properties = properties;
        if (pendingRecoveryAge == null || pendingRecoveryAge.isNegative() || pendingRecoveryAge.isZero()) {
            throw new IllegalArgumentException("pendingRecoveryAge must be positive");
        }
        this.pendingRecoveryAge = pendingRecoveryAge;
    }

    public Decision decide(String filename, String mediaType, boolean archiveChild, long sizeBytes) {
        return decide(filename, mediaType, archiveChild, sizeBytes, null, false);
    }

    public Decision decide(String filename, String mediaType, boolean archiveChild, long sizeBytes, byte[] header) {
        return decide(filename, mediaType, archiveChild, sizeBytes, header, true);
    }

    private Decision decide(String filename, String mediaType, boolean archiveChild, long sizeBytes,
                            byte[] header, boolean verifySignature) {
        if (filename == null || filename.isBlank() || sizeBytes <= 0
                || sizeBytes > (archiveChild ? properties.maxEntrySize().toBytes() : properties.maxUploadSize().toBytes())) {
            return Decision.REJECTED;
        }
        String extension = extension(filename);
        if (REJECTED_EXTENSIONS.contains(extension)) {
            return Decision.REJECTED;
        }
        if (archiveChild) {
            return ARCHIVE_CHILD_ALLOWLIST.contains(extension) && (!verifySignature || magicMatches(extension, header))
                    ? Decision.ARCHIVE_CHILD_ALLOWED : Decision.REJECTED;
        }
        if (!Set.of("zip", "pdf", "doc", "docx", "png", "jpg", "jpeg").contains(extension)
                || mediaType == null) {
            return Decision.REJECTED;
        }
        String mime = mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        boolean mimeMatches = switch (extension) {
            case "zip" -> Set.of("application/zip", "application/x-zip-compressed").contains(mime);
            case "pdf" -> "application/pdf".equals(mime);
            case "doc" -> "application/msword".equals(mime);
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime);
            case "png" -> "image/png".equals(mime);
            case "jpg", "jpeg" -> "image/jpeg".equals(mime);
            default -> false;
        };
        return mimeMatches && (!verifySignature || magicMatches(extension, header))
                ? Decision.DIRECT_UPLOAD_ALLOWED : Decision.REJECTED;
    }

    public boolean isParseSupported(ProjectFileCategory category, String filename) {
        String extension = extension(filename);
        return category != ProjectFileCategory.PACKAGE
                && Set.of("pdf", "doc", "docx", "png", "jpg", "jpeg").contains(extension);
    }

    public Duration pendingRecoveryAge() {
        return pendingRecoveryAge;
    }

    public ProjectIntakeProperties properties() {
        return properties;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean magicMatches(String extension, byte[] header) {
        if (header == null || header.length < 2) return false;
        return switch (extension) {
            case "pdf" -> startsWith(header, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "zip", "docx", "xlsx" -> startsWith(header, new byte[]{'P', 'K', 3, 4});
            case "doc" -> startsWith(header, new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                    (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1});
            case "xls" -> startsWith(header, new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0});
            case "png" -> startsWith(header, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
            case "jpg", "jpeg" -> header.length >= 3 && (header[0] & 0xff) == 0xff
                    && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
            case "txt" -> isUtf8(header);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] actual, byte[] expected) {
        if (actual.length < expected.length) return false;
        for (int index = 0; index < expected.length; index++) if (actual[index] != expected[index]) return false;
        return true;
    }

    private static boolean isUtf8(byte[] value) {
        java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        java.nio.CharBuffer decoded = java.nio.CharBuffer.allocate(value.length);
        java.nio.charset.CoderResult result = decoder.decode(java.nio.ByteBuffer.wrap(value), decoded, false);
        if (result.isError()) return false;
        decoded.flip();
        while (decoded.hasRemaining()) {
            char character = decoded.get();
            if (Character.isISOControl(character)
                    && character != '\t' && character != '\n' && character != '\r') {
                return false;
            }
        }
        return true;
    }
}
