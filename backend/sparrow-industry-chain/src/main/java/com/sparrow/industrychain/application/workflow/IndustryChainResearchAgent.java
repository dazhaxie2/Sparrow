package com.sparrow.industrychain.application.workflow;

import com.sparrow.industrychain.application.forum.ForumBus;
import com.sparrow.industrychain.application.forum.ForumEvent;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient.SearchSource;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 产业链调研角色 Agent：执行「首轮概览搜索 → 总结 → 反思 → 深挖搜索 → 再总结」的反思循环。
 *
 * <p>对照 BettaFish 各 Engine 的 agent.py 反思循环(MAX_REFLECTIONS)。每轮总结写入论坛(供主持人读取)，
 * 反思总结前读取最新主持人发言注入 prompt(软耦合)。三个角色 Agent(行业/检索/洞察)共用本类，
 * 仅 systemPrompt 不同——分别侧重产业链结构、权威检索、纵深洞察。
 */
public class IndustryChainResearchAgent {

    private static final Logger log = LoggerFactory.getLogger(IndustryChainResearchAgent.class);

    /** 每个角色的反思轮次。1 轮在调研深度与耗时之间取平衡(每轮 = 1 反思 + 1 检索 + 1 总结)。 */
    static final int MAX_REFLECTIONS = 1;

    /** 流式 token 推送的最小间隔:避免高频 token 淹没 SSE 通道。 */
    static final long STREAM_FLUSH_MS = 250;

    /** 当前调研上下文(research() 入口设置),供 summarize/reflect/chatStream 推送 stream 事件。 */
    private PublishContext currentCtx;

    private final String role;
    private final String roleText;
    private final String systemPrompt;
    private final ChatModel chatModel;
    private final WebSearchClient webSearch;
    private final ForumBus forum;
    /** 思考进度回调:(source, message) → 推送 thinking 事件;为 null 时不推送(兼容旧调用)。 */
    private final BiConsumer<String, String> thinkingSink;
    /**
     * 流式对话门面:供总结/反思路径逐 token 推送(stream 事件)。为 null 时回退阻塞模型
     * (chatModel.chat),仅失去「逐 token」体验,功能不受影响。
     */
    private final com.sparrow.industrychain.infrastructure.llm.ChatModelProvider chatProvider;

    public IndustryChainResearchAgent(String role, String roleText, String systemPrompt,
                              ChatModel chatModel, WebSearchClient webSearch, ForumBus forum,
                              BiConsumer<String, String> thinkingSink,
                              com.sparrow.industrychain.infrastructure.llm.ChatModelProvider chatProvider) {
        this.role = role;
        this.roleText = roleText;
        this.systemPrompt = systemPrompt;
        this.chatModel = chatModel;
        this.webSearch = webSearch;
        this.forum = forum;
        this.thinkingSink = thinkingSink;
        this.chatProvider = chatProvider;
    }

    /** 带 thinkingSink 但无流式 provider 的构造器(回退阻塞模型)。 */
    public IndustryChainResearchAgent(String role, String roleText, String systemPrompt,
                              ChatModel chatModel, WebSearchClient webSearch, ForumBus forum,
                              BiConsumer<String, String> thinkingSink) {
        this(role, roleText, systemPrompt, chatModel, webSearch, forum, thinkingSink, null);
    }

    /** 旧构造器兼容:无思考回调、无流式 provider。 */
    public IndustryChainResearchAgent(String role, String roleText, String systemPrompt,
                              ChatModel chatModel, WebSearchClient webSearch, ForumBus forum) {
        this(role, roleText, systemPrompt, chatModel, webSearch, forum, null, null);
    }

