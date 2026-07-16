package com.sparrow.ai.application;

import com.sparrow.ai.application.harness.AiChatHarness;
import com.sparrow.ai.application.harness.AiChatHarness.Prepared;
import com.sparrow.ai.application.config.AiAgentConfigService;
import com.sparrow.ai.infrastructure.agent.TechTreeAgent;
import com.sparrow.ai.infrastructure.client.GraphClient;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeBrief;
import com.sparrow.ai.infrastructure.client.UserClient;
import com.sparrow.ai.infrastructure.config.AiProperties;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository;
import com.sparrow.ai.infrastructure.rag.MilvusStore;
import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.ai.AiAgentProfile;
import com.sparrow.common.ai.AiHarness.Metadata;
import com.sparrow.common.exception.BizException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 服务核心类。
 * 提供基于科技图图谱的问答能力,支持三种模式的分级降级:
 * 1. Agent 工具链模式 - 最高级,使用 AI Agent 自主调用工具
 * 2. RAG 检索增强模式 - 中间级,检索上下文后直接调用大模型
 * 3. 规则引擎模式 - 兜底级,纯图谱规则匹配回答
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final ExecutorService CHAT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 来源引用记录。
     *
     * @param id   节点 ID
     * @param name 节点名称
     * @param url  来源 URL
     */
    public record SourceRef(Long id, String name, String url) {
    }

    /**
     * Agent 执行步骤记录。
     *
     * @param key    步骤标识
     * @param label  步骤标签
     * @param status 步骤状态(done/partial)
     */
    public record AgentStep(String key, String label, String status) {
    }

    /**
     * 流式输出端口:把生成事件(meta/thinking/delta/done/error)投递给传输层(SSE / 其他)。
     * 与具体 HTTP 实现解耦,便于测试与替换传输。
     */
    public interface StreamSink {

        /** 投递一个事件(event=事件名,data=JSON 序列化前的键值)。 */
        void emit(String event, Map<String, ?> data);

        /** 正常结束流。 */
        void complete();

        /** 异常结束流:先推送 error 事件,再以错误收尾。 */
        void completeWithError(Throwable error);
    }

    /**
     * 问答结果记录。
     *
     * @param answer         回答内容
     * @param mode           回答模式(agent/rag/rules)
     * @param format         格式版本(markdown:v1)
     * @param intent         意图分类
     * @param sources        来源引用列表
     * @param steps          执行步骤列表
     * @param remainingQuota 剩余免费配额(-1 表示会员无限)
     */
    public record AskResult(String answer, String mode, String format, String intent,
                            List<SourceRef> sources, List<AgentStep> steps, long remainingQuota,
                            Metadata harness) {
    }

    private final AiProperties props;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final MilvusStore milvus;
    private final GraphClient graphClient;
    private final UserClient userClient;
    private final StringRedisTemplate redis;
    private final ObjectProvider<TechTreeAgent> agentProvider;
    private final AiChatHarness chatHarness;
    private final AiApplicationClassifier applicationClassifier;
    private final AiQuotaGuard quotaGuard;
    private final AiRuleAnswerer ruleAnswerer;
    private final AiRetriever retriever;
    private AiAgentConfigService agentConfigs;

    /**
     * 构造函数。
     *
     * @param props              AI 配置属性
     * @param chatModel          大语言模型
     * @param streamingChatModel 流式大语言模型,可为 null(未配置时退回同步生成)
     * @param embeddingModel     向量嵌入模型
     * @param milvus             Milvus 向量存储
     * @param graphClient        图谱服务客户端
     * @param userClient         用户服务客户端
     * @param redis              Redis 模板(用于配额管理)
     * @param agentProvider      Agent 提供者(懒加载)
     */
    public AiService(AiProperties props, ChatModel chatModel, StreamingChatModel streamingChatModel,
                     EmbeddingModel embeddingModel, MilvusStore milvus, GraphClient graphClient,
                     UserClient userClient, StringRedisTemplate redis,
                     ObjectProvider<TechTreeAgent> agentProvider, ChatHistoryRepository chatHistory) {
        this.props = props;
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.milvus = milvus;
        this.graphClient = graphClient;
        this.userClient = userClient;
        this.redis = redis;
        this.agentProvider = agentProvider;
        this.chatHarness = new AiChatHarness(chatHistory);
        this.applicationClassifier = new AiApplicationClassifier(chatModel);
        this.quotaGuard = new AiQuotaGuard(props, redis, userClient);
        this.ruleAnswerer = new AiRuleAnswerer(graphClient);
        this.retriever = new AiRetriever(chatModel, embeddingModel, milvus, graphClient);
    }

    @Autowired(required = false)
    void setAgentConfigs(AiAgentConfigService agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    private AiAgentProfile agentProfile() {
        return agentConfigs == null ? AiAgentConfigService.defaultProfile() : agentConfigs.runtime();
    }

    /**
     * 检查 LLM 是否已配置。
     *
     * @return true 表示大语言模型和嵌入模型都已就绪
     */
    public boolean llmConfigured() {
        return chatModel != null && embeddingModel != null;
    }

    /** 流式可用:LLM 已配置且流式模型就位。 */
    public boolean streamConfigured() {
        return llmConfigured() && streamingChatModel != null;
    }

    /**
     * 执行 AI 问答。
     * 按优先级尝试三种模式: Agent → RAG → 规则引擎,任一模式失败自动降级。
     *
     * @param userId    用户 ID
     * @param question  用户问题
     * @param sessionId 会话 id,可选;非空时本次问答落库到该会话历史
     * @return 问答结果
     */
    public AskResult ask(Long userId, String question, Long sessionId) {
        return ask(userId, question, sessionId, "general-chat");
    }

    /** 带调用界面标识的同步 Harness 问答入口。 */
    public AskResult ask(Long userId, String question, Long sessionId, String surface) {
        AiHarness.Run harness = AiHarness.start(surface);
        try {
            Prepared prepared = chatHarness.prepare(harness, userId, sessionId, question, agentProfile());
            long remaining = consumeQuota(userId);
            String intent = classifyIntent(prepared.question());

            AiRetriever.Retrieved retrieved = retrieve(prepared.question(), prepared.profile().maxSteps());
            List<SourceRef> sources = buildSources(retrieved);
            harness.checkpoint(AiHarness.Stage.RETRIEVAL,
                    sources.isEmpty() ? "上下文检索完成，未命中可引用来源" : "上下文检索与来源装配完成");

            TechTreeAgent agent = agentProvider.getIfAvailable();
            if (llmConfigured() && agent != null) {
                try {
                    harness.checkpoint(AiHarness.Stage.EXECUTION, "使用 Agent 工具链生成回答");
                    String answer = agent.chat(String.valueOf(userId),
                            agentUserMessage(prepared.question(), intent, prepared.conversationContext()));
                    return finish(prepared, userId, sessionId, answer, "agent", intent, sources,
                            buildSteps(intent, "检索图谱与会话上下文", "Agent 工具链生成回答", sources.isEmpty()),
                            remaining);
                } catch (Exception error) {
                    harness.fallback("Agent 工具链不可用，切换 RAG");
                    log.warn("Agent 调用失败,降级为 RAG [traceId={}]: {}", harness.traceId(), AiHarness.safeFailure(error));
                }
            }
            if (llmConfigured()) {
                try {
                    harness.checkpoint(AiHarness.Stage.EXECUTION, "使用 RAG 模型生成回答");
                    String answer = chatModel.chat(prepared.profile().systemPrompt() + "\n\n"
                            + ragUserMessage(prepared.question(), retrieved.nodes(), retrieved.chunks(),
                            prepared.conversationContext()));
                    return finish(prepared, userId, sessionId, answer, "rag", intent, sources,
                            buildSteps(intent, "检索图谱与会话上下文", "RAG 生成统一回答", sources.isEmpty()),
                            remaining);
                } catch (Exception error) {
                    harness.fallback("RAG 模型不可用，切换本地规则");
                    log.warn("LLM 调用失败,降级为规则问答 [traceId={}]: {}", harness.traceId(), AiHarness.safeFailure(error));
                }
            } else {
                harness.fallback("模型未配置，使用本地规则");
            }

            harness.checkpoint(AiHarness.Stage.EXECUTION, "使用本地图谱规则生成回答");
            return finish(prepared, userId, sessionId, rulesAnswer(prepared.question(), retrieved.nodes()),
                    "rules", intent, sources,
                    buildSteps(intent, "关键词匹配图谱与会话上下文", "规则引擎生成统一回答", sources.isEmpty()),
                    remaining);
        } catch (BizException error) {
            Metadata failed = harness.fail(error.getCode() >= 500, "请求未通过 Harness 或服务暂时不可用");
            log.warn("AI 对话被拒绝 [traceId={} code={}]: {}", failed.traceId(), error.getCode(), error.getMessage());
            throw new BizException(error.getCode(), error.getMessage() + "（追踪 ID: " + failed.traceId() + "）");
        } catch (Exception error) {
            Metadata failed = harness.fail(true, "AI 对话执行失败");
            log.warn("AI 对话执行失败 [traceId={}]: {}", failed.traceId(), AiHarness.safeFailure(error));
            throw new BizException(503, "AI 服务暂时不可用（追踪 ID: " + failed.traceId() + "）");
        }
    }

    private AskResult finish(Prepared prepared, long userId, Long sessionId, String rawAnswer,
                             String mode, String intent, List<SourceRef> sources,
                             List<AgentStep> steps, long remaining) {
        String answer = chatHarness.validateAnswer(prepared.run(), rawAnswer,
                prepared.profile().maxOutputChars());
        chatHarness.persistExchange(prepared.run(), userId, sessionId,
                prepared.question(), answer, mode);
        Metadata metadata = prepared.run().complete();
        return new AskResult(answer, mode, "markdown:v1", intent, sources, steps, remaining, metadata);
    }

    /**
     * 流式问答:逐 token 推送思考过程(thinking)与正文(delta),最后 done 收尾。
     *
     * <p>事件顺序:meta(来源/配额/步骤) → thinking*(reasoning 增量,可选) → delta*(正文增量)
     * → done。失败降级:Agent 流式 → RAG 流式 → 规则(一次性 delta) → error。
     * 任何异常都保证调用 {@link StreamSink#complete()} 或 {@link StreamSink#completeWithError(Throwable)},
     * 不会让 SSE 连接悬挂。</p>
     *
     * <p>异步执行:本方法立即返回,生成在独立线程推进;客户端断开由 SseEmitter 超时兜底。</p>
     *
     * @param userId   用户 ID
     * @param question 用户问题
     * @param sink     流式输出端口
     */
    public void askStream(Long userId, String question, Long sessionId, StreamSink sink) {
        askStream(userId, question, sessionId, "general-chat", sink);
    }

    /** 带调用界面标识的流式 Harness 问答入口。 */
    public void askStream(Long userId, String question, Long sessionId, String surface, StreamSink sink) {
        AiHarness.Run harness = AiHarness.start(surface);
        CompletableFuture.runAsync(() -> {
            try {
                emitHarness(sink, harness);
                askStreamInternal(userId, question, sessionId, sink, harness);
            } catch (BizException error) {
                Metadata failed = harness.fail(error.getCode() >= 500,
                        "请求未通过 Harness 或服务暂时不可用");
                emitHarness(sink, harness);
                sink.emit("error", Map.of(
                        "message", error.getMessage(),
                        "traceId", failed.traceId(),
                        "retryable", failed.retryable(),
                        "harness", failed));
                sink.complete();
            } catch (Exception error) {
                Metadata failed = harness.fail(true, "AI 流式执行失败");
                log.warn("流式问答失败 [traceId={} userId={}]: {}", failed.traceId(), userId, AiHarness.safeFailure(error));
                emitHarness(sink, harness);
                sink.emit("error", Map.of(
                        "message", "AI 服务暂时不可用",
                        "traceId", failed.traceId(),
                        "retryable", true,
                        "harness", failed));
                sink.complete();
            }
        }, CHAT_EXECUTOR);
    }

    private void askStreamInternal(Long userId, String question, Long sessionId, StreamSink sink,
                                   AiHarness.Run harness) {
        Prepared prepared = chatHarness.prepare(harness, userId, sessionId, question, agentProfile());
        emitHarness(sink, harness);
        long remaining = consumeQuota(userId);
        String intent = classifyIntent(prepared.question());

        // 检索上下文(三种模式共用),来源/步骤先于正文推给前端,UI 可先渲染来源卡片。
        AiRetriever.Retrieved retrieved = retrieve(prepared.question(), prepared.profile().maxSteps());
        List<SourceRef> sources = buildSources(retrieved);
        harness.checkpoint(AiHarness.Stage.RETRIEVAL,
                sources.isEmpty() ? "上下文检索完成，未命中可引用来源" : "上下文检索与来源装配完成");
        emitHarness(sink, harness);

        // 1. Agent 流式(优先)。TokenStream 流式 + 工具调用在部分 provider 上不稳,
        //    onError 时降级 RAG 流式。
        TechTreeAgent agent = agentProvider.getIfAvailable();
        if (streamConfigured() && agent != null) {
            harness.checkpoint(AiHarness.Stage.EXECUTION, "使用 Agent 工具链流式生成回答");
            Map<String, Object> meta = streamMeta("agent", intent, sources,
                    buildSteps(intent, "检索图谱与会话上下文", "Agent 工具链流式生成回答", sources.isEmpty()),
                    remaining, harness);
            sink.emit("meta", meta);
            StringBuilder answer = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            if (streamAgent(agent, String.valueOf(userId),
                    agentUserMessage(prepared.question(), intent, prepared.conversationContext()),
                    sink, answer, thinking)) {
                finishStream(prepared, userId, sessionId, sink, answer.toString(), "agent", intent);
                return;
            }
            // 流式被判失败(超时/onError),但若已吐出足够完整的正文,直接收尾保留——
            // 不再 reset+降级,避免把已给用户看到的回答清空只剩尾部。
            if (hasSubstantialAnswer(answer)) {
                harness.checkpoint(AiHarness.Stage.VALIDATION, "Agent 流式超时但已有完整正文，直接收尾");
                emitHarness(sink, harness);
                finishStream(prepared, userId, sessionId, sink, answer.toString(), "agent", intent);
                return;
            }
            resetPartialStream(sink, answer, thinking);
            harness.fallback("Agent 流式不可用，切换 RAG");
            emitHarness(sink, harness);
        }

        // 2. RAG 流式。
        if (streamConfigured()) {
            harness.checkpoint(AiHarness.Stage.EXECUTION, "使用 RAG 模型流式生成回答");
            Map<String, Object> meta = streamMeta("rag", intent, sources,
                    buildSteps(intent, "检索图谱与会话上下文", "RAG 流式生成回答", sources.isEmpty()),
                    remaining, harness);
            sink.emit("meta", meta);
            StringBuilder answer = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            if (streamRag(prepared, retrieved, sink, answer, thinking)) {
                finishStream(prepared, userId, sessionId, sink, answer.toString(), "rag", intent);
                return;
            }
            // 同 Agent:RAG 超时但正文已足够完整时直接收尾,不丢弃降级。
            if (hasSubstantialAnswer(answer)) {
                harness.checkpoint(AiHarness.Stage.VALIDATION, "RAG 流式超时但已有完整正文，直接收尾");
                emitHarness(sink, harness);
                finishStream(prepared, userId, sessionId, sink, answer.toString(), "rag", intent);
                return;
            }
            resetPartialStream(sink, answer, thinking);
            harness.fallback("RAG 流式不可用，切换本地规则");
            emitHarness(sink, harness);
        } else {
            harness.fallback("流式模型未配置，使用本地规则");
            emitHarness(sink, harness);
        }

        // 3. 规则模式:本地拼好整段,一次性推送(伪流式,体验统一)。
        harness.checkpoint(AiHarness.Stage.EXECUTION, "使用本地图谱规则生成回答");
        String answer = rulesAnswer(prepared.question(), retrieved.nodes());
        Map<String, Object> meta = streamMeta("rules", intent, sources,
                buildSteps(intent, "关键词匹配图谱与会话上下文", "规则引擎生成统一回答", sources.isEmpty()),
                remaining, harness);
        sink.emit("meta", meta);
        String validated = chatHarness.validateAnswer(harness, answer,
                prepared.profile().maxOutputChars());
        sink.emit("delta", Map.of("text", validated));
        finishValidatedStream(prepared, userId, sessionId, sink, validated, "rules", intent);
    }

    private void finishStream(Prepared prepared, long userId, Long sessionId, StreamSink sink,
                              String rawAnswer, String mode, String intent) {
        String validated = chatHarness.validateAnswer(prepared.run(), rawAnswer,
                prepared.profile().maxOutputChars());
        if (rawAnswer == null || rawAnswer.isBlank()) {
            sink.emit("delta", Map.of("text", validated));
        }
        finishValidatedStream(prepared, userId, sessionId, sink, validated, mode, intent);
    }

    private void finishValidatedStream(Prepared prepared, long userId, Long sessionId, StreamSink sink,
                                       String answer, String mode, String intent) {
        chatHarness.persistExchange(prepared.run(), userId, sessionId,
                prepared.question(), answer, mode);
        Metadata metadata = prepared.run().complete();
        sink.emit("done", Map.of(
                "mode", mode,
                "format", "markdown:v1",
                "intent", intent,
                "harness", metadata));
        sink.complete();
    }

    private void resetPartialStream(StreamSink sink, StringBuilder answer, StringBuilder thinking) {
        if (!answer.isEmpty() || !thinking.isEmpty()) {
            sink.emit("reset", Map.of("reason", "fallback"));
        }
    }

    /**
     * 判断流式虽未收到 onCompleteResponse(超时/onError)但已吐出的正文是否足够完整,
     * 足以直接收尾而非丢弃降级。
     *
     * <p>背景:部分 provider 的 TokenStream 会逐 token 稳定回调 onPartialResponse,
     * 但 onCompleteResponse 偶发不触发或在 55s 超时后才触发。旧实现一律判失败→reset→
     * 降级,导致已经流给用户、内容完整的回答被前端清空,最终只剩降级尾部。
     * 这里按"非空白字符数"判实质内容:阈值远小于最小输出上限(500),只用于区分
     * "真没产出"与"产出已被用户读到"。</p>
     */
    private boolean hasSubstantialAnswer(StringBuilder answer) {
        int nonBlank = 0;
        for (int i = 0; i < answer.length(); i++) {
            if (!Character.isWhitespace(answer.charAt(i))) {
                nonBlank++;
            }
        }
        return nonBlank >= 100;
    }

    private void emitHarness(StreamSink sink, AiHarness.Run harness) {
        sink.emit("harness", Map.of("harness", harness.snapshot()));
    }

    /** 用流式模型直接生成 RAG 回答;逐 token 推 delta,reasoning 推 thinking。返回 true=成功,false=降级。 */
    private boolean streamRag(Prepared prepared, AiRetriever.Retrieved retrieved, StreamSink sink,
                              StringBuilder answer, StringBuilder thinking) {
        String prompt = prepared.profile().systemPrompt() + "\n\n"
                + ragUserMessage(prepared.question(), retrieved.nodes(), retrieved.chunks(),
                prepared.conversationContext());
        return streamWithString(prompt, sink, answer, thinking, "RAG");
    }

    /**
     * 用 Agent 的流式接口(TokenStream)生成回答。
     * 流式 + 工具调用可能不稳定,失败时返回 false 以便上层降级。
     */
    private boolean streamAgent(TechTreeAgent agent, String memoryId, String userMessage,
                                StreamSink sink, StringBuilder answer, StringBuilder thinking) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            agent.chatStream(memoryId, userMessage)
                    .onPartialResponse(token -> {
                        answer.append(token);
                        sink.emit("delta", Map.of("text", token));
                    })
                    .onPartialThinking(pt -> {
                        String t = pt.text();
                        if (t != null && !t.isEmpty()) {
                            thinking.append(t);
                            sink.emit("thinking", Map.of("text", t));
                        }
                    })
                    .onCompleteResponse(resp -> latch.countDown())
                    .onError(err -> {
                        error.set(err);
                        latch.countDown();
                    })
                    .start();
        } catch (Exception e) {
            log.warn("Agent 流式启动失败,降级 RAG: {}", AiHarness.safeFailure(e));
            return false;
        }
        return awaitStream(latch, error, "Agent");
    }

    /** 用 streamingChatModel.chat(String, handler) 驱动流式,把回调桥接到 sink。 */
    private boolean streamWithString(String prompt, StreamSink sink, StringBuilder answer,
                                     StringBuilder thinking, String tag) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        streamingChatModel.chat(prompt, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                answer.append(partialResponse);
                sink.emit("delta", Map.of("text", partialResponse));
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                String t = partialThinking.text();
                if (t != null && !t.isEmpty()) {
                    thinking.append(t);
                    sink.emit("thinking", Map.of("text", t));
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable err) {
                error.set(err);
                latch.countDown();
            }
        });
        return awaitStream(latch, error, tag);
    }

    /** 等待流式结束,带超时兜底,防止 provider 不回调导致 SSE 悬挂。 */
    private boolean awaitStream(CountDownLatch latch, AtomicReference<Throwable> error, String tag) {
        try {
            // 单级 55s:保证降级链 agent(55)+rag(55)+rules 兜底(合计 ~110s)在 SseEmitter
            // 120s 超时前完成,避免两级连续悬挂导致 done/error 都发不出、前端永久转圈。
            if (!latch.await(55, TimeUnit.SECONDS)) {
                log.warn("{} 流式超时(55s),降级", tag);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (error.get() != null) {
            log.warn("{} 流式出错,降级: {}", tag, AiHarness.safeFailure(error.get()));
            return false;
        }
        return true;
    }

    /** 构造首帧 meta 事件的 payload。 */
    private Map<String, Object> streamMeta(String mode, String intent, List<SourceRef> sources,
                                           List<AgentStep> steps, long remaining,
                                           AiHarness.Run harness) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", mode);
        meta.put("intent", intent);
        meta.put("sources", sources);
        meta.put("steps", steps);
        meta.put("remainingQuota", remaining);
        meta.put("harness", harness.snapshot());
        return meta;
    }

    /**
     * 将文本列表转换为向量嵌入(委托 retriever)。
     * 保留 public 签名:RagIndexer / VectorSearchTool 通过 AiService 调用。
     *
     * @param texts 待嵌入的文本列表
     * @return 向量列表
     */
    public List<float[]> embed(List<String> texts) {
        return retriever.embed(texts);
    }

    /** Agent 模式用户消息(委托 ruleAnswerer)。 */
    private String agentUserMessage(String question, String intent, String conversationContext) {
        return ruleAnswerer.agentUserMessage(question, intent, conversationContext);
    }

    /**
     * 构建执行步骤列表。
     *
     * @param intent         意图分类
     * @param contextLabel   上下文步骤标签
     * @param answerLabel    回答步骤标签
     * @param partialContext 是否为部分上下文
     * @return 步骤列表
     */
    private List<AgentStep> buildSteps(String intent, String contextLabel, String answerLabel, boolean partialContext) {
        return List.of(
                new AgentStep("route", "识别意图: " + intentLabel(intent), "done"),
                new AgentStep("context", contextLabel, partialContext ? "partial" : "done"),
                new AgentStep("answer", answerLabel, "done")
        );
    }

    /**
     * 分类用户问题意图。
     * 支持的意图: dependency(依赖关系)、learning_path(学习路径)、why(原理解释)、compare(对比分析)、general(综合问答)。
     *
     * @param question 用户问题
     * @return 意图标识
     */
    private String classifyIntent(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (containsAny(q, "前置", "依赖", "解锁", "关系", "链", "prerequisite", "dependency")) {
            return "dependency";
        }
        if (containsAny(q, "下一步", "推荐", "路线", "路径", "学习", "学什么", "next", "learn", "path")) {
            return "learning_path";
        }
        if (containsAny(q, "为什么", "为何", "重要", "意义", "原理", "why")) {
            return "why";
        }
        if (containsAny(q, "比较", "对比", "区别", "差异", "compare", "versus")) {
            return "compare";
        }
        return "general";
    }

    /**
     * 检查文本是否包含任一候选词。
     *
     * @param text      待检查文本
     * @param candidates 候选词列表
     * @return true 表示包含任一候选词
     */
    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取意图的中文标签。
     *
     * @param intent 意图标识
     * @return 中文标签
     */
    private String intentLabel(String intent) {
        return switch (intent) {
            case "dependency" -> "依赖关系";
            case "learning_path" -> "学习路径";
            case "why" -> "原理解释";
            case "compare" -> "对比分析";
            default -> "综合问答";
        };
    }

    /**
     * 检索与问题相关的上下文(委托 retriever)。
     * 向量检索优先,失败降级为关键词匹配。
     *
     * @param question 用户问题
     * @return 检索结果(节点和语料块)
     */
    private AiRetriever.Retrieved retrieve(String question, int configuredLimit) {
        return retriever.retrieve(question, configuredLimit);
    }

    /**
     * 构建来源引用列表(委托 retriever)。
     *
     * @param retrieved 检索结果
     * @return 来源引用列表
     */
    private List<SourceRef> buildSources(AiRetriever.Retrieved retrieved) {
        return retriever.buildSources(retrieved);
    }

    /** RAG 模式用户消息(委托 ruleAnswerer)。 */
    private String ragUserMessage(String question, List<NodeBrief> hits,
                                  List<MilvusStore.ChunkHit> chunks,
                                  String conversationContext) {
        return ruleAnswerer.ragUserMessage(question, hits, chunks, conversationContext);
    }

    /** 规则引擎回答(委托 ruleAnswerer)。 */
    private String rulesAnswer(String question, List<NodeBrief> hits) {
        return ruleAnswerer.rulesAnswer(question, hits);
    }

    /**
     * 消耗用户免费配额(委托 quotaGuard)。
     *
     * @param userId 用户 ID
     * @return 剩余配额(-1 表示会员无限)
     */
    private long consumeQuota(Long userId) {
        return quotaGuard.consume(userId);
    }

    // ── 「应用与产业链」判定(供 sparrow-graph 反向调用) ──

    /** 待判定的单个邻居。 */
    public record NeighborBrief(Long id, String name, String summary, String category) {
    }

    /** 应用判定请求。 */
    public record ApplicationClassifyRequest(Long nodeId, String nodeName, String category,
                                             List<NeighborBrief> neighbors) {
    }

    /**
     * 用 LLM 从材料节点的邻居中挑出下游应用/产业链,排除属性/分类噪声。
     *
     * <p>单次 prompt 处理一个节点的全部邻居,要求模型只返回 JSON 数组(应用邻居的 id)。
     * LLM 未配置/调用失败/解析失败时返回空列表(调用方降级为不显示应用区块)。</p>
     *
     * @param req 节点信息 + 邻居清单
     * @return 被判为应用的邻居 id 列表
     */
    public List<Long> classifyApplications(ApplicationClassifyRequest req) {
        if (!llmConfigured() || req == null || req.neighbors() == null || req.neighbors().isEmpty()) {
            return List.of();
        }
        return applicationClassifier.classify(req);
    }
}
