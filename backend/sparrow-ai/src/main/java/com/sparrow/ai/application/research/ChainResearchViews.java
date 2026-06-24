package com.sparrow.ai.application.research;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

public final class ChainResearchViews {
    private ChainResearchViews() {
    }

    public record CardSummary(long id, String title, String brief, String status, String currentStage,
                              int progress, int nodeCount, int edgeCount, String lastError,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record MessageView(long id, String role, String agent, String content, LocalDateTime createdAt) {
    }

    public record RunView(long id, String status, String currentStage, int progress, String errorMessage,
                          LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record SourceView(long id, String sourceRef, String title, String url,
                             String publisher, String snippet) {
    }

    public record CardDetail(CardSummary card, List<MessageView> messages, RunView activeRun,
                             JsonNode graph, String reportMarkdown, List<SourceView> sources) {
    }

    public record MessageReply(MessageView userMessage, MessageView assistantMessage) {
    }

    public record StartRunResult(long runId, int remainingQuota) {
    }
}
