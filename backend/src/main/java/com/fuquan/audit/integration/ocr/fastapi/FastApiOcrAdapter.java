package com.fuquan.audit.integration.ocr.fastapi;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.exception.ExternalServiceType;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.integration.http.FastApiContractPaths;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import java.util.Map;
import com.fuquan.audit.review.application.port.OcrPage;
import com.fuquan.audit.review.application.port.OcrPort;
import com.fuquan.audit.review.application.port.OcrRequest;
import com.fuquan.audit.review.application.port.OcrResult;
import java.util.List;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Profile({"local-dev & local-ai", "dev29", "customer"})
public class FastApiOcrAdapter implements OcrPort {

    private static final Logger LOG = LoggerFactory.getLogger(FastApiOcrAdapter.class);
    private static final Pattern UNSAFE_LOG_CHARACTER = Pattern.compile("[^A-Za-z0-9._:/-]");
    private final RestClient restClient;
    // Bound shared OCR calls across modules without creating waiting worker threads.
    private final java.util.concurrent.Semaphore capacity;

    public FastApiOcrAdapter(RestClient restClient) {this(restClient,1);}

    @org.springframework.beans.factory.annotation.Autowired
    public FastApiOcrAdapter(@Qualifier("ocrRestClient") RestClient restClient,
            @org.springframework.beans.factory.annotation.Value("${audit.ocr.page-concurrency:1}") int concurrency) {
        if(concurrency<1||concurrency>4)throw new IllegalArgumentException("OCR concurrency must be between one and four");
        this.restClient = restClient;this.capacity=new java.util.concurrent.Semaphore(concurrency,true);
    }

    @Override
    public OcrResult recognize(OcrRequest request) {
        MediaType contentType = parseContentType(request.contentType());
        if (!capacity.tryAcquire()) {
            try (var span = PipelineDiagnostics.start("OCR", "CAPACITY", PipelineDiagnostics.resourceId(request.documentId()), Map.of())) {
                span.outcome("BUSY", Map.of("reason", "OCR_CAPACITY_EXHAUSTED"));
            }
            throw new com.fuquan.audit.review.application.port.OcrCapacityUnavailableException();
        }
        try (var span = PipelineDiagnostics.start("OCR", "OCR_CALL", PipelineDiagnostics.resourceId(request.documentId()),
                Map.of("inputBytes", request.source().contentLength()))) {
          try {
            OcrEnvelope envelope = execute(request, contentType);
            OcrResult result = mapResponse(request.documentId(), envelope);
            logSuccess(request.documentId(), result);
            span.success(Map.of("pageCount", result.pages().size(), "outputChars", result.text().length()));
            return result;
        } catch (ExternalServiceException exception) {
            span.fail(exception);
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            span.fail(exception);
            throw new ExternalServiceException(ExternalServiceType.OCR, exception);
          }
        } finally {
            capacity.release();
        }
    }

    private MediaType parseContentType(String contentType) {
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            if (parsed.isWildcardType() || parsed.isWildcardSubtype()) {
                throw new IllegalArgumentException("wildcard content type is not allowed");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    BusinessErrorCode.INVALID_ARGUMENT,
                    BusinessErrorCode.INVALID_ARGUMENT.defaultMessage(),
                    exception);
        }
    }

    private OcrEnvelope execute(OcrRequest request, MediaType contentType) {
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("file", new ReopenableOcrResource(request))
                .filename(request.fileName())
                .contentType(contentType);

        RestClient.RequestBodySpec httpRequest = restClient.post()
                .uri(FastApiContractPaths.OCR_EXTRACT);
        propagateRequestId(httpRequest);
        return httpRequest
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart.build())
                .retrieve()
                .body(OcrEnvelope.class);
    }

    private OcrResult mapResponse(String documentId, OcrEnvelope envelope) {
        if (envelope == null || envelope.code() == null || envelope.code() != 200 || envelope.data() == null) {
            throw invalidResponse();
        }
        OcrData data = envelope.data();
        if (data.text() == null || data.confidence() == null || data.pages() == null
                || data.engine() == null || data.durationMs() == null) {
            throw invalidResponse();
        }
        List<OcrPage> pages = data.pages().stream()
                .map(this::mapPage)
                .toList();
        return new OcrResult(documentId, data.text(), data.confidence(), pages, false,
                data.engine(), data.durationMs());
    }

    private OcrPage mapPage(OcrPageData page) {
        if (page == null || page.pageNum() == null || page.text() == null || page.confidence() == null) {
            throw invalidResponse();
        }
        var regions=new java.util.ArrayList<com.fuquan.audit.common.material.visual.VisualIndex.Region>();
        if(page.regions()!=null&&page.regions().size()<=10000)for(var r:page.regions()){
            try{
                if(r==null||r.end()>page.text().length()||r.polygon()==null||r.polygon().size()!=4)continue;
                var polygon=r.polygon().stream().map(p->{
                    if(p==null||p.size()!=2)throw new IllegalArgumentException("invalid optional geometry");
                    return new com.fuquan.audit.common.material.visual.VisualIndex.Point(p.get(0),p.get(1));
                }).toList();
                regions.add(new com.fuquan.audit.common.material.visual.VisualIndex.Region(r.start(),r.end(),polygon,r.confidence(),r.verification()));
            }catch(IllegalArgumentException|NullPointerException ignored){/* Keep valid OCR text without an unproven box. */}
        }
        return new OcrPage(page.pageNum(), page.text(), page.confidence(),regions);
    }

    private ExternalServiceException invalidResponse() {
        return new ExternalServiceException(
                ExternalServiceType.OCR,
                new IllegalStateException("invalid downstream response"));
    }

    private void propagateRequestId(RestClient.RequestHeadersSpec<?> request) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null && !requestId.isBlank()) {
            request.header(RequestIdFilter.HEADER_NAME, requestId);
        }
    }

    private void logSuccess(String documentId, OcrResult result) {
        LOG.info("OCR_CALL_COMPLETE operation=OCR_EXTRACT requestId={} documentId={} engine={} durationMs={} outcome=SUCCESS",
                safeLogIdentifier(MDC.get(RequestIdFilter.MDC_KEY)), safeLogIdentifier(documentId),
                safeLogIdentifier(result.engine()), result.durationMs());
    }

    private static String safeLogIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        return UNSAFE_LOG_CHARACTER.matcher(value.substring(0, Math.min(value.length(), 128)))
                .replaceAll("_");
    }

    private record OcrEnvelope(Integer code, String message, OcrData data) {
    }

    private record OcrData(String text, Double confidence, List<OcrPageData> pages,
                           String engine, Long durationMs) {
    }

    private record OcrPageData(Integer pageNum, String text, Double confidence,List<OcrRegionData> regions) {
    }
    private record OcrRegionData(int start,int end,List<List<Double>> polygon,double confidence,String verification) {}

    private static final class ReopenableOcrResource extends AbstractResource {
        private final OcrRequest request;

        private ReopenableOcrResource(OcrRequest request) {
            this.request = request;
        }

        @Override
        public String getDescription() {
            return "reopenable OCR document " + request.documentId();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return request.source().openStream();
        }

        @Override
        public long contentLength() {
            return request.source().contentLength();
        }

        @Override
        public String getFilename() {
            return request.fileName();
        }
    }
}
