package com.sparrow.ai.application.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.ai.application.research.agent.ChainForumBus;
import com.sparrow.ai.application.research.agent.ChainReportBuilder;
import com.sparrow.ai.application.research.agent.ChainResearchAgent;
import com.sparrow.ai.application.research.agent.ChatModelProvider;
import com.sparrow.ai.application.research.agent.ForumEvent;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository.MessageRow;
import com.sparrow.ai.infrastructure.research.WebSearchClient;
import com.sparrow.ai.infrastructure.research.WebSearchClient.SearchSource;
import com.sparrow.common.exception.BizException;
import org.springframework.stereotype.Component;

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
public class ChainResearchOrchestrator {

    private static final Pattern SOURCE_REFERENCE = Pattern.compile("\\[(S\\d+)]");

    private final ChatModelProvider chat;
    private final ObjectMapper objectMapper;
    private final WebSearchClient webSearch;
    private final ChainForumBus forum;
    private final ChainReportBuilder reportBuilder;
    private final Executor executor;

    public ChainResearchOrchestrator(ChatModelProvider chat, ObjectMapper objectMapper,
                                     WebSearchClient webSearch, ChainForumBus forum,
                                     ChainReportBuilder reportBuilder,
                                     Executor chainResearchExecutor) {
        this.chat = chat;
        this.objectMapper = objectMapper;
        this.webSearch = webSearch;
        this.forum = forum;
        this.reportBuilder = reportBuilder;
        this.executor = chainResearchExecutor;
    }

    public interface StageListener {
        void update(String stage, int progress, String message);
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
        StringBuilder context = new StringBuilder();
        history.stream().skip(Math.max(0, history.size() - 12L)).forEach(message -> context
                .append(message.role()).append(": ").append(compact(message.content(), 800)).append('\n'));
        return chat.chatOr("""
                你是产业链深度调研工作台的规划 Agent。你的任务是通过对话帮助用户明确调研范围，
                包括核心产品/企业、地域、时间范围、上中下游边界和最关心的问题。不要假装已经联网，
                不要直接编造供应关系。回答简洁、具体；信息不足时一次最多追问三个关键问题。

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
        if (!chat.available()) throw new BizException(503, "AI 服务未配置，无法执行深度调研");
        List<SearchSource> provided = userSources == null ? List.of() : userSources;

        listener.update("planning", 8, "规划 Agent 正在拆解调研范围");
        forum.publish(cardId, runId, userId, ForumEvent.SYSTEM, "新一轮 Multi-Agent 调研启动");
        String plan = chat.chat(planningPrompt(title, brief, provided, history));
        List<String> planQueries = extractQueries(plan);

        listener.update("searching", 24, "行业 / 检索 / 洞察 Agent 正在并行联网调研");
        // 三角色并行反思循环；用户资料优先编号 S1..Sk，联网结果接续
        ChainResearchAgent industryAgent = new ChainResearchAgent(ForumEvent.INDUSTRY, "行业 Agent",
                "你是产业链「行业结构」调研 Agent，专注上中下游环节、核心企业与竞争格局。", chat.model(), webSearch, forum);
        ChainResearchAgent queryAgent = new ChainResearchAgent(ForumEvent.QUERY, "检索 Agent",
                "你是产业链「权威检索」调研 Agent，侧重权威报告、市场份额、政策与技术趋势的精准检索。", chat.model(), webSearch, forum);
        ChainResearchAgent insightAgent = new ChainResearchAgent(ForumEvent.INSIGHT, "洞察 Agent",
                "你是产业链「纵深洞察」调研 Agent，侧重供应风险、依赖关系与潜在断点。", chat.model(), webSearch, forum);
        ChainResearchAgent.PublishContext ctx = new ChainResearchAgent.PublishContext(cardId, runId, userId);

        CompletableFuture<ChainResearchAgent.AgentResult> industryFuture =
                supply(industryAgent, title, brief, planQueries, provided.size(), ctx);
        CompletableFuture<ChainResearchAgent.AgentResult> queryFuture =
                supply(queryAgent, title, brief, planQueries, provided.size(), ctx);
        CompletableFuture<ChainResearchAgent.AgentResult> insightFuture =
                supply(insightAgent, title, brief, planQueries, provided.size(), ctx);
        CompletableFuture.allOf(industryFuture, queryFuture, insightFuture).join();

        ChainResearchAgent.AgentResult industry = industryFuture.join();
        ChainResearchAgent.AgentResult query = queryFuture.join();
        ChainResearchAgent.AgentResult insight = insightFuture.join();

        // 合并来源：用户资料 S1..Sk + 联网来源去重接续
        List<SearchSource> merged = mergeSources(provided, industry.sources(), query.sources(), insight.sources());
        if (merged.isEmpty()) throw new BizException(502, "未获得任何可用来源，请稍后重试或补充资料");

        listener.update("verifying", 58, "证据 Agent 正在交叉核验来源");
        String forumDigest = forumDigest(cardId, runId);
        String evidence = chat.chat(evidencePrompt(plan, merged, forumDigest));

        listener.update("mapping", 74, "产业链 Agent 正在构建节点与关系");
        JsonNode graph = parseAndValidateGraph(chat.chat(graphPrompt(title, evidence, merged)), merged);
        String graphJson = writeJson(graph);

        listener.update("writing", 88, "报告 Agent 正在生成带引用的深度报告");
        ChainReportBuilder.ReportResult report = reportBuilder.build(title, evidence, graph, merged, forumDigest);

        forum.reset(runId);
        return new ResearchResult(graphJson, report.irJson(), report.markdown(),
                graph.path("nodes").size(), graph.path("edges").size(), merged);
    }

    private CompletableFuture<ChainResearchAgent.AgentResult> supply(ChainResearchAgent agent, String title,
                                                                     String brief, List<String> planQueries,
                                                                     int startRefIndex,
                                                                     ChainResearchAgent.PublishContext ctx) {
        return CompletableFuture.supplyAsync(
                () -> agent.research(title, brief, planQueries, startRefIndex, ctx), executor);
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
        return """
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
        return """
                你是证据核验 Agent。只能使用下方来源（含用户提供的资料与联网搜索结果），整理可以被来源直接支持的产业链事实。
                忽略广告、问答猜测和互相矛盾且无法核实的内容。每条事实末尾必须标注来源编号，格式为 [S1]；证据不足要明确写「待核验」，不得凭常识补齐。

                调研计划：%s
                多 Agent 论坛记录：%s
                来源：%s
                """.formatted(compact(plan, 3000), compact(forumDigest, 1500), sourceContext(sources));
    }

