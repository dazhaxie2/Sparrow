package com.sparrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sparrow.ai")
public record AiProperties(String baseUrl, String apiKey, String chatModel,
                           String embeddingModel, int freeQuotaPerDay) {

    public boolean llmConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
