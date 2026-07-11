package com.sparrow.ai.application;

import com.sparrow.ai.application.AiService.AgentStep;
import com.sparrow.ai.application.AiService.ApplicationClassifyRequest;
import com.sparrow.ai.application.AiService.AskResult;
import com.sparrow.ai.application.AiService.NeighborBrief;
import com.sparrow.ai.application.AiService.SourceRef;
import com.sparrow.ai.application.AiService.StreamSink;
import com.sparrow.ai.infrastructure.agent.TechTreeAgent;
import com.sparrow.ai.infrastructure.client.GraphClient;
import com.sparrow.ai.infrastructure.client.GraphViews.EdgeBrief;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeBrief;
import com.sparrow.ai.infrastructure.client.GraphViews.Tree;
import com.sparrow.ai.infrastructure.client.UserClient;
import com.sparrow.ai.infrastructure.config.AiProperties;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository.ChatMessageRow;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository.ChatSessionRow;
import com.sparrow.ai.infrastructure.rag.MilvusStore;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AiService 单元测试 —— 聚焦三类核心行为:
 * 1. 配额门控(会员无限 / 免费用户日限额 429)
 * 2. 三级降级链 Agent → RAG → 规则
 * 3. 应用判定 LLM 解析
 * 全部用纯 Mockito,无 Spring 上下文,与现有 TradeServiceTest/GraphServiceTest 风格一致。
 */
class AiServiceTest {

    private AiProperties props;
    private ChatModel chatModel;
    private StreamingChatModel streamingChatModel;
    private EmbeddingModel embeddingModel;
    private MilvusStore milvus;
    private GraphClient graphClient;
    private UserClient userClient;
    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TechTreeAgent> agentProvider = mock(ObjectProvider.class);
    private ChatHistoryRepository chatHistory;

    private TechTreeAgent agent;
    private AiService aiService;

    /** 无 agent、无 LLM 的最简实例(走规则路径)。 */
    private AiService rulesOnlyService;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        props = new AiProperties("https://x", "key", "glm-4", "embedding-3", 5);
        chatModel = mock(ChatModel.class);
        streamingChatModel = mock(StreamingChatModel.class);
        embeddingModel = mock(EmbeddingModel.class);
        milvus = mock(MilvusStore.class);
        graphClient = mock(GraphClient.class);
        userClient = mock(UserClient.class);
        redis = mock(StringRedisTemplate.class);
        agent = mock(TechTreeAgent.class);
        chatHistory = mock(ChatHistoryRepository.class);

        aiService = new AiService(props, chatModel, streamingChatModel, embeddingModel,
                milvus, graphClient, userClient, redis, agentProvider, chatHistory);

        // 无 LLM 的实例:chatModel/embeddingModel 传 null
        rulesOnlyService = new AiService(props, null, null, null,
                milvus, graphClient, userClient, redis, agentProvider, chatHistory);

