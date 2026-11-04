package com.fuquan.audit.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void missingHeaderGeneratesUuidForMdcAndResponse() throws Exception {
        FilterResult result = execute(null);

        assertGeneratedUuid(result.finalRequestId());
        assertThat(result.mdcRequestIdInsideChain()).isEqualTo(result.finalRequestId());
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void validHeaderIsPassedThroughUnchanged() throws Exception {
        String requestId = "Valid.request_Id-20260814";

        FilterResult result = execute(requestId);

        assertThat(result.finalRequestId()).isEqualTo(requestId);
        assertThat(result.mdcRequestIdInsideChain()).isEqualTo(requestId);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void headerAtMaximumLengthIsPassedThroughUnchanged() throws Exception {
        String requestId = "a".repeat(128);

        FilterResult result = execute(requestId);

        assertThat(result.finalRequestId()).isEqualTo(requestId);
        assertThat(result.mdcRequestIdInsideChain()).isEqualTo(requestId);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void headerLongerThan128CharactersIsReplaced() throws Exception {
        assertReplacedWithUuid("a".repeat(129));
    }

    @Test
    void headerWithDisallowedCharacterIsReplaced() throws Exception {
        assertReplacedWithUuid("invalid request id");
    }

    @Test
    void blankHeaderIsReplaced() throws Exception {
        assertReplacedWithUuid("   ");
    }

    @Test
    void headerWithControlCharacterIsReplaced() throws Exception {
        assertReplacedWithUuid("client\u0001request");
    }

    private void assertReplacedWithUuid(String invalidRequestId) throws Exception {
        FilterResult result = execute(invalidRequestId);

        assertThat(result.finalRequestId()).isNotEqualTo(invalidRequestId);
        assertGeneratedUuid(result.finalRequestId());
        assertThat(result.mdcRequestIdInsideChain()).isEqualTo(result.finalRequestId());
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    private FilterResult execute(String incomingRequestId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incomingRequestId != null) {
            request.addHeader(RequestIdFilter.HEADER_NAME, incomingRequestId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcRequestId = new AtomicReference<>();

        filter.doFilter(request, response,
                (servletRequest, servletResponse) -> mdcRequestId.set(MDC.get(RequestIdFilter.MDC_KEY)));

        return new FilterResult(
                response.getHeader(RequestIdFilter.HEADER_NAME),
                mdcRequestId.get());
    }

    private void assertGeneratedUuid(String requestId) {
        assertThat(requestId).isNotBlank();
        assertThat(UUID.fromString(requestId)).isNotNull();
    }

    private record FilterResult(String finalRequestId, String mdcRequestIdInsideChain) {
    }
}
