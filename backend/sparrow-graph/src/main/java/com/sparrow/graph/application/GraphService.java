package com.sparrow.graph.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.common.exception.BizException;
import com.sparrow.graph.domain.model.TechEdge;
import com.sparrow.graph.domain.model.TechNode;
import com.sparrow.graph.infrastructure.client.UserClient;
import com.sparrow.graph.infrastructure.event.GraphEventPublisher;
import com.sparrow.graph.infrastructure.persistence.TechEdgeMapper;
import com.sparrow.graph.infrastructure.persistence.TechNodeMapper;
import com.sparrow.graph.interfaces.dto.GraphDtos.EdgeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.IndexableNode;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeDetail;
import com.sparrow.graph.interfaces.dto.GraphDtos.Tree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
import java.util.UUID;

@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);
    private static final String TREE_CACHE_KEY = "sparrow:graph:tree";
    private static final Duration TREE_CACHE_TTL = Duration.ofHours(1);

    private final TechNodeMapper nodeMapper;
    private final TechEdgeMapper edgeMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserClient userClient;
    private final GraphEventPublisher eventPublisher;

    public GraphService(TechNodeMapper nodeMapper, TechEdgeMapper edgeMapper,
                        StringRedisTemplate redis, ObjectMapper objectMapper,
                        UserClient userClient, GraphEventPublisher eventPublisher) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.userClient = userClient;
        this.eventPublisher = eventPublisher;
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
        boolean locked = isPremium && (userId == null || !isMember(userId));
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

    public List<IndexableNode> listIndexableNodes() {
        return nodeMapper.selectList(null).stream()
                .sorted(Comparator.comparing(TechNode::getEraRank).thenComparing(TechNode::getId))
                .map(n -> new IndexableNode(n.getId(), n.getCode(), n.getName(), n.getEra(),
                        n.getYearLabel(), n.getSummary(), n.getDetail()))
                .toList();
    }

    public GraphChangedEvent requestReindex() {
        redis.delete(TREE_CACHE_KEY);
        int nodeCount = Math.toIntExact(nodeMapper.selectCount(null));
        GraphChangedEvent event = new GraphChangedEvent(
                UUID.randomUUID().toString(),
                GraphChangedEvent.TYPE_REINDEX,
                nodeCount,
                Instant.now()
        );
        eventPublisher.publish(event);
        return event;
    }

    private boolean isMember(Long userId) {
        try {
            ApiResponse<Map<String, Object>> resp = userClient.membership(userId);
            return resp != null && resp.data() != null
                    && Boolean.TRUE.equals(resp.data().get("member"));
        } catch (Exception e) {
            log.warn("会员校验失败,按非会员处理: userId={} err={}", userId, e.getMessage());
            return false;
        }
    }
}
