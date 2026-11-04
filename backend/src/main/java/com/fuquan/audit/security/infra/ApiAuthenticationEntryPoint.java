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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 将未认证或 Session 已失效请求统一映射为 JSON 401 响应。
 *
 * <p>该入口不执行页面跳转，也不引入 JWT/Bearer 认证流程。</p>
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /**
     * 创建使用公共响应结构输出错误的未认证入口。
     *
     * @param objectMapper JSON 序列化器
     */
    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写出 UTF-8 JSON 401 响应而不重定向登录页。
     *
     * @param request 未认证的 HTTP 请求
     * @param response 待写入的 HTTP 响应
     * @param exception Spring Security 认证异常
     * @throws IOException 写响应流失败时抛出
     * @throws ServletException Servlet 处理链报告错误时抛出
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(
                HttpStatus.UNAUTHORIZED.value(),
                BusinessErrorCode.UNAUTHENTICATED.defaultMessage()));
    }
}
