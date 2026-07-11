package com.sparrow.gateway;

import com.sparrow.common.security.TokenKeys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthGlobalFilterTest {

    @Test
    void validTokenInjectsUserHeaderAndInvokesChainOnce() {
        ReactiveStringRedisTemplate redis = mockRedisGet("abc", Mono.just("42:0"), Mono.empty(), false);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/me")
                .header("Authorization", "Bearer abc")
                .header(TokenKeys.USER_ID_HEADER, "999")
                .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.calls).hasValue(1);
        assertThat(chain.lastRequest.get().getHeaders().getFirst(TokenKeys.USER_ID_HEADER)).isEqualTo("42");
    }

    @Test
    void missingTokenStripsForgedUserHeader() {
        ReactiveStringRedisTemplate redis = mockRedisGet("missing", Mono.empty(), Mono.empty(), false);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/graph/tree")
                .header("Authorization", "Bearer missing")
                .header(TokenKeys.USER_ID_HEADER, "999")
                .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.calls).hasValue(1);
        assertThat(chain.lastRequest.get().getHeaders()).doesNotContainKey(TokenKeys.USER_ID_HEADER);
    }

    @Test
    void redisErrorFallsBackToAnonymousRequest() {
        ReactiveStringRedisTemplate redis = mockRedisGet("abc",
                Mono.error(new IllegalStateException("redis down")), Mono.empty(), false);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/me")
                .header("Authorization", "Bearer abc")
                .header(TokenKeys.USER_ID_HEADER, "999")
                .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.calls).hasValue(1);
        assertThat(chain.lastRequest.get().getHeaders()).doesNotContainKey(TokenKeys.USER_ID_HEADER);
    }

    @Test
    void tokenIssuedBeforePasswordChangeIsRejected() {
        ReactiveStringRedisTemplate redis = mockRedisGet("old", Mono.just("42:0"), Mono.just("1"), true);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/me")
                .header("Authorization", "Bearer old").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.lastRequest.get().getHeaders()).doesNotContainKey(TokenKeys.USER_ID_HEADER);
    }

    @Test
    void tokenWithCurrentAuthVersionRemainsValid() {
        ReactiveStringRedisTemplate redis = mockRedisGet("new", Mono.just("42:1"), Mono.just("1"), true);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/me")
                .header("Authorization", "Bearer new").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.lastRequest.get().getHeaders().getFirst(TokenKeys.USER_ID_HEADER)).isEqualTo("42");
    }

    @Test
    void legacyTokenIsRejectedAfterPasswordChange() {
        ReactiveStringRedisTemplate redis = mockRedisGet("legacy", Mono.just("42"), Mono.just("1"), true);
        AuthGlobalFilter filter = new AuthGlobalFilter(redis);
        CapturingChain chain = new CapturingChain();
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/me")
                .header("Authorization", "Bearer legacy").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.lastRequest.get().getHeaders()).doesNotContainKey(TokenKeys.USER_ID_HEADER);
    }

    @SuppressWarnings("unchecked")
    private static ReactiveStringRedisTemplate mockRedisGet(String token, Mono<String> tokenResult,
                                                             Mono<String> authVersion, boolean versionKeyExists) {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(TokenKeys.TOKEN_KEY_PREFIX + token)).thenReturn(tokenResult);
        when(ops.get(TokenKeys.TOKEN_VERSION_PREFIX + "42")).thenReturn(authVersion);
        when(redis.hasKey(TokenKeys.TOKEN_VERSION_PREFIX + "42"))
                .thenReturn(Mono.just(versionKeyExists));
        return redis;
    }

    private static final class CapturingChain implements GatewayFilterChain {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ServerHttpRequest> lastRequest = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            calls.incrementAndGet();
            lastRequest.set(exchange.getRequest());
            return Mono.empty();
        }
    }
}
