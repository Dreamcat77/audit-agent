package com.fuquan.audit.review.application.port;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reopenable source for one durable OCR document.
 *
 * <p>Implementations must return a new stream positioned at byte zero for every call. This keeps
 * application and HTTP retry mechanics independent from whole-file buffers.</p>
 */
public interface OcrDocumentSource {

    InputStream openStream() throws IOException;

    long contentLength();
}
