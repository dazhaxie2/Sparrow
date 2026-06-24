package com.sparrow.ai.application.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository.MessageRow;
import com.sparrow.ai.infrastructure.research.WebSearchClient;
import com.sparrow.ai.infrastructure.research.WebSearchClient.SearchSource;
import com.sparrow.common.exception.BizException;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ChainResearchOrchestrator {

    private static final Pattern SOURCE_REFERENCE = Pattern.compile("\\[(S\\d+)]");

    public interface StageListener {
        void update(String stage, int progress, String message);
    }

    public record ResearchResult(String graphJson, String reportMarkdown, int nodeCount, int edgeCount,
                                 List<SearchSource> sources) {
    }

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final WebSearchClient webSearch;

    public ChainResearchOrchestrator(ChatModel chatModel, ObjectMapper objectMapper,
                                     WebSearchClient webSearch) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.webSearch = webSearch;
    }

    public String reply(String title, String brief, List<MessageRow> history, String userMessage) {
        if (chatModel == null) {
            return "AI 服务尚未配置。你可以继续编辑卡片范围，配置模型后再开始深度调研。";
        }
        StringBuilder context = new StringBuilder();
        history.stream().skip(Math.max(0, history.size() - 12L)).forEach(message -> context
                .append(message.role()).append(": ").append(compact(message.content(), 800)).append('\n'));
        return chatModel.chat("""
                你是产业链深度调研工作台的规划 Agent。你的任务是通过对话帮助用户明确调研范围，
                包括核心产品/企业、地域、时间范围、上中下游边界和最关心的问题。不要假装已经联网，
                不要直接编造供应关系。回答简洁、具体；信息不足时一次最多追问三个关键问题。

                卡片标题：%s
                当前调研说明：%s
                对话历史：
                %s
                用户最新消息：%s
                """.formatted(title, brief == null ? "" : brief, context, userMessage));
    }

    public ResearchResult research(String title, String brief, List<MessageRow> history,
                                   StageListener listener) {
        if (chatModel == null) throw new BizException(503, "AI 服务未配置，无法执行深度调研");

        listener.update("planning", 10, "规划 Agent 正在拆解调研范围");
        String plan = chatModel.chat("""
                你是产业链研究规划 Agent。根据标题、说明和用户对话，制定一份可执行的调研计划。
                计划必须覆盖上游原料/设备、中游制造/集成、下游客户/应用、核心企业、竞争格局、
                地域政策、供应风险和技术趋势。只写调研计划，不得填充未经联网核验的事实。

                标题：%s
                说明：%s
                对话：%s
                """.formatted(title, brief == null ? "" : brief, historyText(history)));

        listener.update("searching", 28, "搜索 Agent 正在联网收集中文与权威来源");
        List<SearchSource> sources = webSearch.search(title, brief);
        if (sources.isEmpty()) throw new BizException(502, "联网搜索未返回可用来源，请稍后重试");
        String sourceContext = sourceContext(sources);

        listener.update("verifying", 48, "证据 Agent 正在交叉核验来源");
        String evidence = chatModel.chat("""
                你是证据核验 Agent。只能使用下方联网来源，整理可以被来源直接支持的产业链事实。
                忽略广告、问答猜测和互相矛盾且无法核实的内容。每条事实末尾必须标注来源编号，
                格式为 [S1]；证据不足要明确写“待核验”，不得凭常识补齐。

                调研计划：%s

                联网来源：
                %s
                """.formatted(compact(plan, 3000), sourceContext));

        listener.update("mapping", 68, "产业链 Agent 正在构建节点与关系");
        String graphResponse = chatModel.chat(graphPrompt(title, evidence, sources));
        JsonNode graph = parseAndValidateGraph(graphResponse, sources);
        String graphJson = writeJson(graph);

        listener.update("writing", 86, "报告 Agent 正在生成带引用的深度报告");
        String report = chatModel.chat("""
                你是产业链报告 Agent。基于已核验事实和关系图，输出中文 Markdown 深度报告。
                必须包含：摘要、范围与方法、上游、中游、下游、核心企业与竞争、风险、机会与趋势、
                待核验事项。所有关键结论都要使用 [S1] 形式引用来源；不得引入材料之外的事实。

                调研计划：%s
                核验证据：%s
                关系图 JSON：%s
                """.formatted(compact(plan, 2500), compact(evidence, 6500), compact(graphJson, 7000)));
        if (report == null || report.isBlank()) throw new BizException(502, "报告 Agent 未生成有效内容");
        if (!validReportReferences(report, sources)) {
            report = chatModel.chat("修复下面报告的来源标注：只允许使用提供的来源编号，所有关键结论必须有 "
                    + "[S1] 格式引用；不得增加新事实。只输出修复后的 Markdown。\n允许来源："
                    + sources.stream().map(SearchSource::sourceRef).toList() + "\n报告：\n" + compact(report, 9000));
        }
        if (!validReportReferences(report, sources)) throw new BizException(502, "深度报告缺少可验证的来源引用");
        report = report.trim() + sourceAppendix(sources);

        return new ResearchResult(graphJson, report,
                graph.path("nodes").size(), graph.path("edges").size(), sources);
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
            String repaired = chatModel.chat("修复下面内容为严格符合原 schema 的 JSON。删除无来源关系，"
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

    private boolean validReportReferences(String report, List<SearchSource> sources) {
        if (report == null || report.isBlank()) return false;
        Set<String> validRefs = new HashSet<>(sources.stream().map(SearchSource::sourceRef).toList());
        Matcher matcher = SOURCE_REFERENCE.matcher(report);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            if (!validRefs.contains(matcher.group(1))) return false;
        }
        return found;
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

    private String sourceAppendix(List<SearchSource> sources) {
        StringBuilder result = new StringBuilder("\n\n## 来源\n\n");
        for (SearchSource source : sources) {
            result.append("- [").append(source.sourceRef()).append("] [")
                    .append(source.title().replace("[", "").replace("]", "")).append("](")
                    .append(source.url()).append(") · ").append(source.publisher()).append('\n');
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
