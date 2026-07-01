package com.sparrow.industrychain.application.run;

import com.sparrow.industrychain.application.workflow.IndustryChainResearchOrchestrator;
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
import java.util.concurrent.CancellationException;

@Component
public class ResearchRunner {

    private static final Logger log = LoggerFactory.getLogger(ResearchRunner.class);
    private final IndustryChainRepository repository;
    private final IndustryChainResearchOrchestrator orchestrator;
    private final IndustryChainEventHub events;

    public ResearchRunner(IndustryChainRepository repository,
                               IndustryChainResearchOrchestrator orchestrator,
                               IndustryChainEventHub events) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.events = events;
    }

    /** 异步执行产业链深度调研：读取用户附件，调用 orchestrator.research，处理进度更新与结果持久化。 */
    @Async("industryChainResearchExecutor")
    public void run(long userId, long cardId, long runId) {
        try {
            var card = repository.findCard(userId, cardId)
                    .orElseThrow(() -> new IllegalStateException("调研卡片不存在"));
            List<SearchSource> userSources = repository.attachments(userId, cardId).stream()
                    .map(this::toSearchSource).toList();
            var result = orchestrator.research(card.title(), card.brief(), repository.messages(userId, cardId),
                    userSources, userId, cardId, runId,
                    (stage, progress, message) -> {
                        if (!repository.isRunRunning(userId, runId)) throw new CancellationException("任务已取消");
                        repository.updateProgress(userId, cardId, runId, stage, progress);
                        events.progress(cardId, runId, stage, progress, message);
                    });
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
            String message = error.getMessage() == null ? "调研执行失败" : error.getMessage();
            log.error("产业链调研失败: cardId={} runId={}", cardId, runId, error);
            repository.fail(userId, cardId, runId, message);
            events.failed(cardId, runId, message);
        }
    }

    /** 将持久化的附件记录转为 SearchSource，供 orchestrator 使用。 */
    private SearchSource toSearchSource(AttachmentRow attachment) {
        return new SearchSource(attachment.sourceRef(), attachment.title(), attachment.url(),
                attachment.publisher(), attachment.snippet());
    }
}



