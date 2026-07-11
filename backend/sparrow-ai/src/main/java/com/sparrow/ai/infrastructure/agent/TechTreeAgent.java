package com.sparrow.ai.infrastructure.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/** Tool-enabled technology graph Agent. Its system prompt is supplied at runtime. */
public interface TechTreeAgent {

    String chat(@MemoryId String memoryId, @UserMessage String userMessage);

    TokenStream chatStream(@MemoryId String memoryId, @UserMessage String userMessage);
}
