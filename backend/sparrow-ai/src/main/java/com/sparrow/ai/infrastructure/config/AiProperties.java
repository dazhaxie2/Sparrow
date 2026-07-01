package com.sparrow.ai.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务配置属性。
 * 通过 sparrow.ai 前缀配置大语言模型和嵌入模型的参数。
 */
@ConfigurationProperties(prefix = "sparrow.ai")
public record AiProperties(
        /**
         * LLM 服务基础 URL
         */
        String baseUrl,
        /**
         * API 密钥
         */
        String apiKey,
        /**
         * 对话模型名称
         */
        String chatModel,
        /**
         * 嵌入模型名称
         */
        String embeddingModel,
        /**
         * 免费用户每日配额
         */
        int freeQuotaPerDay) {

    /**
     * 检查 LLM 是否已配置。
     *
     * @return true 表示 baseUrl 和 apiKey 都已配置
     */
    public boolean llmConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
