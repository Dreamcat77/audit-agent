package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.StagedFile;
import org.junit.jupiter.api.Test;

class ReviewFileValidatorTest {
    private final ReviewFileValidator validator = new ReviewFileValidator();

    @Test
    void acceptsPdfPngAndJpegOnlyWhenExtensionMimeAndMagicAgree() {
        assertThat(validate("a.pdf", "application/pdf", bytes(0x25, 0x50, 0x44, 0x46, 0x2d)).canonicalContentType())
                .isEqualTo("application/pdf");
        assertThat(validate("a.png", "image/png", bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)).extension())
                .isEqualTo("png");
        assertThat(validate("a.JPG", "image/jpeg", bytes(0xff, 0xd8, 0xff)).extension())
                .isEqualTo("jpg");
        assertThat(validate("a.jpeg", "image/jpeg", bytes(0xff, 0xd8, 0xff)).extension())
                .isEqualTo("jpeg");
    }

    @Test
    void rejectsWrongExtensionMimeAndMagicCombinations() {
        assertInvalid("a.exe", "application/pdf", bytes(0x25, 0x50, 0x44, 0x46, 0x2d));
        assertInvalid("a.pdf", "application/octet-stream", bytes(0x25, 0x50, 0x44, 0x46, 0x2d));
        assertInvalid("a.pdf", "application/pdf", bytes(0x4d, 0x5a, 0x90));
        assertInvalid("a.png", "image/png", bytes(0xff, 0xd8, 0xff));
    }

    @Test
    void stripsBothClientPathStylesButRejectsControlCharactersAndLongNames() {
        assertThat(validate("C:\\fakepath\\contract.pdf", "application/pdf",
                bytes(0x25, 0x50, 0x44, 0x46, 0x2d)).safeFileName()).isEqualTo("contract.pdf");
        assertThat(validate("../../award.pdf", "application/pdf",
                bytes(0x25, 0x50, 0x44, 0x46, 0x2d)).safeFileName()).isEqualTo("award.pdf");
        assertInvalid("bad\u0000.pdf", "application/pdf", bytes(0x25, 0x50, 0x44, 0x46, 0x2d));
        assertInvalid("x".repeat(257) + ".pdf", "application/pdf", bytes(0x25, 0x50, 0x44, 0x46, 0x2d));
    }

    private ValidatedReviewFile validate(String name, String mime, byte[] header) {
        return validator.validate(new StagedFile("opaque", 10, "a".repeat(64), header), name, mime);
    }

    private void assertInvalid(String name, String mime, byte[] header) {
        assertThatThrownBy(() -> validate(name, mime, header)).isInstanceOf(BusinessException.class);
    }

    private byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) result[index] = (byte) values[index];
        return result;
    }
}
