package com.fuquan.audit.security.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定允许携带 Session Cookie 的前端 CORS 来源。
 *
 * <p>安全配置会拒绝通配来源，并仅在来源列表非空时启用跨域凭据。</p>
 */
@ConfigurationProperties("audit.security.cors")
public class CorsProperties {

    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * 返回配置的可信前端 Origin 列表。
     *
     * @return CORS 允许来源
     */
    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    /**
     * 供配置绑定设置可信前端 Origin 列表。
     *
     * @param allowedOrigins 不应包含通配符的来源列表
     */
    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
