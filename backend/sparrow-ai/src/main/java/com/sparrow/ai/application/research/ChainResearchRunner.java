package com.sparrow.ai.application.research;

import com.sparrow.ai.infrastructure.research.ChainResearchEventHub;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository.SourceInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CancellationException;

@Component
public class ChainResearchRunner {

    private static final Logger log = LoggerFactory.getLogger(ChainResearchRunner.class);
    private final ChainResearchRepository repository;
    private final ChainResearchOrchestrator orchestrator;
    private final ChainResearchEventHub events;

    public ChainResearchRunner(ChainResearchRepository repository,
                               ChainResearchOrchestrator orchestrator,
                               ChainResearchEventHub events) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.events = events;
    }

    @Async("chainResearchExecutor")
    public void run(long userId, long cardId, long runId) {
        try {
            var card = repository.findCard(userId, cardId)
                    .orElseThrow(() -> new IllegalStateException("调研卡片不存在"));
            var result = orchestrator.research(card.title(), card.brief(), repository.messages(userId, cardId),
                    (stage, progress, message) -> {
                        if (!repository.isRunRunning(userId, runId)) throw new CancellationException("任务已取消");
                        repository.updateProgress(userId, cardId, runId, stage, progress);
                        events.progress(cardId, runId, stage, progress, message);
                    });
            if (!repository.isRunRunning(userId, runId)) return;
            repository.complete(userId, cardId, runId, result.graphJson(), result.reportMarkdown(),
                    result.nodeCount(), result.edgeCount(), result.sources().stream()
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
}
