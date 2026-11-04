package com.fuquan.audit.integration.ocr.fake;

import com.fuquan.audit.review.application.port.OcrPort;
import com.fuquan.audit.review.application.port.OcrRequest;
import com.fuquan.audit.review.application.port.OcrResult;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "local-dev & !local-ai"})
public class FakeOcrAdapter implements OcrPort {

    @Override
    public OcrResult recognize(OcrRequest request) {
        return new OcrResult(request.documentId(), "", 0.0, List.of(), true, "FAKE_OCR", 0L);
    }
}
