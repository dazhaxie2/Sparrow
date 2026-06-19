package com.sparrow.ai.infrastructure.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI 模型配置类。
 * 创建并配置大语言模型和向量嵌入模型的 Bean。
 */
@Configuration
public class AiConfig {

    /**
     * 创建大语言模型 Bean。
     * 当未配置 AI_API_KEY 时返回 null,允许应用在无 LLM 环境下启动。
     *
     * @param props AI 配置属性
     * @return ChatModel 实例,未配置时返回 null
     */
    @Bean
    public ChatModel chatModel(AiProperties props) {
        if (!props.llmConfigured()) {
            return null;
        }
        return OpenAiChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.chatModel())
                // 显式放开输出上限:部分 OpenAI 兼容网关默认 max_tokens 偏小(512/1024)会硬截断长回答,
                // 给详尽问答留足空间;实际篇幅由提示词把控,避免无意义灌水。
                .maxTokens(3000)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * 创建向量嵌入模型 Bean。
     * 当未配置 AI_API_KEY 时返回 null,允许应用在无 LLM 环境下启动。
     *
     * @param props AI 配置属性
     * @return EmbeddingModel 实例,未配置时返回 null
     */
    @Bean
    public EmbeddingModel embeddingModel(AiProperties props) {
        if (!props.llmConfigured()) {
            return null;
        }
        return OpenAiEmbeddingModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.embeddingModel())
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
