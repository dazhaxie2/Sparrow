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

/**
 * SPA history 路由回退：直接访问 /pay 或 /chains/** 时返回前端 index.html。
 * 静态资源由 spring.web.resources.static-locations 暴露；API 路径不进入此路由。
 */
@Configuration
public class PayPageRouter {

    @Bean
    public RouterFunction<ServerResponse> spaIndex() {
        return RouterFunctions.route(
                GET("/pay").or(GET("/pay/**"))
                        .or(GET("/chains")).or(GET("/chains/**")), req -> {
            Resource index = new FileSystemResource("/app/static/index.html");
            if (!index.exists()) {
                return ServerResponse.notFound().build();
            }
            return ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(index);
        });
    }
}
