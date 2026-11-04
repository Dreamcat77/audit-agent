package com.fuquan.audit.integration.material;

/** Deterministic gate deciding whether PDFBox text is useful enough to avoid OCR. */
public final class PdfTextUsefulness {
    private static final int MIN_MEANINGFUL_CHARACTERS = 4;
    private static final double MIN_MEANINGFUL_RATIO = 0.25d;

    public boolean isUseful(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int visible = 0;
        int meaningful = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                continue;
            }
            visible++;
            if (Character.isLetterOrDigit(codePoint)) {
                meaningful++;
            }
        }
        return meaningful >= MIN_MEANINGFUL_CHARACTERS
                && visible > 0
                && (double) meaningful / visible >= MIN_MEANINGFUL_RATIO;
    }
}
