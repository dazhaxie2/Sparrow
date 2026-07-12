package com.sparrow.industrychain.application.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.industrychain.application.forum.ForumBus;
import com.sparrow.industrychain.application.graph.ResearchGraphExtractor;
import com.sparrow.industrychain.application.report.ResearchReportBuilder;
import com.sparrow.industrychain.application.config.IndustryAgentConfigService;
import com.sparrow.common.ai.AiAgentProfile;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.application.forum.ForumEvent;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageRow;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient.SearchSource;
import com.sparrow.common.exception.BizException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 产业链深度调研编排器：Multi-Agent 论坛协作 + 证据核验 + 图谱构建 + IR 报告。
 *
 * <p>对照 BettaFish 的整体编排：三角色 Agent 并行反思循环 + 论坛主持人，收口后做证据核验、
 * 关系图谱抽取、Document IR 报告生成。所有关键结论强制 [Sx] 来源引用，并通过 Schema 校验守住事实边界。
 */
@Component
public class IndustryChainResearchOrchestrator {

    private final ChatModelProvider chat;
    private final ObjectMapper objectMapper;
    private final WebSearchClient webSearch;
    private final ForumBus forum;
    private final ResearchGraphExtractor graphExtractor;
    private final ResearchReportBuilder reportBuilder;
    private final Executor executor;
    private IndustryAgentConfigService agentConfigs;

    public IndustryChainResearchOrchestrator(ChatModelProvider chat, ObjectMapper objectMapper,
                                     WebSearchClient webSearch, ForumBus forum,
                                     ResearchGraphExtractor graphExtractor,
                                     ResearchReportBuilder reportBuilder,
                                     @Qualifier("industryChainAgentExecutor") Executor industryChainAgentExecutor) {
        this.chat = chat;
        this.objectMapper = objectMapper;
        this.webSearch = webSearch;
        this.forum = forum;
        this.graphExtractor = graphExtractor;
        this.reportBuilder = reportBuilder;
        this.executor = industryChainAgentExecutor;
    }

