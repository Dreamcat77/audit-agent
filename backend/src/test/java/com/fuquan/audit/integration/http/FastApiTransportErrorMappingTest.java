package com.fuquan.audit.integration.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.exception.ExternalServiceType;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisMaterial;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.integration.ai.fastapi.FastApiAiReviewAdapter;
import com.fuquan.audit.integration.ocr.fastapi.FastApiOcrAdapter;
import com.fuquan.audit.review.application.port.OcrRequest;
import com.fuquan.audit.review.application.port.OcrTestSources;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

class FastApiTransportErrorMappingTest {

    @Test
    void mapsConnectFailureToStableExternalError() {
        ClientHttpRequestFactory requestFactory = (uri, method) -> {
            throw new ConnectException("mock connect timeout at internal address");
        };
        FastApiOcrAdapter adapter = new FastApiOcrAdapter(restClient(requestFactory));
        OcrRequest request = new OcrRequest(
                "doc-1", "doc.pdf", "application/pdf", OcrTestSources.utf8("content"));

        ExternalServiceException exception = catchThrowableOfType(
                () -> adapter.recognize(request), ExternalServiceException.class);

        assertThat(exception.serviceType()).isEqualTo(ExternalServiceType.OCR);
        assertThat(exception.getCause()).isInstanceOf(ResourceAccessException.class);
        assertThat(exception)
                .hasMessage("external service unavailable")
                .hasMessageNotContaining("internal address");
    }

    @Test
    void mapsReadTimeoutToStableExternalError() {
        ClientHttpRequestFactory requestFactory = (uri, method) -> {
            throw new SocketTimeoutException("mock read timeout at internal address");
        };
        FastApiAiReviewAdapter adapter = new FastApiAiReviewAdapter(restClient(requestFactory));
        ContractTextAnalysisRequest request = new ContractTextAnalysisRequest(
                UUID.randomUUID(), List.of(new ContractTextAnalysisMaterial(
                UUID.randomUUID(), "CONTRACT_BODY", "primary")));

        ExternalServiceException exception = catchThrowableOfType(
                () -> adapter.analyzeContractText(request), ExternalServiceException.class);

        assertThat(exception.serviceType()).isEqualTo(ExternalServiceType.AI);
        assertThat(exception.getCause()).isInstanceOf(ResourceAccessException.class);
        assertThat(exception)
                .hasMessage("external service unavailable")
                .hasMessageNotContaining("internal address");
    }

    private RestClient restClient(ClientHttpRequestFactory requestFactory) {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1")
                .requestFactory(requestFactory)
                .build();
    }
}
