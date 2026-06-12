package com.sparrow.entity.dto;

import com.sparrow.entity.po.TechNode;

import java.util.List;

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
}
