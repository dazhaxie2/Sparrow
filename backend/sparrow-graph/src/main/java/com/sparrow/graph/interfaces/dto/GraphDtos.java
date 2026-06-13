package com.sparrow.graph.interfaces.dto;

import com.sparrow.graph.domain.model.NeoTechNode;
import com.sparrow.graph.domain.model.TechNode;

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

        public static NodeBrief from(NeoTechNode n) {
            return new NodeBrief(n.getId(), n.getCode(), n.getName(), n.getEra(), n.getEraRank(),
                    n.getYearLabel(), n.getSummary(), Boolean.TRUE.equals(n.getPremium()));
        }
    }

    public record EdgeBrief(Long from, Long to) {
    }

    public record Tree(List<NodeBrief> nodes, List<EdgeBrief> edges) {
    }

    public record NodeDetail(Long id, String code, String name, String era, Integer eraRank,
                             String yearLabel, String summary, String detail, boolean premium,
                             boolean locked, List<NodeBrief> prerequisites, List<NodeBrief> unlocks,
                             List<SourceBrief> sources) {
    }

    public record IndexableNode(Long id, String code, String name, String era, String yearLabel,
                                String summary, String detail) {
    }

    public record SourceBrief(String title, String url, String updatedAt) {
    }

    public record KnowledgeStatus(long ragDocumentCount, String ragUpdatedAt,
                                  boolean ragIndexed, Integer ragNodeCount,
                                  Integer ragChunkCount, String ragIndexUpdatedAt) {
    }
}
