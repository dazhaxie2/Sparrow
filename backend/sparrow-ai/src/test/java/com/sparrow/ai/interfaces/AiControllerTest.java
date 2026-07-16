package com.sparrow.ai.interfaces;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.sparrow.ai.application.AiService;
import com.sparrow.ai.application.AiService.StreamSink;
import com.sparrow.ai.application.chat.ChatHistoryService;
import com.sparrow.ai.application.chat.ChatHistoryViews.CreateSessionResponse;
import com.sparrow.ai.application.chat.ChatHistoryViews.SessionItem;
import com.sparrow.ai.infrastructure.streaming.SseStreamSink;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import com.sparrow.common.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 一句话：AiController SSE/限流/会话委托测试，验证 SseStreamSink 包装与 AiService 正确委托。 */
class AiControllerTest {

    private AiService aiService;
    private ChatHistoryService chatHistoryService;
    private AiController controller;

    @BeforeEach
    void setUp() {
        aiService = mock(AiService.class);
        chatHistoryService = mock(ChatHistoryService.class);
        controller = new AiController(aiService, chatHistoryService);
        UserContext.set(42L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ── SSE 流式 ──

    @Test
    void askStreamWrapsSseEmitterInStreamSinkAndDelegates() {
        AiController.AskRequest req = new AiController.AskRequest("蒸汽机是什么?", 7L, "graph-dialog");

        SseEmitter emitter = controller.askStream(req);

        assertNotNull(emitter);
        verify(aiService).askStream(eq(42L), eq("蒸汽机是什么?"), eq(7L), eq("graph-dialog"), any(StreamSink.class));
    }

    @Test
    void askStreamSinkIsSseStreamSink() {
        AiController.AskRequest req = new AiController.AskRequest("问题", null, null);

        controller.askStream(req);

        var captor = org.mockito.ArgumentCaptor.forClass(StreamSink.class);
        verify(aiService).askStream(anyLong(), anyString(), any(), any(), captor.capture());
        assertInstanceOf(SseStreamSink.class, captor.getValue(), "StreamSink 应为 SseStreamSink 实现");
    }

    @Test
    void askDelegatesToAiServiceWithSurface() {
        AiController.AskRequest req = new AiController.AskRequest("问题", 1L, "rail");
        when(aiService.ask(anyLong(), anyString(), any(), anyString())).thenReturn(
                new AiService.AskResult("回答", "agent", "markdown:v1", "general",
                        List.of(), List.of(), -1L, null));

        ApiResponse<AiService.AskResult> resp = controller.ask(req);

        verify(aiService).ask(42L, "问题", 1L, "rail");
        assertEquals("回答", resp.data().answer());
    }

    // ── 限流降级 ──

    @Test
    void askBlockedThrows429BizException() {
        AiController.AskRequest req = new AiController.AskRequest("问题", null, "rail");

        BizException e = assertThrows(BizException.class,
                () -> controller.askBlocked(req, mock(BlockException.class)));

        assertEquals(429, e.getCode());
        assertTrue(e.getMessage().contains("追踪 ID"), "应含追踪 ID: " + e.getMessage());
    }

    @Test
    void streamBlockedReturnsSseEmitterWithLimitError() {
        AiController.AskRequest req = new AiController.AskRequest("问题", null, "rail");

        SseEmitter emitter = controller.streamBlocked(req, mock(BlockException.class));

        assertNotNull(emitter, "streamBlocked 必须返回 SseEmitter 而非抛异常");
    }

    // ── 会话管理委托 ──

    @Test
    void listSessionsDelegatesToChatHistoryService() {
        when(chatHistoryService.listSessions(42L)).thenReturn(List.of(
                new SessionItem(1L, "标题", 0, null, null)));

        ApiResponse<List<SessionItem>> resp = controller.listSessions();

        verify(chatHistoryService).listSessions(42L);
        assertEquals(1, resp.data().size());
    }

    @Test
    void createSessionDelegatesToChatHistoryService() {
        when(chatHistoryService.createSession(42L, "首问")).thenReturn(99L);

        ApiResponse<CreateSessionResponse> resp = controller.createSession(
                new AiController.CreateSessionRequest("首问"));

        verify(chatHistoryService).createSession(42L, "首问");
        assertEquals(99L, resp.data().sessionId());
    }

    @Test
    void getSessionMessagesDelegatesToChatHistoryService() {
        when(chatHistoryService.getMessages(42L, 5L)).thenReturn(List.of());

        controller.getSessionMessages(5L);

        verify(chatHistoryService).getMessages(42L, 5L);
    }

    @Test
    void deleteSessionDelegatesToChatHistoryService() {
        controller.deleteSession(5L);

        verify(chatHistoryService).deleteSession(42L, 5L);
    }
}
