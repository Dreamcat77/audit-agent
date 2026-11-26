package com.fuquan.audit.security.infra;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods=false)
public class PersonalUploadWebConfiguration implements WebMvcConfigurer {
    private final PersonalUploadAccessInterceptor access;
    public PersonalUploadWebConfiguration(PersonalUploadAccessInterceptor access){this.access=access;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(access).addPathPatterns("/api/v1/**", "/api/blacklist/**");}
}
