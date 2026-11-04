package com.fuquan.audit.integration.ocr.fastapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.exception.ExternalServiceType;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.review.application.port.OcrRequest;
import com.fuquan.audit.review.application.port.OcrResult;
import com.fuquan.audit.review.application.port.OcrTestSources;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FastApiOcrAdapterTest {

    private MockRestServiceServer server;
    private FastApiOcrAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new FastApiOcrAdapter(builder.build());
    }

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void concurrentOcrFailsFastAndSlotIsReleasedAfterInvalidResponse() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.expect(requestTo("http://127.0.0.1/ai/v1/ocr/extract")).andRespond(request -> {
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        server.expect(requestTo("http://127.0.0.1/ai/v1/ocr/extract"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var request = new OcrRequest("doc", "scan.pdf", "application/pdf", OcrTestSources.utf8("file"));
        try {
            var first = executor.submit(() -> { assertThatThrownBy(() -> adapter.recognize(request)).isInstanceOf(ExternalServiceException.class); });
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> adapter.recognize(request)).isInstanceOf(BusinessException.class);
            release.countDown(); first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> adapter.recognize(request)).isInstanceOf(ExternalServiceException.class);
            server.verify();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void postsMultipartMapsConfidenceAndPagesAndPropagatesRequestId() {
        MDC.put(RequestIdFilter.MDC_KEY, "ocr-request-20260814");
        server.expect(requestTo("http://127.0.0.1/ai/v1/ocr/extract"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(RequestIdFilter.HEADER_NAME, "ocr-request-20260814"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(containsString("name=\"file\"")))
                .andExpect(content().string(containsString("contract.pdf")))
                .andExpect(content().string(containsString("Content-Type: application/pdf")))
                .andExpect(content().string(containsString("file-content")))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "text": "recognized",
                            "confidence": 0.96,
                            "pages": [
                              {"pageNum": 1, "text": "page-one", "confidence": 0.95}
                            ],
                            "engine": "PADDLEOCR_3.7.0",
                            "durationMs": 120
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        OcrResult result = adapter.recognize(request());

        assertThat(result.documentId()).isEqualTo("document-1");
        assertThat(result.text()).isEqualTo("recognized");
        assertThat(result.confidence()).isEqualTo(0.96);
        assertThat(result.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(1);
            assertThat(page.text()).isEqualTo("page-one");
            assertThat(page.confidence()).isEqualTo(0.95);
        });
        assertThat(result.simulated()).isFalse();
        assertThat(result.engine()).isEqualTo("PADDLEOCR_3.7.0");
        assertThat(result.durationMs()).isEqualTo(120L);
        server.verify();
    }

    @Test
    void logsOnlySafeOcrCallProvenance() {
        String recognizedText = "OCR-TEXT-SENTINEL-MUST-NOT-BE-LOGGED";
        MDC.put(RequestIdFilter.MDC_KEY, "ocr-log-request-1");
        server.expect(requestTo("http://127.0.0.1/ai/v1/ocr/extract"))
                .andRespond(withSuccess(successResponse(recognizedText), MediaType.APPLICATION_JSON));
        Logger logger = (Logger) LoggerFactory.getLogger(FastApiOcrAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            adapter.recognize(request());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(logs).contains("OCR_CALL_COMPLETE", "operation=OCR_EXTRACT",
                "requestId=ocr-log-request-1", "documentId=document-1",
                "engine=PADDLEOCR_3.7.0", "durationMs=120", "outcome=SUCCESS");
        assertThat(logs).doesNotContain(recognizedText).doesNotContain("contract.pdf");
        server.verify();
    }

    @Test
    void mapsExternalServerErrorWithoutLeakingResponseBody() {
        server.expect(requestTo("http://127.0.0.1/ai/v1/ocr/extract"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("sensitive downstream detail"));

        ExternalServiceException exception = catchThrowableOfType(
                () -> adapter.recognize(request()), ExternalServiceException.class);

        assertThat(exception.serviceType()).isEqualTo(ExternalServiceType.OCR);
        assertThat(exception.getCause()).isNotNull();
        assertThat(exception).hasMessage("external service unavailable")
                .hasMessageNotContaining("sensitive downstream detail")
                .hasMessageNotContaining("127.0.0.1");
        server.verify();
    }

    @Test
    void acceptsImagePngContentType() {
        server.expect(requestTo("http://127.0.0.1/ai/v1/ocr/extract"))
                .andExpect(content().string(containsString("image.png")))
                .andExpect(content().string(containsString("Content-Type: image/png")))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        OcrResult result = adapter.recognize(request("image.png", "image/png"));

        assertThat(result.text()).isEqualTo("recognized");
        server.verify();
    }

    @Test
    void invalidMimeTypeMapsToBadRequestWithoutHttpCall() {
        BusinessException exception = catchThrowableOfType(
                () -> adapter.recognize(request("contract.bin", "not-a-mime")),
                BusinessException.class);

        assertThat(exception).isNotInstanceOf(ExternalServiceException.class);
        assertThat(exception.errorCode()).isEqualTo(BusinessErrorCode.INVALID_ARGUMENT);
        assertThat(exception.errorCode().httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception).hasMessage("invalid argument");
        server.verify();
    }

    @Test
    void mapsMalformedJsonToStableExternalError() {
        server.expect(requestTo("http://127.0.0.1/ai/v1/ocr/extract"))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.recognize(request()))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("external service unavailable");
        server.verify();
    }

    @Test
    void rejectsSuccessfulEnvelopeWithMissingData() {
        server.expect(requestTo("http://127.0.0.1/ai/v1/ocr/extract"))
                .andExpect(headerDoesNotExist(RequestIdFilter.HEADER_NAME))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"success\",\"data\":null}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.recognize(request()))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("external service unavailable");
        server.verify();
    }

    private OcrRequest request() {
        return request("contract.pdf", "application/pdf");
    }

    private OcrRequest request(String fileName, String contentType) {
        return new OcrRequest(
                "document-1",
                fileName,
                contentType,
                OcrTestSources.utf8("file-content"));
    }

    private String successResponse() {
        return successResponse("recognized");
    }

    private String successResponse(String text) {
        return """
                {
                  "code": 200,
                  "message": "success",
                  "data": {
                    "text": "%s",
                    "confidence": 0.96,
                    "pages": [],
                    "engine": "PADDLEOCR_3.7.0",
                    "durationMs": 120
                  }
                }
                """.formatted(text);
    }
}
