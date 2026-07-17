package com.sparrow.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Prevents an HTML shell from outliving the hashed assets it references while
 * keeping immutable Vite assets cacheable for a year.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FrontendCacheControlFilter implements WebFilter {

    static final String DEPLOY_SHA_HEADER = "X-Sparrow-Deploy-Sha";
    static final String NO_STORE = "no-store, no-cache, must-revalidate";
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final String deploySha;

    public FrontendCacheControlFilter(@Value("${SPARROW_DEPLOY_SHA:unknown}") String deploySha) {
        this.deploySha = deploySha;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isFrontendMetadata(path) || isFrontendShell(path)) {
            exchange.getResponse().beforeCommit(() -> {
                HttpHeaders headers = exchange.getResponse().getHeaders();
                headers.set(HttpHeaders.CACHE_CONTROL, NO_STORE);
                headers.set(HttpHeaders.PRAGMA, "no-cache");
                headers.set(HttpHeaders.EXPIRES, "0");
                headers.set(DEPLOY_SHA_HEADER, deploySha);
                return Mono.empty();
            });
        } else if (path.startsWith("/assets/")) {
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, IMMUTABLE);
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    private static boolean isFrontendMetadata(String path) {
        return "/version.json".equals(path);
    }

    private static boolean isFrontendShell(String path) {
        return "/".equals(path)
                || "/index.html".equals(path)
                || "/pay".equals(path)
                || path.startsWith("/pay/")
                || "/chains".equals(path)
                || path.startsWith("/chains/");
    }
}
