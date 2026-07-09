package com.sparrow.industrychain.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ChatModel 访问门面：封装 LangChain4j ChatModel 的可空性(未配置 AI_API_KEY 时 bean 为 null)。
 *
 * <p>让 Multi-Agent / 报告 Builder 等组件通过统一接口获取模型，避免到处重复 null 检查与直接注入可空 bean。
 * 用 {@code @Component} 让 Spring 注入 ChatModel(可能为 null)并对外暴露 {@link #chat(String)} 等便捷方法。
 */
@Component
public class ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ChatModelProvider.class);

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

    /**
     * 软降级对话：模型未配置 <em>或调用失败</em>(限流/超时/网络异常)时返回 fallback。
     *
     * <p>用于规划 Agent 等面向用户的同步路径——云端 LLM 触发速率限制(HttpException 1302)
     * 不应冒泡成 HTTP 500,而应降级为可控的提示文案,与 {@code ResearchRunner} 的兜底风格一致。
     */
    public String chatOr(String prompt, String fallback) {
        if (chatModel == null) return fallback;
        try {
            return chatModel.chat(prompt);
        } catch (Exception error) {
            log.warn("ChatModel 调用失败,返回 fallback: {}", error.toString());
            return fallback;
        }
    }

    /** 暴露底层模型，供需要工具/流式等高级特性的组件使用。 */
    public ChatModel model() {
        return chatModel;
    }
}



