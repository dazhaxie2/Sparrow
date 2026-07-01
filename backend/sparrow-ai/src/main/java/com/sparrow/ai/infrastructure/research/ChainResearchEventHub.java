package com.sparrow.ai.infrastructure.research;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ChainResearchEventHub {

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
