package com.sparrow.industrychain.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

/**
 * ChatModel 访问门面：封装 LangChain4j ChatModel 的可空性(未配置 AI_API_KEY 时 bean 为 null)。
 *
 * <p>让 Multi-Agent / 报告 Builder 等组件通过统一接口获取模型，避免到处重复 null 检查与直接注入可空 bean。
 * 用 {@code @Component} 让 Spring 注入 ChatModel(可能为 null)并对外暴露 {@link #chat(String)} 等便捷方法。
 */
@Component
public class ChatModelProvider {

    private final ChatModel chatModel;

    public ChatModelProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 模型是否可用(已配置 AI_API_KEY)。 */
    public boolean available() {
        return chatModel != null;
    }

    /** 同步对话：模型不可用时抛出，由调用方决定降级文案。 */
    public String chat(String prompt) {
        if (chatModel == null) {
            throw new IllegalStateException("AI 服务尚未配置");
        }
        return chatModel.chat(prompt);
    }

    /** 模型不可用时返回 fallback，否则同步对话。用于规划 Agent 等需要软降级的场景。 */
    public String chatOr(String prompt, String fallback) {
        if (chatModel == null) return fallback;
        return chatModel.chat(prompt);
    }

    /** 暴露底层模型，供需要工具/流式等高级特性的组件使用。 */
    public ChatModel model() {
        return chatModel;
    }
}


