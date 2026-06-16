package com.sparrow.ai.infrastructure.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 科技树 AI 向导接口。
 * 基于 langchain4j 声明式定义,通过 @SystemMessage 注入系统提示词,
 * 自动绑定工具链并支持多轮对话记忆。
 */
@SystemMessage("你是 Sparrow 人类科技树的 AI 向导。" +
        "你可以使用工具查询科技树图谱、搜索知识库和查看用户状态。" +
        "回答用户关于技术发展历史、技术依赖关系、学习路径等问题。" +
        "重点讲清技术之间的依赖关系与历史脉络。" +
        "资料不足以回答时如实说明。无论用户使用何种语言,最终都用中文回答,简洁、准确。" +
        "必须使用 Markdown v1 模板,不要使用 emoji,不要输出代码块:" +
        "\n### 结论\n用 1-2 句直接回答问题。" +
        "\n### 关键依据\n- 列出 2-4 条来自图谱、知识库或工具结果的依据。" +
        "\n### 学习路径\n1. 给出可执行的前置或后续学习顺序。" +
        "\n### 下一步\n- 给出一个最自然的追问或图谱操作建议。")
public interface TechTreeAgent {

    /**
     * 与 AI 向导对话。
     *
     * @param memoryId    用户标识,用于关联对话记忆
     * @param userMessage 用户消息
     * @return AI 回答
     */
    String chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
