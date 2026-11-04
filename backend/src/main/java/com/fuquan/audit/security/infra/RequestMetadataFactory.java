package com.fuquan.audit.security.infra;

import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.security.application.LoginRequestMetadata;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 从当前 HTTP 请求和可观测上下文创建认证审计元数据。
 *
 * <p>生成的 LoginRequestMetadata 仅供当前登录/退出请求和长期审计写入使用，不应进入 SecurityContext 或 Session。</p>
 */
@Component
public class RequestMetadataFactory {

    private final Tracer tracer;

    /**
     * 创建使用当前追踪 Span 的请求元数据工厂。
     *
     * @param tracer Micrometer 当前链路追踪器
     */
    public RequestMetadataFactory(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * 采集 RequestId、TraceId 和 Servlet 解析的来源 IP。
     *
     * @param request 当前 HTTP 请求
     * @return 生命周期仅限本次认证或退出审计的请求元数据
     */
    public LoginRequestMetadata from(HttpServletRequest request) {
        Span currentSpan = tracer.currentSpan();
        String traceId = currentSpan == null ? null : currentSpan.context().traceId();
        return new LoginRequestMetadata(
                MDC.get(RequestIdFilter.MDC_KEY),
                traceId,
                request.getRemoteAddr());
    }
}
