package com.fuquan.audit.contract.application.project.parse;

/** Compatibility facade over the shared deterministic native-text/OCR gate. */
public final class PdfTextUsefulness {
    private final com.fuquan.audit.integration.material.PdfTextUsefulness shared =
            new com.fuquan.audit.integration.material.PdfTextUsefulness();
    public boolean isUseful(String text) { return shared.isUseful(text); }
}
