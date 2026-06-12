package com.sparrow.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.entity.po.TechNode;
import com.sparrow.entity.po.TechEdge;
import com.sparrow.mapper.TechNodeMapper;
import com.sparrow.mapper.TechEdgeMapper;
import com.sparrow.exception.BizException;
import com.sparrow.service.MembershipService;
import com.sparrow.entity.dto.GraphDtos.EdgeBrief;
import com.sparrow.entity.dto.GraphDtos.NodeBrief;
import com.sparrow.entity.dto.GraphDtos.NodeDetail;
import com.sparrow.entity.dto.GraphDtos.Tree;
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

    private final TechNodeMapper nodeMapper;
    private final TechEdgeMapper edgeMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MembershipService membershipService;

    public GraphService(TechNodeMapper nodeMapper, TechEdgeMapper edgeMapper,
                        StringRedisTemplate redis, ObjectMapper objectMapper,
                        MembershipService membershipService) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.membershipService = membershipService;
    }

    public Tree tree() {
        String cached = redis.opsForValue().get(TREE_CACHE_KEY);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, Tree.class);
            } catch (JsonProcessingException ignored) {
            }
        }
        List<NodeBrief> nodes = nodeMapper.selectList(null).stream()
                .sorted(Comparator.comparing(TechNode::getEraRank).thenComparing(TechNode::getId))
                .map(NodeBrief::from)
                .toList();
        List<EdgeBrief> edges = edgeMapper.selectList(null).stream()
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
        TechNode node = nodeMapper.selectById(id);
        if (node == null) {
            throw new BizException(404, "技术节点不存在");
        }
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
