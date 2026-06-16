package com.sparrow.ai.infrastructure.agent;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
     * @param chatModel        大语言模型,可为 null
     * @param graphQueryTool   图谱查询工具
     * @param vectorSearchTool 向量搜索工具
     * @param userProgressTool 用户进度工具
     * @param maxSessions      最大会话数,超出后按 LRU 淘汰
     * @return TechTreeAgent 实例,若 chatModel 为 null 则返回 null
     */
    @Bean
    @ConditionalOnBean(ChatModel.class)
    public TechTreeAgent techTreeAgent(@Nullable ChatModel chatModel,
                                       GraphQueryTool graphQueryTool,
                                       VectorSearchTool vectorSearchTool,
                                       UserProgressTool userProgressTool,
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
        return AiServices.builder(TechTreeAgent.class)
                .chatModel(chatModel)
                .tools(graphQueryTool, vectorSearchTool, userProgressTool)
                .chatMemoryProvider(memoryId -> {
                    String key = (String) memoryId;
                    return memories.computeIfAbsent(key,
                            k -> MessageWindowChatMemory.withMaxMessages(20));
                })
                .build();
    }
}
