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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
                .map(e -> new EdgeBrief(e.getFromId(), e.getToId()))
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

    /** 名称/摘要前缀检索,按重要度排序,默认上限 20。 */
    public List<NodeBrief> searchNodes(String q, int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        int lim = Math.min(Math.max(limit, 1), 50);
        QueryWrapper<TechNode> filter = baseFilter(null, null, q, null);
        filter.orderByDesc("importance").orderByAsc("id").last("LIMIT " + lim);
        return nodeMapper.selectList(filter).stream().map(NodeBrief::from).toList();
    }

    /** 过滤后按重要度取前 limit 个节点 + 这些节点之间的边(有界子图,供前端渲染)。 */
    public Tree subgraph(String category, Integer eraRank, String q,
                         Integer minImportance, int limit) {
        int lim = Math.min(Math.max(limit, 1), 800);
        QueryWrapper<TechNode> filter = baseFilter(category, eraRank, q, minImportance);
        filter.orderByDesc("importance").orderByAsc("id").last("LIMIT " + lim);
        List<TechNode> top = nodeMapper.selectList(filter);
        if (top.isEmpty()) {
            return new Tree(List.of(), List.of());
        }
        Set<Long> ids = top.stream().map(TechNode::getId).collect(Collectors.toSet());
        List<NodeBrief> nodes = top.stream().sorted(ORDER).map(NodeBrief::from).toList();
        QueryWrapper<TechEdge> edgeFilter = new QueryWrapper<>();
        edgeFilter.in("from_id", ids).in("to_id", ids);
        List<EdgeBrief> edges = edgeMapper.selectList(edgeFilter).stream()
                .map(e -> new EdgeBrief(e.getFromId(), e.getToId()))
                .toList();
        return new Tree(nodes, edges);
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
        Map<Long, TechNode> byId = nodesById();
        return edgeMapper.selectList(null).stream()
                .filter(e -> id.equals(e.getToId()))
                .map(e -> byId.get(e.getFromId()))
                .filter(Objects::nonNull)
                .sorted(ORDER)
                .map(NodeBrief::from)
                .toList();
    }

    /** 直接解锁:edges 中 from_id=id 的 to_id 节点(它们以 id 为前置)。 */
    public List<NodeBrief> directUnlocks(Long id) {
        Map<Long, TechNode> byId = nodesById();
        return edgeMapper.selectList(null).stream()
                .filter(e -> id.equals(e.getFromId()))
                .map(e -> byId.get(e.getToId()))
                .filter(Objects::nonNull)
                .sorted(ORDER)
                .map(NodeBrief::from)
                .toList();
    }

    /** 完整前置链:反向 BFS 多跳,等价于 Neo4j 的 {@code [:REQUIRES*]}。 */
    public List<NodeBrief> allPrerequisites(Long id) {
        Map<Long, TechNode> byId = nodesById();
        if (!byId.containsKey(id)) {
            throw new BizException(404, "技术节点不存在");
        }
        Map<Long, List<Long>> prereqOf = new HashMap<>();
        for (TechEdge e : edgeMapper.selectList(null)) {
            prereqOf.computeIfAbsent(e.getToId(), k -> new ArrayList<>()).add(e.getFromId());
        }
        Set<Long> visited = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(id);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            for (Long pre : prereqOf.getOrDefault(cur, List.of())) {
                if (visited.add(pre)) {
                    queue.add(pre);
                }
            }
        }
        return visited.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .sorted(ORDER)
                .map(NodeBrief::from)
                .toList();
    }

    private Map<Long, TechNode> nodesById() {
        return nodeMapper.selectList(null).stream()
                .collect(Collectors.toMap(TechNode::getId, Function.identity(), (a, b) -> a));
    }
}
