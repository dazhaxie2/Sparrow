package com.sparrow.graph.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.common.exception.BizException;
import com.sparrow.graph.domain.model.NeoTechNode;
import com.sparrow.graph.domain.model.TechNode;
import com.sparrow.graph.infrastructure.client.UserClient;
import com.sparrow.graph.infrastructure.event.GraphEventPublisher;
import com.sparrow.graph.infrastructure.neo4j.Neo4jMigrator;
import com.sparrow.graph.infrastructure.neo4j.NeoTechNodeRepository;
import com.sparrow.graph.infrastructure.persistence.KnowledgeMetaRepository;
import com.sparrow.graph.infrastructure.persistence.MysqlGraphReader;
import com.sparrow.graph.interfaces.dto.GraphDtos.EdgeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.IndexableNode;
import com.sparrow.graph.interfaces.dto.GraphDtos.KnowledgeStatus;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeDetail;
import com.sparrow.graph.interfaces.dto.GraphDtos.SourceBrief;
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
import java.util.function.Supplier;

@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);
    private static final String TREE_CACHE_KEY = "sparrow:graph:tree";
    private static final Duration TREE_CACHE_TTL = Duration.ofHours(1);

    private final NeoTechNodeRepository neoRepo;
    private final MysqlGraphReader mysqlReader;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserClient userClient;
    private final GraphEventPublisher eventPublisher;
    private final Neo4jMigrator neo4jMigrator;
    private final KnowledgeMetaRepository knowledgeMetaRepository;

    public GraphService(NeoTechNodeRepository neoRepo, MysqlGraphReader mysqlReader,
                        StringRedisTemplate redis, ObjectMapper objectMapper,
                        UserClient userClient, GraphEventPublisher eventPublisher,
                        Neo4jMigrator neo4jMigrator,
                        KnowledgeMetaRepository knowledgeMetaRepository) {
        this.neoRepo = neoRepo;
        this.mysqlReader = mysqlReader;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.userClient = userClient;
        this.eventPublisher = eventPublisher;
        this.neo4jMigrator = neo4jMigrator;
        this.knowledgeMetaRepository = knowledgeMetaRepository;
    }

    public Tree tree() {
        String cached = redis.opsForValue().get(TREE_CACHE_KEY);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, Tree.class);
            } catch (JsonProcessingException ignored) {
            }
        }
        Tree tree = readWithFallback("tree",
                () -> {
                    List<NodeBrief> nodes = neoRepo.findAllOrdered().stream().map(NodeBrief::from).toList();
                    List<EdgeBrief> edges = neoRepo.findAllEdges().stream()
                            .map(e -> new EdgeBrief(e.fromId(), e.toId())).toList();
                    return new Tree(nodes, edges);
                },
                () -> new Tree(mysqlReader.allOrdered(), mysqlReader.allEdges()));
        try {
            redis.opsForValue().set(TREE_CACHE_KEY, objectMapper.writeValueAsString(tree), TREE_CACHE_TTL);
        } catch (JsonProcessingException ignored) {
        }
        return tree;
    }

    public NodeDetail nodeDetail(Long id, Long userId) {
        return readWithFallback("nodeDetail",
                () -> {
                    NeoTechNode node = neoRepo.findByNodeId(id)
                            .orElseThrow(() -> new BizException(404, "技术节点不存在"));
                    List<NodeBrief> prerequisites = neoRepo.findDirectPrerequisites(id).stream()
                            .map(NodeBrief::from).toList();
                    List<NodeBrief> unlocks = neoRepo.findDirectUnlocks(id).stream()
                            .map(NodeBrief::from).toList();
                    return buildDetail(node.getId(), node.getCode(), node.getName(), node.getEra(),
                            node.getEraRank(), node.getYearLabel(), node.getSummary(), node.getDetail(),
                            Boolean.TRUE.equals(node.getPremium()), prerequisites, unlocks, userId);
                },
                () -> {
                    TechNode node = mysqlReader.findNode(id);
                    if (node == null) {
                        throw new BizException(404, "技术节点不存在");
                    }
                    return buildDetail(node.getId(), node.getCode(), node.getName(), node.getEra(),
                            node.getEraRank(), node.getYearLabel(), node.getSummary(), node.getDetail(),
                            Boolean.TRUE.equals(node.getPremium()),
                            mysqlReader.directPrerequisites(id), mysqlReader.directUnlocks(id), userId);
                });
    }

    public List<NodeBrief> prerequisiteChain(Long id) {
        return readWithFallback("prerequisiteChain",
                () -> {
                    if (!neoRepo.existsByNodeId(id)) {
                        throw new BizException(404, "技术节点不存在");
                    }
                    return neoRepo.findAllPrerequisites(id).stream().map(NodeBrief::from).toList();
                },
                () -> mysqlReader.allPrerequisites(id));
    }

    public List<IndexableNode> listIndexableNodes() {
        return readWithFallback("listIndexableNodes",
                () -> neoRepo.findAllOrdered().stream()
                        .map(n -> new IndexableNode(n.getId(), n.getCode(), n.getName(), n.getEra(),
                                n.getYearLabel(), n.getSummary(), n.getDetail()))
                        .toList(),
                mysqlReader::indexableNodes);
    }

    public GraphChangedEvent requestReindex() {
        redis.delete(TREE_CACHE_KEY);
        int nodeCount = readWithFallback("countAll",
                () -> Math.toIntExact(neoRepo.countAll()), mysqlReader::count);
        GraphChangedEvent event = new GraphChangedEvent(
                UUID.randomUUID().toString(),
                GraphChangedEvent.TYPE_REINDEX,
                nodeCount,
                Instant.now()
        );
        eventPublisher.publish(event);
        return event;
    }

    public GraphChangedEvent importFromMysqlAndReindex() {
        neo4jMigrator.importFromMysql();
        return requestReindex();
    }

    public KnowledgeStatus knowledgeStatus() {
        return knowledgeMetaRepository.status();
    }

    /**
     * Neo4j 读优先,连接/查询异常时降级 MySQL 邻接表读。
     * 业务异常(如 404)直接透传,不触发降级。
     */
    private <T> T readWithFallback(String op, Supplier<T> primary, Supplier<T> fallback) {
        try {
            return primary.get();
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Neo4j 读取失败,降级 MySQL 邻接表 [{}]: {}", op, e.toString());
            return fallback.get();
        }
    }

    private NodeDetail buildDetail(Long id, String code, String name, String era, Integer eraRank,
                                   String yearLabel, String summary, String detail, boolean premium,
                                   List<NodeBrief> prerequisites, List<NodeBrief> unlocks, Long userId) {
        boolean locked = premium && (userId == null || !isMember(userId));
        List<SourceBrief> sources = loadSources(code);
        return new NodeDetail(id, code, name, era, eraRank, yearLabel, summary,
                locked ? null : detail, premium, locked, prerequisites, unlocks, sources);
    }

    private List<SourceBrief> loadSources(String code) {
        try {
            return knowledgeMetaRepository.sourcesForCode(code);
        } catch (Exception e) {
            log.warn("璧勬枡鏉ユ簮璇诲彇澶辫触,code={} err={}", code, e.getMessage());
            return List.of();
        }
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
