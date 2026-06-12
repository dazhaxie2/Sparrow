package com.sparrow.ai;

import com.sparrow.common.BizException;
import com.sparrow.common.MembershipService;
import com.sparrow.graph.GraphDtos.NodeBrief;
import com.sparrow.graph.GraphDtos.Tree;
import com.sparrow.graph.GraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final String QUOTA_KEY_PREFIX = "sparrow:ai:quota:";

    public record SourceRef(Long id, String name) {
    }

    public record AskResult(String answer, String mode, List<SourceRef> sources, long remainingQuota) {
    }

    private final AiProperties props;
    private final OpenAiClient openAi;
    private final MilvusStore milvus;
    private final GraphService graphService;
    private final MembershipService membershipService;
    private final StringRedisTemplate redis;

    public AiService(AiProperties props, OpenAiClient openAi, MilvusStore milvus,
                     GraphService graphService, MembershipService membershipService,
                     StringRedisTemplate redis) {
        this.props = props;
        this.openAi = openAi;
        this.milvus = milvus;
        this.graphService = graphService;
        this.membershipService = membershipService;
        this.redis = redis;
    }

    public AskResult ask(Long userId, String question) {
        long remaining = consumeQuota(userId);
        List<NodeBrief> hits = retrieve(question);
        List<SourceRef> sources = hits.stream()
                .map(n -> new SourceRef(n.id(), n.name()))
                .toList();

        if (openAi.configured()) {
            try {
                String answer = openAi.chat(systemPrompt(), ragUserMessage(question, hits));
                return new AskResult(answer, "rag", sources, remaining);
            } catch (Exception e) {
                log.warn("LLM 调用失败,降级为规则问答: {}", e.getMessage());
            }
        }
        return new AskResult(rulesAnswer(question, hits), "rules", sources, remaining);
    }

    /** 检索:Milvus 向量检索优先,不可用时按节点名关键词匹配 */
    private List<NodeBrief> retrieve(String question) {
        if (openAi.configured() && milvus.ready()) {
            try {
                float[] vec = openAi.embed(List.of(question)).get(0);
                List<Long> ids = milvus.search(vec, 5);
                Map<Long, NodeBrief> byId = nodesById();
                return ids.stream().map(byId::get).filter(n -> n != null).toList();
            } catch (Exception e) {
                log.warn("向量检索失败,降级为关键词匹配: {}", e.getMessage());
            }
        }
        return keywordMatch(question);
    }

    private Map<Long, NodeBrief> nodesById() {
        Map<Long, NodeBrief> map = new LinkedHashMap<>();
        graphService.tree().nodes().forEach(n -> map.put(n.id(), n));
        return map;
    }

    private List<NodeBrief> keywordMatch(String question) {
        Tree tree = graphService.tree();
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

    private String systemPrompt() {
        return "你是 Sparrow 人类科技树的 AI 向导。请仅基于提供的科技树资料回答用户问题," +
                "重点讲清技术之间的依赖关系与历史脉络;资料不足以回答时,如实说明。回答用中文,简洁、准确。";
    }

    private String ragUserMessage(String question, List<NodeBrief> hits) {
        StringBuilder sb = new StringBuilder("### 科技树资料\n");
        if (hits.isEmpty()) {
            sb.append("(未检索到直接相关节点)\n");
        }
        for (NodeBrief n : hits) {
            sb.append("- 【").append(n.name()).append("】").append(n.era())
                    .append(" · ").append(n.yearLabel()).append("\n  ").append(n.summary()).append("\n");
            List<NodeBrief> pres = graphService.nodeDetail(n.id(), null).prerequisites();
            if (!pres.isEmpty()) {
                sb.append("  直接前置:");
                sb.append(String.join("、", pres.stream().map(NodeBrief::name).toList()));
                sb.append("\n");
            }
        }
        sb.append("\n### 用户问题\n").append(question);
        return sb.toString();
    }

    /** 无 LLM 时的确定性回答:节点卡片 + 完整前置链 + 直接解锁 */
    private String rulesAnswer(String question, List<NodeBrief> hits) {
        if (hits.isEmpty()) {
            return "我没有在科技树中找到与问题直接相关的技术节点。可以试试这样问:" +
                    "「蒸汽机的前置技术有哪些?」「互联网解锁了什么?」「文字是什么时候出现的?」";
        }
        NodeBrief top = hits.get(0);
        var detail = graphService.nodeDetail(top.id(), null);
        List<NodeBrief> chain = graphService.prerequisiteChain(top.id());

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(top.name()).append("】").append(top.era())
                .append(" · ").append(top.yearLabel()).append("\n")
                .append(top.summary()).append("\n");

        if (!chain.isEmpty()) {
            sb.append("\n完整前置技术链(共 ").append(chain.size()).append(" 项):\n");
            Map<String, List<String>> byEra = new LinkedHashMap<>();
            for (NodeBrief n : chain) {
                byEra.computeIfAbsent(n.era(), k -> new ArrayList<>()).add(n.name());
            }
            byEra.forEach((era, names) ->
                    sb.append("· ").append(era).append(":").append(String.join("、", names)).append("\n"));
        }
        if (!detail.unlocks().isEmpty()) {
            sb.append("\n它直接解锁:");
            sb.append(String.join("、", detail.unlocks().stream().map(NodeBrief::name).toList()));
            sb.append("\n");
        }
        sb.append("\n(当前为图谱规则问答;部署时配置 AI_API_KEY 可启用大模型深度问答)");
        return sb.toString();
    }

    /** 会员不限次;免费用户每日限额(Redis 计数,自然日过期) */
    private long consumeQuota(Long userId) {
        if (membershipService.isMember(userId)) {
            return -1; // -1 表示不限次
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
}
