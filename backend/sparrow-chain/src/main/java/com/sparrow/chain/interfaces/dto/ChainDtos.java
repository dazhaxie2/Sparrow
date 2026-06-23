package com.sparrow.chain.interfaces.dto;

import java.util.List;

/**
 * 产业链专题 DTO。
 *
 * <p>node_type / edge_type 是爬虫 LLM 抽取时打的标签,这里提供 label 映射供前端直接展示,
 * 并用降级默认值保证数据缺失时不报错。</p>
 */
public final class ChainDtos {

    private ChainDtos() {}

    /** 节点类型 → 中文标签(决定前端分色)。 */
    public static String nodeTypeLabel(String type) {
        if (type == null) return "公司";
        return switch (type) {
            case "核心公司" -> "核心公司";
            case "供应商" -> "供应商";
            case "代工厂" -> "代工厂";
            case "材料商" -> "材料商";
            default -> "公司";
        };
    }

    /** 边类型 → 中文标签(决定前端连线上显示的关系)。 */
    public static String edgeTypeLabel(String type) {
        if (type == null) return "供货";
        return switch (type) {
            case "供货" -> "供货";
            case "代工" -> "代工";
            case "材料供应" -> "材料供应";
            case "授权" -> "授权";
            default -> "供货";
        };
    }

    public record ChainSummary(Long id, String slug, String name, String description,
                               String coverColor, Integer nodeCount) {
    }

    public record ChainNodeBrief(Long id, String name, String nodeType, String nodeTypeText,
                                 String summary, Integer importance) {
        public static ChainNodeBrief from(com.sparrow.chain.domain.model.ChainNode n) {
            return new ChainNodeBrief(n.getId(), n.getName(), n.getNodeType(),
                    nodeTypeLabel(n.getNodeType()), n.getSummary(),
                    n.getImportance() == null ? 0 : n.getImportance());
        }
    }

    public record ChainEdgeBrief(Long from, Long to, String edgeType, String edgeTypeText,
                                 String product) {
        public static ChainEdgeBrief from(com.sparrow.chain.domain.model.ChainEdge e) {
            return new ChainEdgeBrief(e.getFromId(), e.getToId(), e.getEdgeType(),
                    edgeTypeLabel(e.getEdgeType()), e.getProduct());
        }
    }

    public record ChainGraph(List<ChainNodeBrief> nodes, List<ChainEdgeBrief> edges) {
    }
}
