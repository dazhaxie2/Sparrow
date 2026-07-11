package com.sparrow.ai.infrastructure.agent;

import com.sparrow.ai.application.config.AiAgentConfigService;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Agent 配置类。
 * 创建并配置 TechTreeAgent,整合工具链和对话记忆管理。
 */
@Configuration
public class AgentConfig {

    /**
     * 创建 TechTreeAgent Bean。
     * 当 ChatModel 可用时,配置带有工具链和 LRU 对话记忆的 Agent。
     *
     * <p>不用 {@code @ConditionalOnBean(ChatModel.class)}:该条件只能看到"已注册"的 bean,
     * 对用户 {@code @Configuration} 的解析顺序敏感。本类(AgentConfig)类名按字母序排在
     * AiConfig 之前被解析,判定时 ChatModel 尚未注册 → 条件恒假 → Agent bean 永不创建 →
     * AiService 拿到 null、对话恒降级为 RAG。改为直接注入 {@code @Nullable ChatModel} 并在
     * 方法内判空:有 key 时 chatModel bean 必然就位,Agent 一定创建;无 key 时入参为 null,
     * 返回 null,由 AiService 的降级链兜底。
     *
     * @param chatModel          大语言模型,可为 null
     * @param streamingChatModel 流式大语言模型,可为 null;非空时启用 {@link TechTreeAgent#chatStream} 的流式能力
     * @param graphQueryTool     图谱查询工具
     * @param vectorSearchTool   向量搜索工具
     * @param userProgressTool   用户进度工具
     * @param maxSessions        最大会话数,超出后按 LRU 淘汰
     * @return TechTreeAgent 实例,若 chatModel 为 null 则返回 null
     */
    @Bean
    public TechTreeAgent techTreeAgent(@Nullable ChatModel chatModel,
                                       @Nullable StreamingChatModel streamingChatModel,
                                       GraphQueryTool graphQueryTool,
                                       VectorSearchTool vectorSearchTool,
                                       UserProgressTool userProgressTool,
                                       AiAgentConfigService agentConfigService,
                                       @Value("${sparrow.ai.agent.max-sessions:500}") int maxSessions) {
        if (chatModel == null) {
            return null;
        }
        // 按 LRU 上限保留最近活跃会话的对话记忆,超出上限淘汰最久未访问的会话,
        // 防止 memoryId(=userId) 持续增长导致内存无界膨胀。
        Map<String, ChatMemory> memories = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, ChatMemory> eldest) {
                        return size() > maxSessions;
                    }
                });
        AiServices<TechTreeAgent> builder = AiServices.builder(TechTreeAgent.class)
                .chatModel(chatModel)
                .systemMessageProvider(memoryId -> agentConfigService.runtime().systemPrompt())
                .tools(graphQueryTool, vectorSearchTool, userProgressTool)
                .chatMemoryProvider(memoryId -> {
                    String key = (String) memoryId;
                    return memories.computeIfAbsent(key,
                            k -> MessageWindowChatMemory.withMaxMessages(20));
                });
        // 只有配置了流式模型,chatStream(TokenStream) 才能逐 token 返回;
        // 否则 AiServices 在调用 chatStream 时会因缺少 streamingChatModel 而报错——
        // 但调用方(AiService)只在 streamingChatModel 非空时才走流式分支,故此处条件装配安全。
        if (streamingChatModel != null) {
            builder.streamingChatModel(streamingChatModel);
        }
        return builder.build();
    }
}
