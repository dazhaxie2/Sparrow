package com.sparrow.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.common.BizException;
import com.sparrow.common.MembershipService;
import com.sparrow.graph.GraphDtos.EdgeBrief;
import com.sparrow.graph.GraphDtos.NodeBrief;
import com.sparrow.graph.GraphDtos.NodeDetail;
import com.sparrow.graph.GraphDtos.Tree;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GraphService {

    private static final String TREE_CACHE_KEY = "sparrow:graph:tree";
    private static final Duration TREE_CACHE_TTL = Duration.ofHours(1);

    private final TechNodeRepository nodeRepo;
    private final TechEdgeRepository edgeRepo;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MembershipService membershipService;

    public GraphService(TechNodeRepository nodeRepo, TechEdgeRepository edgeRepo,
                        StringRedisTemplate redis, ObjectMapper objectMapper,
                        MembershipService membershipService) {
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.membershipService = membershipService;
    }

    /** 全树:整体进 Redis 缓存(科技树是读多写少的小数据,命中后不打 MySQL) */
    public Tree tree() {
        String cached = redis.opsForValue().get(TREE_CACHE_KEY);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, Tree.class);
            } catch (JsonProcessingException ignored) {
                // 缓存内容损坏则回源重建
            }
        }
        List<NodeBrief> nodes = nodeRepo.findAll().stream()
                .sorted(Comparator.comparing(TechNode::getEraRank).thenComparing(TechNode::getId))
                .map(NodeBrief::from)
                .toList();
        List<EdgeBrief> edges = edgeRepo.findAll().stream()
                .map(e -> new EdgeBrief(e.getFromId(), e.getToId()))
                .toList();
        Tree tree = new Tree(nodes, edges);
        try {
            redis.opsForValue().set(TREE_CACHE_KEY, objectMapper.writeValueAsString(tree), TREE_CACHE_TTL);
        } catch (JsonProcessingException ignored) {
        }
        return tree;
    }

    public NodeDetail nodeDetail(Long id, Long userId) {
        TechNode node = nodeRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "技术节点不存在"));
        Tree tree = tree();
        Map<Long, NodeBrief> byId = new HashMap<>();
        tree.nodes().forEach(n -> byId.put(n.id(), n));

        List<NodeBrief> prerequisites = tree.edges().stream()
                .filter(e -> e.to().equals(id))
                .map(e -> byId.get(e.from()))
                .sorted(Comparator.comparing(NodeBrief::eraRank))
                .toList();
        List<NodeBrief> unlocks = tree.edges().stream()
                .filter(e -> e.from().equals(id))
                .map(e -> byId.get(e.to()))
                .sorted(Comparator.comparing(NodeBrief::eraRank))
                .toList();

        boolean isPremium = Boolean.TRUE.equals(node.getPremium());
        boolean locked = isPremium && (userId == null || !membershipService.isMember(userId));
        return new NodeDetail(node.getId(), node.getCode(), node.getName(), node.getEra(),
                node.getEraRank(), node.getYearLabel(), node.getSummary(),
                locked ? null : node.getDetail(), isPremium, locked, prerequisites, unlocks);
    }

    /** 全部前置依赖链:沿边反向 BFS,按时代排序返回 */
    public List<NodeBrief> prerequisiteChain(Long id) {
        Tree tree = tree();
        Map<Long, NodeBrief> byId = new HashMap<>();
        tree.nodes().forEach(n -> byId.put(n.id(), n));
        if (!byId.containsKey(id)) {
            throw new BizException(404, "技术节点不存在");
        }
        Map<Long, List<Long>> reverse = new HashMap<>();
        for (EdgeBrief e : tree.edges()) {
            reverse.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
        }
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>(reverse.getOrDefault(id, List.of()));
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            if (visited.add(cur)) {
                queue.addAll(reverse.getOrDefault(cur, List.of()));
            }
        }
        return visited.stream()
                .map(byId::get)
                .sorted(Comparator.comparing(NodeBrief::eraRank).thenComparing(NodeBrief::id))
                .toList();
    }
}
