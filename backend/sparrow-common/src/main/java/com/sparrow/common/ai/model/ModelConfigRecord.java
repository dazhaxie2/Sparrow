package com.sparrow.common.ai.model;

/**
 * 模型配置共享契约(纯数据,不含加解密)。
 *
 * <p>两端服务通过此 record 传递模型配置的脱敏/明文视图。加解密(API Key 的 AES-GCM)
 * 仍由 owning 服务(sparrow-industry-chain)的 {@code ModelConfigRepository} 负责;
 * 本 record 只是跨服务传输的数据形状,与 {@code AiAgentProfile} 的设计一致——
 * common 放契约,服务放实现。</p>
 *
 * <p>{@code apiKey} 字段:在 Feign 响应中是明文(仅内网调用,用于建模型);
 * 在前端 API 响应中应为脱敏值(由 owning 服务的 Repository.mask 处理)。</p>
 */
public record ModelConfigRecord(
        Long id,
        String name,
        String baseUrl,
        String modelName,
        String apiKey,
        int maxTokens,
        int timeoutSeconds,
        int maxRetries,
        boolean active,
        ModelScene scene,
        ModelKind kind) {

    /** 防止调试日志或异常消息通过 record 默认 toString 泄露明文 API Key。 */
    @Override
    public String toString() {
        return "ModelConfigRecord[id=" + id + ", name=" + name + ", baseUrl=" + baseUrl
                + ", modelName=" + modelName + ", apiKey=***, maxTokens=" + maxTokens
                + ", timeoutSeconds=" + timeoutSeconds + ", maxRetries=" + maxRetries
                + ", active=" + active + ", scene=" + scene + ", kind=" + kind + "]";
    }
}