    @Autowired(required = false)
    void setAgentConfigs(IndustryAgentConfigService agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    public interface StageListener {
        void update(String stage, int progress, String message);
    }

    @FunctionalInterface
    public interface CheckpointListener {
        void save(ResearchCheckpoint checkpoint);
    }

    /** 每完成一个昂贵阶段就持久化一次，失败恢复时从第一个缺失字段继续。 */
    public record ResearchCheckpoint(String plan, List<String> planQueries, List<SearchSource> sources,
                                     String forumDigest, String evidence, String graphJson,
                                     String reportIrJson, String reportMarkdown) {
        public static ResearchCheckpoint empty() {
            return new ResearchCheckpoint(null, List.of(), List.of(), null, null, null, null, null);
        }
    }

    /** 调研结果：关系图 JSON、报告 IR JSON、降级 Markdown、节点/边数、合并来源。 */
    public record ResearchResult(String graphJson, String reportIrJson, String reportMarkdown,
                                 int nodeCount, int edgeCount, List<SearchSource> sources) {
    }

    /** 规划 Agent：通过对话帮用户收窄调研范围(不联网、不编造事实)。 */
    public String reply(String title, String brief, List<MessageRow> history, String userMessage) {
        if (!chat.available()) {
            return "AI 服务尚未配置。你可以继续编辑卡片范围，配置模型后再开始深度调研。";
        }
        AiAgentProfile profile = agent(IndustryAgentConfigService.PLANNING_CHAT);
        StringBuilder context = new StringBuilder();
        int maxMessages = profile == null ? 12 : profile.maxContextMessages();
        int contextChars = profile == null ? 8000 : profile.maxContextChars();
        int perMessage = Math.max(200, contextChars / Math.max(1, maxMessages));
        history.stream().skip(Math.max(0, history.size() - (long) maxMessages)).forEach(message -> context
                .append(message.role()).append(": ").append(compact(message.content(), perMessage)).append('\n'));
        String prompt = profile == null
                ? "你是产业链深度调研工作台的规划 Agent。帮助用户明确调研范围，不编造事实。"
                : profile.systemPrompt();
        return chat.chatOr(prompt + "\n\n" + """
                卡片标题：%s
                当前调研说明：%s
                对话历史：
                %s
                用户最新消息：%s
                """.formatted(title, brief == null ? "" : brief, context, userMessage),
                "AI 服务尚未配置，请配置模型后再继续对话。");
    }

    /**
     * 启动 Multi-Agent 深度调研。
     *
     * @param userId  用户 ID(落库论坛发言归属)
     * @param cardId  卡片 ID
     * @param runId   运行 ID(区分多次运行)
     */
    public ResearchResult research(String title, String brief, List<MessageRow> history,
                                   List<SearchSource> userSources, long userId, long cardId, long runId,
                                   StageListener listener) {
        return research(title, brief, history, userSources, userId, cardId, runId, listener,
                ResearchCheckpoint.empty(), checkpoint -> {}, false);
    }

    public ResearchResult research(String title, String brief, List<MessageRow> history,
                                   List<SearchSource> userSources, long userId, long cardId, long runId,
                                   StageListener listener, ResearchCheckpoint checkpoint,
                                   CheckpointListener checkpointListener, boolean resumed) {
        if (!chat.available()) throw new BizException(503, "AI 服务未配置，无法执行深度调研");
        List<SearchSource> provided = userSources == null ? List.of() : userSources;
        ResearchCheckpoint current = checkpoint == null ? ResearchCheckpoint.empty() : checkpoint;

        forum.publish(cardId, runId, userId, ForumEvent.SYSTEM, resumed
                ? "正在从上次中断点继续调研，已完成阶段不会重复执行"
                : "新一轮 Multi-Agent 调研启动");
        String plan = current.plan();
        List<String> planQueries = current.planQueries() == null ? List.of() : current.planQueries();
        if (!hasText(plan)) {
            listener.update("planning", 8, "规划 Agent 正在拆解调研范围");
            forum.thinking(cardId, runId, "SYSTEM", "规划 Agent · 拆解调研范围");
            plan = chat.chat(planningPrompt(title, brief, provided, history));
            planQueries = extractQueries(plan);
            current = new ResearchCheckpoint(plan, planQueries, List.of(), null, null, null, null, null);
            checkpointListener.save(current);
        } else if (planQueries.isEmpty()) {
            planQueries = extractQueries(plan);
        }

        List<SearchSource> merged = current.sources() == null ? List.of() : current.sources();
        if (merged.isEmpty()) {
            listener.update("searching", 24, "行业 / 检索 / 洞察 Agent 正在并行联网调研");
        // 共享首轮检索:三角色 Agent 关注的默认查询词相同,统一检索一次后共享给各 Agent,
        // 避免三个 Agent 各自重复跑同一组默认查询(此前是 3× 冗余网络开销)。
        List<SearchSource> sharedFirstBatch = webSearch.search(title, brief, planQueries, provided.size());
        // 思考进度回调:Agent 各子步骤经此推送 thinking 事件,前端实时展示 Agent 当前在做什么。
        java.util.function.BiConsumer<String, String> thinkingSink =
                (source, message) -> forum.thinking(cardId, runId, source, message);
        // 三角色并行反思循环；用户资料优先编号 S1..Sk，联网结果接续
        AiAgentProfile industryProfile = agent(IndustryAgentConfigService.INDUSTRY_RESEARCHER);
        AiAgentProfile searchProfile = agent(IndustryAgentConfigService.SEARCH_RESEARCHER);
        AiAgentProfile insightProfile = agent(IndustryAgentConfigService.INSIGHT_RESEARCHER);
        IndustryChainResearchAgent industryAgent = new IndustryChainResearchAgent(ForumEvent.INDUSTRY, "行业 Agent",
                prompt(industryProfile, "你是产业链行业结构调研 Agent。"), chat.model(), webSearch, forum,
                thinkingSink, chat, steps(industryProfile, 1));
        IndustryChainResearchAgent queryAgent = new IndustryChainResearchAgent(ForumEvent.QUERY, "检索 Agent",
                prompt(searchProfile, "你是产业链权威检索调研 Agent。"), chat.model(), webSearch, forum,
                thinkingSink, chat, steps(searchProfile, 1));
        IndustryChainResearchAgent insightAgent = new IndustryChainResearchAgent(ForumEvent.INSIGHT, "洞察 Agent",
                prompt(insightProfile, "你是产业链纵深洞察调研 Agent。"), chat.model(), webSearch, forum,
                thinkingSink, chat, steps(insightProfile, 1));
        IndustryChainResearchAgent.PublishContext ctx = new IndustryChainResearchAgent.PublishContext(cardId, runId, userId);

        CompletableFuture<IndustryChainResearchAgent.AgentResult> industryFuture =
                supply(industryAgent, title, brief, planQueries, provided.size(), ctx, sharedFirstBatch);
        CompletableFuture<IndustryChainResearchAgent.AgentResult> queryFuture =
                supply(queryAgent, title, brief, planQueries, provided.size(), ctx, sharedFirstBatch);
        CompletableFuture<IndustryChainResearchAgent.AgentResult> insightFuture =
                supply(insightAgent, title, brief, planQueries, provided.size(), ctx, sharedFirstBatch);
        CompletableFuture.allOf(industryFuture, queryFuture, insightFuture).join();

        IndustryChainResearchAgent.AgentResult industry = industryFuture.join();
        IndustryChainResearchAgent.AgentResult query = queryFuture.join();
        IndustryChainResearchAgent.AgentResult insight = insightFuture.join();

        // 合并来源：用户资料 S1..Sk + 联网来源去重接续
            merged = mergeSources(provided, industry.sources(), query.sources(), insight.sources());
        }
        if (merged.isEmpty()) throw new BizException(502, "未获得任何可用来源，请稍后重试或补充资料");

        String forumDigest = hasText(current.forumDigest()) ? current.forumDigest() : forumDigest(cardId, runId);
        if (current.sources() == null || current.sources().isEmpty()) {
            current = new ResearchCheckpoint(plan, planQueries, merged, forumDigest,
                    null, null, null, null);
            checkpointListener.save(current);
        }

        String evidence = current.evidence();
        if (!hasText(evidence)) {
            listener.update("verifying", 58, "证据 Agent 正在交叉核验来源");
            forum.thinking(cardId, runId, "SYSTEM", "证据 Agent · 交叉核验来源");
            evidence = chat.chat(evidencePrompt(plan, merged, forumDigest));
            current = new ResearchCheckpoint(plan, planQueries, merged, forumDigest,
                    evidence, null, null, null);
            checkpointListener.save(current);
        }

        String graphJson = current.graphJson();
        JsonNode graph;
        if (!hasText(graphJson)) {
            listener.update("mapping", 74, "产业链 Agent 正在构建节点与关系");
            forum.thinking(cardId, runId, "SYSTEM", "图谱 Agent · 构建节点与关系");
            graph = graphExtractor.extract(title, evidence, merged);
            graphJson = writeJson(graph);
            current = new ResearchCheckpoint(plan, planQueries, merged, forumDigest,
                    evidence, graphJson, null, null);
            checkpointListener.save(current);
        } else {
            graph = readJson(graphJson, "读取调研图谱检查点失败");
        }

        ResearchReportBuilder.ReportResult report;
        if (!hasText(current.reportIrJson()) || !hasText(current.reportMarkdown())) {
            listener.update("writing", 88, "报告 Agent 正在生成带引用的深度报告");
            forum.thinking(cardId, runId, "SYSTEM", "报告 Agent · 生成深度报告");
            report = reportBuilder.build(title, evidence, graph, merged, forumDigest);
            current = new ResearchCheckpoint(plan, planQueries, merged, forumDigest,
                    evidence, graphJson, report.irJson(), report.markdown());
            checkpointListener.save(current);
        } else {
            report = new ResearchReportBuilder.ReportResult(current.reportIrJson(), current.reportMarkdown());
        }
        listener.update("finalizing", 96, "正在保存调研结果");

        return new ResearchResult(graphJson, report.irJson(), report.markdown(),
                graph.path("nodes").size(), graph.path("edges").size(), merged);
    }

    private CompletableFuture<IndustryChainResearchAgent.AgentResult> supply(IndustryChainResearchAgent agent, String title,
                                                                     String brief, List<String> planQueries, int startRefIndex,
                                                                     IndustryChainResearchAgent.PublishContext ctx,
                                                                     List<SearchSource> sharedFirstBatch) {
        return CompletableFuture.supplyAsync(
                () -> agent.research(title, brief, planQueries, startRefIndex, ctx, sharedFirstBatch), executor);
    }

    /** 合并来源：用户资料优先编号 S1..Sk；联网来源按 URL 去重后接续 S(k+1)..Sn。 */
    @SuppressWarnings("java:S3776")
    private List<SearchSource> mergeSources(List<SearchSource> provided, List<SearchSource>... batches) {
        List<SearchSource> merged = new ArrayList<>(provided.size());
        Set<String> seenUrls = new HashSet<>();
        int index = 1;
        for (SearchSource source : provided) {
            merged.add(new SearchSource("S" + index++, source.title(), source.url(),
                    source.publisher(), source.snippet()));
            seenUrls.add(source.url());
        }
        for (List<SearchSource> batch : batches) {
            for (SearchSource source : batch) {
                if (source.url() != null && seenUrls.add(source.url())) {
                    merged.add(new SearchSource("S" + index++, source.title(), source.url(),
                            source.publisher(), source.snippet()));
                }
            }
        }
        return merged;
    }

    /** 提取规划 Agent 产出中「补充查询：」段的查询词。 */
    private List<String> extractQueries(String plan) {
        if (plan == null) return List.of();
        Matcher matcher = Pattern.compile("补充查询[：:]([\\s\\S]*)").matcher(plan);
        if (!matcher.find()) return List.of();
        String[] lines = matcher.group(1).split("\\r?\\n");
        List<String> queries = new ArrayList<>();
        for (String line : lines) {
            String query = line.replaceAll("^[\\s·\\-\\*\\d.、]+", "").trim();
            if (!query.isBlank() && query.length() <= 80) queries.add(query);
        }
        return queries;
    }

    private String forumDigest(long cardId, long runId) {
        List<ForumEvent> events = forum.history(cardId, runId);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (ForumEvent event : events) {
            if (ForumEvent.HOST.equals(event.source()) || isAgent(event.source())) {
                sb.append(event.sourceText()).append(": ").append(compact(event.content(), 300)).append('\n');
                if (++count >= 12) break;
            }
        }
        return sb.toString();
    }

    private boolean isAgent(String source) {
        return ForumEvent.INDUSTRY.equals(source) || ForumEvent.QUERY.equals(source) || ForumEvent.INSIGHT.equals(source);
    }

    private String planningPrompt(String title, String brief, List<SearchSource> provided, List<MessageRow> history) {
        return prompt(agent(IndustryAgentConfigService.RESEARCH_PLANNER),
                "你是产业链研究规划 Agent。") + "\n\n" + """
                你是产业链研究规划 Agent。根据标题、说明和用户对话，制定一份可执行的调研计划。
                计划必须覆盖上游原料/设备、中游制造/集成、下游客户/应用、核心企业、竞争格局、
                地域政策、供应风险和技术趋势。只写调研计划，不得填充未经联网核验的事实。
                若用户已提供资料，应优先围绕这些资料展开；同时在结尾用「补充查询：」单独成段，
                每行一个查询词，用于补充联网检索那些资料未覆盖的空白（如最新市场份额、地域政策）。

                标题：%s
                说明：%s
                用户已提供资料：%s
                对话：%s
                """.formatted(title, brief == null ? "" : brief,
                provided.isEmpty() ? "无" : provided.size() + " 份", historyText(history));
    }

    private String evidencePrompt(String plan, List<SearchSource> sources, String forumDigest) {
        return prompt(agent(IndustryAgentConfigService.EVIDENCE_VALIDATOR),
                "你是证据核验 Agent。") + "\n\n" + """
                你是证据核验 Agent。只能使用下方来源（含用户提供的资料与联网搜索结果），整理可以被来源直接支持的产业链事实。
                忽略广告、问答猜测和互相矛盾且无法核实的内容。每条事实末尾必须标注来源编号，格式为 [S1]；证据不足要明确写「待核验」，不得凭常识补齐。

                调研计划：%s
                多 Agent 论坛记录：%s
                来源：%s
                """.formatted(compact(plan, 3000), compact(forumDigest, 1500), sourceContext(sources));
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new BizException(500, "关系图序列化失败");
        }
    }

    private JsonNode readJson(String value, String errorMessage) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new BizException(500, errorMessage);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private AiAgentProfile agent(String agentKey) {
        return agentConfigs == null ? null : agentConfigs.requireEnabled(agentKey);
    }

    private String prompt(AiAgentProfile profile, String fallback) {
        return profile == null ? fallback : profile.systemPrompt();
    }

    private int steps(AiAgentProfile profile, int fallback) {
        return profile == null ? fallback : profile.maxSteps();
    }

    private String sourceContext(List<SearchSource> sources) {
        StringBuilder result = new StringBuilder();
        for (SearchSource source : sources) {
            result.append(source.sourceRef()).append(" | ").append(source.title()).append(" | ")
                    .append(source.publisher()).append(" | ").append(source.url()).append('\n')
                    .append(compact(source.snippet(), 800)).append("\n\n");
        }
        return result.toString();
    }

    private String historyText(List<MessageRow> history) {
        StringBuilder result = new StringBuilder();
        history.stream().skip(Math.max(0, history.size() - 16L)).forEach(message -> result
                .append(message.role()).append('/').append(message.agent() == null ? "" : message.agent())
                .append(": ").append(compact(message.content(), 600)).append('\n'));
        return result.toString();
    }

    private String compact(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}


