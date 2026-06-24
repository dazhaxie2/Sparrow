package com.sparrow.ai.infrastructure.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 科技树 AI 向导接口。
 * 基于 langchain4j 声明式定义,通过 @SystemMessage 注入系统提示词,
 * 自动绑定工具链并支持多轮对话记忆。
 */
@SystemMessage("你是 Sparrow 人类科技树的 AI 向导。" +
        "你可以使用工具查询科技树图谱、搜索知识库和查看用户状态。" +
        "回答用户关于技术发展历史、技术依赖关系、学习路径等问题,自然地把技术之间的依赖关系与历史脉络讲清楚。" +
        "资料不足以回答时如实说明。无论用户使用何种语言,最终都用中文回答。" +
        "像朋友聊天那样自然、口语化地组织语言,直接说重点;" +
        "回答要有信息量、讲充分:把来龙去脉讲透,给足必要的背景,带上关键的年代、数字、事实和具体例子," +
        "不仅说清「是什么」,还要把「为什么会这样」「它从哪来、又通向哪」说明白,宁可多展开一些也不要敷衍带过;" +
        "可以顺着内容自然地分成几个层次来讲,需要理清脉络或并列要点时用小标题或列表都行," +
        "但不要套用「结论/关键依据/学习路径/下一步」这类与内容无关的固定模板;" +
        "不要使用 emoji,不要输出代码块。")
public interface TechTreeAgent {

    /**
     * 与 AI 向导对话。
     *
     * @param memoryId    用户标识,用于关联对话记忆
     * @param userMessage 用户消息
     * @return AI 回答
     */
    String chat(@MemoryId String memoryId, @UserMessage String userMessage);

    /**
     * 与 AI 向导流式对话(逐 token 返回)。
     * 需要 Agent 配置了 streamingChatModel 才可用;返回 {@link TokenStream} 后,
     * 调用方通过 onPartialResponse / onPartialThinking 等回调消费增量。
     *
     * @param memoryId    用户标识,用于关联对话记忆
     * @param userMessage 用户消息
     * @return token 流
     */
    TokenStream chatStream(@MemoryId String memoryId, @UserMessage String userMessage);
}
