package com.sparrow.graph.interfaces.dto;

import com.sparrow.graph.domain.model.NeoTechNode;
import com.sparrow.graph.domain.model.TechNode;

import java.util.List;

public final class GraphDtos {

    private GraphDtos() {
    }

    public record NodeBrief(Long id, String code, String name, String era, Integer eraRank,
                            String yearLabel, String summary, boolean premium,
                            String category, Integer importance) {

        public static NodeBrief from(TechNode n) {
            return new NodeBrief(n.getId(), n.getCode(), n.getName(), n.getEra(), n.getEraRank(),
                    n.getYearLabel(), n.getSummary(), Boolean.TRUE.equals(n.getPremium()),
                    n.getCategory(), n.getImportance());
        }

        public static NodeBrief from(NeoTechNode n) {
            return new NodeBrief(n.getId(), n.getCode(), n.getName(), n.getEra(), n.getEraRank(),
                    n.getYearLabel(), n.getSummary(), Boolean.TRUE.equals(n.getPremium()),
                    n.getCategory(), n.getImportance());
        }
    }

    public record EdgeBrief(Long from, Long to) {
    }

    public record Tree(List<NodeBrief> nodes, List<EdgeBrief> edges) {
    }

    public record NodeDetail(Long id, String code, String name, String era, Integer eraRank,
                             String yearLabel, String summary, String detail, boolean premium,
                             boolean locked, List<NodeBrief> prerequisites, List<NodeBrief> unlocks,
                             List<SourceBrief> sources, String category, Integer importance) {
    }

    /** 总览端点:领域×时代格子的聚合计数 + 该格代表节点(按重要度取 top)。 */
    public record OverviewCell(String category, Integer eraRank, String era, long count,
                               List<NodeBrief> topNodes) {
    }

    public record Overview(List<String> categories, List<EraBrief> eras, List<OverviewCell> cells,
                           long totalNodes, long totalEdges) {
    }

    public record EraBrief(Integer eraRank, String era) {
    }

    /** 分页节点列表(过滤/检索结果)。 */
    public record NodePage(List<NodeBrief> nodes, long total, int page, int size) {
    }

    /** 邻域子图:中心节点 + 直接前置/后继(展开式浏览)。 */
    public record Neighborhood(NodeBrief center, List<NodeBrief> nodes, List<EdgeBrief> edges) {
    }

    public record IndexableNode(Long id, String code, String name, String era, String yearLabel,
                                String summary, String detail, String category, Integer importance) {
    }

    public record SourceBrief(String title, String url, String updatedAt) {
    }

    public record KnowledgeStatus(long ragDocumentCount, String ragUpdatedAt,
                                  boolean ragIndexed, Integer ragNodeCount,
                                  Integer ragChunkCount, String ragIndexUpdatedAt) {
    }
}