    /**
     * 执行调研。返回该角色整理出的、带来源编号的结论文本，以及它检索到的来源。
     *
     * <p>首轮概览来源(sharedFirstBatch)由编排器统一检索一次后共享给三角色 Agent,
     * 避免三个 Agent 各自重复检索相同的默认查询词。各角色的差异化视角体现在
     * 「对同一批来源做不同职责的总结」与「反思后各自深挖不同方向」,而非首轮检索本身。
     *
     * @param sharedFirstBatch 编排器预检索的首轮来源;为空时由本 Agent 自行检索(兼容旧调用)
     * @param publishToForum   发言落库回调(传 cardId/runId/userId 上下文)
     */
    public AgentResult research(String title, String brief, List<String> planQueries,
                                int startRefIndex, PublishContext publishToForum,
                                List<SearchSource> sharedFirstBatch) {
        this.currentCtx = publishToForum;
        List<SearchSource> sources = new ArrayList<>();
        StringBuilder findings = new StringBuilder();

        // 首轮：优先用编排器共享检索结果;无共享结果时回退到自行检索(保留原能力)。
        thinking("首轮概览检索");
        listener(publishToForum).accept(roleText + " 启动首轮概览检索", "");
        List<SearchSource> firstBatch = sharedFirstBatch != null && !sharedFirstBatch.isEmpty()
                ? sharedFirstBatch
                : webSearch.search(title, brief, planQueries, startRefIndex);
        sources.addAll(firstBatch);
        thinking("整理首轮结论");
        String firstSummary;
        try {
            firstSummary = summarize(title, brief, firstBatch, "");
        } catch (RuntimeException error) {
            log.warn("Agent 首轮总结失败，保留已检索来源继续汇总: role={} runId={}", role,
                    publishToForum.runId(), error);
            firstSummary = roleText + " 的模型总结暂时失败，已保留本轮检索来源供证据 Agent 后续核验。";
        }
        findings.append("### 首轮概览\n").append(firstSummary).append("\n\n");
        publish(publishToForum, role, firstSummary);

        // 反思循环：基于缺口分析 + 主持人引导做深挖
        for (int round = 1; round <= MAX_REFLECTIONS; round++) {
            String hostSpeech = forum.latestHostSpeech(publishToForum.runId());
            thinking("反思第 " + round + " 轮 · 分析缺口");
            String reflection;
            try {
                reflection = reflect(title, findings.toString(), round, hostSpeech);
            } catch (RuntimeException error) {
                log.warn("Agent 反思调用失败，结束当前角色的深挖但保留已有结果: role={} round={} runId={}",
                        role, round, publishToForum.runId(), error);
                publish(publishToForum, role, roleText + " 本轮深挖暂时中断，已保留此前检索结果。");
                break;
            }
            if (reflection == null || reflection.isBlank()) break;
            List<String> extraQueries = extractQueries(reflection);
            if (extraQueries.isEmpty()) {
                findings.append("### 反思第 ").append(round).append(" 轮\n").append(reflection).append("\n\n");
                publish(publishToForum, role, reflection);
                continue;
            }
            int offset = startRefIndex + sources.size();
            thinking("反思第 " + round + " 轮 · 深挖检索");
            List<SearchSource> deepBatch = webSearch.search(title, brief, extraQueries, offset);
            sources.addAll(deepBatch);
            thinking("反思第 " + round + " 轮 · 整理深挖结论");
            String deepSummary;
            try {
                deepSummary = summarize(title, brief, deepBatch, hostSpeech);
            } catch (RuntimeException error) {
                log.warn("Agent 深挖总结失败，保留已检索来源: role={} round={} runId={}",
                        role, round, publishToForum.runId(), error);
                deepSummary = roleText + " 的深挖总结暂时失败，新增来源已保留供证据 Agent 核验。";
            }
            findings.append("### 反思第 ").append(round).append(" 轮(深挖)\n").append(deepSummary).append("\n\n");
            publish(publishToForum, role, deepSummary);
        }

        return new AgentResult(findings.toString(), sources);
    }

    /** 首轮/深挖总结：把检索到的来源整理为带 [Sx] 引用的结论。hostSpeech 非空时注入主持人引导。 */
    private String summarize(String title, String brief, List<SearchSource> batch, String hostSpeech) {
        if (batch.isEmpty()) return roleText + " 本轮未检索到新来源。";
        StringBuilder ctx = new StringBuilder();
        for (SearchSource s : batch) {
            ctx.append(s.sourceRef()).append(" | ").append(s.title()).append(" | ").append(s.publisher())
                    .append('\n').append(compact(s.snippet(), 600)).append("\n\n");
        }
        String hostBlock = hostSpeech == null || hostSpeech.isBlank() ? "" :
                "\n\n论坛主持人最新引导(可参考其观点与待核验问题):\n" + compact(hostSpeech, 800);
        return chatStream((systemPrompt + """

                研究对象：%s
                调研说明：%s
                本角色本轮来源：
                %s%s

                请只依据上方来源，整理与本角色职责相关的结论，每条结论末尾标注来源编号 [Sx]。
                来源不足的写「待核验」，不得凭常识补齐。控制在 400 字以内。
                """).formatted(title, brief == null ? "" : brief, ctx, hostBlock), "整理结论");
    }

