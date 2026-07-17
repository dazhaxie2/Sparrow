package com.sparrow.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendCacheControlFilterTest {

    private static final String REVISION = "0123456789abcdef";
    private final FrontendCacheControlFilter filter = new FrontendCacheControlFilter(REVISION);

    @Test
    void frontendShellCannotBeServedFromAStaleCache() {
        MockServerWebExchange exchange = exchange("/chains/42");

        apply(exchange);

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo(FrontendCacheControlFilter.NO_STORE);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(exchange.getResponse().getHeaders().getFirst(FrontendCacheControlFilter.DEPLOY_SHA_HEADER))
                .isEqualTo(REVISION);
    }

    @Test
    void versionMetadataCannotBeServedFromAStaleCache() {
        MockServerWebExchange exchange = exchange("/version.json");

        apply(exchange);

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo(FrontendCacheControlFilter.NO_STORE);
        assertThat(exchange.getResponse().getHeaders().getFirst(FrontendCacheControlFilter.DEPLOY_SHA_HEADER))
                .isEqualTo(REVISION);
    }

    @Test
    void hashedAssetsRemainLongLived() {
        MockServerWebExchange exchange = exchange("/assets/index-abc123.js");

        apply(exchange);

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo(FrontendCacheControlFilter.IMMUTABLE);
        assertThat(exchange.getResponse().getHeaders())
                .doesNotContainKey(FrontendCacheControlFilter.DEPLOY_SHA_HEADER);
    }

    @Test
    void apiResponsesAreNotModified() {
        MockServerWebExchange exchange = exchange("/api/user/me");

        apply(exchange);

        assertThat(exchange.getResponse().getHeaders()).doesNotContainKey(HttpHeaders.CACHE_CONTROL);
        assertThat(exchange.getResponse().getHeaders())
                .doesNotContainKey(FrontendCacheControlFilter.DEPLOY_SHA_HEADER);
    }

    private void apply(MockServerWebExchange exchange) {
        WebFilterChain chain = current -> current.getResponse().setComplete();
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
