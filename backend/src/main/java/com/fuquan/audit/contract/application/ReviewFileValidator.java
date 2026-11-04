package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.StagedFile;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** extension、MIME、magic 与安全文件名的联合校验。 */
@Component
class ReviewFileValidator {
    private static final int MAX_FILE_NAME = 256;

    ValidatedReviewFile validate(StagedFile staged, String originalName, String declaredContentType) {
        String safeName = safeFileName(originalName);
        String extension = extension(safeName);
        String mime = declaredContentType == null ? "" : declaredContentType.trim().toLowerCase(Locale.ROOT);
        String expectedMime = switch (extension) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> throw invalid("unsupported file extension");
        };
        if (!expectedMime.equals(mime)) {
            throw invalid("file extension and Content-Type do not match");
        }
        byte[] header = staged.header();
        boolean magicMatches = switch (extension) {
            case "pdf" -> startsWith(header, new int[] {0x25, 0x50, 0x44, 0x46, 0x2d});
            case "png" -> startsWith(header, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "jpg", "jpeg" -> startsWith(header, new int[] {0xff, 0xd8, 0xff});
            default -> false;
        };
        if (!magicMatches) {
            throw invalid("file magic signature does not match extension and Content-Type");
        }
        return new ValidatedReviewFile(staged, safeName, extension, expectedMime);
    }

    private String safeFileName(String originalName) {
        if (originalName == null) {
            throw invalid("file name is required");
        }
        for (int index = 0; index < originalName.length(); index++) {
            if (Character.isISOControl(originalName.charAt(index))) {
                throw invalid("file name contains control characters");
            }
        }
        String normalized = originalName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.length() > MAX_FILE_NAME) {
            throw invalid("file name is invalid");
        }
        return name;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            throw invalid("supported file extension is required");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean startsWith(byte[] actual, int[] prefix) {
        if (actual.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((actual[index] & 0xff) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, message);
    }
}
