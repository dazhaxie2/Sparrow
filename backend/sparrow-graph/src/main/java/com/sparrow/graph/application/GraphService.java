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
import com.sparrow.graph.interfaces.dto.GraphDtos.EraBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.IndexableNode;
import com.sparrow.graph.interfaces.dto.GraphDtos.KnowledgeStatus;
import com.sparrow.graph.interfaces.dto.GraphDtos.Neighborhood;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeDetail;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodePage;
import com.sparrow.graph.interfaces.dto.GraphDtos.Overview;
import com.sparrow.graph.interfaces.dto.GraphDtos.OverviewCell;
import com.sparrow.graph.interfaces.dto.GraphDtos.SourceBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.Tile;
import com.sparrow.graph.interfaces.dto.GraphDtos.TileNode;
import com.sparrow.graph.interfaces.dto.GraphDtos.Tree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);
    private static final String TREE_CACHE_KEY = "sparrow:graph:tree";
    private static final String OVERVIEW_CACHE_KEY = "sparrow:graph:overview";
    private static final String SUBGRAPH_CACHE_PREFIX = "sparrow:graph:subgraph:";
    private static final String TILE_CACHE_PREFIX = "sparrow:graph:tile:";
    private static final Duration TREE_CACHE_TTL = Duration.ofHours(1);

    /** 领域轴的稳定列顺序(与种子回填、前端筛选一致);数据中出现的其它领域追加在后。 */
    private static final List<String> CANONICAL_CATEGORIES = List.of(
            "能源动力", "材料冶金", "农业食品", "交通运输", "信息计算", "通信网络", "电气电子",
            "医学生物", "化学化工", "建筑工程", "军事技术", "航天航空", "数学与基础科学", "制造与机械");
    private static final int OVERVIEW_TOP_PER_CELL = 5;

    private final NeoTechNodeRepository neoRepo;
    private final MysqlGraphReader mysqlReader;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserClient userClient;
    private final GraphEventPublisher eventPublisher;
    private final Neo4jMigrator neo4jMigrator;
    private final KnowledgeMetaRepository knowledgeMetaRepository;
    private final com.sparrow.graph.infrastructure.persistence.NodeLayoutMapper layoutMapper;

    public GraphService(NeoTechNodeRepository neoRepo, MysqlGraphReader mysqlReader,
                        StringRedisTemplate redis, ObjectMapper objectMapper,
                        UserClient userClient, GraphEventPublisher eventPublisher,
                        Neo4jMigrator neo4jMigrator,
                        KnowledgeMetaRepository knowledgeMetaRepository,
                        com.sparrow.graph.infrastructure.persistence.NodeLayoutMapper layoutMapper) {
        this.neoRepo = neoRepo;
        this.mysqlReader = mysqlReader;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.userClient = userClient;
        this.eventPublisher = eventPublisher;
        this.neo4jMigrator = neo4jMigrator;
        this.knowledgeMetaRepository = knowledgeMetaRepository;
        this.layoutMapper = layoutMapper;
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

    /**
     * 维基级入口:领域×时代聚合总览(响应体小,可缓存)。返回 byte[]:
     * Redis 缓存 String → getBytes(UTF_8),Controller 用 StreamingResponseBody
     * 直接写入 OutputStream,绕开 Spring 消息转换器,降低高并发下 ~259KB/req 的堆驻留。
     */
    public byte[] overviewBytes() {
        String cached = redis.opsForValue().get(OVERVIEW_CACHE_KEY);
        if (cached != null) {
            return cached.getBytes(StandardCharsets.UTF_8);
        }
        List<NodeBrief> nodes = readWithFallback("overviewNodes",
                () -> neoRepo.findAllOrdered().stream().map(NodeBrief::from).toList(),
                mysqlReader::allOrdered);
        long totalEdges = readWithFallback("overviewEdges",
                neoRepo::countEdges, mysqlReader::countEdges);
        Overview overview = aggregateOverview(nodes, totalEdges);
        String body = toBodyJson(ApiResponse.ok(overview));
        redis.opsForValue().set(OVERVIEW_CACHE_KEY, body, TREE_CACHE_TTL);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private Overview aggregateOverview(List<NodeBrief> nodes, long totalEdges) {
        // 时代:rank -> era 名称(稳定按 rank 升序)
        Map<Integer, String> eraNames = new TreeMap<>();
        // 领域分桶:category -> (eraRank -> 该格节点列表)
        Map<String, Map<Integer, List<NodeBrief>>> buckets = new LinkedHashMap<>();
        for (NodeBrief n : nodes) {
            String cat = n.category() == null ? "未分类" : n.category();
            Integer rank = n.eraRank() == null ? 0 : n.eraRank();
            if (n.eraRank() != null) {
                eraNames.putIfAbsent(rank, n.era());
            }
            buckets.computeIfAbsent(cat, k -> new LinkedHashMap<>())
                    .computeIfAbsent(rank, k -> new ArrayList<>()).add(n);
        }
        // 列顺序:固定 14 类在前,数据里多出来的领域追加在后
        List<String> categories = new ArrayList<>();
        for (String c : CANONICAL_CATEGORIES) {
            if (buckets.containsKey(c)) {
                categories.add(c);
            }
        }
        for (String c : buckets.keySet()) {
            if (!categories.contains(c)) {
                categories.add(c);
            }
        }
        List<EraBrief> eras = eraNames.entrySet().stream()
                .map(e -> new EraBrief(e.getKey(), e.getValue())).toList();
        Comparator<NodeBrief> byImportance = Comparator
                .comparing((NodeBrief n) -> n.importance() == null ? 0 : n.importance())
                .reversed()
                .thenComparing(NodeBrief::id);
        List<OverviewCell> cells = new ArrayList<>();
        for (String cat : categories) {
            for (EraBrief era : eras) {
                List<NodeBrief> cell = buckets.getOrDefault(cat, Map.of())
                        .getOrDefault(era.eraRank(), List.of());
                if (cell.isEmpty()) {
                    continue;
                }
                List<NodeBrief> top = cell.stream().sorted(byImportance)
                        .limit(OVERVIEW_TOP_PER_CELL).toList();
                cells.add(new OverviewCell(cat, era.eraRank(), era.era(), cell.size(), top));
            }
        }
        return new Overview(categories, eras, cells, nodes.size(), totalEdges);
    }

    /** 过滤 + 分页节点列表(MySQL 为权威源,关系型过滤/分页天然合适)。 */
    public NodePage nodes(String category, Integer eraRank, String q,
                          Integer minImportance, int page, int size) {
        return mysqlReader.pageNodes(category, eraRank, q, minImportance, page, size);
    }

    /** 名称/摘要检索。 */
    public List<NodeBrief> search(String q, int limit) {
        return mysqlReader.searchNodes(q, limit);
    }

    /**
     * 有界子图:返回 byte[]。同 overviewBytes(),~380KB/req。
     * 自由文本检索(q)结果发散不缓存;其余为有限组合,Redis 缓存命中绕开 MySQL。
     */
    public byte[] subgraphBytes(String category, Integer eraRank, String q,
                                Integer minImportance, int limit) {
        String cacheKey = (q == null || q.isBlank())
                ? subgraphCacheKey(category, eraRank, minImportance, limit) : null;
        if (cacheKey != null) {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached.getBytes(StandardCharsets.UTF_8);
            }
        }
        Tree tree = mysqlReader.subgraph(category, eraRank, q, minImportance, limit);
        String body = toBodyJson(ApiResponse.ok(tree));
        if (cacheKey != null) {
            redis.opsForValue().set(cacheKey, body, TREE_CACHE_TTL);
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * LOD 瓦片:返回 byte[](流式字节回写)。
     * level 0 = 顶层簇全景(忽略 clusterId,返回所有簇心点,无边);
     * level ≥ 1 = 指定簇内节点坐标 + 簇内边。
     * 组合有限,Redis 缓存命中绕开数据库。
     */
    public byte[] tileBytes(int level, long clusterId) {
        int lvl = Math.max(0, Math.min(level, 3));
        String cacheKey = TILE_CACHE_PREFIX + lvl + ":" + clusterId;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached.getBytes(StandardCharsets.UTF_8);
        }
        Tile tile = (lvl == 0) ? buildTopLevelTile() : buildClusterTile(lvl, clusterId);
        String body = toBodyJson(ApiResponse.ok(tile));
        redis.opsForValue().set(cacheKey, body, TREE_CACHE_TTL);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** 顶层全景:所有簇代表点(level 0),远观视图 —— 几十个簇心点,无边。 */
    private Tile buildTopLevelTile() {
        List<TileNode> nodes = layoutMapper.topLevelNodes().stream()
                .map(row -> new TileNode(
                        ((Number) row.get("id")).longValue(),
                        ((Number) row.get("clusterId")).longValue(),
                        ((Number) row.get("x")).doubleValue(),
                        ((Number) row.get("y")).doubleValue(),
                        (String) row.get("name"),
                        (String) row.get("category"),
                        row.get("importance") == null ? null : ((Number) row.get("importance")).intValue()))
                .toList();
        return new Tile(0, 0L, nodes, List.of());
    }

    /** 簇内瓦片:指定 level + clusterId 的节点坐标 + 簇内边。 */
    private Tile buildClusterTile(int level, long clusterId) {
        List<TileNode> nodes = layoutMapper.tileNodes(level, clusterId).stream()
                .map(row -> new TileNode(
                        ((Number) row.get("id")).longValue(),
                        ((Number) row.get("clusterId")).longValue(),
                        ((Number) row.get("x")).doubleValue(),
                        ((Number) row.get("y")).doubleValue(),
                        (String) row.get("name"),
                        (String) row.get("category"),
                        row.get("importance") == null ? null : ((Number) row.get("importance")).intValue()))
                .toList();
        List<EdgeBrief> edges = layoutMapper.tileEdges(level, clusterId).stream()
                .map(row -> new EdgeBrief(
                        ((Number) row.get("from")).longValue(),
                        ((Number) row.get("to")).longValue()))
                .toList();
        return new Tile(level, clusterId, nodes, edges);
    }

    private String subgraphCacheKey(String category, Integer eraRank, Integer minImportance, int limit) {
        return SUBGRAPH_CACHE_PREFIX
                + (category == null ? "_" : category) + ":"
                + (eraRank == null ? "_" : eraRank) + ":"
                + (minImportance == null ? "_" : minImportance) + ":"
                + limit;
    }

    /** 将响应对象序列化为完整响应体 JSON(缓存原始字节用)。 */
    private String toBodyJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /** 节点邻域子图(中心 + 直接前置 + 直接后继),前端展开式浏览用。 */
    public Neighborhood neighborhood(Long id) {
        return readWithFallback("neighborhood",
                () -> {
                    NeoTechNode node = neoRepo.findByNodeId(id)
                            .orElseThrow(() -> new BizException(404, "技术节点不存在"));
                    NodeBrief center = NodeBrief.from(node);
                    List<NodeBrief> prereqs = neoRepo.findDirectPrerequisites(id).stream()
                            .map(NodeBrief::from).toList();
                    List<NodeBrief> unlocks = neoRepo.findDirectUnlocks(id).stream()
                            .map(NodeBrief::from).toList();
                    return buildNeighborhood(center, prereqs, unlocks);
                },
                () -> {
                    TechNode node = mysqlReader.findNode(id);
                    if (node == null) {
                        throw new BizException(404, "技术节点不存在");
                    }
                    return buildNeighborhood(NodeBrief.from(node),
                            mysqlReader.directPrerequisites(id), mysqlReader.directUnlocks(id));
                });
    }

    private Neighborhood buildNeighborhood(NodeBrief center, List<NodeBrief> prereqs,
                                           List<NodeBrief> unlocks) {
        Map<Long, NodeBrief> nodes = new LinkedHashMap<>();
        nodes.put(center.id(), center);
        List<EdgeBrief> edges = new ArrayList<>();
        for (NodeBrief p : prereqs) {
            nodes.putIfAbsent(p.id(), p);
            edges.add(new EdgeBrief(p.id(), center.id())); // 前置 -> 中心(中心 REQUIRES 前置)
        }
        for (NodeBrief u : unlocks) {
            nodes.putIfAbsent(u.id(), u);
            edges.add(new EdgeBrief(center.id(), u.id())); // 中心 -> 后继(后继 REQUIRES 中心)
        }
        return new Neighborhood(center, new ArrayList<>(nodes.values()), edges);
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
                            Boolean.TRUE.equals(node.getPremium()), node.getCategory(), node.getImportance(),
                            prerequisites, unlocks, userId);
                },
                () -> {
                    TechNode node = mysqlReader.findNode(id);
                    if (node == null) {
                        throw new BizException(404, "技术节点不存在");
                    }
                    return buildDetail(node.getId(), node.getCode(), node.getName(), node.getEra(),
                            node.getEraRank(), node.getYearLabel(), node.getSummary(), node.getDetail(),
                            Boolean.TRUE.equals(node.getPremium()), node.getCategory(), node.getImportance(),
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
                                n.getYearLabel(), n.getSummary(), n.getDetail(),
                                n.getCategory(), n.getImportance()))
                        .toList(),
                mysqlReader::indexableNodes);
    }

    public GraphChangedEvent requestReindex() {
        redis.delete(List.of(TREE_CACHE_KEY, OVERVIEW_CACHE_KEY));
        Set<String> subgraphKeys = redis.keys(SUBGRAPH_CACHE_PREFIX + "*");
        if (subgraphKeys != null && !subgraphKeys.isEmpty()) {
            redis.delete(subgraphKeys);
        }
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
                                   String category, Integer importance,
                                   List<NodeBrief> prerequisites, List<NodeBrief> unlocks, Long userId) {
        boolean locked = premium && (userId == null || !isMember(userId));
        List<SourceBrief> sources = loadSources(code);
        return new NodeDetail(id, code, name, era, eraRank, yearLabel, summary,
                locked ? null : detail, premium, locked, prerequisites, unlocks, sources,
                category, importance);
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
