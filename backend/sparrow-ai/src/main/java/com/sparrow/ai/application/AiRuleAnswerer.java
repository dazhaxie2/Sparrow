package com.sparrow.ai.application;

import com.sparrow.ai.infrastructure.client.GraphClient;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeBrief;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeDetail;
import com.sparrow.ai.infrastructure.rag.MilvusStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一句话：规则与提示词——RAG/Agent 用户消息拼接 + 规则模板回答(不调 LLM,纯图谱数据)。 */
class AiRuleAnswerer {

    private final GraphClient graphClient;

    AiRuleAnswerer(GraphClient graphClient) {
        this.graphClient = graphClient;
    }

    /** Agent 模式的用户消息模板:历史 + 问题 + 回答风格约束。 */
    String agentUserMessage(String question, String intent, String conversationContext) {
        String history = conversationContext == null || conversationContext.isBlank()
                ? "" : conversationContext + "\n\n";
        return history + "用户问题:\n" + question + "\n\n" +
                "请用中文自然、口语化地回答,直接讲重点,把相关的技术依赖与历史脉络说清楚;" +
                "内容要充分、有信息量,给足背景和关键的年代、数字、事实与例子,把「为什么」讲透,宁可多展开也别敷衍带过;" +
                "可以顺着内容自然分层,必要时用小标题或列表理清脉络," +
                "但不要套用「结论/关键依据/学习路径/下一步」之类与内容无关的固定模板;" +
                "不要使用 emoji,不要输出代码块。";
    }

    /** RAG 模式用户消息:科技图资料 + 相关词条摘录 + 会话上下文 + 用户问题。 */
    String ragUserMessage(String question, List<NodeBrief> hits,
                          List<MilvusStore.ChunkHit> chunks, String conversationContext) {
        StringBuilder sb = new StringBuilder("### 科技图资料\n");
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
        if (conversationContext != null && !conversationContext.isBlank()) {
            sb.append("\n").append(conversationContext).append("\n");
        }
        sb.append("\n### 用户问题\n").append(question);
        return sb.toString();
    }

    /** 规则引擎模式:基于图谱数据生成结构化回答(结论/依据/前置链/解锁/下一步)。 */
    String rulesAnswer(String question, List<NodeBrief> hits) {
        if (hits.isEmpty()) {
            return "### 结论\n我没有在科技图中找到与问题直接相关的技术节点。\n\n" +
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
        return sb.toString();
    }
}