    /** 反思：分析当前结论的缺口，输出下一步该查什么(「补充查询：」段每行一个查询词)。 */
    private String reflect(String title, String findings, int round, String hostSpeech) {
        String hostBlock = hostSpeech == null || hostSpeech.isBlank() ? "" :
                "\n\n论坛主持人最新引导:\n" + compact(hostSpeech, 600);
        return chatStream((systemPrompt + """

                研究对象：%s
                你目前的结论：
                %s%s

                这是第 %d 轮反思。指出当前结论的缺口与待核验问题，并用「补充查询：」单独成段，
                每行一个查询词，用于下一轮深挖检索。不要重复已有结论。
                """).formatted(title, compact(findings, 2500), hostBlock, round), "反思分析");
    }

    /** 从反思结论中提取「补充查询：」段的查询词(对照 Orchestrator 原有逻辑)。 */
    @SuppressWarnings("StringEquality")
    private List<String> extractQueries(String reflection) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("补充查询[：:]([\\s\\S]*)").matcher(reflection);
        if (!matcher.find()) return List.of();
        String[] lines = matcher.group(1).split("\\r?\\n");
        List<String> queries = new ArrayList<>();
        for (String line : lines) {
            String q = line.replaceAll("^[\\s·\\-\\*\\d.、]+", "").trim();
            if (!q.isBlank() && q.length() <= 80) queries.add(q);
        }
        return queries;
    }

    private void publish(PublishContext ctx, String source, String content) {
        try {
            forum.publish(ctx.cardId(), ctx.runId(), ctx.userId(), source, content);
        } catch (Exception error) {
            log.warn("Agent 发言落论坛失败: role={} runId={}", role, ctx.runId(), error);
        }
    }

    /** 推送细粒度思考进度(「角色 · message」),供前端实时展示 Agent 当前在做什么。 */
    private void thinking(String message) {
        if (thinkingSink != null) {
            try {
                thinkingSink.accept(role, roleText + " · " + message);
            } catch (Exception ignored) {
                // 思考事件推送失败不影响调研流程
            }
        }
    }

    /**
     * 流式对话并逐 token 推送 stream 事件。返回完整文本。
     *
     * <p>通过 {@link PublishContext} 拿 cardId/runId,经 ForumBus.stream 推送增量。
     * stream 事件携带:streamId(幂等 key,前端据此更新同一条气泡)、source、text(累积全文)。
     * 节流:每 {@link #STREAM_FLUSH_MS} 毫秒最多发一次,避免高频 token 淹没 SSE。
     *
     * <p>无流式 provider(chatProvider==null)时回退阻塞模型 chatModel.chat,整段一次性推送。
     */
    private String chatStream(String prompt, String label) {
        // 先发一条「正在思考」thinking,让前端在首个 token 到达前有反馈。
        thinking(label + "中…");
        PublishContext ctx = this.currentCtx;
        if (ctx == null || chatProvider == null) {
            // 无上下文或无流式 provider:阻塞模型一次性返回。
            return chatModel.chat(prompt);
        }
        String streamId = role + "-" + System.nanoTime();
        java.util.concurrent.atomic.AtomicLong lastFlush =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() - STREAM_FLUSH_MS);
        StringBuilder accumulator = new StringBuilder();
        java.util.function.Consumer<String> onToken = token -> {
            accumulator.append(token);
            long now = System.currentTimeMillis();
            if (now - lastFlush.get() >= STREAM_FLUSH_MS || accumulator.length() < 32) {
                lastFlush.set(now);
                forum.stream(ctx.cardId(), ctx.runId(), streamId, role, accumulator.toString());
            }
        };
        try {
            String full = chatProvider.stream(prompt, onToken, error -> { });
            // 最终推送一次完整文本,确保前端拿到结尾。
            emitStream.accept(full);
            return full;
        } catch (RuntimeException error) {
            // 流式失败:回退阻塞模型(已在 chatProvider.stream 内部尝试过,这里兜底)。
            log.warn("Agent 流式失败,回退阻塞: role={} runId={}", role, ctx.runId(), error);
            return chatModel.chat(prompt);
        }
    }

    private BiConsumer<String, String> listener(PublishContext ctx) {
        return (stage, message) -> { /* 进度由 Orchestrator 统一推进，此处保留钩子 */ };
    }

    private String compact(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    /** Agent 调研结果：结论文本 + 检索到的来源(已编号)。 */
    public record AgentResult(String findings, List<SearchSource> sources) {
    }

    /** 发言落库所需的卡片上下文。 */
    public record PublishContext(long cardId, long runId, long userId) {
    }
}


