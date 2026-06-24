package com.sparrow.ai.infrastructure.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.ai.application.AiService.StreamSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Spring MVC {@link SseEmitter} 的 {@link StreamSink} 实现。
 *
 * <p>SSE 事件协议:每个事件由 {@code name}(事件类型) + {@code data}(JSON) 组成,
 * 前端按 name 分流处理。所有事件 JSON 用共享 {@link ObjectMapper} 序列化。</p>
 */
public class SseStreamSink implements StreamSink {

    private static final Logger log = LoggerFactory.getLogger(SseStreamSink.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SseEmitter emitter;

    public SseStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void emit(String event, Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(toJson(data)));
        } catch (Exception e) {
            // 客户端断开 / 超时等会进入这里;记 debug 即可,不抛出以免污染生成线程。
            log.debug("SSE 发送失败 [event={}]: {}", event, e.toString());
        }
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // emitter 可能已被客户端断开,忽略。
        }
    }

    @Override
    public void completeWithError(Throwable error) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", error.getMessage() == null ? "生成失败" : error.getMessage());
            emitter.send(SseEmitter.event().name("error").data(toJson(err)));
        } catch (Exception ignore) {
            // ignore
        }
        emitter.completeWithError(error);
    }

    private String toJson(Map<String, ?> data) {
        try {
            return MAPPER.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            log.warn("SSE 事件序列化失败: {}", e.toString());
            return "{}";
        }
    }
}
