package com.fuquan.audit.contract.application.closeout.port;

public interface ReportRendererPort {
    RenderedReport render(String snapshotJson);
    record RenderedReport(byte[] bytes, String mediaType) {
        public RenderedReport { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
