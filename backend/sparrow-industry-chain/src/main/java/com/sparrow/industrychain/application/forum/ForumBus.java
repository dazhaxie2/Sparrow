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

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 论坛事件总线：Multi-Agent 协作的核心。
 *
 * <p>对照 BettaFish 的 {@code ForumEngine/monitor.py} + {@code utils/forum_reader.py}：
 * <ul>
 *   <li>Agent 发言写入论坛(内存队列 + DB 持久化 + SSE 实时推送)；</li>
 *   <li>每攒够 {@link #HOST_TRIGGER_THRESHOLD} 条 Agent 发言，同步触发主持人 LLM 生成四段式总结，写入论坛；</li>
 *   <li>Agent 反思总结前可读取「最新主持人发言」注入 prompt(软耦合)。</li>
 * </ul>
 * 主持人串行生成(AtomicBoolean 守卫)，避免并发重复触发。
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
    private IndustryAgentConfigService agentConfigs;

    /** 每次运行的事件缓冲(供主持人读取最近发言)与未结算计数器。 */
    private final Map<Long, Queue<ForumEvent>> buffers = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> hostGenerating = new ConcurrentHashMap<>();

    public ForumBus(IndustryChainRepository repository, IndustryChainEventHub events,
                    ChatModelProvider chatModelProvider, ObjectMapper objectMapper) {
        this.repository = repository;
        this.events = events;
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void setAgentConfigs(IndustryAgentConfigService agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    /** Agent/系统 发言：写入 DB + 推送 SSE + 累计触发主持人。 */
    public synchronized void publish(long cardId, long runId, long userId, String source, String content) {
        String timestamp = ZonedDateTime.now(CHINA_ZONE).toOffsetDateTime().toString();
        ForumEvent event = new ForumEvent(cardId, runId, source, content, timestamp);
        repository.addForumEvent(userId, cardId, runId, source, content);
        buffers.computeIfAbsent(runId, k -> new ConcurrentLinkedQueue<>()).add(event);
        emitForum(cardId, runId, event);
        if (isAgent(source)) {
            int pending = pendingCounts.computeIfAbsent(runId, k -> new AtomicInteger())
                    .incrementAndGet();
            if (pending >= HOST_TRIGGER_THRESHOLD) {
                triggerHost(cardId, runId, userId);
            }
        }
    }

    /** 读取本次运行最新的主持人发言，供 Agent 反思总结时注入 prompt。无则返回空串。 */
    public String latestHostSpeech(long runId) {
        Queue<ForumEvent> queue = buffers.get(runId);
        if (queue == null) return "";
        ForumEvent latest = null;
        for (ForumEvent event : queue) {
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
        buffers.remove(runId);
        pendingCounts.remove(runId);
        hostGenerating.remove(runId);
    }

    private void triggerHost(long cardId, long runId, long userId) {
        AtomicBoolean guard = hostGenerating.computeIfAbsent(runId, k -> new AtomicBoolean());
        if (!guard.compareAndSet(false, true)) return;
        try {
            Queue<ForumEvent> queue = buffers.get(runId);
            if (queue == null) return;
            // 取最近 HOST_TRIGGER_THRESHOLD 条 Agent 发言
            List<ForumEvent> recent = queue.stream()
                    .filter(e -> isAgent(e.source())).toList();
            int from = Math.max(0, recent.size() - HOST_TRIGGER_THRESHOLD);
            List<ForumEvent> batch = recent.subList(from, recent.size());
            if (batch.isEmpty()) return;
            String hostSpeech = generateHostSpeech(batch);
            if (hostSpeech != null && !hostSpeech.isBlank()) {
                pendingCounts.get(runId).set(0);
                publish(cardId, runId, userId, ForumEvent.HOST, hostSpeech);
            }
        } catch (Exception error) {
            log.warn("论坛主持人发言生成失败: runId={}", runId, error);
        } finally {
            guard.set(false);
        }
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
}

