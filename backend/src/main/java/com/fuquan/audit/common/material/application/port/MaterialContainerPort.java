package com.fuquan.audit.common.material.application.port;

import java.io.InputStream;

/** Validate a bounded DOCX container before constructing its document model. */
public interface MaterialContainerPort {
    byte[] validate(InputStream input);
}
