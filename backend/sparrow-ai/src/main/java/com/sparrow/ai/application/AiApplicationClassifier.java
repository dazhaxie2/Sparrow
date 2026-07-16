package com.sparrow.ai.application;

import com.sparrow.ai.application.AiService.ApplicationClassifyRequest;
import com.sparrow.ai.application.AiService.NeighborBrief;
import com.sparrow.common.ai.AiHarness;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一句话：应用判定——从材料节点邻居中用 LLM 挑出下游应用/产业链,排除属性噪声;失败降级为空列表。 */
class AiApplicationClassifier {

    private static final Logger log = LoggerFactory.getLogger(AiApplicationClassifier.class);

    private final ChatModel chatModel;

    AiApplicationClassifier(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    List<Long> classify(ApplicationClassifyRequest req) {
        try {
            String answer = chatModel.chat(buildApplicationPrompt(req));
            return parseApplicationIds(answer, req.neighbors());
        } catch (Exception e) {
            log.warn("应用判定 LLM 调用失败 [nodeId={}]: {}", req.nodeId(), AiHarness.safeFailure(e));
            return List.of();
        }
    }

    private String buildApplicationPrompt(ApplicationClassifyRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是材料与化工领域的知识图谱编辑。下面是材料「")
                .append(req.nodeName()).append("」(领域:").append(req.category()).append(")的直接关联条目清单。\n");
        sb.append("请从中挑出属于「").append(req.nodeName())
                .append("」的【下游应用 / 产业链 / 制成品 / 工业用途】的条目")
                .append("(例如:用它制成的产品、器件,以它为关键原料的工业流程或终端应用)。\n");
        sb.append("必须排除以下类型的条目:化学式、CAS号、摩尔量/焓/密度/沸点等物理化学属性、")
                .append("同位素、晶系、族/周期分类、可见光/光学性质、纯度量纲、命名空间词条等纯属性或分类条目。\n\n");
        sb.append("条目清单(每行一条):\n");
        for (NeighborBrief n : req.neighbors()) {
            sb.append("- id=").append(n.id()).append(" 名称=").append(n.name());
            if (n.category() != null && !n.category().isBlank()) sb.append(" 领域=").append(n.category());
            if (n.summary() != null && !n.summary().isBlank()) sb.append(" 摘要=").append(n.summary());
            sb.append("\n");
        }
        sb.append("\n只返回一个 JSON 数组,元素为被判为应用的条目的 id(数字),不要任何解释文字。")
                .append("若无任何应用条目,返回 []。例如:[123, 456, 789]");
        return sb.toString();
    }

    /** 解析 LLM 返回的 JSON 数组,并把 name/id 回填校验后返回 id。 */
    private List<Long> parseApplicationIds(String answer, List<NeighborBrief> neighbors) {
        if (answer == null) return List.of();
        // 容错:LLM 可能附带前后文字,提取第一个 [ ... ] 片段。
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        String json = answer.substring(start, end + 1).trim();
        // name→id 映射(LLM 偶尔返回 name 而非 id 时也能兜底)。
        Map<String, Long> nameToId = new LinkedHashMap<>();
        Set<Long> validIds = new HashSet<>();
        for (NeighborBrief n : neighbors) {
            if (n.id() != null) validIds.add(n.id());
            if (n.name() != null) nameToId.put(n.name(), n.id());
        }
        List<Long> result = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> tokens = mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            for (String t : tokens) {
                if (t == null) continue;
                String s = t.trim();
                if (s.isEmpty()) continue;
                try {
                    long id = Long.parseLong(s);
                    if (validIds.contains(id)) result.add(id);
                } catch (NumberFormatException nfe) {
                    Long id = nameToId.get(s);
                    if (id != null) result.add(id);
                }
            }
        } catch (Exception e) {
            log.warn("应用判定响应解析失败,降级为空列表: raw={}", json);
            return List.of();
        }
        return result;
    }
}
