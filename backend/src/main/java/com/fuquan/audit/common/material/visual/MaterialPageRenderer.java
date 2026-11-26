package com.fuquan.audit.common.material.visual;
import java.io.*;
/** Renders pixels from a verified original; never from recognized text. */
public interface MaterialPageRenderer {
    record Image(byte[] png,int pageCount) {}
    Image render(InputStream original,long size,String sha256,int pageNumber) throws IOException;
    /** Native legacy Word layout, for an isolated browser preview; never reconstructed from OCR. */
    default String legacyWord(InputStream original,long size,String sha256)throws IOException {throw new IOException("LEGACY_WORD_PREVIEW_UNAVAILABLE");}
}
