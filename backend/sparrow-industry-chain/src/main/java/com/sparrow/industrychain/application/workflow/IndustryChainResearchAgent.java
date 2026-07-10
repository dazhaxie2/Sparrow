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

    /** 每个角色的反思轮次。 */
    static final int MAX_REFLECTIONS = 2;

    private final String role;
    private final String roleText;
    private final String systemPrompt;
    private final ChatModel chatModel;
    private final WebSearchClient webSearch;
    private final ForumBus forum;

    public IndustryChainResearchAgent(String role, String roleText, String systemPrompt,
                              ChatModel chatModel, WebSearchClient webSearch, ForumBus forum) {
        this.role = role;
        this.roleText = roleText;
        this.systemPrompt = systemPrompt;
        this.chatModel = chatModel;
        this.webSearch = webSearch;
        this.forum = forum;
    }

    /**
     * 执行调研。返回该角色整理出的、带来源编号的结论文本，以及它检索到的来源。
     *
     * @param publishToForum 发言落库回调(传 cardId/runId/userId 上下文)
     */
    public AgentResult research(String title, String brief, List<String> planQueries,
                                int startRefIndex, PublishContext publishToForum) {
        List<SearchSource> sources = new ArrayList<>();
        StringBuilder findings = new StringBuilder();

        // 首轮：用规划 Agent 产出的查询词检索，覆盖性收集来源
        listener(publishToForum).accept(roleText + " 启动首轮概览检索", "");
        List<SearchSource> firstBatch = webSearch.search(title, brief, planQueries, startRefIndex);
        sources.addAll(firstBatch);
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
            List<SearchSource> deepBatch = webSearch.search(title, brief, extraQueries, offset);
            sources.addAll(deepBatch);
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
        return chatModel.chat((systemPrompt + """

                研究对象：%s
                调研说明：%s
                本角色本轮来源：
                %s%s

                请只依据上方来源，整理与本角色职责相关的结论，每条结论末尾标注来源编号 [Sx]。
                来源不足的写「待核验」，不得凭常识补齐。控制在 400 字以内。
                """).formatted(title, brief == null ? "" : brief, ctx, hostBlock));
    }

    /** 反思：分析当前结论的缺口，输出下一步该查什么(「补充查询：」段每行一个查询词)。 */
    private String reflect(String title, String findings, int round, String hostSpeech) {
        String hostBlock = hostSpeech == null || hostSpeech.isBlank() ? "" :
                "\n\n论坛主持人最新引导:\n" + compact(hostSpeech, 600);
        return chatModel.chat((systemPrompt + """

                研究对象：%s
                你目前的结论：
                %s%s

                这是第 %d 轮反思。指出当前结论的缺口与待核验问题，并用「补充查询：」单独成段，
                每行一个查询词，用于下一轮深挖检索。不要重复已有结论。
                """).formatted(title, compact(findings, 2500), hostBlock, round));
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


