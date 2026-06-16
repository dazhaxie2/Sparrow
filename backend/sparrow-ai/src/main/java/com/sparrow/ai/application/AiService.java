package com.sparrow.ai.application;

import com.sparrow.ai.infrastructure.agent.TechTreeAgent;
import com.sparrow.ai.infrastructure.client.GraphClient;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeBrief;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeDetail;
import com.sparrow.ai.infrastructure.client.GraphViews.Tree;
import com.sparrow.ai.infrastructure.client.UserClient;
import com.sparrow.ai.infrastructure.config.AiProperties;
import com.sparrow.ai.infrastructure.rag.MilvusStore;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
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

/**
 * AI 服务核心类。
 * 提供基于科技树图谱的问答能力,支持三种模式的分级降级:
 * 1. Agent 工具链模式 - 最高级,使用 AI Agent 自主调用工具
 * 2. RAG 检索增强模式 - 中间级,检索上下文后直接调用大模型
 * 3. 规则引擎模式 - 兜底级,纯图谱规则匹配回答
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final String QUOTA_KEY_PREFIX = "sparrow:ai:quota:";

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
                            List<SourceRef> sources, List<AgentStep> steps, long remainingQuota) {
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
    private final EmbeddingModel embeddingModel;
    private final MilvusStore milvus;
    private final GraphClient graphClient;
    private final UserClient userClient;
    private final StringRedisTemplate redis;
    private final ObjectProvider<TechTreeAgent> agentProvider;

    /**
     * 构造函数。
     *
     * @param props         AI 配置属性
     * @param chatModel     大语言模型
     * @param embeddingModel 向量嵌入模型
     * @param milvus        Milvus 向量存储
     * @param graphClient   图谱服务客户端
     * @param userClient    用户服务客户端
     * @param redis         Redis 模板(用于配额管理)
     * @param agentProvider Agent 提供者(懒加载)
     */
    public AiService(AiProperties props, ChatModel chatModel, EmbeddingModel embeddingModel,
                     MilvusStore milvus, GraphClient graphClient, UserClient userClient,
                     StringRedisTemplate redis,
                     ObjectProvider<TechTreeAgent> agentProvider) {
        this.props = props;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.milvus = milvus;
        this.graphClient = graphClient;
        this.userClient = userClient;
        this.redis = redis;
        this.agentProvider = agentProvider;
    }

    /**
     * 检查 LLM 是否已配置。
     *
     * @return true 表示大语言模型和嵌入模型都已就绪
     */
    public boolean llmConfigured() {
        return chatModel != null && embeddingModel != null;
    }

    /**
     * 执行 AI 问答。
     * 按优先级尝试三种模式: Agent → RAG → 规则引擎,任一模式失败自动降级。
     *
     * @param userId   用户 ID
     * @param question 用户问题
     * @return 问答结果
     */
    public AskResult ask(Long userId, String question) {
        long remaining = consumeQuota(userId);
        String intent = classifyIntent(question);

        TechTreeAgent agent = agentProvider.getIfAvailable();
        if (llmConfigured() && agent != null) {
            try {
                Retrieved retrieved = retrieve(question);
                List<SourceRef> sources = buildSources(retrieved);
                String answer = agent.chat(String.valueOf(userId), agentUserMessage(question, intent));
                return result(answer, "agent", intent, sources,
                        buildSteps(intent, "检索图谱与知识库上下文", "Agent 工具链生成回答", sources.isEmpty()),
                        remaining);
            } catch (Exception e) {
                log.warn("Agent 调用失败,降级为 RAG: {}", e.getMessage());
            }
        }
        if (llmConfigured()) {
            try {
                Retrieved retrieved = retrieve(question);
                List<SourceRef> sources = buildSources(retrieved);
                String answer = chatModel.chat(systemPrompt() + "\n\n"
                        + ragUserMessage(question, retrieved.nodes(), retrieved.chunks()));
                return result(answer, "rag", intent, sources,
                        buildSteps(intent, "检索图谱与知识库上下文", "RAG 生成统一回答", sources.isEmpty()),
                        remaining);
            } catch (Exception e) {
                log.warn("LLM 调用失败,降级为规则问答: {}", e.getMessage());
            }
        }

        Retrieved retrieved = retrieve(question);
        List<SourceRef> sources = buildSources(retrieved);
        return result(rulesAnswer(question, retrieved.nodes()), "rules", intent, sources,
                buildSteps(intent, "关键词匹配图谱上下文", "规则引擎生成统一回答", sources.isEmpty()),
                remaining);
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
     * 构建问答结果。
     *
     * @param answer    回答内容
     * @param mode      回答模式
     * @param intent    意图分类
     * @param sources   来源列表
     * @param steps     步骤列表
     * @param remaining 剩余配额
     * @return 封装后的 AskResult
     */
    private AskResult result(String answer, String mode, String intent, List<SourceRef> sources,
                             List<AgentStep> steps, long remaining) {
        return new AskResult(normalizeMarkdownAnswer(answer, mode, intent, sources),
                mode, "markdown:v1", intent, sources, steps, remaining);
    }

    /**
     * 构建 Agent 模式的用户消息模板。
     *
     * @param question 用户问题
     * @param intent   识别的意图
     * @return 格式化后的用户消息
     */
    private String agentUserMessage(String question, String intent) {
        return "用户原问题:\n" + question + "\n\n" +
                "识别意图: " + intentLabel(intent) + "\n\n" +
                "请无论用户使用何种语言,最终都用中文回答。严格使用 Markdown v1 模板:" +
                "\n### 结论\n1-2 句直接回答。" +
                "\n### 关键依据\n- 2-4 条图谱、知识库或工具依据。" +
                "\n### 学习路径\n1. 可执行的前置或后续学习顺序。" +
                "\n### 下一步\n- 一个最自然的追问或图谱操作建议。" +
                "\n不要使用 emoji,不要用加粗文本冒充标题,不要输出代码块。";
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
     * 标准化 Markdown 回答格式。
     * 将 AI 生成的回答统一为 Markdown v1 模板格式,确保包含结论、关键依据、学习路径和下一步四个部分。
     *
     * @param answer  原始回答内容
     * @param mode    回答模式
     * @param intent  意图分类
     * @param sources 来源列表
     * @return 标准化后的 Markdown 回答
     */
    private String normalizeMarkdownAnswer(String answer, String mode, String intent, List<SourceRef> sources) {
        String normalized = answer == null ? "" : answer
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();
        if (normalized.isBlank()) {
            normalized = "资料不足以生成可靠回答。";
        }
        normalized = normalized
                .replaceAll("(?m)^#{1,2}\\s+", "### ")
                .replaceAll("(?m)^\\*\\*(结论|关键依据|学习路径|下一步|前置链|解锁方向)\\*\\*\\s*[:：]?", "### $1")
                .replaceAll("\n{3,}", "\n\n");
        if (hasRequiredSections(normalized)) {
            return normalized;
        }
        String body = normalized
                .replaceAll("(?m)^###\\s+", "")
                .replaceFirst("^(结论|关键依据|依据|学习路径|前置链|解锁方向|下一步)\\s*\\n", "")
                .trim();
        return "### 结论\n" + body + "\n\n" +
                "### 关键依据\n" +
                "- 回答模式: " + answerModeLabel(mode) + "。\n" +
                "- 问题意图: " + intentLabel(intent) + "。\n" +
                "- 参考上下文: " + (sources.isEmpty() ? "未命中明确来源,已按可用图谱规则兜底。" : "命中 " + sources.size() + " 个相关节点。") + "\n\n" +
                "### 学习路径\n" +
                "1. 先确认当前命中节点是否符合你的问题。\n" +
                "2. 再沿着前置链理解依赖,或沿着解锁方向继续探索。\n\n" +
                "### 下一步\n" +
                "- 可以继续追问它的前置链、历史意义或推荐学习顺序。";
    }

    /**
     * 检查回答是否包含所有必需的 Markdown 章节。
     *
     * @param answer 回答内容
     * @return true 表示包含结论、关键依据和下一步三个章节
     */
    private boolean hasRequiredSections(String answer) {
        return answer.contains("### 结论")
                && (answer.contains("### 关键依据") || answer.contains("### 依据"))
                && answer.contains("### 下一步");
    }

    /**
     * 获取回答模式的中文标签。
     *
     * @param mode 模式标识
     * @return 中文标签
     */
    private String answerModeLabel(String mode) {
        return switch (mode) {
            case "agent" -> "Agent 工具链";
            case "rag" -> "RAG 检索增强";
            case "rules" -> "图谱规则";
            default -> mode;
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
                log.warn("向量检索失败,降级为关键词匹配: {}", e.getMessage());
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
        return "你是 Sparrow 人类科技树的 AI 向导。请仅基于提供的科技树资料回答用户问题," +
                "重点讲清技术之间的依赖关系与历史脉络;资料不足以回答时,如实说明。回答用中文,简洁、准确。" +
                "\n请严格使用 Markdown v1 模板,不要使用 emoji,不要输出代码块:" +
                "\n### 结论\n用 1-2 句直接回答问题。" +
                "\n### 关键依据\n- 列出 2-4 条来自图谱或知识库的依据。" +
                "\n### 学习路径\n1. 给出可执行的前置或后续学习顺序。" +
                "\n### 下一步\n- 给出一个最自然的追问或图谱操作建议。";
    }

    /**
     * 构建 RAG 模式的用户消息,包含科技树资料和相关词条。
     *
     * @param question 用户问题
     * @param hits     匹配的节点列表
     * @param chunks   匹配的语料块列表
     * @return 格式化后的用户消息
     */
    private String ragUserMessage(String question, List<NodeBrief> hits,
                                  List<MilvusStore.ChunkHit> chunks) {
        StringBuilder sb = new StringBuilder("### 科技树资料\n");
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
            return "### 结论\n我没有在科技树中找到与问题直接相关的技术节点。\n\n" +
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
        sb.append("- 当前为图谱规则问答;部署时配置 AI_API_KEY 可启用大模型深度问答。");
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
            log.warn("会员校验失败,按非会员处理: userId={} err={}", userId, e.getMessage());
            return false;
        }
    }
}
