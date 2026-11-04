package com.fuquan.audit.integration.http;

import java.time.Duration;
import java.util.Map;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.common.web.RequestIdFilter;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Profile({"local-dev & local-ai", "dev29", "customer"})
public class FastApiHttpClientConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(FastApiHttpClientConfiguration.class);

    @Bean
    @Qualifier("ocrRestClient")
    RestClient ocrRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${audit.ocr.base-url}") String baseUrl,
            @Value("${audit.ocr.connect-timeout-ms:5000}") long connectTimeoutMs,
            // OCR is page-serial and can legitimately take longer than the model call.
            // Keep a bounded document window, with a small margin over the Python
            // isolated worker's 1800-second deadline so the remote outcome is known.
            @Value("${audit.ocr.read-timeout-ms:1900000}") long readTimeoutMs) {
        long effective=ocrReadTimeoutMillis(readTimeoutMs);
        if(effective!=readTimeoutMs)LOG.warn("OCR legacy read timeout normalized requestedMs={} effectiveMs={}",readTimeoutMs,effective);
        return createRestClient(restClientBuilder, baseUrl, "OCR", connectTimeoutMs, effective);
    }
    static long ocrReadTimeoutMillis(long requested) {
        if(requested<=0)throw new IllegalArgumentException("OCR timeout must be positive");
        // A short HTTP timeout abandons a still-running isolated worker. Preserve
        // longer customer windows, but allow the worker's bounded outcome to return.
        return Math.max(1900000,requested);
    }

    @Bean
    @Qualifier("aiRestClient")
    RestClient aiRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${audit.ai.base-url}") String baseUrl,
            @Value("${audit.ai.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${audit.ai.read-timeout-ms:960000}") long readTimeoutMs) {
        return createRestClient(restClientBuilder, baseUrl, "AI", connectTimeoutMs, readTimeoutMs);
    }

    private RestClient createRestClient(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String adapterName,
            long connectTimeoutMs,
            long readTimeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("FastAPI baseUrl must not be blank");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("FastAPI timeouts must be positive");
        }
        LOG.info("FastAPI {} client configured connectTimeoutMs={} readTimeoutMs={}",
                adapterName, connectTimeoutMs, readTimeoutMs);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        var clientBuilder = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
        // Multipart OCR stays streaming: a body-byte interceptor would buffer
        // the entire uploaded document. OCR adapter spans already record sizes.
        if ("OCR".equals(adapterName)) return clientBuilder.build();
        return clientBuilder
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                    if (requestId != null && requestId.matches("[A-Za-z0-9._-]{1,128}"))
                        request.getHeaders().set(RequestIdFilter.HEADER_NAME, requestId);
                    String operation = switch (request.getURI().getPath()) {
                        case FastApiContractPaths.OCR_EXTRACT -> "OCR_EXTRACT";
                        case FastApiContractPaths.CONTRACT_TEXT_ANALYSIS -> "CONTRACT_TEXT_ANALYSIS";
                        case FastApiContractPaths.BUDGET_SEMANTIC_ANALYSIS -> "BUDGET_SEMANTIC_ANALYSIS";
                        case "/ai/v1/materials/extract-fields" -> "PROCUREMENT_MATERIAL_EXTRACTION";
                        case FastApiContractPaths.PROCUREMENT_HEADER_MAPPING -> "PROCUREMENT_HEADER_MAPPING";
                        case FastApiContractPaths.PROCUREMENT_TENDER_COMPLIANCE -> "PROCUREMENT_TENDER_COMPLIANCE";
                        default -> adapterName;
                    };
                    try (var span = PipelineDiagnostics.start(operation, "DOWNSTREAM_HTTP", null,
                            Map.of("inputBytes", body.length, "connectTimeoutMs", connectTimeoutMs,
                                    "readTimeoutMs", "PROCUREMENT_MATERIAL_EXTRACTION".equals(operation) ? 965000L : readTimeoutMs))) {
                        try {
                            var response = execution.execute(request, body);
                            span.outcome(response.getStatusCode().isError() ? "HTTP_REJECTED" : "HTTP_RESPONSE",
                                    Map.of("httpStatus", response.getStatusCode().value()));
                            return response;
                        } catch (java.io.IOException | RuntimeException failure) {
                            span.fail(failure);
                            throw failure;
                        }
                    }
                })
                .build();
    }
}
