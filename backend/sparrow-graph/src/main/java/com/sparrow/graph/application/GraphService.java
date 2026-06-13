package com.sparrow.graph.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.common.exception.BizException;
import com.sparrow.graph.domain.model.NeoTechNode;
import com.sparrow.graph.infrastructure.client.UserClient;
import com.sparrow.graph.infrastructure.event.GraphEventPublisher;
import com.sparrow.graph.infrastructure.neo4j.NeoEdgeRecord;
import com.sparrow.graph.infrastructure.neo4j.NeoTechNodeRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);
    private static final String TREE_CACHE_KEY = "sparrow:graph:tree";
    private static final Duration TREE_CACHE_TTL = Duration.ofHours(1);

    private final NeoTechNodeRepository neoRepo;
    private final TechNodeMapper nodeMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserClient userClient;
    private final GraphEventPublisher eventPublisher;

    public GraphService(NeoTechNodeRepository neoRepo, TechNodeMapper nodeMapper,
                        StringRedisTemplate redis, ObjectMapper objectMapper,
                        UserClient userClient, GraphEventPublisher eventPublisher) {
        this.neoRepo = neoRepo;
        this.nodeMapper = nodeMapper;
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
        List<NodeBrief> nodes = neoRepo.findAllOrdered().stream()
                .map(NodeBrief::from)
                .toList();
        List<EdgeBrief> edges = neoRepo.findAllEdges().stream()
                .map(e -> new EdgeBrief(e.fromId(), e.toId()))
                .toList();
        Tree tree = new Tree(nodes, edges);
        try {
            redis.opsForValue().set(TREE_CACHE_KEY, objectMapper.writeValueAsString(tree), TREE_CACHE_TTL);
        } catch (JsonProcessingException ignored) {
        }
        return tree;
    }

    public NodeDetail nodeDetail(Long id, Long userId) {
        NeoTechNode node = neoRepo.findByNodeId(id).orElse(null);
        if (node == null) {
            throw new BizException(404, "技术节点不存在");
        }
        List<NodeBrief> prerequisites = neoRepo.findDirectPrerequisites(id).stream()
                .map(NodeBrief::from).toList();
        List<NodeBrief> unlocks = neoRepo.findDirectUnlocks(id).stream()
                .map(NodeBrief::from).toList();

        boolean isPremium = Boolean.TRUE.equals(node.getPremium());
        boolean locked = isPremium && (userId == null || !isMember(userId));
        return new NodeDetail(node.getId(), node.getCode(), node.getName(), node.getEra(),
                node.getEraRank(), node.getYearLabel(), node.getSummary(),
                locked ? null : node.getDetail(), isPremium, locked, prerequisites, unlocks);
    }

    public List<NodeBrief> prerequisiteChain(Long id) {
        if (!neoRepo.existsByNodeId(id)) {
            throw new BizException(404, "技术节点不存在");
        }
        return neoRepo.findAllPrerequisites(id).stream()
                .map(NodeBrief::from)
                .toList();
    }

    public List<IndexableNode> listIndexableNodes() {
        return neoRepo.findAllOrdered().stream()
                .map(n -> new IndexableNode(n.getId(), n.getCode(), n.getName(), n.getEra(),
                        n.getYearLabel(), n.getSummary(), n.getDetail()))
                .toList();
    }

    public GraphChangedEvent requestReindex() {
        redis.delete(TREE_CACHE_KEY);
        int nodeCount = Math.toIntExact(neoRepo.countAll());
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
