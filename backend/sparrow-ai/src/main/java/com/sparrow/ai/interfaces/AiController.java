package com.sparrow.ai.interfaces;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.sparrow.ai.application.AiService;
import com.sparrow.ai.application.AiService.AskResult;
import com.sparrow.ai.application.AiService.StreamSink;
import com.sparrow.ai.application.chat.ChatHistoryService;
import com.sparrow.ai.application.chat.ChatHistoryViews.CreateSessionResponse;
import com.sparrow.ai.application.chat.ChatHistoryViews.MessageItem;
import com.sparrow.ai.application.chat.ChatHistoryViews.SessionItem;
import com.sparrow.ai.infrastructure.config.SentinelRuleConfig;
import com.sparrow.ai.infrastructure.streaming.SseStreamSink;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.exception.BizException;
import com.sparrow.common.security.UserContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * AI 问答接口控制器。
 * 提供 AI 问答的 HTTP 接口,支持限流保护。
 */
@RestController
@RequestMapping("/api/ai")
@Validated
public class AiController {

    /**
     * 问答请求记录。
     *
     * @param question  用户问题,非空且不超过 500 字符
     * @param sessionId 会话 id,可选;非空时本次问答落库到该会话历史
     */
    public record AskRequest(@NotBlank @Size(max = 500) String question, Long sessionId,
                             @Size(max = 48) String surface) {

        public AskRequest(String question, Long sessionId) {
            this(question, sessionId, null);
        }
    }

    /** 创建会话请求:title 由首问截断得来。 */
    public record CreateSessionRequest(@NotBlank @Size(max = 500) String question) {
    }

    private final AiService aiService;
    private final ChatHistoryService chatHistoryService;

    public AiController(AiService aiService, ChatHistoryService chatHistoryService) {
        this.aiService = aiService;
        this.chatHistoryService = chatHistoryService;
    }

    @PostMapping("/ask")
    @SentinelResource(value = SentinelRuleConfig.RESOURCE_AI_ASK, blockHandler = "askBlocked")
    public ApiResponse<AskResult> ask(@RequestBody @Validated AskRequest req) {
        return ApiResponse.ok(aiService.ask(UserContext.require(), req.question(), req.sessionId(), req.surface()));
    }

    /**
     * 流式问答:SSE 逐 token 推送思考过程(thinking)与正文(delta),最后以 done 收尾。
     * 失败降级时推送 error 事件后结束。限流命中时由 blockHandler 推送 error 事件。
     *
     * @param req 问答请求
     * @return SSE 流
     */
    @PostMapping(value = "/ask/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @SentinelResource(value = SentinelRuleConfig.RESOURCE_AI_ASK, blockHandler = "streamBlocked")
    public SseEmitter askStream(@RequestBody @Validated AskRequest req) {
        // 120s 超时,覆盖检索 + 流式生成全过程;客户端断开会自动终止 emitter。
        SseEmitter emitter = new SseEmitter(120_000L);
        StreamSink sink = new SseStreamSink(emitter);
        // 流式生成是异步的,不能阻塞请求线程;交由 AiService 内部线程推进。
        // sessionId 非空时, AiService 会在 done 处把本次问答落库到该会话。
        aiService.askStream(UserContext.require(), req.question(), req.sessionId(), req.surface(), sink);
        return emitter;
    }

    // ==================== 历史会话管理 ====================

    /** 列出当前用户的所有会话(最近活跃在前)。 */
    @GetMapping("/sessions")
    public ApiResponse<List<SessionItem>> listSessions() {
        return ApiResponse.ok(chatHistoryService.listSessions(UserContext.require()));
    }

    /** 创建新会话,title 由首问截断得来。返回新会话 id。 */
    @PostMapping("/sessions")
    public ApiResponse<CreateSessionResponse> createSession(@RequestBody @Validated CreateSessionRequest req) {
        long id = chatHistoryService.createSession(UserContext.require(), req.question());
        return ApiResponse.ok(new CreateSessionResponse(id));
    }

    /** 取会话的全部消息(历史回放)。 */
    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<MessageItem>> getSessionMessages(@PathVariable long id) {
        return ApiResponse.ok(chatHistoryService.getMessages(UserContext.require(), id));
    }

    /** 删除会话(级联删除消息)。 */
    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable long id) {
        chatHistoryService.deleteSession(UserContext.require(), id);
        return ApiResponse.ok(null);
    }

    // ==================== 限流降级 ====================

    public ApiResponse<AskResult> askBlocked(AskRequest req, BlockException ex) {
        AiHarness.Metadata failed = AiHarness.start(req.surface())
                .fail(true, "请求触发服务端限流");
        throw new BizException(429, "AI 问答请求过于频繁,请稍后再试（追踪 ID: " + failed.traceId() + "）");
    }

    /** 流式限流降级:推送 error 事件后结束 emitter,而非抛异常(SSE 已开始)。 */
    public SseEmitter streamBlocked(AskRequest req, BlockException ex) {
        SseEmitter emitter = new SseEmitter(120_000L);
        AiHarness.Metadata failed = AiHarness.start(req.surface())
                .fail(true, "请求触发服务端限流");
        try {
            emitter.send(SseEmitter.event().name("harness").data(Map.of("harness", failed)));
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "message", "AI 问答请求过于频繁,请稍后再试",
                    "traceId", failed.traceId(),
                    "retryable", true,
                    "harness", failed)));
            emitter.complete();
        } catch (Exception ignore) {
            emitter.completeWithError(ignore);
        }
        return emitter;
    }
}
