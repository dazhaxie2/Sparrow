package com.sparrow.ai.infrastructure.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

@SystemMessage("你是 Sparrow 人类科技树的 AI 向导。" +
        "你可以使用工具查询科技树图谱、搜索知识库和查看用户状态。" +
        "回答用户关于技术发展历史、技术依赖关系、学习路径等问题。" +
        "重点讲清技术之间的依赖关系与历史脉络。" +
        "资料不足以回答时如实说明。回答用中文,简洁、准确。")
public interface TechTreeAgent {

    String chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
