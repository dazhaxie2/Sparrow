package com.sparrow.ai.infrastructure.client;

import java.util.List;

/**
 * ai 自持的 graph 服务 DTO 副本(消费方契约)。
 * 与 sparrow-graph 的 GraphDtos 形状一致,但有意独立——升级 graph DTO 不强制升 ai。
 */
public final class GraphViews {

    private GraphViews() {
    }

    public record NodeBrief(Long id, String code, String name, String era, Integer eraRank,
                            String yearLabel, String summary, boolean premium) {
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
}