    private String graphPrompt(String title, String evidence, List<SearchSource> sources) {
        String refs = sources.stream().map(SearchSource::sourceRef).reduce((a, b) -> a + "," + b).orElse("");
        return """
                你是产业链关系抽取 Agent。只根据核验证据生成关系图，输出严格 JSON，不要 Markdown 围栏。
                节点类型限：核心对象、上游供应商、材料商、设备商、代工厂、中游制造、下游客户、应用市场。
                边方向固定为上游/提供方 -> 下游/接受方。每条边必须有至少一个 sourceRefs，且只能使用：%s。
                没有直接证据的关系不得输出。

                JSON schema：
                {"nodes":[{"id":"n1","name":"名称","type":"类型","summary":"摘要","sourceRefs":["S1"]}],
                "edges":[{"from":"n1","to":"n2","type":"供货|代工|材料供应|设备供应|客户|应用",
                "product":"产品或环节","sourceRefs":["S1"]}]}

                研究对象：%s
                核验证据：%s
                """.formatted(refs, title, compact(evidence, 7500));
    }

    private JsonNode parseAndValidateGraph(String raw, List<SearchSource> sources) {
        JsonNode graph = parseJson(raw);
        if (!validGraph(graph, sources)) {
            String repaired = chat.chat("修复下面内容为严格符合原 schema 的 JSON。删除无来源关系，"
                    + "不要添加新事实，只输出 JSON：\n" + compact(raw, 8000));
            graph = parseJson(repaired);
        }
        if (!validGraph(graph, sources)) throw new BizException(502, "关系图 Agent 返回的数据无法验证");
        return graph;
    }

    private boolean validGraph(JsonNode graph, List<SearchSource> sources) {
        if (graph == null || !graph.path("nodes").isArray() || !graph.path("edges").isArray()
                || graph.path("nodes").isEmpty()) return false;
        Set<String> validRefs = new HashSet<>(sources.stream().map(SearchSource::sourceRef).toList());
        Set<String> nodeIds = new HashSet<>();
        for (JsonNode node : graph.path("nodes")) {
            if (node.path("id").asText().isBlank() || node.path("name").asText().isBlank()) return false;
            JsonNode refs = node.path("sourceRefs");
            if (!refs.isArray() || refs.isEmpty()) return false;
            for (JsonNode ref : refs) if (!validRefs.contains(ref.asText())) return false;
            nodeIds.add(node.path("id").asText());
        }
        for (JsonNode edge : graph.path("edges")) {
            if (!nodeIds.contains(edge.path("from").asText()) || !nodeIds.contains(edge.path("to").asText())) {
                return false;
            }
            JsonNode refs = edge.path("sourceRefs");
            if (!refs.isArray() || refs.isEmpty()) return false;
            for (JsonNode ref : refs) if (!validRefs.contains(ref.asText())) return false;
        }
        return true;
    }

    private JsonNode parseJson(String raw) {
        try {
            int start = raw == null ? -1 : raw.indexOf('{');
            int end = raw == null ? -1 : raw.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            return objectMapper.readTree(raw.substring(start, end + 1));
        } catch (Exception error) {
            return null;
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new BizException(500, "关系图序列化失败");
        }
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
