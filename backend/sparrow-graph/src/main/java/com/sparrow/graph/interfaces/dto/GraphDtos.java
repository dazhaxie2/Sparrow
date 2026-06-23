package com.sparrow.graph.interfaces.dto;

import com.sparrow.graph.domain.model.NeoTechNode;
import com.sparrow.graph.domain.model.TechEdge;
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

    /**
     * 边:relation 是机器可读的关系类型,label 由其推导供前端显示。
     * 0=依赖/前置, 1=结构/分类归属, 2=衍生/应用。现有边全部为依赖边(relation=0);
     * 爬虫显式"分类归属边/衍生应用边"上线后据 relation 区分。
     */
    public record EdgeBrief(Long from, Long to, int relation, String label) {

        public static final int REL_DEPENDENCY = 0;
        public static final int REL_STRUCTURAL = 1;
        public static final int REL_DERIVED = 2;

        /** 默认依赖边(relation=0)。 */
        public EdgeBrief(Long from, Long to) {
            this(from, to, REL_DEPENDENCY);
        }

        public EdgeBrief(Long from, Long to, int relation) {
            this(from, to, relation, labelFor(relation));
        }

        public static EdgeBrief from(TechEdge e) {
            return new EdgeBrief(e.getFromId(), e.getToId(),
                    e.getRelation() == null ? REL_DEPENDENCY : e.getRelation());
        }

        private static String labelFor(int relation) {
            return switch (relation) {
                case REL_STRUCTURAL -> "结构";
                case REL_DERIVED -> "衍生";
                default -> "前置";
            };
        }
    }

    public record Tree(List<NodeBrief> nodes, List<EdgeBrief> edges) {
    }

    public record NodeDetail(Long id, String code, String name, String era, Integer eraRank,
                             String yearLabel, String summary, String detail, boolean premium,
                             boolean locked, List<NodeBrief> prerequisites, List<NodeBrief> unlocks,
                             List<NodeBrief> applications, List<SourceBrief> sources,
                             String category, Integer importance) {
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

    // ── 百万级 LOD 瓦片 DTO(M2) ──

    /** 瓦片内的可渲染节点:只携带渲染必需字段(id/坐标/领域/重要度),响应体尽量小。 */
    public record TileNode(Long id, Long clusterId, double x, double y, String name,
                           String category, Integer importance) {
    }

    /** 单个 LOD 瓦片:某层级某簇下的节点坐标 + 簇内边。 */
    public record Tile(int level, Long clusterId, List<TileNode> nodes, List<EdgeBrief> edges) {
    }

    /** 社区聚簇总览：簇大小用于前端气泡尺寸，代表节点用于点击下钻。 */
    public record ClusterNode(Long id, Long clusterId, double x, double y, String name,
                              String category, Integer importance, long nodeCount) {
    }

    public record ClusterOverview(long representedNodes, List<ClusterNode> clusters) {
    }
}
