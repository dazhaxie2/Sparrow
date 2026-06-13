package com.sparrow.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

/**
 * 兼容 Phase 1:GET /pay 直接 forward 到前端打包后的 index.html(SPA 收银台)。
 * 静态资源由 spring.web.resources.static-locations 暴露;此处只处理路径覆盖。
 */
@Configuration
public class PayPageRouter {

    @Bean
    public RouterFunction<ServerResponse> payIndex() {
        return RouterFunctions.route(GET("/pay").or(path("/pay/**")), req -> {
            Resource index = new FileSystemResource("/app/static/index.html");
            if (!index.exists()) {
                return ServerResponse.notFound().build();
            }
            return ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(index);
        });
    }
}
