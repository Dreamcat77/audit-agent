package com.fuquan.audit.security.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 将已认证但无权限的 Spring Security 拒绝统一映射为 JSON 403 响应。
 *
 * <p>403 不代表 Session 失效，客户端不应因此清除登录态。</p>
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * 创建使用公共响应结构输出错误的权限拒绝处理器。
     *
     * @param objectMapper JSON 序列化器
     */
    public ApiAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写出 UTF-8 JSON 403 响应而不重定向 HTML 页面。
     *
     * @param request 被拒绝的 HTTP 请求
     * @param response 待写入的 HTTP 响应
     * @param exception Spring Security 权限拒绝异常
     * @throws IOException 写响应流失败时抛出
     * @throws ServletException Servlet 处理链报告错误时抛出
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException, ServletException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(
                HttpStatus.FORBIDDEN.value(),
                BusinessErrorCode.FORBIDDEN.defaultMessage()));
    }
}
