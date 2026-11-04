package com.fuquan.audit.common.web;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestAuditLoggingFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_USER_ID_ATTRIBUTE =
            RequestAuditLoggingFilter.class.getName() + ".authenticatedUserId";
    private static final Logger log = LoggerFactory.getLogger(RequestAuditLoggingFilter.class);
    private final ObjectProvider<FlowDebugLogger> flowDebugLoggerProvider;

    public RequestAuditLoggingFilter(ObjectProvider<FlowDebugLogger> flowDebugLoggerProvider) {
        this.flowDebugLoggerProvider = flowDebugLoggerProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        PipelineDiagnostics.Span diagnostic = isHealthRequest(request) ? null : PipelineDiagnostics.start("HTTP", "REQUEST", null,
                Map.of("inputBytes", Math.max(0, request.getContentLengthLong())));
        FlowDebugLogger flowDebugLogger = flowDebugLoggerProvider.getIfAvailable();
        if (flowDebugLogger != null && !isHealthRequest(request)) {
            flowDebugLogger.log(
                    "HTTP_IN",
                    "HTTP",
                    "WEB",
                    request.getMethod() + " " + request.getRequestURI(),
                    Map.of("method", request.getMethod(), "path", request.getRequestURI()));
        }
        try {
            filterChain.doFilter(request, response);
            if (diagnostic != null) diagnostic.outcome(response.getStatus() >= 400 ? "HTTP_REJECTED" : "HTTP_RESPONSE",
                    Map.of("httpStatus", response.getStatus()));
        } catch (IOException | ServletException | RuntimeException error) {
            if (diagnostic != null) diagnostic.fail(error);
            throw error;
        } finally {
            if (diagnostic != null) diagnostic.close();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            String userId = currentUserId(request);
            log.atInfo()
                    .addKeyValue("event", "HTTP_OUT")
                    .addKeyValue("module", "HTTP")
                    .addKeyValue("layer", "WEB")
                    .addKeyValue("operation", request.getMethod() + " " + request.getRequestURI())
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("userId", userId)
                    .addKeyValue("durationMs", durationMs)
                    .addKeyValue("outcome", response.getStatus())
                    .log(
                            "HTTP_OUT method={} path={} status={} durationMs={} userId={}",
                            request.getMethod(),
                            request.getRequestURI(),
                            response.getStatus(),
                            durationMs,
                            userId);
        }
    }

    private boolean isHealthRequest(HttpServletRequest request) {
        return "/api/v1/health".equals(request.getRequestURI())
                || "/actuator/health".equals(request.getRequestURI());
    }

    private String currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AUTHENTICATED_USER_ID_ATTRIBUTE);
        return value instanceof String userId && !userId.isBlank() ? userId : "anonymous";
    }
}
