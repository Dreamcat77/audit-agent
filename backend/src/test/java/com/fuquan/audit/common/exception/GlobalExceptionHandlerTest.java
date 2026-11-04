package com.fuquan.audit.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.web.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    @Test void genericMissingTableFailureRetainsDatabaseCodeWithoutSqlText(CapturedOutput output) {
        MDC.put(RequestIdFilter.MDC_KEY, "synthetic-missing-table-request");
        var error = new org.springframework.jdbc.BadSqlGrammarException("SECRET_SQL_SENTINEL", "SECRET_SQL_SENTINEL",
                new java.sql.SQLException("SECRET_DATABASE_SENTINEL", "42000", -2106));
        assertThat(handler.handleUnexpected(error).getStatusCode().value()).isEqualTo(500);
        assertThat(output).contains("reason=DATABASE_ERROR", "sqlState=42000", "sqlErrorCode=-2106")
                .doesNotContain("SECRET_SQL_SENTINEL", "SECRET_DATABASE_SENTINEL");
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            java.util.Optional.of(mock(FlowDebugLogger.class)));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @EnumSource(BusinessErrorCode.class)
    void mapsEveryBusinessErrorToItsHttpStatus(BusinessErrorCode errorCode) {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusinessException(new BusinessException(errorCode));

        assertThat(response.getStatusCode()).isEqualTo(errorCode.httpStatus());
        assertThat(Objects.requireNonNull(response.getBody()).code())
                .isEqualTo(errorCode.httpStatus().value());
        assertThat(response.getBody().message()).isEqualTo(errorCode.defaultMessage());
    }

    @Test
    void hidesUnexpectedExceptionDetailsAndLogsSafeFrame(CapturedOutput output) {
        MDC.put(RequestIdFilter.MDC_KEY, "safe-request-id");
        MDC.put("traceId", "safe-trace-id");
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpected(new IllegalStateException(
                        "sensitive internal detail at D:\\private\\storage\\secret.bin"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(Objects.requireNonNull(response.getBody()).message())
                .isEqualTo("internal server error");
        assertThat(output).contains("requestId=safe-request-id")
                .contains("traceId=safe-trace-id")
                .contains("exceptionType=java.lang.IllegalStateException")
                .contains("stackClass=")
                .doesNotContain("sensitive internal detail")
                .doesNotContain("private\\storage");
    }

    @Test
    void mapsConstraintViolationToBadRequestWithoutDetails() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleConstraintViolation(new ConstraintViolationException(null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(Objects.requireNonNull(response.getBody()).code()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("constraint validation failed");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    void logsOnlySafeDatabaseDiagnosticsWithoutRootMessageOrThrowable(CapturedOutput output) {
        MDC.put(RequestIdFilter.MDC_KEY, "database-request-id");
        MDC.put("traceId", "database-trace-id");
        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("insert failed",
                        new java.sql.SQLException(
                                "PASSWORD-CANARY BUSINESS_SQL_SENTINEL Violate referenced constraint",
                                "23000", 23503)));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(Objects.requireNonNull(response.getBody()).message()).isEqualTo("internal server error");
        assertThat(output).contains("requestId=database-request-id")
                .contains("traceId=database-trace-id")
                .contains("rootCauseType=java.sql.SQLException")
                .contains("sqlState=23000")
                .contains("vendorCode=23503")
                .contains("stackClass=")
                .contains("rootCauseClass=SQLException")
                .contains("reason=DATABASE_ERROR")
                .contains("sqlErrorCode=23503")
                .doesNotContain("PASSWORD-CANARY")
                .doesNotContain("BUSINESS_SQL_SENTINEL")
                .doesNotContain("Violate referenced constraint")
                .doesNotContain("insert failed");
    }

    @Test
    void mapsMultipartLimitToPayloadTooLarge() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(50L * 1024 * 1024));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(Objects.requireNonNull(response.getBody()).code()).isEqualTo(413);
        assertThat(response.getBody().message()).isEqualTo("upload exceeds request size limit");
    }

    @Test
    void mapsExternalFailureToFixedResponseAndLogsOnlySafeDiagnostics(CapturedOutput output) {
        IllegalStateException cause = new IllegalStateException(
                "sensitive response body from http://internal-service");
        ExternalServiceException exception =
                new ExternalServiceException(ExternalServiceType.OCR, cause);
        MDC.put(RequestIdFilter.MDC_KEY, "safe-request-id");

        ResponseEntity<ApiResponse<Void>> response = handler.handleExternalServiceException(exception);

        assertThat(exception.serviceType()).isEqualTo(ExternalServiceType.OCR);
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(Objects.requireNonNull(response.getBody()).code()).isEqualTo(502);
        assertThat(response.getBody().message()).isEqualTo("external service unavailable");
        assertThat(response.getBody().data()).isNull();
        assertThat(output).contains("requestId=safe-request-id")
                .contains("service=OCR")
                .contains("causeType=java.lang.IllegalStateException")
                .doesNotContain("sensitive response body")
                .doesNotContain("internal-service");
    }
}
