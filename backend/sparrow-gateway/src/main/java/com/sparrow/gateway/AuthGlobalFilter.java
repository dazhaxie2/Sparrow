package com.sparrow.gateway;

import com.sparrow.common.security.TokenKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关边界鉴权过滤器:
 * 1) 无条件剥除入站 X-User-Id(防伪造,必须先于其它逻辑)
 * 2) 有 Bearer token 且查 Redis 命中时注入 X-User-Id
 * 3) 查不到不拒绝——保留 Phase 1 的"匿名可看 /api/graph/tree"行为
 *    下游受保护端点用 UserContext.require() 抛 401
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private final ReactiveStringRedisTemplate redis;

    public AuthGlobalFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1) 先剥除伪造头
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(h -> h.remove(TokenKeys.USER_ID_HEADER))
                .build();
        ServerWebExchange strippedExchange = exchange.mutate().request(stripped).build();

        String auth = stripped.getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return chain.filter(strippedExchange);
        }
        String token = auth.substring(7).trim();
        if (token.isEmpty()) {
            return chain.filter(strippedExchange);
        }

        return redis.opsForValue().get(TokenKeys.TOKEN_KEY_PREFIX + token)
                .flatMap(tokenValue -> authenticatedRequest(stripped, tokenValue))
                .defaultIfEmpty(stripped)
                .flatMap(request -> chain.filter(exchange.mutate().request(request).build()))
                .onErrorResume(e -> {
                    log.warn("Redis 查 token 失败,按匿名处理: {}", e.getMessage());
                    return chain.filter(strippedExchange);
                });
    }

    /**
     * Token value supports both the legacy {@code userId} form and the new
     * {@code userId:authVersion} form. A password change increments the per-user
     * version, which invalidates every older token without scanning Redis.
     */
    private Mono<ServerHttpRequest> authenticatedRequest(ServerHttpRequest request, String tokenValue) {
        String[] parts = tokenValue == null ? new String[0] : tokenValue.split(":", 2);
        if (parts.length == 0 || !parts[0].matches("\\d+")) {
            return Mono.empty();
        }
        String userId = parts[0];
        long tokenVersion;
        try {
            tokenVersion = parts.length == 2 ? Long.parseLong(parts[1]) : 0L;
        } catch (NumberFormatException error) {
            return Mono.empty();
        }
        return redis.opsForValue().get(TokenKeys.TOKEN_VERSION_PREFIX + userId)
                .filter(value -> {
                    try {
                        return tokenVersion == Long.parseLong(value);
                    } catch (NumberFormatException error) {
                        return false;
                    }
                })
                .map(ignored -> withUserHeader(request, userId))
                // No version marker means this user has not changed their password since
                // the rollout, so legacy tokens remain valid until their normal TTL expires.
                .switchIfEmpty(redis.hasKey(TokenKeys.TOKEN_VERSION_PREFIX + userId)
                        .flatMap(exists -> exists ? Mono.empty() : Mono.just(withUserHeader(request, userId))));
    }

    private ServerHttpRequest withUserHeader(ServerHttpRequest request, String userId) {
        return request.mutate().header(TokenKeys.USER_ID_HEADER, userId).build();
    }

    @Override
    public int getOrder() {
        return -100; // 路由前
    }
}
