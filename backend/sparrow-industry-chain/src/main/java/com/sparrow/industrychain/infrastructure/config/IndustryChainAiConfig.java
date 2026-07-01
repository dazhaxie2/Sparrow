package com.sparrow.industrychain.infrastructure.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 产业链调研专用 LLM 配置。
 */
@Configuration
public class IndustryChainAiConfig {

    @Bean
    public ChatModel industryChainChatModel(IndustryChainProperties props) {
        if (!props.llmConfigured()) {
            return null;
        }
        return OpenAiChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.chatModel())
                .maxTokens(3000)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
