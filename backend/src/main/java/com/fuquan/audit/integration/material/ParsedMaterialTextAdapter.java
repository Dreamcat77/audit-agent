package com.fuquan.audit.integration.material;

import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.common.material.application.port.MaterialParsePort;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Compatibility boundary: all existing text consumers use the common parser once. */
@Component
@Primary
public final class ParsedMaterialTextAdapter implements DocumentTextPort {
    private final MaterialParsePort parser;

    public ParsedMaterialTextAdapter(MaterialParsePort parser) { this.parser = parser; }

    @Override
    public Result read(InputStream input, String fileName, String resourceId, long maxBytes, int maxPages, int maxChars)
            throws IOException {
        return parser.parse(new MaterialParsePort.ParseRequest(resourceId, fileName, null, input,
                maxBytes, maxPages, maxChars, Map.of())).textResult();
    }
    @Override
    public Result read(InputStream input,String fileName,String resourceId,long maxBytes,int maxPages,int maxChars,
            PdfCheckpoint checkpoint,java.util.function.Consumer<PdfCheckpoint> progress) throws IOException {
        return parser.parse(new MaterialParsePort.ParseRequest(resourceId,fileName,null,input,
                maxBytes,maxPages,maxChars,Map.of()),checkpoint,progress).textResult();
    }
}
