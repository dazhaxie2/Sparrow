package com.sparrow.industrychain.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 产业链调研服务配置属性。
 */
@ConfigurationProperties(prefix = "sparrow.industry-chain")
public record IndustryChainProperties(
        String baseUrl,
        String apiKey,
        String chatModel,
        Integer requestTimeoutSeconds,
        Integer maxRetries,
        int researchFreePerDay,
        int researchMemberPerDay,
        String searchUrl) {

    public boolean llmConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    public int effectiveRequestTimeoutSeconds() {
        return requestTimeoutSeconds != null && requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 45;
    }

    public int effectiveMaxRetries() {
        return maxRetries != null && maxRetries >= 0 ? maxRetries : 1;
    }
}
