package com.fuquan.audit.review.application.port;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class OcrTestSources {
    private OcrTestSources() {
    }

    public static OcrDocumentSource utf8(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new OcrDocumentSource() {
            @Override
            public ByteArrayInputStream openStream() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public long contentLength() {
                return bytes.length;
            }
        };
    }
}
