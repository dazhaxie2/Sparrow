package com.sparrow.graph.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparrow.common.exception.BizException;
import com.sparrow.graph.domain.model.TechEdge;
import com.sparrow.graph.domain.model.TechNode;
import com.sparrow.graph.interfaces.dto.GraphDtos.EdgeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.IndexableNode;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodePage;
import com.sparrow.graph.interfaces.dto.GraphDtos.Tree;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Neo4j 不可达时的降级读路径:直接读 MySQL `tech_node/tech_edge` 邻接表。
 * 多跳前置链用反向 BFS 复刻 Neo4j 的 {@code MATCH (n)-[:REQUIRES*]->(pre)},保证 API 响应结构不变。
 * edge 语义:from_id 是 to_id 的前置(to_id REQUIRES from_id)。
 */
@Component
public class MysqlGraphReader {

    private static final int MAX_PREREQUISITE_PATH_NODES = 200;
    private static final int MAX_PREREQUISITE_DEPTH = 8;

    private static final Comparator<TechNode> ORDER =
            Comparator.comparing(TechNode::getEraRank, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(TechNode::getId);

    private final TechNodeMapper nodeMapper;
    private final TechEdgeMapper edgeMapper;

    public MysqlGraphReader(TechNodeMapper nodeMapper, TechEdgeMapper edgeMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
    }

    public List<NodeBrief> allOrdered() {
        return nodeMapper.selectList(null).stream()
                .sorted(ORDER)
                .map(NodeBrief::from)
                .toList();
    }

    public List<EdgeBrief> allEdges() {
        return edgeMapper.selectList(null).stream()
                .map(EdgeBrief::from)
                .toList();
    }

    public List<IndexableNode> indexableNodes() {
        return nodeMapper.selectList(null).stream()
                .sorted(ORDER)
                .map(n -> new IndexableNode(n.getId(), n.getCode(), n.getName(), n.getEra(),
                        n.getYearLabel(), n.getSummary(), n.getDetail(),
                        n.getCategory(), n.getImportance()))
                .toList();
    }

    public TechNode findNode(Long id) {
        return nodeMapper.selectById(id);
    }

    public boolean exists(Long id) {
        return nodeMapper.selectById(id) != null;
    }

    public int count() {
        Long c = nodeMapper.selectCount(null);
        return c == null ? 0 : c.intValue();
    }

    public long countEdges() {
        Long c = edgeMapper.selectCount(null);
        return c == null ? 0L : c;
    }

    /** 过滤 + 分页节点列表(维基级:避免一次性返回全树)。按重要度降序、id 升序稳定排序。 */
    public NodePage pageNodes(String category, Integer eraRank, String q,
                              Integer minImportance, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        QueryWrapper<TechNode> filter = baseFilter(category, eraRank, q, minImportance);
        long total = nodeMapper.selectCount(filter);
        filter.orderByDesc("importance").orderByAsc("id")
                .last("LIMIT " + ((p - 1) * s) + ", " + s);
        List<NodeBrief> rows = nodeMapper.selectList(filter).stream().map(NodeBrief::from).toList();
        return new NodePage(rows, total, p, s);
    }

    /** 名称/摘要检索：名称完全匹配 > 名称前缀 > 名称包含 > 摘要包含，同级按重要度排序。 */
    public List<NodeBrief> searchNodes(String q, int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        int lim = Math.min(Math.max(limit, 1), 50);
        return nodeMapper.searchRelevant(q.trim(), lim).stream().map(NodeBrief::from).toList();
    }

    /** 过滤后构造有界子图:保留核心高重要度节点,同时补入可连接核心的邻居以提升可见关系密度。 */
    public Tree subgraph(String category, Integer eraRank, String q,
                         Integer minImportance, int limit) {
        // 上限 3000：满足超大类目全量浏览；候选池按 lim*3 比例放大，保证核心节点能选到足够邻居提升关系密度。
        int lim = Math.min(Math.max(limit, 1), 3000);
        int candidateLimit = Math.min(Math.max(lim * 3, lim), 9000);
        QueryWrapper<TechNode> filter = baseFilter(category, eraRank, q, minImportance);
        filter.orderByDesc("importance").orderByAsc("id").last("LIMIT " + candidateLimit);
        List<TechNode> candidates = nodeMapper.selectList(filter);
        if (candidates.isEmpty()) {
            return new Tree(List.of(), List.of());
        }
        Set<Long> candidateIds = candidates.stream().map(TechNode::getId).collect(Collectors.toSet());
        QueryWrapper<TechEdge> edgeFilter = new QueryWrapper<>();
        edgeFilter.in("from_id", candidateIds).in("to_id", candidateIds);
        List<TechEdge> candidateEdges = edgeMapper.selectList(edgeFilter);

        Map<Long, Integer> degree = new HashMap<>();
        for (TechEdge edge : candidateEdges) {
            degree.merge(edge.getFromId(), 1, Integer::sum);
            degree.merge(edge.getToId(), 1, Integer::sum);
        }

        Map<Long, TechNode> byId = candidates.stream()
                .collect(Collectors.toMap(TechNode::getId, Function.identity(), (a, b) -> a));
        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>();
        int anchorCount = Math.min(candidates.size(), Math.max(1, lim * 7 / 10));
        for (int i = 0; i < anchorCount; i++) {
            selectedIds.add(candidates.get(i).getId());
        }

        List<TechEdge> edgeRank = candidateEdges.stream()
                .sorted((a, b) -> Integer.compare(edgeSignal(b, degree), edgeSignal(a, degree)))
                .toList();
        for (TechEdge edge : edgeRank) {
            if (selectedIds.size() >= lim) {
                break;
            }
            boolean fromSelected = selectedIds.contains(edge.getFromId());
            boolean toSelected = selectedIds.contains(edge.getToId());
            if (fromSelected && !toSelected) {
                selectedIds.add(edge.getToId());
            } else if (toSelected && !fromSelected) {
                selectedIds.add(edge.getFromId());
            }
        }

        candidates.stream()
                .sorted((a, b) -> {
                    int signal = Integer.compare(nodeSignal(b, degree), nodeSignal(a, degree));
                    return signal != 0 ? signal : ORDER.compare(a, b);
                })
                .map(TechNode::getId)
                .forEach(id -> {
                    if (selectedIds.size() < lim) {
                        selectedIds.add(id);
                    }
                });

        Set<Long> ids = selectedIds.stream().limit(lim).collect(Collectors.toCollection(LinkedHashSet::new));
        List<NodeBrief> nodes = ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .sorted(ORDER)
                .map(NodeBrief::from)
                .toList();
        Set<Long> visibleIds = nodes.stream().map(NodeBrief::id).collect(Collectors.toSet());
        List<EdgeBrief> edges = candidateEdges.stream()
                .filter(e -> visibleIds.contains(e.getFromId()) && visibleIds.contains(e.getToId()))
                .map(EdgeBrief::from)
                .toList();
        return new Tree(nodes, edges);
    }

    private int nodeSignal(TechNode node, Map<Long, Integer> degree) {
        return (node.getImportance() == null ? 0 : node.getImportance()) + degree.getOrDefault(node.getId(), 0) * 12;
    }

    private int edgeSignal(TechEdge edge, Map<Long, Integer> degree) {
        return degree.getOrDefault(edge.getFromId(), 0) + degree.getOrDefault(edge.getToId(), 0);
    }

    private QueryWrapper<TechNode> baseFilter(String category, Integer eraRank, String q,
                                              Integer minImportance) {
        QueryWrapper<TechNode> w = new QueryWrapper<>();
        if (category != null && !category.isBlank()) {
            w.eq("category", category);
        }
        if (eraRank != null) {
            w.eq("era_rank", eraRank);
        }
        if (minImportance != null) {
            w.ge("importance", minImportance);
        }
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            w.and(x -> x.like("name", kw).or().like("summary", kw));
        }
        return w;
    }

