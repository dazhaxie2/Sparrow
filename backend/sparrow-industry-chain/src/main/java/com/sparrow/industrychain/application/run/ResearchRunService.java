package com.sparrow.industrychain.application.run;

import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.application.card.ResearchCardViews.RunView;
import com.sparrow.industrychain.application.card.ResearchCardViews.StartRunResult;
import com.sparrow.industrychain.application.card.ResearchCardViews.ResumeRunResult;
import com.sparrow.industrychain.infrastructure.event.IndustryChainEventHub;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.CardRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.RunRow;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Service
public class ResearchRunService {

    private final IndustryChainRepository repository;
    private final ResearchRunner runner;
    private final ResearchQuotaService quotaService;
    private final IndustryChainEventHub events;

    public ResearchRunService(IndustryChainRepository repository, ResearchRunner runner,
                              ResearchQuotaService quotaService, IndustryChainEventHub events) {
        this.repository = repository;
        this.runner = runner;
        this.quotaService = quotaService;
        this.events = events;
    }

    public StartRunResult start(long userId, long cardId) {
        owned(userId, cardId);
        long runId = repository.createRun(userId, cardId);
        int remaining;
        try {
            remaining = quotaService.consume(userId);
        } catch (RuntimeException error) {
            repository.cancelRun(userId, cardId, runId);
            throw error;
        }
        submit(userId, cardId, runId, false, true);
        return new StartRunResult(runId, remaining);
    }

    public ResumeRunResult resume(long userId, long cardId) {
        owned(userId, cardId);
        RunRow run = repository.resumeLastFailed(userId, cardId);
        submit(userId, cardId, run.id(), true, false);
        return new ResumeRunResult(run.id(), run.currentStage(), run.progress());
    }

    public RunView run(long userId, long cardId, long runId) {
        owned(userId, cardId);
        return repository.findRun(userId, cardId, runId).map(this::runView)
                .orElseThrow(() -> new BizException(404, "调研任务不存在"));
    }

    public void cancel(long userId, long cardId, long runId) {
        run(userId, cardId, runId);
        repository.cancelRun(userId, cardId, runId);
        events.failed(cardId, runId, "任务已取消");
    }

    public SseEmitter subscribe(long userId, long cardId) {
        CardRow card = owned(userId, cardId);
        SseEmitter emitter = events.subscribe(cardId);
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(Map.of(
                    "status", card.status(), "stage", card.currentStage() == null ? "" : card.currentStage(),
                    "progress", card.progress())));
        } catch (Exception error) {
            emitter.completeWithError(error);
        }
        return emitter;
    }

    private CardRow owned(long userId, long cardId) {
        return repository.findCard(userId, cardId)
                .orElseThrow(() -> new BizException(404, "调研卡片不存在"));
    }

    private RunView runView(RunRow run) {
        return new RunView(run.id(), run.status(), run.currentStage(), run.progress(), run.errorMessage(),
                run.startedAt(), run.finishedAt());
    }

    private void submit(long userId, long cardId, long runId, boolean resumed, boolean refundQuota) {
        try {
            runner.run(userId, cardId, runId, resumed);
        } catch (RuntimeException error) {
            String message = "当前调研任务较多，任务未能进入执行队列，请稍后重试。";
            repository.fail(userId, cardId, runId, message);
            if (refundQuota) quotaService.refund(userId);
            throw new BizException(503, message);
        }
    }
}
