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
        "回答用户关于技术发展历史、技术依赖关系、学习路径等问题,自然地把技术之间的依赖关系与历史脉络讲清楚。" +
        "资料不足以回答时如实说明。无论用户使用何种语言,最终都用中文回答。" +
        "像朋友聊天那样自然、口语化地组织语言,直接说重点;" +
        "不要套用「结论/关键依据/学习路径/下一步」这类固定小标题模板,不要使用 emoji,不要输出代码块;" +
        "用连贯的段落表达,只有在确实需要罗列时才用列表;篇幅适中,别啰嗦。")
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
