package com.sparrow.ai.infrastructure.config;

import com.sparrow.common.ai.model.ModelConfigRecord;
import com.sparrow.common.ai.model.ModelScene;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI 模型配置类。
 * 创建并配置大语言模型和向量嵌入模型的 Bean。
 */
@Configuration
public class AiConfig {

    /** 启动期只读取每个场景一次，chat/streaming 共用同一份配置快照。 */
    @Bean
    public SparrowAiModelSelection modelSelection(ModelPoolConfigResolver resolver) {
        return new SparrowAiModelSelection(
                resolver.resolve(ModelScene.SPARROW_AI_CHAT),
                resolver.resolve(ModelScene.SPARROW_AI_EMBEDDING));
    }

    /**
     * 创建大语言模型 Bean。
     * 当未配置 AI_API_KEY 时返回 null,允许应用在无 LLM 环境下启动。
     *
     * @param selection 启动期模型池快照
     * @return ChatModel 实例,未配置时返回 null
     */
    @Bean
    public ChatModel chatModel(SparrowAiModelSelection selection) {
        ModelConfigRecord config = selection.chat();
        if (config == null) {
            return null;
        }
        return OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                // 显式放开输出上限:部分 OpenAI 兼容网关默认 max_tokens 偏小(512/1024)会硬截断长回答,
                // 给详尽问答留足空间;实际篇幅由提示词把控,避免无意义灌水。
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .maxRetries(config.maxRetries())
                .build();
    }

    /**
     * 创建流式大语言模型 Bean。
     * 用于逐 token 流式问答;1.9.0 起其 StreamingChatResponseHandler 暴露
     * onPartialThinking,可拿到 reasoning 模型的 reasoning_content(思考过程)。
     * 当未配置 AI_API_KEY 时返回 null。
     *
     * @param selection 启动期模型池快照
     * @return StreamingChatModel 实例,未配置时返回 null
     */
    @Bean
    public StreamingChatModel streamingChatModel(SparrowAiModelSelection selection) {
        ModelConfigRecord config = selection.chat();
        if (config == null) {
            return null;
        }
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    /**
     * 创建向量嵌入模型 Bean。
     * 当未配置 AI_API_KEY 时返回 null,允许应用在无 LLM 环境下启动。
     *
     * @param selection 启动期模型池快照
     * @return EmbeddingModel 实例,未配置时返回 null
     */
    @Bean
    public EmbeddingModel embeddingModel(SparrowAiModelSelection selection) {
        ModelConfigRecord config = selection.embedding();
        if (config == null) {
            return null;
        }
        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    public record SparrowAiModelSelection(ModelConfigRecord chat, ModelConfigRecord embedding) {
    }
}
