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

@Configuration
public class AgentConfig {

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
