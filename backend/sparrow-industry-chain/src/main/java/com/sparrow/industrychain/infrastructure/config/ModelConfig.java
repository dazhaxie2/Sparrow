package com.sparrow.industrychain.infrastructure.config;

import java.time.LocalDateTime;

/**
 * 模型配置记录。
 *
 * <p>{@code apiKeyMasked} 仅供 API 返回展示(脱敏),{@code apiKeyPlain} 仅在需要实际建模型时解密填充,
 * 二者不同时出现。通过静态工厂 {@link #forView} / {@link #decrypted} 区分用途,避免明文泄漏。
 */
public record ModelConfig(
        Long id,
        String name,
        String baseUrl,
        String modelName,
        String apiKeyMasked,
        String apiKeyPlain,
        int maxTokens,
        int timeoutSeconds,
        int maxRetries,
        boolean active,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 视图层:apiKey 脱敏,永不返回明文。 */
    public static ModelConfig forView(Long id, String name, String baseUrl, String modelName,
                                      String apiKeyMasked, int maxTokens, int timeoutSeconds,
                                      int maxRetries, boolean active, Long createdBy,
                                      LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new ModelConfig(id, name, baseUrl, modelName, apiKeyMasked, null,
                maxTokens, timeoutSeconds, maxRetries, active, createdBy, createdAt, updatedAt);
    }

    /** 建模型层:apiKey 明文(已解密),不用于 API 返回。 */
    public static ModelConfig decrypted(Long id, String name, String baseUrl, String modelName,
                                        String apiKeyPlain, int maxTokens, int timeoutSeconds,
                                        int maxRetries, boolean active) {
        return new ModelConfig(id, name, baseUrl, modelName, null, apiKeyPlain,
                maxTokens, timeoutSeconds, maxRetries, active, null, null, null);
    }

    /** 脱敏显示:仅保留首尾片段,中间用 **** 代替。 */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        int len = apiKey.length();
        if (len <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(len - 4);
    }
}
