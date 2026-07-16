package com.sparrow.ai.infrastructure.streaming;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 一句话：SseStreamSink 把 emit/complete/completeWithError 正确桥接到 SseEmitter，且吞掉传输异常。 */
class SseStreamSinkTest {

    @Test
    void emitSendsNamedEventWithJsonData() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseStreamSink sink = new SseStreamSink(emitter);

        sink.emit("delta", Map.of("text", "hello"));

        var captor = forClass(SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        String joined = joinBuiltEvent(captor.getValue());
        assertTrue(joined.contains("delta"), "事件名应出现在 SSE 事件中: " + joined);
        assertTrue(joined.contains("hello"), "JSON 数据应出现在 SSE 事件中: " + joined);
    }

    @Test
    void completeCallsEmitterComplete() {
        SseEmitter emitter = mock(SseEmitter.class);
        SseStreamSink sink = new SseStreamSink(emitter);

        sink.complete();

        verify(emitter).complete();
    }

    @Test
    void completeWithErrorSendsErrorEventThenCompletesWithError() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseStreamSink sink = new SseStreamSink(emitter);
        Throwable error = new RuntimeException("boom");

        sink.completeWithError(error);

        var captor = forClass(SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        String joined = joinBuiltEvent(captor.getValue());
        assertTrue(joined.contains("error"), "应发送 error 事件: " + joined);
        assertTrue(joined.contains("boom"), "error 事件应含异常消息: " + joined);
        verify(emitter).completeWithError(error);
    }

    @Test
    void emitSwallowsSendFailureWithoutThrowing() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseStreamSink sink = new SseStreamSink(emitter);
        doThrow(new RuntimeException("client disconnected")).when(emitter).send(any(SseEventBuilder.class));

        assertDoesNotThrow(() -> sink.emit("delta", Map.of("text", "x")));
    }

    @Test
    void emitNullDataSerializesAsEmptyObject() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseStreamSink sink = new SseStreamSink(emitter);

        sink.emit("meta", null);

        var captor = forClass(SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        String joined = joinBuiltEvent(captor.getValue());
        assertTrue(joined.contains("{}"), "null data 应序列化为空对象: " + joined);
    }

    @Test
    void completeSwallowsFailureWithoutThrowing() {
        SseEmitter emitter = mock(SseEmitter.class);
        SseStreamSink sink = new SseStreamSink(emitter);
        doThrow(new RuntimeException("already closed")).when(emitter).complete();

        assertDoesNotThrow(sink::complete);
    }

    @Test
    void completeWithErrorSwallowsSendFailureAndStillCompletesWithError() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseStreamSink sink = new SseStreamSink(emitter);
        doThrow(new RuntimeException("send failed")).when(emitter).send(any(SseEventBuilder.class));
        Throwable error = new IllegalStateException("generation failed");

        assertDoesNotThrow(() -> sink.completeWithError(error));
        verify(emitter).completeWithError(error);
    }

    /** 用反射提取 DataWithMediaType.data，兼容 record 与 class 两种形态。 */
    private String joinBuiltEvent(SseEventBuilder builder) throws Exception {
        Set<DataWithMediaType> pieces = builder.build();
        java.lang.reflect.Field dataField = DataWithMediaType.class.getDeclaredField("data");
        dataField.setAccessible(true);
        StringBuilder sb = new StringBuilder();
        for (DataWithMediaType piece : pieces) {
            sb.append(String.valueOf(dataField.get(piece)));
        }
        return sb.toString();
    }
}
