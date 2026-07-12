package com.sparrow.industrychain.application.forum;

import com.sparrow.industrychain.application.config.IndustryAgentConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.industrychain.infrastructure.event.IndustryChainEventHub;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 论坛事件总线：Multi-Agent 协作的核心。
 *
 * <p>对照 BettaFish 的 {@code ForumEngine/monitor.py} + {@code utils/forum_reader.py}：
 * <ul>
 *   <li>Agent 发言写入论坛(内存队列 + DB 持久化 + SSE 实时推送)；</li>
 *   <li>每攒够 {@link #HOST_TRIGGER_THRESHOLD} 条 Agent 发言，异步触发主持人 LLM 生成四段式总结，写入论坛；</li>
 *   <li>Agent 反思总结前可读取「最新主持人发言」注入 prompt(软耦合)。</li>
 * </ul>
 * 主持人按单次运行串行生成(AtomicBoolean 守卫)，不同运行互不阻塞。
 */
@Component
public class ForumBus {

    private static final Logger log = LoggerFactory.getLogger(ForumBus.class);

    /** 每捕获多少条 Agent 发言触发一次主持人发言(对照 BettaFish host_speech_threshold=5)。 */
    static final int HOST_TRIGGER_THRESHOLD = 5;
    static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final IndustryChainRepository repository;
    private final IndustryChainEventHub events;
    // 通过门面按需取当前模型,使管理端热切换后主持人发言也用新模型
    // (旧实现构造时捕获 ChatModel 永不更新)。
    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;
    private final Executor hostExecutor;
    private IndustryAgentConfigService agentConfigs;

    /** 每次运行独立的事件缓冲、未结算计数和主持人生成状态。 */
    private final Map<Long, ForumState> states = new ConcurrentHashMap<>();

    public ForumBus(IndustryChainRepository repository, IndustryChainEventHub events,
                    ChatModelProvider chatModelProvider, ObjectMapper objectMapper,
                    @Qualifier("industryChainForumExecutor") Executor hostExecutor) {
        this.repository = repository;
        this.events = events;
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
        this.hostExecutor = hostExecutor;
    }

    @Autowired(required = false)
    void setAgentConfigs(IndustryAgentConfigService agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    /** Agent/系统 发言：写入 DB + 推送 SSE + 累计触发主持人。 */
    public void publish(long cardId, long runId, long userId, String source, String content) {
        String timestamp = ZonedDateTime.now(CHINA_ZONE).toOffsetDateTime().toString();
        ForumEvent event = new ForumEvent(cardId, runId, source, content, timestamp);
        repository.addForumEvent(userId, cardId, runId, source, content);
        ForumState state = states.computeIfAbsent(runId, ignored -> new ForumState());
        state.events.add(event);
        emitForum(cardId, runId, event);
        if (isAgent(source)) {
            int pending = state.pending.incrementAndGet();
            if (pending >= HOST_TRIGGER_THRESHOLD) {
                scheduleHost(cardId, runId, userId, state);
            }
        }
    }

    /** 读取本次运行最新的主持人发言，供 Agent 反思总结时注入 prompt。无则返回空串。 */
    public String latestHostSpeech(long runId) {
        ForumState state = states.get(runId);
        if (state == null) return "";
        ForumEvent latest = null;
        for (ForumEvent event : state.events) {
            if (ForumEvent.HOST.equals(event.source())) latest = event;
        }
        return latest == null ? "" : latest.content();
    }

    /**
     * 推送细粒度思考进度(转发到 EventHub 的 thinking 事件)。
     * 供编排器/Agent 在各子步骤开始时调用,让前端实时显示「谁正在做什么」。
     * 与 publish() 不同:不落库、不触发主持人、不占发言计数,纯瞬时进度提示。
     */
    public void thinking(long cardId, long runId, String source, String message) {
        events.thinking(cardId, runId, source, message);
    }

    /**
     * 推送流式 token(转发到 EventHub 的 stream 事件)。
     * Agent 逐 token 生成时调用,前端按 streamId 幂等更新同一条气泡(打字机效果)。
     * 不落库:完整结果由轮次结束后的 forum 发言(publish)持久化。
     */
    public void stream(long cardId, long runId, String streamId, String source, String text) {
        events.stream(cardId, runId, streamId, source, text);
    }

    /** 加载历史论坛记录(工作台初次进入时还原流)。按 cardId+runId 检索，归属由调用方保证。 */
    public List<ForumEvent> history(long cardId, long runId) {
        return repository.forumEvents(cardId, runId).stream()
                .map(row -> new ForumEvent(row.cardId(), row.runId(), row.source(), row.content(),
                        row.createdAt() == null ? "" : row.createdAt().atOffset(ZoneOffset.UTC)
                                .atZoneSameInstant(CHINA_ZONE).toOffsetDateTime().toString()))
                .toList();
    }

    /** 运行结束时清理本次运行的内存状态。 */
    public void reset(long runId) {
        ForumState state = states.remove(runId);
        if (state != null) state.active.set(false);
    }

    private void scheduleHost(long cardId, long runId, long userId, ForumState state) {
        if (!state.active.get() || state.pending.get() < HOST_TRIGGER_THRESHOLD
                || !state.hostGenerating.compareAndSet(false, true)) return;
        try {
            hostExecutor.execute(() -> generateHost(cardId, runId, userId, state));
        } catch (RuntimeException error) {
            state.hostGenerating.set(false);
            log.warn("论坛主持人任务提交失败: runId={}", runId, error);
        }
    }

    private void generateHost(long cardId, long runId, long userId, ForumState state) {
        try {
            if (!isCurrent(runId, state)) return;
            int claimed = state.pending.getAndUpdate(value ->
                    value >= HOST_TRIGGER_THRESHOLD ? value - HOST_TRIGGER_THRESHOLD : value);
            if (claimed < HOST_TRIGGER_THRESHOLD) return;
            // 取最近 HOST_TRIGGER_THRESHOLD 条 Agent 发言
            List<ForumEvent> recent = state.events.stream()
                    .filter(e -> isAgent(e.source())).toList();
            int from = Math.max(0, recent.size() - HOST_TRIGGER_THRESHOLD);
            List<ForumEvent> batch = recent.subList(from, recent.size());
            if (batch.isEmpty()) return;
            String hostSpeech = generateHostSpeech(batch);
            if (isCurrent(runId, state) && hostSpeech != null && !hostSpeech.isBlank()) {
                publish(cardId, runId, userId, ForumEvent.HOST, hostSpeech);
            }
        } catch (Exception error) {
            log.warn("论坛主持人发言生成失败: runId={}", runId, error);
        } finally {
            state.hostGenerating.set(false);
            if (isCurrent(runId, state) && state.pending.get() >= HOST_TRIGGER_THRESHOLD) {
                scheduleHost(cardId, runId, userId, state);
            }
        }
    }

    private boolean isCurrent(long runId, ForumState state) {
        return state.active.get() && states.get(runId) == state;
    }

    /** 主持人 LLM：基于最近一批 Agent 发言生成四段式总结(时间线/观点整合/分歧/引导问题)。 */
    private String generateHostSpeech(List<ForumEvent> speeches) {
        ChatModel chatModel = chatModelProvider.model();
        if (chatModel == null) return null;
        StringBuilder sb = new StringBuilder();
        for (ForumEvent s : speeches) {
            sb.append(s.sourceText()).append(": ").append(s.content()).append("\n\n");
        }
        String configuredPrompt = agentConfigs == null ? "你是产业链调研论坛主持人。"
                : agentConfigs.requireEnabled(IndustryAgentConfigService.FORUM_HOST).systemPrompt();
        return chatModel.chat(configuredPrompt + "\n\n" + """
                你是产业链调研论坛的主持人。下面是多个调研 Agent 的最近发言。请生成一份不超过 600 字的中文总结，
                分为四段，用 Markdown 二级/三级标题区分：① 关键发现时间线；② 观点整合与共识；③ 分歧与待核验问题；
                ④ 给各 Agent 的下一轮引导问题(2-3 个)。不得编造发言中没有的事实，只做整合与引导。

                Agent 发言：
                %s
                """.formatted(sb));
    }

    private void emitForum(long cardId, long runId, ForumEvent event) {
        try {
            events.forum(cardId, runId, objectMapper.convertValue(event, Map.class));
        } catch (Exception ignored) {
            // SSE 推送失败不应阻断调研流程
        }
    }

    private boolean isAgent(String source) {
        return ForumEvent.INDUSTRY.equals(source) || ForumEvent.QUERY.equals(source)
                || ForumEvent.INSIGHT.equals(source);
    }

    private static final class ForumState {
        private final Queue<ForumEvent> events = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicBoolean hostGenerating = new AtomicBoolean();
        private final AtomicBoolean active = new AtomicBoolean(true);
    }
}
