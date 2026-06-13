package com.sparrow.common.web;

import com.sparrow.common.security.UserHeaderInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 下游 5 个 MVC 服务共用的 Web 配置。
 * 注意:CORS 统一在 gateway 配,这里不重复添加(否则响应头会重复)。
 */
@Configuration
public class CommonWebConfig implements WebMvcConfigurer {

    private final UserHeaderInterceptor userHeaderInterceptor;

    public CommonWebConfig(UserHeaderInterceptor userHeaderInterceptor) {
        this.userHeaderInterceptor = userHeaderInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userHeaderInterceptor)
                .addPathPatterns("/api/**", "/internal/**");
    }
}
