package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import com.fuquan.audit.contract.application.project.parse.ProjectParseException;

class MaterialParsingConfigurationTest {
    private byte[] docx(String extraName, boolean compress) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var name : java.util.List.of("[Content_Types].xml", "word/document.xml", extraName)) {
                byte[] content = name.equals(extraName) ? new byte[26 * 1024 * 1024] : "<xml/>".getBytes();
                var entry = new ZipEntry(name);
                if (!compress) {
                    var crc = new CRC32(); crc.update(content);
                    entry.setMethod(ZipEntry.STORED); entry.setSize(content.length); entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(content); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    @Test void acceptsValidDocxAboveFormerTwentyMegabyteBound() throws Exception {
        byte[] content = docx("word/media/test.bmp", false);
        var guard = new MaterialParsingConfiguration().procurementDocxGuard();
        assertThat(guard.validate(new ByteArrayInputStream(content))).hasSize(content.length);
    }
    @Test void largerUploadBoundDoesNotDisablePathAndCompressionBombChecks() throws Exception {
        var guard = new MaterialParsingConfiguration().procurementDocxGuard();
        for (byte[] content : java.util.List.of(docx("../outside.bmp", false), docx("word/media/test.bmp", true))) {
            assertThatThrownBy(() -> guard.validate(new ByteArrayInputStream(content)))
                    .isInstanceOf(ProjectParseException.class);
        }
    }
}
