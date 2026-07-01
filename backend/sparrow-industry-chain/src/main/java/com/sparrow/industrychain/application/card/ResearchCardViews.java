package com.sparrow.industrychain.application.card;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

public final class ResearchCardViews {
    private ResearchCardViews() {
    }

    /** 调研卡片摘要：基本信息、状态、进度与统计。 */
    public record CardSummary(long id, String title, String brief, String status, String currentStage,
                              int progress, int nodeCount, int edgeCount, String lastError,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 对话消息视图：角色、发送方、内容与时间。 */
    public record MessageView(long id, String role, String agent, String content, LocalDateTime createdAt) {
    }

    /** 运行任务视图：状态、阶段、进度与错误信息。 */
    public record RunView(long id, String status, String currentStage, int progress, String errorMessage,
                          LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** 来源视图：编号、标题、URL、发布者与摘要。 */
    public record SourceView(long id, String sourceRef, String title, String url,
                             String publisher, String snippet) {
    }

    /** 卡片详情：包含对话历史、运行任务、图谱、报告 IR/Markdown、来源与附件。 */
    public record CardDetail(CardSummary card, List<MessageView> messages, RunView activeRun,
                             JsonNode graph, JsonNode reportIr, String reportMarkdown,
                             List<SourceView> sources, List<SourceView> attachments) {
    }

    /** 消息回复：用户消息与助手回复的配对。 */
    public record MessageReply(MessageView userMessage, MessageView assistantMessage) {
    }

    /** 论坛事件视图：Multi-Agent 协作发言的对外展示。 */
    public record ForumEventView(long id, String source, String sourceText, String content, String createdAt) {
    }

    /** 启动调研结果：运行 ID 与剩余配额。 */
    public record StartRunResult(long runId, int remainingQuota) {
    }
}

