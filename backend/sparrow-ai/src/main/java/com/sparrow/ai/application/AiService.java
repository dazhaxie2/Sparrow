package com.sparrow.ai.application;

import com.sparrow.ai.application.harness.AiChatHarness;
import com.sparrow.ai.application.harness.AiChatHarness.Prepared;
import com.sparrow.ai.infrastructure.agent.TechTreeAgent;
import com.sparrow.ai.infrastructure.client.GraphClient;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeBrief;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeDetail;
import com.sparrow.ai.infrastructure.client.GraphViews.Tree;
import com.sparrow.ai.infrastructure.client.UserClient;
import com.sparrow.ai.infrastructure.config.AiProperties;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository;
import com.sparrow.ai.infrastructure.rag.MilvusStore;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.ai.AiHarness.Metadata;
import com.sparrow.common.exception.BizException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
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
    private static final String QUOTA_KEY_PREFIX = "sparrow:ai:quota:";
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

    /**
     * 内部检索结果记录。
     *
     * @param nodes  匹配的节点列表
     * @param chunks 匹配的语料块列表
     */
    private record Retrieved(List<NodeBrief> nodes, List<MilvusStore.ChunkHit> chunks) {
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
    private final ChatHistoryRepository chatHistory;
    private final AiChatHarness chatHarness;

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
        this.chatHistory = chatHistory;
        this.chatHarness = new AiChatHarness(chatHistory);
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
            Prepared prepared = chatHarness.prepare(harness, userId, sessionId, question);
            long remaining = consumeQuota(userId);
            String intent = classifyIntent(prepared.question());

            Retrieved retrieved = retrieve(prepared.question());
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
                    String answer = chatModel.chat(systemPrompt() + "\n\n"
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
        String answer = chatHarness.validateAnswer(prepared.run(), rawAnswer);
        try {
            chatHarness.persistExchange(prepared.run(), userId, sessionId,
                    prepared.question(), answer, mode);
        } catch (Exception error) {
            prepared.run().warning("聊天历史暂未保存，可稍后重试");
            log.warn("聊天历史原子落库失败 [traceId={} userId={} sessionId={}]: {}",
                    prepared.run().traceId(), userId, sessionId, AiHarness.safeFailure(error));
        }
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
        Prepared prepared = chatHarness.prepare(harness, userId, sessionId, question);
        emitHarness(sink, harness);
        long remaining = consumeQuota(userId);
        String intent = classifyIntent(prepared.question());

        // 检索上下文(三种模式共用),来源/步骤先于正文推给前端,UI 可先渲染来源卡片。
        Retrieved retrieved = retrieve(prepared.question());
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
        String validated = chatHarness.validateAnswer(harness, answer);
        sink.emit("delta", Map.of("text", validated));
        finishValidatedStream(prepared, userId, sessionId, sink, validated, "rules", intent);
    }

    private void finishStream(Prepared prepared, long userId, Long sessionId, StreamSink sink,
                              String rawAnswer, String mode, String intent) {
        String validated = chatHarness.validateAnswer(prepared.run(), rawAnswer);
        if (rawAnswer == null || rawAnswer.isBlank()) {
            sink.emit("delta", Map.of("text", validated));
        }
        finishValidatedStream(prepared, userId, sessionId, sink, validated, mode, intent);
    }

    private void finishValidatedStream(Prepared prepared, long userId, Long sessionId, StreamSink sink,
                                       String answer, String mode, String intent) {
        try {
            chatHarness.persistExchange(prepared.run(), userId, sessionId,
                    prepared.question(), answer, mode);
        } catch (Exception error) {
            prepared.run().warning("聊天历史暂未保存，可稍后重试");
            log.warn("流式聊天历史原子落库失败 [traceId={} userId={} sessionId={}]: {}",
                    prepared.run().traceId(), userId, sessionId, AiHarness.safeFailure(error));
        }
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

    private void emitHarness(StreamSink sink, AiHarness.Run harness) {
        sink.emit("harness", Map.of("harness", harness.snapshot()));
    }

    /** 用流式模型直接生成 RAG 回答;逐 token 推 delta,reasoning 推 thinking。返回 true=成功,false=降级。 */
    private boolean streamRag(Prepared prepared, Retrieved retrieved, StreamSink sink,
                              StringBuilder answer, StringBuilder thinking) {
        String prompt = systemPrompt() + "\n\n"
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
     * 将文本列表转换为向量嵌入。
     * 分批处理(每批 10 条)以避免超出模型限制。
     *
     * @param texts 待嵌入的文本列表
     * @return 向量列表
     */
    public List<float[]> embed(List<String> texts) {
        if (embeddingModel == null) {
            throw new BizException(503, "AI 服务未配置");
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += 10) {
            List<String> batch = texts.subList(i, Math.min(i + 10, texts.size()));
            List<TextSegment> segments = batch.stream().map(TextSegment::from).toList();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            for (Embedding emb : embeddings) {
                result.add(emb.vector());
            }
        }
        return result;
    }

    /**
     * 构建 Agent 模式的用户消息模板。
     *
     * @param question 用户问题
     * @param intent   识别的意图
     * @return 格式化后的用户消息
     */
    private String agentUserMessage(String question, String intent, String conversationContext) {
        String history = conversationContext == null || conversationContext.isBlank()
                ? "" : conversationContext + "\n\n";
        return history + "用户问题:\n" + question + "\n\n" +
                "请用中文自然、口语化地回答,直接讲重点,把相关的技术依赖与历史脉络说清楚;" +
                "内容要充分、有信息量,给足背景和关键的年代、数字、事实与例子,把「为什么」讲透,宁可多展开也别敷衍带过;" +
                "可以顺着内容自然分层,必要时用小标题或列表理清脉络," +
                "但不要套用「结论/关键依据/学习路径/下一步」之类与内容无关的固定模板;" +
                "不要使用 emoji,不要输出代码块。";
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
     * 检索与问题相关的上下文。
     * 优先使用向量检索,失败时降级为关键词匹配。
     *
     * @param question 用户问题
     * @return 检索结果(节点和语料块)
     */
    private Retrieved retrieve(String question) {
        if (llmConfigured() && milvus.ready()) {
            try {
                float[] vec = embed(List.of(question)).get(0);
                List<Long> ids = milvus.search(vec, 5);
                List<MilvusStore.ChunkHit> chunks = milvus.searchChunks(vec, 4);

                Map<Long, NodeBrief> byId = nodesById();
                List<NodeBrief> nodes = new ArrayList<>(
                        ids.stream().map(byId::get).filter(n -> n != null).toList());
                Map<String, NodeBrief> byCode = new LinkedHashMap<>();
                byId.values().forEach(n -> byCode.put(n.code(), n));
                for (MilvusStore.ChunkHit c : chunks) {
                    NodeBrief n = byCode.get(c.code());
                    if (n != null && nodes.stream().noneMatch(x -> x.id().equals(n.id()))) {
                        nodes.add(n);
                    }
                }
                return new Retrieved(nodes, chunks);
            } catch (Exception e) {
                log.warn("向量检索失败,降级为关键词匹配: {}", AiHarness.safeFailure(e));
            }
        }
        return new Retrieved(keywordMatch(question), List.of());
    }

    /**
     * 构建来源引用列表。
     *
     * @param retrieved 检索结果
     * @return 来源引用列表
     */
    private List<SourceRef> buildSources(Retrieved retrieved) {
        Map<String, String> urlByCode = new LinkedHashMap<>();
        for (MilvusStore.ChunkHit c : retrieved.chunks()) {
            if (c.url() != null && !c.url().isBlank()) {
                urlByCode.putIfAbsent(c.code(), c.url());
            }
        }
        return retrieved.nodes().stream()
                .map(n -> new SourceRef(n.id(), n.name(), urlByCode.get(n.code())))
                .toList();
    }

    /**
     * 获取所有节点的 ID 映射。
     *
     * @return 节点 ID 到 NodeBrief 的映射
     */
    private Map<Long, NodeBrief> nodesById() {
        Map<Long, NodeBrief> map = new LinkedHashMap<>();
        Tree tree = graphClient.tree().data();
        if (tree != null) {
            tree.nodes().forEach(n -> map.put(n.id(), n));
        }
        return map;
    }

    /**
     * 关键词匹配节点。
     * 从节点名称中提取关键词进行匹配,按名称长度倒序排序,最多返回 3 个匹配结果。
     *
     * @param question 用户问题
     * @return 匹配的节点列表
     */
    private List<NodeBrief> keywordMatch(String question) {
        Tree tree = graphClient.tree().data();
        if (tree == null) {
            return List.of();
        }
        List<NodeBrief> matched = new ArrayList<>();
        for (NodeBrief n : tree.nodes()) {
            String key = n.name().split("[((]")[0];
            if (key.length() >= 2 && question.contains(key)) {
                matched.add(n);
            }
        }
        matched.sort(Comparator.comparingInt((NodeBrief n) -> n.name().length()).reversed());
        return matched.size() > 3 ? matched.subList(0, 3) : matched;
    }

    /**
     * 获取 RAG 模式的系统提示词。
     *
     * @return 系统提示词
     */
    private String systemPrompt() {
        return "你是 Sparrow 科技图的 AI 向导。请基于提供的科技图资料回答用户问题," +
                "自然地讲清技术之间的依赖关系与历史脉络;资料不足以回答时,如实说明。" +
                "回答用中文,像聊天那样自然、口语化,直接说重点;" +
                "内容要充分、有信息量,给足背景和关键的年代、数字、事实与例子,把「为什么」讲透,宁可多展开也别敷衍带过;" +
                "可以顺着内容自然分层,必要时用小标题或列表理清脉络," +
                "但不要套用「结论/关键依据/学习路径/下一步」这类与内容无关的固定模板;" +
                "不要使用 emoji,不要输出代码块。";
    }

    /**
     * 构建 RAG 模式的用户消息,包含科技图资料和相关词条。
     *
     * @param question 用户问题
     * @param hits     匹配的节点列表
     * @param chunks   匹配的语料块列表
     * @return 格式化后的用户消息
     */
    private String ragUserMessage(String question, List<NodeBrief> hits,
                                  List<MilvusStore.ChunkHit> chunks,
                                  String conversationContext) {
        StringBuilder sb = new StringBuilder("### 科技图资料\n");
        if (hits.isEmpty()) {
            sb.append("(未检索到直接相关节点)\n");
        }
        for (NodeBrief n : hits) {
            sb.append("- 【").append(n.name()).append("】").append(n.era())
                    .append(" · ").append(n.yearLabel()).append("\n  ").append(n.summary()).append("\n");
            try {
                NodeDetail detail = graphClient.nodeDetail(n.id(), null).data();
                if (detail != null && !detail.prerequisites().isEmpty()) {
                    sb.append("  直接前置:");
                    sb.append(String.join("、", detail.prerequisites().stream().map(NodeBrief::name).toList()));
                    sb.append("\n");
                }
            } catch (Exception ignored) {
            }
        }
        if (!chunks.isEmpty()) {
            sb.append("\n### 相关词条摘录\n");
            for (MilvusStore.ChunkHit c : chunks) {
                sb.append("- ").append(c.text()).append("\n");
                if (c.url() != null && !c.url().isBlank()) {
                    sb.append("  (来源: ").append(c.url()).append(")\n");
                }
            }
        }
        if (conversationContext != null && !conversationContext.isBlank()) {
            sb.append("\n").append(conversationContext).append("\n");
        }
        sb.append("\n### 用户问题\n").append(question);
        return sb.toString();
    }

    /**
     * 规则引擎模式生成回答。
     * 直接基于图谱数据生成结构化回答,不调用大模型。
     *
     * @param question 用户问题
     * @param hits     匹配的节点列表
     * @return 规则生成的回答
     */
    private String rulesAnswer(String question, List<NodeBrief> hits) {
        if (hits.isEmpty()) {
            return "### 结论\n我没有在科技图中找到与问题直接相关的技术节点。\n\n" +
                    "### 关键依据\n- 当前检索没有命中明确节点。\n" +
                    "- 问题可以改成具体技术名、时代名或关系问题。\n\n" +
                    "### 学习路径\n1. 先选择图谱上的一个节点。\n" +
                    "2. 再询问它的前置技术、重要性或解锁方向。\n\n" +
                    "### 下一步\n- 可以试试: 蒸汽机的前置技术有哪些? 互联网解锁了什么? 文字是什么时候出现的?";
        }
        NodeBrief top = hits.get(0);
        NodeDetail detail = graphClient.nodeDetail(top.id(), null).data();
        List<NodeBrief> chain = graphClient.prerequisites(top.id()).data();
        if (chain == null) {
            chain = List.of();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### 结论\n")
                .append("【").append(top.name()).append("】属于 ").append(top.era())
                .append(" · ").append(top.yearLabel()).append("。")
                .append(top.summary()).append("\n\n");

        sb.append("### 关键依据\n")
                .append("- 命中节点: ").append(top.name()).append("\n")
                .append("- 时代位置: ").append(top.era()).append(" · ").append(top.yearLabel()).append("\n");
        if (detail != null && detail.detail() != null && !detail.detail().isBlank()) {
            sb.append("- 图谱详情: ").append(detail.detail()).append("\n");
        }

        sb.append("\n### 前置链\n");
        if (!chain.isEmpty()) {
            sb.append("- 完整前置技术链共 ").append(chain.size()).append(" 项。\n");
            Map<String, List<String>> byEra = new LinkedHashMap<>();
            for (NodeBrief n : chain) {
                byEra.computeIfAbsent(n.era(), k -> new ArrayList<>()).add(n.name());
            }
            byEra.forEach((era, names) ->
                    sb.append("- ").append(era).append(": ").append(String.join("、", names)).append("\n"));
        } else {
            sb.append("- 图谱中暂未记录它的完整前置链,可把它视作当前命中的基础或孤立节点。\n");
        }

        sb.append("\n### 解锁方向\n");
        if (detail != null && !detail.unlocks().isEmpty()) {
            sb.append("- 它直接解锁: ")
                    .append(String.join("、", detail.unlocks().stream().map(NodeBrief::name).toList()))
                    .append("\n");
        } else {
            sb.append("- 图谱中暂未记录直接解锁节点。\n");
        }

        sb.append("\n### 下一步\n");
        if (detail != null && !detail.unlocks().isEmpty()) {
            sb.append("- 可以沿着「").append(detail.unlocks().get(0).name()).append("」继续探索它带来的技术演化。\n");
        } else {
            sb.append("- 可以追问它为什么重要,或让 AI 继续补齐可学习的前置技术。\n");
        }
        return sb.toString();
    }

    /**
     * 消耗用户免费配额。
     * 会员用户不受配额限制,免费用户每日有固定配额。
     *
     * @param userId 用户 ID
     * @return 剩余配额(-1 表示会员无限)
     */
    private long consumeQuota(Long userId) {
        if (isMember(userId)) {
            return -1;
        }
        String key = QUOTA_KEY_PREFIX + userId + ":" + LocalDate.now();
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1L) {
            redis.expire(key, Duration.ofDays(1));
        }
        long remaining = props.freeQuotaPerDay() - (used == null ? 0 : used);
        if (remaining < 0) {
            throw new BizException(429, "今日免费问答次数已用完,开通会员畅享无限次 AI 问答");
        }
        return remaining;
    }

    /**
     * 检查用户是否为会员。
     *
     * @param userId 用户 ID
     * @return true 表示会员
     */
    private boolean isMember(Long userId) {
        try {
            ApiResponse<Map<String, Object>> resp = userClient.membership(userId);
            return resp != null && resp.data() != null
                    && Boolean.TRUE.equals(resp.data().get("member"));
        } catch (Exception e) {
            log.warn("会员校验失败,按非会员处理: userId={} err={}", userId, AiHarness.safeFailure(e));
            return false;
        }
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
        String prompt = buildApplicationPrompt(req);
        try {
            String answer = chatModel.chat(prompt);
            return parseApplicationIds(answer, req.neighbors());
        } catch (Exception e) {
            log.warn("应用判定 LLM 调用失败 [nodeId={}]: {}", req.nodeId(), AiHarness.safeFailure(e));
            return List.of();
        }
    }

    private String buildApplicationPrompt(ApplicationClassifyRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是材料与化工领域的知识图谱编辑。下面是材料「")
                .append(req.nodeName()).append("」(领域:").append(req.category()).append(")的直接关联条目清单。\n");
        sb.append("请从中挑出属于「").append(req.nodeName())
                .append("」的【下游应用 / 产业链 / 制成品 / 工业用途】的条目")
                .append("(例如:用它制成的产品、器件,以它为关键原料的工业流程或终端应用)。\n");
        sb.append("必须排除以下类型的条目:化学式、CAS号、摩尔量/焓/密度/沸点等物理化学属性、")
                .append("同位素、晶系、族/周期分类、可见光/光学性质、纯度量纲、命名空间词条等纯属性或分类条目。\n\n");
        sb.append("条目清单(每行一条):\n");
        for (NeighborBrief n : req.neighbors()) {
            sb.append("- id=").append(n.id()).append(" 名称=").append(n.name());
            if (n.category() != null && !n.category().isBlank()) sb.append(" 领域=").append(n.category());
            if (n.summary() != null && !n.summary().isBlank()) sb.append(" 摘要=").append(n.summary());
            sb.append("\n");
        }
        sb.append("\n只返回一个 JSON 数组,元素为被判为应用的条目的 id(数字),不要任何解释文字。")
                .append("若无任何应用条目,返回 []。例如:[123, 456, 789]");
        return sb.toString();
    }

    /** 解析 LLM 返回的 JSON 数组,并把 name/id 回填校验后返回 id。 */
    private List<Long> parseApplicationIds(String answer, List<NeighborBrief> neighbors) {
        if (answer == null) return List.of();
        // 容错:LLM 可能附带前后文字,提取第一个 [ ... ] 片段。
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        String json = answer.substring(start, end + 1).trim();
        // name→id 映射(LLM 偶尔返回 name 而非 id 时也能兜底)。
        Map<String, Long> nameToId = new LinkedHashMap<>();
        Set<Long> validIds = new HashSet<>();
        for (NeighborBrief n : neighbors) {
            if (n.id() != null) validIds.add(n.id());
            if (n.name() != null) nameToId.put(n.name(), n.id());
        }
        List<Long> result = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> tokens = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            for (String t : tokens) {
                if (t == null) continue;
                String s = t.trim();
                if (s.isEmpty()) continue;
                try {
                    long id = Long.parseLong(s);
                    if (validIds.contains(id)) result.add(id);
                } catch (NumberFormatException nfe) {
                    Long id = nameToId.get(s);
                    if (id != null) result.add(id);
                }
            }
        } catch (Exception e) {
            log.warn("应用判定响应解析失败,降级为空列表: raw={}", json);
            return List.of();
        }
        return result;
    }
}
