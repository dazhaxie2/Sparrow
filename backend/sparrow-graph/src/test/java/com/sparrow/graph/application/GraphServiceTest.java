package com.sparrow.graph.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.common.exception.BizException;
import com.sparrow.graph.domain.model.NeoTechNode;
import com.sparrow.graph.domain.model.TechNode;
import com.sparrow.graph.infrastructure.client.AiClient;
import com.sparrow.graph.infrastructure.client.UserClient;
import com.sparrow.graph.infrastructure.event.GraphEventPublisher;
import com.sparrow.graph.infrastructure.neo4j.Neo4jMigrator;
import com.sparrow.graph.infrastructure.neo4j.NeoEdgeRecord;
import com.sparrow.graph.infrastructure.neo4j.NeoTechNodeRepository;
import com.sparrow.graph.infrastructure.persistence.KnowledgeMetaRepository;
import com.sparrow.graph.infrastructure.persistence.MysqlGraphReader;
import com.sparrow.graph.infrastructure.persistence.NodeApplicationRepository;
import com.sparrow.graph.infrastructure.persistence.NodeLayoutMapper;
import com.sparrow.graph.interfaces.dto.GraphDtos.EdgeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeDetail;
import com.sparrow.graph.interfaces.dto.GraphDtos.SourceBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.Tree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphServiceTest {

    private NeoTechNodeRepository neoRepo;
    private MysqlGraphReader mysqlReader;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private UserClient userClient;
    private GraphEventPublisher eventPublisher;
    private Neo4jMigrator neo4jMigrator;
    private KnowledgeMetaRepository knowledgeMetaRepository;
    private NodeLayoutMapper nodeLayoutMapper;
    private NodeApplicationRepository applicationRepository;
    private AiClient aiClient;
    private FavoriteService favoriteService;
    private GraphService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        neoRepo = mock(NeoTechNodeRepository.class);
        mysqlReader = mock(MysqlGraphReader.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        userClient = mock(UserClient.class);
        eventPublisher = mock(GraphEventPublisher.class);
        neo4jMigrator = mock(Neo4jMigrator.class);
        knowledgeMetaRepository = mock(KnowledgeMetaRepository.class);
        nodeLayoutMapper = mock(NodeLayoutMapper.class);
        applicationRepository = mock(NodeApplicationRepository.class);
        aiClient = mock(AiClient.class);
        favoriteService = mock(FavoriteService.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new GraphService(neoRepo, mysqlReader, redis, new ObjectMapper(),
                userClient, eventPublisher, neo4jMigrator, knowledgeMetaRepository, nodeLayoutMapper,
                applicationRepository, aiClient, favoriteService);
    }

    @Test
    void treeReadsFromNeoAndCachesOnMiss() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(neoRepo.findAllOrdered()).thenReturn(List.of(
                neoNode(1L, "fire", "火", false, "detail"),
                neoNode(2L, "tool", "石器", false, "detail")));
        when(neoRepo.findAllEdges()).thenReturn(List.of(new NeoEdgeRecord(1L, 2L)));

        Tree tree = service.tree();

        assertEquals(2, tree.nodes().size());
        assertEquals(1, tree.edges().size());
        verify(valueOps).set(anyString(), anyString(), any());
        verify(mysqlReader, never()).allOrdered();
    }

    @Test
    void treeReadsFromCacheWhenHit() throws Exception {
        Tree cached = new Tree(List.of(), List.of());
        when(valueOps.get(anyString())).thenReturn(new ObjectMapper().writeValueAsString(cached));

        Tree tree = service.tree();

        assertEquals(0, tree.nodes().size());
        verify(neoRepo, never()).findAllOrdered();
    }

    @Test
    void nodeDetailLocksPremiumForNonMember() {
        when(neoRepo.findByNodeId(41L)).thenReturn(Optional.of(
                neoNode(41L, "steam_engine", "蒸汽机", true, "机密详情")));
        when(neoRepo.findDirectPrerequisites(41L)).thenReturn(List.of());
        when(neoRepo.findDirectUnlocks(41L)).thenReturn(List.of());

        NodeDetail detail = service.nodeDetail(41L, null);

        assertTrue(detail.locked());
        assertNull(detail.detail());
    }

    @Test
    void nodeDetailUnlocksPremiumForMember() {
        when(neoRepo.findByNodeId(41L)).thenReturn(Optional.of(
                neoNode(41L, "steam_engine", "蒸汽机", true, "机密详情")));
        when(neoRepo.findDirectPrerequisites(41L)).thenReturn(List.of());
        when(neoRepo.findDirectUnlocks(41L)).thenReturn(List.of());
        when(knowledgeMetaRepository.sourcesForCode("steam_engine"))
                .thenReturn(List.of(new SourceBrief("Wikipedia", "https://example.com", "2026-06-13 10:00:00")));
        Map<String, Object> membership = new HashMap<>();
        membership.put("member", true);
        when(userClient.membership(eq(42L))).thenReturn(ApiResponse.ok(membership));

        NodeDetail detail = service.nodeDetail(41L, 42L);

        assertFalse(detail.locked());
        assertEquals("机密详情", detail.detail());
        assertEquals(1, detail.sources().size());
    }

    @Test
    void nodeDetailThrowsWhenMissing() {
        when(neoRepo.findByNodeId(999L)).thenReturn(Optional.empty());
        when(mysqlReader.findNode(999L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.nodeDetail(999L, null));
        verify(mysqlReader).findNode(999L);
    }

    @Test
    void prerequisiteChainThrowsWhenMissing() {
        when(neoRepo.existsByNodeId(999L)).thenReturn(false);
        when(mysqlReader.findNode(999L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.prerequisiteChain(999L));
        verify(mysqlReader).findNode(999L);
    }

    @Test
    void requestReindexEvictsCacheAndPublishesEvent() {
        when(neoRepo.countAll()).thenReturn(77L);

        GraphChangedEvent event = service.requestReindex();

        verify(redis).delete(anyCollection());
        verify(eventPublisher).publish(any(GraphChangedEvent.class));
        assertEquals(77, event.nodeCount());
        assertEquals(GraphChangedEvent.TYPE_REINDEX, event.changeType());
    }

    @Test
    void importFromMysqlRebuildsNeo4jThenPublishesEvent() {
        when(neoRepo.countAll()).thenReturn(88L);

        GraphChangedEvent event = service.importFromMysqlAndReindex();

        verify(neo4jMigrator).importFromMysql();
        verify(redis).delete(anyCollection());
        verify(eventPublisher).publish(any(GraphChangedEvent.class));
        assertEquals(88, event.nodeCount());
    }

    // ===== Neo4j 不可达降级到 MySQL =====

    @Test
    void topLevelTileKeepsClusterIdentityAndDisplayName() throws Exception {
        when(nodeLayoutMapper.topLevelNodes()).thenReturn(List.of(Map.of(
                "id", 41L,
                "clusterId", 7L,
                "x", 12.5,
                "y", -3.25,
                "name", "Steam engine",
                "category", "Energy",
                "importance", 96)));

        byte[] body = service.tileBytes(0, 999L);
        var json = new ObjectMapper().readTree(body);
        var node = json.path("data").path("nodes").get(0);

        assertEquals(0, json.path("data").path("level").asInt());
        assertEquals(7L, node.path("clusterId").asLong());
        assertEquals("Steam engine", node.path("name").asText());
        verify(nodeLayoutMapper).topLevelNodes();
    }

    @Test
    void clusterOverviewIncludesMemberCounts() throws Exception {
        when(nodeLayoutMapper.clusterSummaries()).thenReturn(List.of(Map.of(
                "id", 41L,
                "clusterId", 7L,
                "x", 12.5,
                "y", -3.25,
                "name", "Steam engine",
                "category", "Energy",
                "importance", 96,
                "nodeCount", 320L)));

        byte[] body = service.clusterOverviewBytes();
        var json = new ObjectMapper().readTree(body);
        var data = json.path("data");

        assertEquals(320L, data.path("representedNodes").asLong());
        assertEquals(320L, data.path("clusters").get(0).path("nodeCount").asLong());
        assertEquals(7L, data.path("clusters").get(0).path("clusterId").asLong());
    }

    @Test
    void overviewUsesMysqlAsAuthoritativeMillionScaleCount() throws Exception {
        when(neoRepo.findAllOrdered()).thenReturn(List.of(
                neoNode(1L, "fire", "Fire", false, "detail")));
        when(mysqlReader.count()).thenReturn(860_935);
        when(mysqlReader.countEdges()).thenReturn(10_097_645L);

        byte[] body = service.overviewBytes();
        var json = new ObjectMapper().readTree(body);

        assertEquals(860_935L, json.path("data").path("totalNodes").asLong());
        assertEquals(10_097_645L, json.path("data").path("totalEdges").asLong());
    }

    @Test
    void clusterTileReturnsNodesAndInternalEdges() throws Exception {
        when(nodeLayoutMapper.tileNodes(3, 7L)).thenReturn(List.of(Map.of(
                "id", 41L,
                "clusterId", 7L,
                "x", 12.5,
                "y", -3.25,
                "name", "Steam engine",
                "category", "Energy",
                "importance", 96)));
        when(nodeLayoutMapper.tileEdges(3, 7L)).thenReturn(List.of(Map.of(
                "from", 40L,
                "to", 41L,
                "relation", 1)));

        byte[] body = service.tileBytes(3, 7L);
        var json = new ObjectMapper().readTree(body);

        assertEquals(7L, json.path("data").path("clusterId").asLong());
        assertEquals(1, json.path("data").path("nodes").size());
        var edge = json.path("data").path("edges").get(0);
        assertEquals(41L, edge.path("to").asLong());
        // relation 透传 + label 由类型推导(1=结构/分类归属)
        assertEquals(1, edge.path("relation").asInt());
        assertEquals("结构", edge.path("label").asText());
    }

    @Test
    void treeFallsBackToMysqlWhenNeo4jDown() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(neoRepo.findAllOrdered()).thenThrow(new RuntimeException("neo4j unavailable"));
        when(mysqlReader.allOrdered()).thenReturn(List.of(
                new NodeBrief(1L, "fire", "火", "石器时代", 1, "约公元前50万年", "摘要", false, "能源动力", 100)));
        when(mysqlReader.allEdges()).thenReturn(List.of(new EdgeBrief(1L, 2L)));

        Tree tree = service.tree();

        assertEquals(1, tree.nodes().size());
        assertEquals(1, tree.edges().size());
        verify(mysqlReader).allOrdered();
        verify(mysqlReader).allEdges();
    }

    @Test
    void prerequisiteChainFallsBackToMysqlWhenNeo4jDown() {
        when(neoRepo.existsByNodeId(41L)).thenThrow(new RuntimeException("neo4j unavailable"));
        when(mysqlReader.findNode(41L)).thenReturn(mock(TechNode.class));
        when(mysqlReader.allPrerequisites(41L)).thenReturn(List.of(
                new NodeBrief(1L, "fire", "火", "石器时代", 1, "约公元前50万年", "摘要", false, "能源动力", 100)));

        List<NodeBrief> chain = service.prerequisiteChain(41L);

        assertEquals(1, chain.size());
        verify(mysqlReader).allPrerequisites(41L);
    }

    @Test
    void nodeDetailFallsBackToMysqlWhenNeo4jDown() {
        when(neoRepo.findByNodeId(41L)).thenThrow(new RuntimeException("neo4j unavailable"));
        TechNode mn = mock(TechNode.class);
        when(mn.getId()).thenReturn(41L);
        when(mn.getCode()).thenReturn("steam_engine");
        when(mn.getName()).thenReturn("蒸汽机");
        when(mn.getEra()).thenReturn("工业时代");
        when(mn.getEraRank()).thenReturn(7);
        when(mn.getYearLabel()).thenReturn("1769");
        when(mn.getSummary()).thenReturn("摘要");
        when(mn.getDetail()).thenReturn("机密详情");
        when(mn.getPremium()).thenReturn(true);
        when(mysqlReader.findNode(41L)).thenReturn(mn);
        when(mysqlReader.directPrerequisites(41L)).thenReturn(List.of());
        when(mysqlReader.directUnlocks(41L)).thenReturn(List.of());

        NodeDetail detail = service.nodeDetail(41L, null);

        assertTrue(detail.locked());
        assertNull(detail.detail());
        verify(mysqlReader).findNode(41L);
    }

    @Test
    void missingNeoNodeChecksAuthoritativeMysqlBeforeReturningNotFound() {
        when(neoRepo.findByNodeId(999L)).thenReturn(Optional.empty());
        when(mysqlReader.findNode(999L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.nodeDetail(999L, null));
        verify(mysqlReader).findNode(999L);
    }

    @Test
    void applicationsReturnsCachedWithoutCallingAi() {
        TechNode node = materialNode(31401L, "二氧化硅");
        when(mysqlReader.findNode(31401L)).thenReturn(node);
        when(applicationRepository.findAppIds(31401L)).thenReturn(List.of(1078L, 2051L));
        when(mysqlReader.briefsForIds(List.of(1078L, 2051L))).thenReturn(List.of(
                new NodeBrief(1078L, "glass", "玻璃", "古代", 1, "", "", false, "材料冶金", 90)));

        List<NodeBrief> apps = service.applicationsOf(31401L);

        assertEquals(1, apps.size());
        assertEquals("玻璃", apps.get(0).name());
        verify(aiClient, never()).classifyApplications(any());
    }

    @Test
    void applicationsClassifiesViaAiAndCachesWhenCacheMisses() {
        TechNode node = materialNode(31401L, "二氧化硅");
        when(mysqlReader.findNode(31401L)).thenReturn(node);
        when(applicationRepository.findAppIds(31401L)).thenReturn(List.of());
        List<NodeBrief> neighbors = List.of(
                new NodeBrief(1078L, "glass", "玻璃", "古代", 1, "", "二氧化硅为主要成分", false, "材料冶金", 90),
                new NodeBrief(30099L, "insulator", "绝缘体", "现代", 5, "", "电学属性", false, "电气电子", 20));
        when(mysqlReader.directPrerequisites(31401L)).thenReturn(List.of());
        when(mysqlReader.directUnlocks(31401L)).thenReturn(neighbors);
        when(aiClient.classifyApplications(any())).thenReturn(
                ApiResponse.ok(List.of(1078L)));
        when(mysqlReader.briefsForIds(List.of(1078L))).thenReturn(List.of(
                new NodeBrief(1078L, "glass", "玻璃", "古代", 1, "", "二氧化硅为主要成分", false, "材料冶金", 90)));

        List<NodeBrief> apps = service.applicationsOf(31401L);

        assertEquals(1, apps.size());
        assertEquals("玻璃", apps.get(0).name());
        verify(applicationRepository).saveAll(31401L, List.of(1078L));
    }

    @Test
    void applicationsSkipsNonMaterialCategory() {
        TechNode node = categoryNode(36L, "透视法与解剖学", "医学生物");
        when(mysqlReader.findNode(36L)).thenReturn(node);

        List<NodeBrief> apps = service.applicationsOf(36L);

        assertTrue(apps.isEmpty());
        verify(applicationRepository, never()).findAppIds(any());
        verify(aiClient, never()).classifyApplications(any());
    }

    @Test
    void applicationsDegradesToEmptyWhenAiThrows() {
        TechNode node = materialNode(31401L, "二氧化硅");
        when(mysqlReader.findNode(31401L)).thenReturn(node);
        when(applicationRepository.findAppIds(31401L)).thenReturn(List.of());
        when(mysqlReader.directPrerequisites(31401L)).thenReturn(List.of());
        when(mysqlReader.directUnlocks(31401L)).thenReturn(List.of(
                new NodeBrief(1078L, "glass", "玻璃", "古代", 1, "", "", false, "材料冶金", 90)));
        when(aiClient.classifyApplications(any())).thenThrow(new RuntimeException("ai unavailable"));
        when(mysqlReader.briefsForIds(List.of())).thenReturn(List.of());

        List<NodeBrief> apps = service.applicationsOf(31401L);

        assertTrue(apps.isEmpty());
        // AI 降级为空列表后仍会写入缓存(空结果也缓存,避免反复重试)。
        verify(applicationRepository).saveAll(eq(31401L), eq(List.of()));
    }

    /** 构造一个已 stub 了 id/name/category 的 TechNode mock(在 when() 外独立 stub,避免嵌套)。 */
    private TechNode materialNode(long id, String name) {
        return categoryNode(id, name, "材料冶金");
    }

    private TechNode categoryNode(long id, String name, String category) {
        // TechNode 无 setter,用 mock stub 其 getter;mock 必须在 when().thenReturn() 之外完成 stub。
        TechNode n = mock(TechNode.class);
        when(n.getId()).thenReturn(id);
        when(n.getName()).thenReturn(name);
        when(n.getCategory()).thenReturn(category);
        return n;
    }

    private NeoTechNode neoNode(long id, String code, String name, boolean premium, String detail) {
        NeoTechNode n = new NeoTechNode();
        n.setId(id);
        n.setCode(code);
        n.setName(name);
        n.setEra("现代");
        n.setEraRank(5);
        n.setYearLabel("1900");
        n.setSummary("摘要");
        n.setDetail(detail);
        n.setPremium(premium);
        return n;
    }
}
