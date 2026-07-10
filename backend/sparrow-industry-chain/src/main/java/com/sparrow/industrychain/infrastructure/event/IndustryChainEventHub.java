package com.sparrow.industrychain.infrastructure.event;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class IndustryChainEventHub {

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long cardId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitters.computeIfAbsent(cardId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> remove(cardId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        return emitter;
    }

    public void progress(long cardId, long runId, String stage, int progress, String message) {
        emit(cardId, "progress", Map.of(
                "runId", runId,
                "stage", stage,
                "progress", progress,
                "message", message));
    }

    /** 论坛事件：Multi-Agent 协作过程中的实时发言(对照 BettaFish forum_message)。 */
    public void forum(long cardId, long runId, Object event) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("event", event);
        emit(cardId, "forum", payload);
    }

    public void completed(long cardId, long runId) {
        emit(cardId, "completed", Map.of("runId", runId, "progress", 100));
    }

    public void failed(long cardId, long runId, String message) {
        emit(cardId, "failed", Map.of("runId", runId, "message", message));
    }

    /**
     * 流式 token 事件:Agent 逐 token 生成时推送累积全文。
     * 与 thinking(瞬时状态)不同:stream 携带 streamId,前端按 streamId 幂等更新同一条气泡,
     * 实现「打字机」效果。不落库(完整结果由 forum 发言在轮次结束后持久化)。
     *
     * @param streamId 幂等 key:同一轮生成的多次推送用相同 streamId,前端据此更新同一条气泡
     * @param source   发言方(INDUSTRY/QUERY/INSIGHT)
     * @param text     当前累积的完整文本(每次推送替换前一次)
     */
    public void stream(long cardId, long runId, String streamId, String source, String text) {
        emit(cardId, "stream", Map.of(
                "runId", runId,
                "streamId", streamId,
                "source", source,
                "text", text));
    }

    /**
     * 细粒度思考事件：在 Agent/编排器执行各子步骤(检索、总结、反思、阶段切换)前推送,
     * 让前端实时显示「谁正在做什么」,填补两个 progress 节点之间的空白。
     * 与 forum 事件不同:thinking 是瞬时进度提示,不落库、不触发主持人,前端展示为「当前活动」而非持久气泡。
     */
    public void thinking(long cardId, long runId, String source, String message) {
        emit(cardId, "thinking", Map.of(
                "runId", runId,
                "source", source,
                "message", message));
    }

    /** 定时发送心跳，避免长时调研的事件流被网关或反向代理按空闲连接断开。 */
    @Scheduled(fixedRateString = "${sparrow.industry-chain.sse-heartbeat-millis:15000}")
    public void heartbeat() {
        for (Long cardId : emitters.keySet()) {
            emit(cardId, "heartbeat", Map.of("timestamp", System.currentTimeMillis()));
        }
    }

    private void emit(long cardId, String name, Object payload) {
        for (SseEmitter emitter : emitters.getOrDefault(cardId, new CopyOnWriteArrayList<>())) {
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
                if ("completed".equals(name) || "failed".equals(name)) emitter.complete();
            } catch (IOException error) {
                emitter.completeWithError(error);
                remove(cardId, emitter);
            }
        }
    }

    private void remove(long cardId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(cardId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(cardId, list);
    }
}


