package com.sparrow.graph.interfaces.dto;

import com.sparrow.graph.domain.model.TechNode;

import java.util.List;

/**
 * graph 服务对外契约 DTO。
 * 字段一个都不能动:前端按此结构反序列化,跨服务 Feign 调用方也按此结构理解。
 */
public final class GraphDtos {

    private GraphDtos() {
    }

    public record NodeBrief(Long id, String code, String name, String era, Integer eraRank,
                            String yearLabel, String summary, boolean premium) {

        public static NodeBrief from(TechNode n) {
            return new NodeBrief(n.getId(), n.getCode(), n.getName(), n.getEra(), n.getEraRank(),
                    n.getYearLabel(), n.getSummary(), Boolean.TRUE.equals(n.getPremium()));
        }
    }

    public record EdgeBrief(Long from, Long to) {
    }

    public record Tree(List<NodeBrief> nodes, List<EdgeBrief> edges) {
    }

    /** detail 为会员专属时,非会员返回 locked=true 且 detail=null */
    public record NodeDetail(Long id, String code, String name, String era, Integer eraRank,
                             String yearLabel, String summary, String detail, boolean premium,
                             boolean locked, List<NodeBrief> prerequisites, List<NodeBrief> unlocks) {
    }

    /** ai 索引节点用 */
    public record IndexableNode(Long id, String code, String name, String era, String yearLabel,
                                String summary, String detail) {
    }
}
