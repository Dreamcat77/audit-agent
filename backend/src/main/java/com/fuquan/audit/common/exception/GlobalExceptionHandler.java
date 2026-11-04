package com.fuquan.audit.common.exception;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.SafeExceptionDiagnostics;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.common.web.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final FlowDebugLogger flowDebugLogger;

    public GlobalExceptionHandler(Optional<FlowDebugLogger> flowDebugLogger) {
        this.flowDebugLogger = flowDebugLogger.orElse(null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        try (var span = PipelineDiagnostics.start("DATABASE", "HTTP_EXCEPTION", null, Map.of())) {
            span.fail(exception);
        }
        SafeExceptionDiagnostics.SafeStackFrame frame =
                SafeExceptionDiagnostics.safeTopStackFrame(exception);
        SafeExceptionDiagnostics.SafeSqlDiagnostic sql =
                SafeExceptionDiagnostics.safeSqlDiagnostic(exception);
        log.error("event=DATABASE_CONSTRAINT_FAILURE diagnosticSchemaVersion=1 requestId={} traceId={} "
                        + "module=DATABASE layer=HTTP operation=dataIntegrityViolation stage=HTTP_EXCEPTION "
                        + "outcome=FAILED reasonCode=DATA_INTEGRITY_VIOLATION exceptionType={} "
                        + "rootCauseType={} sqlState={} vendorCode={} "
                        + "stackClass={} stackMethod={} stackLine={}",
                MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"), exception.getClass().getName(),
                SafeExceptionDiagnostics.findRootCauseType(exception), sql.sqlState(), sql.vendorCode(),
                frame.className(), frame.methodName(), frame.lineNumber());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(500, BusinessErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleExternalServiceException(
            ExternalServiceException exception) {
        try (var span = PipelineDiagnostics.start("INTEGRATION", "HTTP_EXCEPTION", null, Map.of())) {
            span.fail(exception);
        }
        log.warn("requestId={} module=integration action=externalService result=error service={} causeType={}",
                MDC.get(RequestIdFilter.MDC_KEY),
                exception.serviceType(),
                exception.getCause().getClass().getName());
        HttpStatus status = BusinessErrorCode.EXTERNAL_SERVICE_ERROR.httpStatus();
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(
                        status.value(),
                        BusinessErrorCode.EXTERNAL_SERVICE_ERROR.defaultMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        HttpStatus status = exception.errorCode().httpStatus();
        if (status.is5xxServerError()) {
            log.error(
                    "requestId={} traceId={} module=common stage=HTTP_EXCEPTION operation=businessResponse result=error errorCode={} status={}",
                    MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"), exception.errorCode(), status.value());
        } else {
            if (flowDebugLogger != null) {
                flowDebugLogger.log(
                        "BUSINESS_REQUEST_REJECTED",
                        "COMMON",
                        "WEB",
                        "businessResponse",
                        Map.of("errorCode", exception.errorCode(), "status", status.value()));
            }
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(status.value(), exception.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(
            AuthenticationException exception) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(
                        status.value(),
                        BusinessErrorCode.UNAUTHENTICATED.defaultMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        HttpStatus status = HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(status.value(), BusinessErrorCode.FORBIDDEN.defaultMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(400, "validation failed", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequestBody(
            HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(400, "malformed request body"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(400, "constraint validation failed"));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidHttpArgument(Exception exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(400, BusinessErrorCode.INVALID_ARGUMENT.defaultMessage()));
    }

    @ExceptionHandler({
            MissingServletRequestPartException.class,
            MultipartException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleMultipartError(Exception exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(400, "预算材料上传请求无效"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        HttpStatus status = HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(status.value(), BusinessErrorCode.NOT_FOUND.defaultMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception) {
        HttpStatus status = HttpStatus.METHOD_NOT_ALLOWED;
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(status.value(), "request method is not supported"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.failure(HttpStatus.PAYLOAD_TOO_LARGE.value(), "upload exceeds request size limit"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        try (var span = PipelineDiagnostics.start("COMMON", "HTTP_EXCEPTION", null, Map.of())) {
            span.fail(exception);
        }
        SafeExceptionDiagnostics.SafeStackFrame frame =
                SafeExceptionDiagnostics.safeTopStackFrame(exception);
        log.error(
                "requestId={} traceId={} module=common stage=HTTP_EXCEPTION operation=handleUnexpected "
                        + "result=error exceptionType={} rootCauseType={} "
                        + "stackClass={} stackMethod={} stackLine={}",
                MDC.get(RequestIdFilter.MDC_KEY),
                MDC.get("traceId"),
                exception.getClass().getName(),
                SafeExceptionDiagnostics.findRootCauseType(exception),
                frame.className(),
                frame.methodName(),
                frame.lineNumber());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(500, BusinessErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

}