    /** 直接前置:edges 中 to_id=id 的 from_id 节点。 */
    public List<NodeBrief> directPrerequisites(Long id) {
        QueryWrapper<TechEdge> query = new QueryWrapper<>();
        query.eq("to_id", id);
        Set<Long> ids = edgeMapper.selectList(query).stream()
                .map(TechEdge::getFromId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return nodesForIds(ids);
    }

    /** 直接解锁:edges 中 from_id=id 的 to_id 节点(它们以 id 为前置)。 */
    public List<NodeBrief> directUnlocks(Long id) {
        QueryWrapper<TechEdge> query = new QueryWrapper<>();
        query.eq("from_id", id);
        Set<Long> ids = edgeMapper.selectList(query).stream()
                .map(TechEdge::getToId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return nodesForIds(ids);
    }

    /** 完整前置链:反向 BFS 多跳,等价于 Neo4j 的 {@code [:REQUIRES*]}。 */
    public List<NodeBrief> allPrerequisites(Long id) {
        if (!exists(id)) {
            throw new BizException(404, "技术节点不存在");
        }
        Set<Long> visited = new LinkedHashSet<>();
        Set<Long> frontier = new LinkedHashSet<>(List.of(id));
        int depth = 0;
        while (!frontier.isEmpty()
                && visited.size() < MAX_PREREQUISITE_PATH_NODES
                && depth++ < MAX_PREREQUISITE_DEPTH) {
            QueryWrapper<TechEdge> query = new QueryWrapper<>();
            query.in("to_id", frontier);
            query.last("LIMIT " + Math.max(1, (MAX_PREREQUISITE_PATH_NODES - visited.size()) * 4));
            Set<Long> next = new LinkedHashSet<>();
            for (TechEdge edge : edgeMapper.selectList(query)) {
                Long prerequisiteId = edge.getFromId();
                if (!id.equals(prerequisiteId) && visited.add(prerequisiteId)) {
                    next.add(prerequisiteId);
                    if (visited.size() >= MAX_PREREQUISITE_PATH_NODES) break;
                }
            }
            frontier = next;
        }
        return nodesForIds(visited);
    }

    private List<NodeBrief> nodesForIds(Set<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return nodeMapper.selectBatchIds(ids).stream()
                .sorted(ORDER)
                .map(NodeBrief::from)
                .toList();
    }

    /** 按任意 id 集合取节点 brief(应用/产业链等场景复用);按重要度降序、id 升序稳定排序。 */
    public List<NodeBrief> briefsForIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return nodeMapper.selectBatchIds(new LinkedHashSet<>(ids)).stream()
                .sorted(ORDER)
                .map(NodeBrief::from)
                .toList();
    }
}