        // 默认:图谱有节点,供 keywordMatch 命中
        when(graphClient.tree()).thenReturn(ApiResponse.ok(tree()));
        // rulesAnswer 命中节点后会取详情 + 前置链,默认返回空壳避免 NPE
        when(graphClient.nodeDetail(anyLong(), any())).thenReturn(ApiResponse.ok(null));
        when(graphClient.prerequisites(anyLong())).thenReturn(ApiResponse.ok(List.of()));
    }

    // ── 配额门控 ──

    @Test
    void t1_quotaExhaustedThrows429BeforeAnyModelCall() {
        // 免费用户 + 配额已耗尽:Redis 计数 6 > 限额 5
        stubMembership(false);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(6L);

        BizException e = assertThrows(BizException.class,
                () -> aiService.ask(42L, "蒸汽机是什么?", null));

        assertEquals(429, e.getCode());
        // 关键:配额检查先于一切模型调用
        verifyNoInteractions(chatModel, embeddingModel, milvus);
    }

    @Test
    void t2_memberBypassesQuotaAndReturnsUnlimited() {
        // 会员:不碰 Redis 配额,remainingQuota = -1
        stubMembership(true);
        // LLM 未配置 → 落规则路径,但会员身份已被记录
        AiService memberService = new AiService(props, null, null, null,
                milvus, graphClient, userClient, redis, agentProvider, chatHistory);

        AskResult r = memberService.ask(42L, "蒸汽机是什么?", null);

        assertEquals(-1, r.remainingQuota());
        assertEquals("degraded", r.harness().status());
        assertTrue(r.harness().fallbackUsed());
        verify(redis, never()).opsForValue();
    }

    // ── 三级降级链 ──

    @Test
    void t3_agentSuccessShortCircuitsRag() {
        // Agent 成功 → mode=agent,RAG 的 chatModel.chat 不被调用
        stubMembership(true);
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        when(agent.chat(anyString(), anyString())).thenReturn("Agent 给出的深度回答");
        // milvus 未就绪 → retrieve 走 keywordMatch,避免触发 embedding
        when(milvus.ready()).thenReturn(false);

        AskResult r = aiService.ask(42L, "蒸汽机的前置技术有哪些?", null);

        assertEquals("agent", r.mode());
        assertEquals("markdown:v1", r.format());
        assertFalse(r.answer().isEmpty());
        assertEquals("completed", r.harness().status());
        assertEquals("general-chat", r.harness().surface());
        // 证明 RAG 分支没执行
        verify(chatModel, never()).chat(anyString());
    }

    @Test
    void t4a_agentFailureFallsBackToRag() {
        stubMembership(true);
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        when(agent.chat(anyString(), anyString())).thenThrow(new RuntimeException("agent down"));
        when(milvus.ready()).thenReturn(false);
        when(chatModel.chat(anyString())).thenReturn("RAG 生成的回答");

        AskResult r = aiService.ask(42L, "蒸汽机是什么?", null);

        assertEquals("rag", r.mode());
        assertTrue(r.answer().contains("RAG 生成的回答"));
        assertEquals("degraded", r.harness().status());
    }

    @Test
    void t4b_agentAndRagBothFailFallBackToRules() {
        stubMembership(true);
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        when(agent.chat(anyString(), anyString())).thenThrow(new RuntimeException("agent down"));
        when(milvus.ready()).thenReturn(false);
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("llm down"));

        AskResult r = aiService.ask(42L, "蒸汽机是什么?", null);

        // 命中 keywordMatch("蒸汽机") → rulesAnswer 产出结构化模板
        assertEquals("rules", r.mode());
        assertTrue(r.answer().contains("蒸汽机"));
        assertEquals("degraded", r.harness().status());
    }

    @Test
    void t4c_rulesPathWithNoHitReturnsNotFoundTemplate() {
        stubMembership(true);
        // 问题不匹配任何节点名 → 命中"未找到"模板
        when(milvus.ready()).thenReturn(false);

        AskResult r = rulesOnlyService.ask(42L, "一个完全不存在的名词xyz", null);

        assertEquals("rules", r.mode());
        assertTrue(r.answer().contains("没有在科技图中找到"));
    }

    @Test
    void t4d_sessionHistoryIsBoundedContextAndExchangePersistsAtomically() {
        stubMembership(true);
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        when(agent.chat(anyString(), anyString())).thenReturn("结合历史后的回答");
        when(milvus.ready()).thenReturn(false);
        LocalDateTime now = LocalDateTime.now();
        when(chatHistory.findSession(42L, 7L))
                .thenReturn(Optional.of(new ChatSessionRow(7L, 42L, "蒸汽机", now, now)));
        when(chatHistory.messages(42L, 7L)).thenReturn(List.of(
                new ChatMessageRow(1L, 7L, 42L, "user", "上一轮问题", null, now),
                new ChatMessageRow(2L, 7L, 42L, "assistant", "上一轮回答", "agent", now)));

        AskResult result = aiService.ask(42L, "继续解释", 7L, "graph-dialog");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(agent).chat(eq("42"), prompt.capture());
        assertTrue(prompt.getValue().contains("上一轮问题"));
        assertTrue(prompt.getValue().contains("上一轮回答"));
        assertEquals(2, result.harness().contextMessages());
        assertEquals("graph-dialog", result.harness().surface());
        verify(chatHistory).addExchange(42L, 7L, "继续解释", "结合历史后的回答", "agent");
    }

    @Test
    void t4e_sessionPersistenceFailureDoesNotReturnCompletedResponse() {
        stubMembership(true);
        when(milvus.ready()).thenReturn(false);
        LocalDateTime now = LocalDateTime.now();
        when(chatHistory.findSession(42L, 7L))
                .thenReturn(Optional.of(new ChatSessionRow(7L, 42L, "蒸汽机", now, now)));
        when(chatHistory.messages(42L, 7L)).thenReturn(List.of());
        doThrow(new IllegalStateException("database unavailable")).when(chatHistory)
                .addExchange(eq(42L), eq(7L), anyString(), anyString(), anyString());

        BizException error = assertThrows(BizException.class,
                () -> rulesOnlyService.ask(42L, "继续解释", 7L));

        assertEquals(503, error.getCode());
        assertTrue(error.getMessage().contains("追踪 ID"));
    }

    // ── 应用判定 ──

    @Test
    void t5a_classifyApplicationsReturnsEmptyWhenLlmUnconfigured() {
        ApplicationClassifyRequest req = new ApplicationClassifyRequest(
                1L, "钢铁", "材料",
                List.of(new NeighborBrief(10L, "钢材", "摘要", "应用")));

        List<Long> result = rulesOnlyService.classifyApplications(req);

        assertTrue(result.isEmpty());
        verifyNoInteractions(chatModel);
    }

    @Test
    void t5b_classifyApplicationsParsesJsonArrayAndFiltersUnknownIds() {
        // LLM 返回含有效 id、无效 id、name 回填三种情况
        when(chatModel.chat(anyString())).thenReturn(
                "前缀 [\"10\", \"99\", \"钢材\"] 后缀");  // 99 不在 validIds; "钢材" 走 name→id 回填
        ApplicationClassifyRequest req = new ApplicationClassifyRequest(
                1L, "钢铁", "材料",
                List.of(
                        new NeighborBrief(10L, "钢材", "摘要", "应用"),
                        new NeighborBrief(20L, "其他", "摘要", "分类")));

        List<Long> result = aiService.classifyApplications(req);

        // 10(有效 id) + 钢材(name→id=10) 去重后;99 过滤掉
        // 注意:同一 id 10 出现两次(id 直填 + name 回填),结果含两个 10
        assertEquals(List.of(10L, 10L), result);
    }

    @Test
    void t5c_classifyApplicationsReturnsEmptyOnLlmFailure() {
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("llm down"));
        ApplicationClassifyRequest req = new ApplicationClassifyRequest(
                1L, "钢铁", "材料",
                List.of(new NeighborBrief(10L, "钢材", "摘要", "应用")));

        List<Long> result = aiService.classifyApplications(req);

        assertTrue(result.isEmpty());
    }

    @Test
    void t5d_classifyApplicationsReturnsEmptyForNullRequest() {
        assertTrue(aiService.classifyApplications(null).isEmpty());
        verifyNoInteractions(chatModel);
    }

    // ── 流式(rules 路径,验证事件顺序)──

    @Test
    void t6_askStreamRulesPathEmitsMetaDeltaDoneComplete() throws Exception {
        // 用 rulesOnlyService:streamingChatModel=null → streamConfigured=false
        // → askStreamInternal 跳过 Agent/RAG 流式,直接走规则一次性 delta,可同步断言
        stubMembership(true);
        when(milvus.ready()).thenReturn(false);

        // recording sink:按序记录事件,complete 时 countDown
        List<String> events = new ArrayList<>();
        List<Map<String, ?>> payloads = new ArrayList<>();
        AtomicReference<String> firstError = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        StreamSink sink = new StreamSink() {
            @Override public void emit(String event, Map<String, ?> data) {
                events.add(event);
                payloads.add(data);
            }
            @Override public void complete() { done.countDown(); }
            @Override public void completeWithError(Throwable error) {
                firstError.set(error.toString());
                done.countDown();
            }
        };

        rulesOnlyService.askStream(42L, "蒸汽机是什么?", null, sink);

        // askStream 异步跑在虚拟线程,等它结束(给足时间:虚拟线程启动 + mock 链)
        assertTrue(done.await(15, TimeUnit.SECONDS),
                "流式应在 15s 内完成,事件=" + events + " error=" + firstError.get());

        // Harness 阶段事件可多次出现；业务输出仍严格保持 meta → delta → done。
        assertEquals("harness", events.get(0));
        List<String> businessEvents = events.stream().filter(event -> !"harness".equals(event)).toList();
        assertEquals(List.of("meta", "delta", "done"), businessEvents,
                "实际事件序列错误: " + events);
        // meta 含 mode=rules
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) payloads.get(events.indexOf("meta"));
        assertEquals("rules", meta.get("mode"));
        assertTrue(meta.containsKey("harness"));
        // done 含 mode=rules
        @SuppressWarnings("unchecked")
        Map<String, Object> donePayload = (Map<String, Object>) payloads.get(events.indexOf("done"));
        assertEquals("rules", donePayload.get("mode"));
        assertTrue(donePayload.containsKey("harness"));
    }

    @Test
    void t6b_streamPersistenceFailureEmitsErrorWithoutDone() throws Exception {
        stubMembership(true);
        when(milvus.ready()).thenReturn(false);
        LocalDateTime now = LocalDateTime.now();
        when(chatHistory.findSession(42L, 7L))
                .thenReturn(Optional.of(new ChatSessionRow(7L, 42L, "蒸汽机", now, now)));
        when(chatHistory.messages(42L, 7L)).thenReturn(List.of());
        doThrow(new IllegalStateException("database unavailable")).when(chatHistory)
                .addExchange(eq(42L), eq(7L), anyString(), anyString(), anyString());
        List<String> events = new ArrayList<>();
        CountDownLatch finished = new CountDownLatch(1);
        StreamSink sink = new StreamSink() {
            @Override public void emit(String event, Map<String, ?> data) { events.add(event); }
            @Override public void complete() { finished.countDown(); }
            @Override public void completeWithError(Throwable error) { finished.countDown(); }
        };

        rulesOnlyService.askStream(42L, "继续解释", 7L, sink);

        assertTrue(finished.await(15, TimeUnit.SECONDS), "流式失败应及时收尾，事件=" + events);
        assertTrue(events.contains("error"), "持久化失败必须发送 error，事件=" + events);
        assertFalse(events.contains("done"), "持久化失败不得发送 done，事件=" + events);
    }

    // ── 辅助 ──

    private void stubMembership(boolean member) {
        Map<String, Object> m = new HashMap<>();
        m.put("member", member);
        when(userClient.membership(anyLong())).thenReturn(ApiResponse.ok(m));
    }

    /** 构造含"蒸汽机"节点的最小图谱,供 keywordMatch 命中。 */
    private Tree tree() {
        return new Tree(
                List.of(
                        new NodeBrief(41L, "steam_engine", "蒸汽机", "工业革命", 4, "18世纪", "蒸汽机摘要", false),
                        new NodeBrief(1L, "fire", "火", "石器时代", 1, "约公元前50万年", "火摘要", false)),
                List.of(new EdgeBrief(1L, 41L)));
    }
}
