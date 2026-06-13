package com.sparrow.graph.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.common.exception.BizException;
import com.sparrow.graph.domain.model.NeoTechNode;
import com.sparrow.graph.domain.model.TechNode;
import com.sparrow.graph.infrastructure.client.UserClient;
import com.sparrow.graph.infrastructure.event.GraphEventPublisher;
import com.sparrow.graph.infrastructure.neo4j.NeoEdgeRecord;
import com.sparrow.graph.infrastructure.neo4j.NeoTechNodeRepository;
import com.sparrow.graph.infrastructure.persistence.MysqlGraphReader;
import com.sparrow.graph.interfaces.dto.GraphDtos.EdgeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeDetail;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GraphServiceTest {

    private NeoTechNodeRepository neoRepo;
    private MysqlGraphReader mysqlReader;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private UserClient userClient;
    private GraphEventPublisher eventPublisher;
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
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new GraphService(neoRepo, mysqlReader, redis, new ObjectMapper(),
                userClient, eventPublisher);
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
        Map<String, Object> membership = new HashMap<>();
        membership.put("member", true);
        when(userClient.membership(eq(42L))).thenReturn(ApiResponse.ok(membership));

        NodeDetail detail = service.nodeDetail(41L, 42L);

        assertFalse(detail.locked());
        assertEquals("机密详情", detail.detail());
    }

    @Test
    void nodeDetailThrowsWhenMissing() {
        when(neoRepo.findByNodeId(999L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.nodeDetail(999L, null));
    }

    @Test
    void prerequisiteChainThrowsWhenMissing() {
        when(neoRepo.existsByNodeId(999L)).thenReturn(false);
        assertThrows(BizException.class, () -> service.prerequisiteChain(999L));
    }

    @Test
    void requestReindexEvictsCacheAndPublishesEvent() {
        when(neoRepo.countAll()).thenReturn(77L);

        GraphChangedEvent event = service.requestReindex();

        verify(redis).delete(anyString());
        verify(eventPublisher).publish(any(GraphChangedEvent.class));
        assertEquals(77, event.nodeCount());
        assertEquals(GraphChangedEvent.TYPE_REINDEX, event.changeType());
    }

    // ===== Neo4j 不可达降级到 MySQL =====

    @Test
    void treeFallsBackToMysqlWhenNeo4jDown() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(neoRepo.findAllOrdered()).thenThrow(new RuntimeException("neo4j unavailable"));
        when(mysqlReader.allOrdered()).thenReturn(List.of(
                new NodeBrief(1L, "fire", "火", "石器时代", 1, "约公元前50万年", "摘要", false)));
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
        when(mysqlReader.allPrerequisites(41L)).thenReturn(List.of(
                new NodeBrief(1L, "fire", "火", "石器时代", 1, "约公元前50万年", "摘要", false)));

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
    void businessNotFoundDoesNotTriggerFallback() {
        when(neoRepo.findByNodeId(999L)).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> service.nodeDetail(999L, null));
        verifyNoInteractions(mysqlReader);
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
