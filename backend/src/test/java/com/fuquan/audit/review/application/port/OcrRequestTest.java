package com.fuquan.audit.review.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OcrRequestTest {

    @Test
    void exposesAReopenableSourceWithoutMaterializingWholeFileContent() throws Exception {
        OcrRequest request = new OcrRequest(
                "doc-1", "doc.txt", "text/plain", OcrTestSources.utf8("original"));

        assertThat(request.source().contentLength()).isEqualTo(8);
        try (InputStream first = request.source().openStream();
             InputStream second = request.source().openStream()) {
            assertThat(first).isNotSameAs(second);
            assertThat(first.readNBytes(8)).isEqualTo("original".getBytes(StandardCharsets.UTF_8));
            assertThat(second.readNBytes(8)).isEqualTo("original".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsAnEmptyStreamingSourceBeforeCallingInfrastructure() {
        assertThatThrownBy(() -> new OcrRequest(
                "doc-1", "doc.txt", "text/plain", OcrTestSources.utf8("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentLength");
    }
}
