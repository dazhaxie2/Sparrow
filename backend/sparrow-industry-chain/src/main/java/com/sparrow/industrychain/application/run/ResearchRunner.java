package com.sparrow.industrychain.application.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.industrychain.application.workflow.IndustryChainResearchOrchestrator;
import com.sparrow.industrychain.application.workflow.IndustryChainResearchOrchestrator.ResearchCheckpoint;
import com.sparrow.industrychain.application.forum.ForumBus;
import com.sparrow.industrychain.application.forum.ForumEvent;
import com.sparrow.industrychain.infrastructure.event.IndustryChainEventHub;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.AttachmentRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.SourceInput;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient.SearchSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

@Component
public class ResearchRunner {

    private static final Logger log = LoggerFactory.getLogger(ResearchRunner.class);
    private final IndustryChainRepository repository;
    private final IndustryChainResearchOrchestrator orchestrator;
    private final IndustryChainEventHub events;
    private final ForumBus forum;
    private final ObjectMapper objectMapper;

    public ResearchRunner(IndustryChainRepository repository,
                               IndustryChainResearchOrchestrator orchestrator,
                               IndustryChainEventHub events,
                               ForumBus forum,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.events = events;
        this.forum = forum;
        this.objectMapper = objectMapper;
    }

    /** 异步执行产业链深度调研：读取用户附件，调用 orchestrator.research，处理进度更新与结果持久化。 */
    @Async("industryChainRunExecutor")
    public void run(long userId, long cardId, long runId, boolean resumed) {
        try {
            var card = repository.findCard(userId, cardId)
                    .orElseThrow(() -> new IllegalStateException("调研卡片不存在"));
            List<SearchSource> userSources = repository.attachments(userId, cardId).stream()
                    .map(this::toSearchSource).toList();
            ResearchCheckpoint checkpoint = readCheckpoint(repository.runCheckpoint(userId, cardId, runId));
            var result = orchestrator.research(card.title(), card.brief(), repository.messages(userId, cardId),
                    userSources, userId, cardId, runId,
                    (stage, progress, message) -> {
                        if (!repository.isRunRunning(userId, runId)) throw new CancellationException("任务已取消");
                        repository.updateProgress(userId, cardId, runId, stage, progress);
                        events.progress(cardId, runId, stage, progress, message);
                    }, checkpoint,
                    value -> repository.saveCheckpoint(userId, cardId, runId, writeCheckpoint(value)), resumed);
            if (!repository.isRunRunning(userId, runId)) return;
            repository.complete(userId, cardId, runId, result.graphJson(), result.reportIrJson(),
                    result.reportMarkdown(), result.nodeCount(), result.edgeCount(), result.sources().stream()
                            .map(source -> new SourceInput(source.sourceRef(), source.title(), source.url(),
                                    source.publisher(), source.snippet())).toList());
            repository.addMessage(userId, cardId, "assistant", "reporter",
                    "深度调研已完成：生成 " + result.nodeCount() + " 个节点、"
                            + result.edgeCount() + " 条有来源关系。可在右侧查看图谱、报告和来源。");
            events.completed(cardId, runId);
        } catch (CancellationException cancelled) {
            log.info("产业链调研任务已取消: runId={}", runId);
        } catch (Exception error) {
            String message = userFacingFailure(error);
            log.error("产业链调研失败: cardId={} runId={}", cardId, runId, error);
            repository.fail(userId, cardId, runId, message);
            try {
                String recoveryNotice = checkpointAvailable(userId, cardId, runId)
                        ? " 本轮已完成的调研记录和检查点已保留，可从中断点继续。"
                        : " 本轮尚未形成可恢复检查点，请处理模型配置后重新调研。";
                repository.addForumEvent(userId, cardId, runId, ForumEvent.SYSTEM,
                        message + recoveryNotice);
            } catch (RuntimeException persistError) {
                log.warn("保存调研失败提示时出错: cardId={} runId={}", cardId, runId, persistError);
            }
            events.failed(cardId, runId, message);
        } finally {
            forum.reset(runId);
        }
    }

    private ResearchCheckpoint readCheckpoint(String json) {
        if (json == null || json.isBlank()) return ResearchCheckpoint.empty();
        try {
            return objectMapper.readValue(json, ResearchCheckpoint.class);
        } catch (Exception error) {
            log.warn("调研检查点无法读取，将从最早未完成阶段继续: {}", error.getMessage());
            return ResearchCheckpoint.empty();
        }
    }

    private String writeCheckpoint(ResearchCheckpoint checkpoint) {
        try {
            return objectMapper.writeValueAsString(checkpoint);
        } catch (Exception error) {
            throw new IllegalStateException("保存调研检查点失败", error);
        }
    }

    private boolean checkpointAvailable(long userId, long cardId, long runId) {
        try {
            String checkpoint = repository.runCheckpoint(userId, cardId, runId);
            return checkpoint != null && !checkpoint.isBlank();
        } catch (RuntimeException error) {
            log.warn("读取失败任务检查点时出错: cardId={} runId={}", cardId, runId, error);
            return false;
        }
    }

    static String userFacingFailure(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            String text = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (text.contains("\"code\":\"1113\"") || text.contains("余额不足")
                    || text.contains("insufficient balance") || text.contains("resource package")) {
                return "AI 模型账户余额不足或无可用资源包，请充值或切换可用模型配置后重试。";
            }
            if (text.contains("timeout") || text.contains("timed out") || text.contains("超时")) {
                return "AI 服务响应超时，调研任务已安全停止。";
            }
            cause = cause.getCause();
        }
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? "调研执行失败，请稍后重试。" : detail;
    }

    /** 将持久化的附件记录转为 SearchSource，供 orchestrator 使用。 */
    private SearchSource toSearchSource(AttachmentRow attachment) {
        return new SearchSource(attachment.sourceRef(), attachment.title(), attachment.url(),
                attachment.publisher(), attachment.snippet());
    }
}



