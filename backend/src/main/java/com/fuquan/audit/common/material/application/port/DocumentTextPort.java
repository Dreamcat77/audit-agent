package com.fuquan.audit.common.material.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Complete bounded text extraction. A skipped or unreadable unit must not become success. */
public interface DocumentTextPort {
    record Result(List<MaterialFieldAiPort.Segment> segments, String method, String sha256,
                  List<MaterialParsePort.Table> tables, List<MaterialParsePort.OcrProvenance> ocrProvenance, List<String> coverageGaps) {
        public Result(List<MaterialFieldAiPort.Segment> segments, String method, String sha256,
                List<MaterialParsePort.Table> tables, List<MaterialParsePort.OcrProvenance> provenance) {
            this(segments,method,sha256,tables,provenance,List.of());
        }
        public Result(List<MaterialFieldAiPort.Segment> segments, String method, String sha256) {
            this(segments, method, sha256, List.of(), List.of(), List.of());
        }
        public Result {
            segments = List.copyOf(segments);
            tables = List.copyOf(tables);
            ocrProvenance = List.copyOf(ocrProvenance);
            coverageGaps = List.copyOf(coverageGaps);
        }
    }
    Result read(InputStream input, String fileName, String resourceId, long maxBytes, int maxPages, int maxChars)
            throws IOException;
    /** Acknowledged PDF pages only. Stored by the business owner, never by a log or cache. */
    record PdfCheckpoint(String sourceSha256, String policy, int pageCount, List<Integer> completedPages,
                         List<MaterialFieldAiPort.Segment> segments,
                         List<MaterialParsePort.OcrProvenance> provenance,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         List<com.fuquan.audit.common.material.visual.VisualIndex.Block> visualBlocks) {
        public PdfCheckpoint(String sha,String policy,int count,List<Integer> pages,List<MaterialFieldAiPort.Segment> segments,List<MaterialParsePort.OcrProvenance> provenance){this(sha,policy,count,pages,segments,provenance,null);}
        public PdfCheckpoint {
            completedPages=List.copyOf(completedPages);segments=List.copyOf(segments);provenance=List.copyOf(provenance);
            visualBlocks=visualBlocks==null?null:List.copyOf(visualBlocks);
        }
    }
    default Result read(InputStream input, String fileName, String resourceId, long maxBytes, int maxPages, int maxChars,
                        PdfCheckpoint checkpoint, java.util.function.Consumer<PdfCheckpoint> progress) throws IOException {
        return read(input,fileName,resourceId,maxBytes,maxPages,maxChars);
    }
    class ReadFailure extends IOException {
        private final String code;
        public ReadFailure(String code) { super(code); this.code = code; }
        public ReadFailure(String code,Throwable cause) { super(code,cause); this.code=code; }
        public String code() { return code; }
    }
}
